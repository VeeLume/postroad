package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.roads.RoadNode
import io.github.veelume.postroad.travel.SignNodes
import io.github.veelume.postroad.travel.SignWriter
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtUtils
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lays planned roads into chunks as they load, on the server thread under a block budget per
 * tick. A chunk's share of a road is the segments that start at the road points inside it;
 * each segment is a strip of `build.width` blocks on the actual surface, ground blocks the
 * styles file allows replaced, plants above cleared. Lampposts every `build.lampInterval`
 * blocks on alternating sides; a signpost with a way sign at every junction.
 *
 * Chunk load events may arrive off the server thread, so they only drop a key into a queue;
 * everything that reads the plan or touches blocks happens in the tick handler.
 */
object RoadBuilder {
    private class Job(val dimension: ResourceLocation, val chunk: Long)

    private val candidates = ConcurrentLinkedQueue<Job>()
    private val queue = ArrayDeque<Job>()
    private val queued = HashSet<String>()

    var chunksBuilt: Int = 0
        private set
    var blocksPlaced: Long = 0
        private set
    var signsPlaced: Int = 0
        private set

    val queueSize: Int get() = queue.size

    // ---- events ---------------------------------------------------------------------------------

    fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? ServerLevel ?: return
        candidates.add(Job(level.dimension().location(), event.chunk.pos.toLong()))
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        if (!PostroadConfig.planEnabled) { candidates.clear(); return }
        val storage = RoadPlanStorage.get(server)
        // Filter candidates against the plan; most loaded chunks carry no road.
        var taken = 0
        while (taken < 256) {
            val job = candidates.poll() ?: break
            taken++
            if (needsWork(storage, job)) offer(job)
        }
        if (queue.isEmpty()) return
        var budget = PostroadConfig.buildBlocksPerTick
        val deadline = System.nanoTime() + PostroadConfig.buildMillisPerTick * 1_000_000L
        while (budget > 0 && queue.isNotEmpty() && System.nanoTime() < deadline) {
            val job = queue.removeFirst()
            queued.remove(key(job))
            val level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, job.dimension)) ?: continue
            if (!level.hasChunk(ChunkPos.getX(job.chunk), ChunkPos.getZ(job.chunk))) continue
            val t0 = System.nanoTime()
            budget -= buildChunk(level, storage, job.chunk)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms > 20) Postroad.LOGGER.warn("Road chunk {} took {} ms to build", ChunkPos(job.chunk), ms)
        }
    }

    private fun key(job: Job) = "${job.dimension}|${job.chunk}"

    private fun offer(job: Job) {
        if (queued.add(key(job))) queue.addLast(job)
    }

    private fun needsWork(storage: RoadPlanStorage, job: Job): Boolean =
        storage.roadsInChunk(job.dimension, job.chunk).any { !it.builtChunks.contains(job.chunk) } ||
            storage.junctions.any { !it.signPlaced && it.dimension == job.dimension && ChunkPos.asLong(it.pos.x shr 4, it.pos.z shr 4) == job.chunk }

    /** Queues every already-loaded chunk of [road]; called when a plan lands after the chunks did. */
    fun enqueueLoaded(level: ServerLevel, road: PlannedRoad) {
        val dim = level.dimension().location()
        for (c in road.chunks()) {
            if (road.builtChunks.contains(c)) continue
            if (level.hasChunk(ChunkPos.getX(c), ChunkPos.getZ(c))) offer(Job(dim, c))
        }
    }

    /** `/postroad roads rebuild`: re-queues every loaded, unbuilt road chunk. Returns how many. */
    fun requeueLoaded(server: MinecraftServer): Int {
        val storage = RoadPlanStorage.get(server)
        var n = 0
        for (road in storage.roads.values) {
            val level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, road.dimension)) ?: continue
            for (c in road.chunks()) {
                if (road.builtChunks.contains(c) || !level.hasChunk(ChunkPos.getX(c), ChunkPos.getZ(c))) continue
                offer(Job(road.dimension, c)); n++
            }
        }
        return n
    }

    // ---- building -------------------------------------------------------------------------------

    /** Builds everything planned for [chunk]; returns blocks placed. */
    fun buildChunk(level: ServerLevel, storage: RoadPlanStorage, chunk: Long): Int {
        val dim = level.dimension().location()
        val styles = RoadStyles.current
        val boxes = storage.townsIn(dim).map { it.box }
        var placed = 0
        for (road in storage.roadsInChunk(dim, chunk)) {
            if (road.builtChunks.contains(chunk)) continue
            placed += buildRoadInChunk(level, road, chunk, styles, boxes)
            road.builtChunks.add(chunk)
            storage.setDirty()
        }
        for (junction in storage.junctions) {
            if (junction.signPlaced || junction.dimension != dim) continue
            if (ChunkPos.asLong(junction.pos.x shr 4, junction.pos.z shr 4) != chunk) continue
            placed += placeJunctionSign(level, storage, junction, styles, boxes)
            junction.signPlaced = true
            storage.setDirty()
        }
        if (placed > 0) { chunksBuilt++; blocksPlaced += placed }
        return placed
    }

    private fun chunkOf(p: BlockPos): Long = ChunkPos.asLong(p.x shr 4, p.z shr 4)

    private fun buildRoadInChunk(level: ServerLevel, road: PlannedRoad, chunk: Long, styles: RoadStyleSet, boxes: List<BoundingBox>): Int {
        val width = PostroadConfig.buildWidth
        val half = width / 2
        val lampInterval = PostroadConfig.buildLampInterval.toDouble()
        var placed = 0
        var length = 0.0
        val points = road.points
        for (i in points.indices) {
            val a = points[i]
            val prevLength = length
            if (i > 0) length += horizontal(points[i - 1], a)
            if (chunkOf(a) != chunk) continue
            val style = styles.style(road.families.getOrElse(i) { 0 }.toInt())
            val b = points.getOrNull(i + 1) ?: a
            val dx = (b.x - a.x).toDouble()
            val dz = (b.z - a.z).toDouble()
            val len = sqrt(dx * dx + dz * dz)
            val nx = if (len > 0) -dz / len else 0.0
            val nz = if (len > 0) dx / len else 1.0
            val steps = if (len > 0) ceil(len).toInt() else 0
            for (s in 0..steps) {
                val t = if (steps == 0) 0.0 else s.toDouble() / steps
                if (s == steps && i + 1 < points.size) break // the next point's segment starts there
                val cx = a.x + dx * t
                val cz = a.z + dz * t
                for (w in -half..half) {
                    val x = (cx + nx * w).roundToInt()
                    val z = (cz + nz * w).roundToInt()
                    val palette = if (w == 0) style.surface else style.edge
                    if (placeSurface(level, x, z, palette, styles, boxes)) placed++
                }
            }
            // A lamppost where the running length crosses a multiple of the interval, sides alternating.
            if (i > 0 && lampInterval > 0 && (prevLength / lampInterval).toInt() != (length / lampInterval).toInt()) {
                val side = if ((length / lampInterval).toInt() % 2 == 0) 1 else -1
                val lx = (a.x + nx * (half + 1) * side).roundToInt()
                val lz = (a.z + nz * (half + 1) * side).roundToInt()
                placed += placeLamppost(level, lx, lz, style, styles, boxes)
            }
        }
        return placed
    }

    private fun horizontal(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dz * dz)
    }

    private fun inBox(x: Int, z: Int, boxes: List<BoundingBox>): Boolean =
        boxes.any { x >= it.minX() - 1 && x <= it.maxX() + 1 && z >= it.minZ() - 1 && z <= it.maxZ() + 1 }

    /** Ground level at (x, z): the block below the first motion-blocking, non-leaf block. */
    private fun groundY(level: ServerLevel, x: Int, z: Int): Int = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1

    private fun randomAt(level: ServerLevel, x: Int, z: Int): Random = Random(Mth.getSeed(x, 0, z) xor level.seed)

    /** Replaces the ground block at (x, z) with a palette block and clears what grows on it. */
    private fun placeSurface(level: ServerLevel, x: Int, z: Int, palette: Palette, styles: RoadStyleSet, boxes: List<BoundingBox>): Boolean {
        if (!level.hasChunk(x shr 4, z shr 4) || inBox(x, z, boxes)) return false
        val y = groundY(level, x, z)
        if (y <= level.minBuildHeight) return false
        val ground = BlockPos(x, y, z)
        val state = level.getBlockState(ground)
        if (!state.fluidState.isEmpty || !level.getFluidState(ground.above()).isEmpty) return false
        if (!styles.isReplaceable(state)) return false
        val above = level.getBlockState(ground.above())
        if (!styles.isClearable(above)) return false
        val chosen = palette.pick(randomAt(level, x, z))
        var changed = false
        if (!above.isAir) { level.setBlock(ground.above(), Blocks.AIR.defaultBlockState(), 2 or 16); changed = true }
        val above2 = level.getBlockState(ground.above(2))
        if (!above2.isAir && styles.isClearable(above2)) level.setBlock(ground.above(2), Blocks.AIR.defaultBlockState(), 2 or 16)
        if (state != chosen) { level.setBlock(ground, chosen, 2 or 16); changed = true }
        return changed
    }

    private fun placeLamppost(level: ServerLevel, x: Int, z: Int, style: RoadStyle, styles: RoadStyleSet, boxes: List<BoundingBox>): Int {
        if (!level.hasChunk(x shr 4, z shr 4) || inBox(x, z, boxes)) return 0
        val y = groundY(level, x, z)
        if (y <= level.minBuildHeight) return 0
        val ground = BlockPos(x, y, z)
        val state = level.getBlockState(ground)
        if (!state.fluidState.isEmpty || !state.isSolid) return 0
        if (!styles.isClearable(level.getBlockState(ground.above())) || !styles.isClearable(level.getBlockState(ground.above(2)))) return 0
        level.setBlock(ground.above(), style.post, 3)
        level.setBlock(ground.above(2), style.lamp, 3)
        return 2
    }

    /**
     * A signpost one block off the joined road at the junction: the family's post with a way sign
     * on it (Supplementaries' if present, else a plain sign). Registered as a sign node on the
     * joined road; arms point at the road's two towns.
     */
    private fun placeJunctionSign(level: ServerLevel, storage: RoadPlanStorage, junction: PlannedJunction, styles: RoadStyleSet, boxes: List<BoundingBox>): Int {
        val road = storage.roads[junction.roadA] ?: return 0
        val index = road.points.indexOfFirst { it.x == junction.pos.x && it.z == junction.pos.z }.takeIf { it >= 0 } ?: return 0
        val style = styles.style(road.families.getOrElse(index) { 0 }.toInt())
        // Off the road: perpendicular to the joined road's direction here.
        val before = road.points.getOrElse(index - 1) { road.points[index] }
        val after = road.points.getOrElse(index + 1) { road.points[index] }
        val dx = (after.x - before.x).toDouble()
        val dz = (after.z - before.z).toDouble()
        val len = sqrt(dx * dx + dz * dz).takeIf { it > 0 } ?: 1.0
        val offset = PostroadConfig.buildWidth / 2 + 2
        for (side in intArrayOf(1, -1)) {
            val x = (junction.pos.x - dz / len * offset * side).roundToInt()
            val z = (junction.pos.z + dx / len * offset * side).roundToInt()
            if (!level.hasChunk(x shr 4, z shr 4) || inBox(x, z, boxes)) continue
            val y = groundY(level, x, z)
            if (y <= level.minBuildHeight) continue
            val ground = BlockPos(x, y, z)
            val state = level.getBlockState(ground)
            if (!state.fluidState.isEmpty || !state.isSolid) continue
            if (!styles.isClearable(level.getBlockState(ground.above())) || !styles.isClearable(level.getBlockState(ground.above(2)))) continue
            val signPos = ground.above(2)
            level.setBlock(ground.above(), style.post, 3)
            val waySign = BuiltInRegistries.BLOCK.getOptional(WAY_SIGN).orElse(null)
            val signState: BlockState = waySign?.defaultBlockState() ?: Blocks.OAK_SIGN.defaultBlockState()
            level.setBlock(signPos, signState, 3)
            if (waySign != null) mimic(level, signPos, style.post)
            val network = Network.get(level.server)
            val node = SignNodes.linkGenerated(level, signPos, road.id, index) ?: return 2
            level.getBlockEntity(signPos)?.let { entity ->
                if (entity !is net.minecraft.world.level.block.entity.SignBlockEntity) {
                    val targets = listOf(townTarget(network, storage, road.from, road, 0), townTarget(network, storage, road.to, road, road.points.size - 1))
                    SignWriter.pointWaySign(level, entity, network, node, viewer = null, explicit = targets)
                } else {
                    SignWriter.labelSign(level, entity, node.name, "")
                }
            }
            signsPlaced++
            return 3
        }
        return 0
    }

    /** A stand-in node for a road's town end: named after its place if the depot has registered, else "a village". */
    private fun townTarget(network: Network, storage: RoadPlanStorage, townId: String, road: PlannedRoad, index: Int): RoadNode {
        val name = network.places[townId]?.name ?: net.minecraft.network.chat.Component.translatable("sign.postroad.village").string
        return RoadNode("virtual/$townId", RoadNode.KIND_TOWN, road.dimension, road.points[index], road.id, index, name, townId)
    }

    /** Sets the fence a way sign renders around, through its tile NBT (Moonlight's mimic tile). */
    private fun mimic(level: ServerLevel, pos: BlockPos, post: BlockState) {
        val entity = level.getBlockEntity(pos) ?: return
        try {
            val registries = level.registryAccess()
            val tag = entity.saveWithoutMetadata(registries)
            tag.put("Mimic", NbtUtils.writeBlockState(post))
            entity.loadWithComponents(tag, registries)
            entity.setChanged()
            level.sendBlockUpdated(pos, entity.blockState, entity.blockState, 3)
        } catch (e: Exception) {
            Postroad.LOGGER.warn("Could not set way-sign post: {}", e.toString())
        }
    }

    fun reset() {
        candidates.clear(); queue.clear(); queued.clear()
    }

    val WAY_SIGN: ResourceLocation = ResourceLocation.fromNamespaceAndPath("supplementaries", "way_sign")
}
