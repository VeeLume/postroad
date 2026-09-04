package io.github.veelume.postroad.roads.gen

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.registry.PostroadStructures
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
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
    class Segment(val roadId: String, val points: List<BlockPos>, val family: Int)

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
                map.getOrPut(chunk) { ArrayList() }.add(Segment(road.id, points.subList(i, exit + 1), road.families.getOrElse(i) { 0 }.toInt()))
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

/** The structure: one piece per planned run in the chunk. */
class RoadStructure(settings: StructureSettings) : Structure(settings) {
    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        val runs = RoadPlanSnapshot.segmentsAt(context.chunkPos().x, context.chunkPos().z)
        if (runs.isEmpty()) return Optional.empty()
        RoadPlanSnapshot.starts.incrementAndGet()
        return Optional.of(GenerationStub(runs[0].points.first()) { builder ->
            for (run in runs) builder.addPiece(RoadPiece(run.roadId, run.points, run.family))
        })
    }

    override fun type(): StructureType<*> = PostroadStructures.ROAD_TYPE.get()

    companion object {
        val CODEC: MapCodec<RoadStructure> = simpleCodec(::RoadStructure)
    }
}

/**
 * One chunk's run of a road, generated with the chunk: a 3-wide strip on the freshly shaped
 * surface. Its bounding box is also its beard box: `beard_thin` adaptation raises the terrain to
 * the box's floor and carves above it, so the ground is graded to the planned height before a
 * block is placed. Spike version: straight interpolation, no stairs yet.
 */
class RoadPiece : StructurePiece, PieceBeardifierModifier {
    val roadId: String
    val points: List<BlockPos>
    val family: Int

    constructor(roadId: String, points: List<BlockPos>, family: Int) : super(PostroadStructures.ROAD_PIECE.get(), 0, boxOf(points)) {
        this.roadId = roadId; this.points = points; this.family = family
    }

    constructor(tag: CompoundTag) : super(PostroadStructures.ROAD_PIECE.get(), tag) {
        roadId = tag.getString("Road")
        points = tag.getLongArray("Points").map { BlockPos.of(it) }
        family = tag.getInt("Family")
    }

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, tag: CompoundTag) {
        tag.putString("Road", roadId)
        tag.putLongArray("Points", points.map { it.asLong() }.toLongArray())
        tag.putInt("Family", family)
    }

    override fun getBeardifierBox(): BoundingBox = boundingBox
    override fun getTerrainAdjustment(): TerrainAdjustment = TerrainAdjustment.BEARD_THIN
    override fun getGroundLevelDelta(): Int = 0

    override fun postProcess(level: WorldGenLevel, structureManager: StructureManager, generator: ChunkGenerator, random: RandomSource, box: BoundingBox, chunkPos: ChunkPos, pos: BlockPos) {
        val styles = RoadStyles.current
        val style = styles.style(family)
        val placed = HashSet<Long>()
        val cursor = BlockPos.MutableBlockPos()
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val dx = (b.x - a.x).toDouble(); val dz = (b.z - a.z).toDouble()
            val steps = Math.ceil(maxOf(Math.abs(dx), Math.abs(dz))).toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val cx = Math.round(a.x + dx * t).toInt(); val cz = Math.round(a.z + dz * t).toInt()
                for (oz in -1..1) for (ox in -1..1) {
                    if (ox * ox + oz * oz > 2) continue
                    val x = cx + ox; val z = cz + oz
                    if (!box.isInside(x, box.minY(), z)) continue
                    val key = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
                    if (!placed.add(key)) continue
                    val ground = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1
                    if (ground <= level.minBuildHeight) continue
                    cursor.set(x, ground, z)
                    val state = level.getBlockState(cursor)
                    if (!state.fluidState.isEmpty || !styles.isReplaceable(state)) continue
                    val palette = if (ox == 0 && oz == 0) style.surface else style.edge
                    level.setBlock(cursor, palette.pick(Random(net.minecraft.util.Mth.getSeed(x, 0, z) xor level.seed)), 2)
                    for (dy in 1..2) {
                        cursor.set(x, ground + dy, z)
                        val above = level.getBlockState(cursor)
                        if (!above.isAir && styles.isClearable(above)) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2)
                    }
                }
            }
        }
        RoadPlanSnapshot.piecesPlaced.incrementAndGet()
    }

    companion object {
        /** The strip's box: a block either side of the points, from the lowest planned surface to three above the highest. */
        fun boxOf(points: List<BlockPos>): BoundingBox {
            val minX = points.minOf { it.x } - 1; val maxX = points.maxOf { it.x } + 1
            val minZ = points.minOf { it.z } - 1; val maxZ = points.maxOf { it.z } + 1
            val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y } + 3
            return BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
        }
    }
}
