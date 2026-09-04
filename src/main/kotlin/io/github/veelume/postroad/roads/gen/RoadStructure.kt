package io.github.veelume.postroad.roads.gen

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.registry.PostroadStructures
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.levelgen.structure.StructurePiece
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType
import net.neoforged.neoforge.common.world.PieceBeardifierModifier
import java.util.Optional
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

/**
 * The plan as worldgen sees it: planned road runs per chunk, published as an immutable map by
 * the server thread and read by structure generation on worker threads. A chunk with a run gets
 * a road structure start; a chunk without one gets nothing.
 */
object RoadPlanSnapshot {
    /** A road's run through one chunk, with a few planned points either side as context for the flattening. */
    class Segment(val roadId: String, val points: List<BlockPos>, val before: List<BlockPos>, val after: List<BlockPos>, val family: Int)

    @Volatile
    var segments: Map<Long, List<Segment>> = emptyMap()
        private set

    val starts = AtomicInteger()
    val piecesPlaced = AtomicInteger()

    fun segmentsAt(chunkX: Int, chunkZ: Int): List<Segment> = segments[ChunkPos.asLong(chunkX, chunkZ)] ?: emptyList()

    /** Rebuilds the snapshot from the plan: for every road, each chunk's run from its first point in the chunk to the exit point. */
    fun publish(storage: RoadPlanStorage, dimension: ResourceLocation) {
        val map = HashMap<Long, MutableList<Segment>>()
        for (road in storage.roadsIn(dimension)) {
            val points = road.points
            var i = 0
            while (i < points.size) {
                val chunk = ChunkPos.asLong(points[i].x shr 4, points[i].z shr 4)
                var last = i
                while (last + 1 < points.size && ChunkPos.asLong(points[last + 1].x shr 4, points[last + 1].z shr 4) == chunk) last++
                val exit = minOf(last + 1, points.size - 1)
                val before = points.subList(maxOf(0, i - RoadBuilder.CONTEXT_POINTS), i)
                val after = points.subList(minOf(points.size, exit + 1), minOf(points.size, exit + 1 + RoadBuilder.CONTEXT_POINTS))
                map.getOrPut(chunk) { ArrayList() }.add(Segment(road.id, points.subList(i, exit + 1), before, after, road.families.getOrElse(i) { 0 }.toInt()))
                i = last + 1
            }
        }
        segments = map
        Postroad.LOGGER.info("Road plan snapshot: {} chunk(s) with runs", map.size)
    }

    fun clear() {
        segments = emptyMap()
    }
}

/** Placement: a chunk is a structure chunk when the plan has a run there. */
class RoadPlacement(locateOffset: Vec3i, method: StructurePlacement.FrequencyReductionMethod, frequency: Float, salt: Int, exclusion: Optional<StructurePlacement.ExclusionZone>) :
    StructurePlacement(locateOffset, method, frequency, salt, exclusion) {

    override fun isPlacementChunk(structureState: ChunkGeneratorStructureState, x: Int, z: Int): Boolean =
        RoadPlanSnapshot.segmentsAt(x, z).isNotEmpty()

    override fun type(): StructurePlacementType<*> = PostroadStructures.PLANNED_PLACEMENT.get()

    companion object {
        val CODEC: MapCodec<RoadPlacement> = RecordCodecBuilder.mapCodec { instance ->
            placementCodec(instance).apply(instance, ::RoadPlacement)
        }
    }
}

/**
 * The structure: per planned run in the chunk, one [RoadRunPiece] that lays the road and one
 * [RoadBeardPiece] per 4-block segment that grades the ground to it. The grading is split by
 * segment so the terrain follows the road's profile instead of one flat floor per chunk run.
 */
class RoadStructure(settings: StructureSettings) : Structure(settings) {
    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        val runs = RoadPlanSnapshot.segmentsAt(context.chunkPos().x, context.chunkPos().z)
        if (runs.isEmpty()) return Optional.empty()
        RoadPlanSnapshot.starts.incrementAndGet()
        return Optional.of(GenerationStub(runs[0].points.first()) { builder ->
            for (run in runs) {
                for (i in 1 until run.points.size) builder.addPiece(RoadBeardPiece(listOf(run.points[i - 1], run.points[i])))
                builder.addPiece(RoadRunPiece(run.roadId, run.points, run.before, run.after, run.family, context.chunkPos()))
            }
        })
    }

    override fun type(): StructureType<*> = PostroadStructures.ROAD_TYPE.get()

    companion object {
        val CODEC: MapCodec<RoadStructure> = simpleCodec(::RoadStructure)
    }
}

/**
 * A road's run through one chunk, laid while the chunk generates, after the surface and before
 * vegetation. The ground under it has already been graded toward the plan by the run's
 * [RoadBeardPiece]s; what is left is the same shaping the chunk-load builder does: the profile
 * along the strip flattened and smoothed to one block per column, columns cut or filled to it,
 * slabs and stairs on rises, lampposts at intervals. Water columns are skipped (no bridges yet).
 * Its box is clipped to the chunk, so it is placed exactly once; it grades nothing itself.
 */
class RoadRunPiece : StructurePiece, PieceBeardifierModifier {
    val roadId: String
    val points: List<BlockPos>
    val before: List<BlockPos>
    val after: List<BlockPos>
    val family: Int

    constructor(roadId: String, points: List<BlockPos>, before: List<BlockPos>, after: List<BlockPos>, family: Int, chunk: ChunkPos) :
        super(PostroadStructures.ROAD_PIECE.get(), 0, runBox(points, chunk)) {
        this.roadId = roadId; this.points = points; this.before = before; this.after = after; this.family = family
    }

    constructor(tag: CompoundTag) : super(PostroadStructures.ROAD_PIECE.get(), tag) {
        roadId = tag.getString("Road")
        points = tag.getLongArray("Points").map { BlockPos.of(it) }
        before = tag.getLongArray("Before").map { BlockPos.of(it) }
        after = tag.getLongArray("After").map { BlockPos.of(it) }
        family = tag.getInt("Family")
    }

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, tag: CompoundTag) {
        tag.putString("Road", roadId)
        tag.putLongArray("Points", points.map { it.asLong() }.toLongArray())
        tag.putLongArray("Before", before.map { it.asLong() }.toLongArray())
        tag.putLongArray("After", after.map { it.asLong() }.toLongArray())
        tag.putInt("Family", family)
    }

    override fun getBeardifierBox(): BoundingBox = boundingBox
    override fun getTerrainAdjustment(): TerrainAdjustment = TerrainAdjustment.NONE
    override fun getGroundLevelDelta(): Int = 0

    override fun postProcess(level: WorldGenLevel, structureManager: StructureManager, generator: ChunkGenerator, random: RandomSource, box: BoundingBox, chunkPos: ChunkPos, pos: BlockPos) {
        if (points.size < 2) return
        val styles = RoadStyles.current
        val style = styles.style(family)
        val half = PostroadConfig.buildWidth / 2
        val lampInterval = PostroadConfig.buildLampInterval
        val path = RoadBuilder.straight(points)
        // The generated ground per column; water columns count as unknown and are left alone.
        fun ground(x: Int, z: Int): Int {
            if (!level.hasChunk(x shr 4, z shr 4)) return Int.MIN_VALUE
            val y = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1
            if (y <= level.minBuildHeight) return Int.MIN_VALUE
            if (!level.getFluidState(BlockPos(x, y + 1, z)).isEmpty) return Int.MIN_VALUE
            return y
        }
        fun ground(c: IntArray): Int = ground(c[0], c[1])
        val terrain = path.map(::ground)
        val beforeH = if (before.isNotEmpty()) RoadBuilder.straight(before + points.first()).dropLast(1).map(::ground) else emptyList()
        val afterH = if (after.isNotEmpty()) RoadBuilder.straight(listOf(points.last()) + after).drop(1).map(::ground) else emptyList()
        val flat = RoadBuilder.flatten(beforeH + terrain + afterH).toList().subList(beforeH.size, beforeH.size + terrain.size)
        val target = RoadBuilder.smooth(flat, null)
        // Every strip block belongs to one column: its own centre, else the first stamp that reaches it.
        val owner = it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
        fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
        for ((i, c) in path.withIndex()) for (dz in -half..half) for (dx in -half..half) {
            if (dx * dx + dz * dz > (half + 0.5) * (half + 0.5)) continue
            if (dx == 0 && dz == 0) owner[key(c[0] + dx, c[1] + dz)] = i else owner.putIfAbsent(key(c[0] + dx, c[1] + dz), i)
        }
        for ((i, c) in path.withIndex()) {
            if (target[i] == Int.MIN_VALUE) continue
            val shape = RoadShapes.at(path, target, i)
            for (dz in -half..half) for (dx in -half..half) {
                if (dx * dx + dz * dz > (half + 0.5) * (half + 0.5)) continue
                val x = c[0] + dx; val z = c[1] + dz
                if (owner[key(x, z)] != i || !box.isInside(x, box.minY(), z)) continue
                val g = ground(x, z)
                if (g == Int.MIN_VALUE) continue
                val centre = dx == 0 && dz == 0
                RoadBuilder.placeColumnAt(level, x, z, target[i], g, if (centre) style.surface else style.edge, style, styles, shape.kind, c, shape.higher ?: c)
            }
            if (lampInterval > 0 && i > 0 && i % lampInterval == 0) {
                val prev = path[i - 1]
                val dxp = (c[0] - prev[0]).toDouble(); val dzp = (c[1] - prev[1]).toDouble()
                val len = Math.sqrt(dxp * dxp + dzp * dzp).takeIf { it > 0 } ?: 1.0
                val side = if ((i / lampInterval) % 2 == 0) 1 else -1
                val lx = Math.round(c[0] - dzp / len * (half + 1) * side).toInt()
                val lz = Math.round(c[1] + dxp / len * (half + 1) * side).toInt()
                if (box.isInside(lx, box.minY(), lz)) RoadBuilder.placeLamppostAt(level, lx, lz, ground(lx, lz), style, styles)
            }
        }
        RoadPlanSnapshot.piecesPlaced.incrementAndGet()
    }

    companion object {
        /** The run's strip, clipped to its chunk; tall enough for any cut or fill the builder makes. */
        fun runBox(points: List<BlockPos>, chunk: ChunkPos): BoundingBox {
            val half = PostroadConfig.buildWidth / 2 + 1
            val minX = maxOf(points.minOf { it.x } - half, chunk.minBlockX); val maxX = minOf(points.maxOf { it.x } + half, chunk.maxBlockX)
            val minZ = maxOf(points.minOf { it.z } - half, chunk.minBlockZ); val maxZ = minOf(points.maxOf { it.z } + half, chunk.maxBlockZ)
            return BoundingBox(minX, points.minOf { it.y } - 12, minZ, maxX, points.maxOf { it.y } + 12, maxZ)
        }
    }
}

/**
 * One planned 4-block segment's grading: its box is its beard box, floor at the mean planned
 * surface, so `beard_thin` raises the ground to it and carves above it before any block is
 * placed. It places nothing itself.
 */
class RoadBeardPiece : StructurePiece, PieceBeardifierModifier {
    val points: List<BlockPos>

    constructor(points: List<BlockPos>) : super(PostroadStructures.ROAD_BEARD_PIECE.get(), 0, boxOf(points)) {
        this.points = points
    }

    constructor(tag: CompoundTag) : super(PostroadStructures.ROAD_BEARD_PIECE.get(), tag) {
        points = tag.getLongArray("Points").map { BlockPos.of(it) }
    }

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, tag: CompoundTag) {
        tag.putLongArray("Points", points.map { it.asLong() }.toLongArray())
    }

    override fun getBeardifierBox(): BoundingBox = boundingBox
    override fun getTerrainAdjustment(): TerrainAdjustment = TerrainAdjustment.BEARD_THIN
    override fun getGroundLevelDelta(): Int = 0

    override fun postProcess(level: WorldGenLevel, structureManager: StructureManager, generator: ChunkGenerator, random: RandomSource, box: BoundingBox, chunkPos: ChunkPos, pos: BlockPos) {}

    companion object {
        /** A block either side of the segment, floor at the mean planned surface, three blocks of headroom above the higher end. */
        fun boxOf(points: List<BlockPos>): BoundingBox {
            val minX = points.minOf { it.x } - 1; val maxX = points.maxOf { it.x } + 1
            val minZ = points.minOf { it.z } - 1; val maxZ = points.maxOf { it.z } + 1
            val floor = Math.round(points.sumOf { it.y }.toDouble() / points.size).toInt()
            val maxY = points.maxOf { it.y } + 3
            return BoundingBox(minX, floor, minZ, maxX, maxY, maxZ)
        }
    }
}
