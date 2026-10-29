package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.network.asDimensionKey
import io.github.veelume.postroad.roads.RoadRules
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Keeps trees off the roads. World generation puts trees down after the road feature (vegetation is a
 * later step of the same chunk), so they stand on the road or hang into it. Once per road chunk, after
 * it has loaded and the road is there, this looks at the space over the road: a tree whose trunk stands
 * in it is felled (its logs; the crown then decays the vanilla way), natural leaves and plants in it go.
 * Logs with no natural leaves on them are somebody's building and stay, as do the road's own posts and
 * lamps; nothing inside a village's pieces or a pit stop is touched, nor followed into from a tree.
 * Chunks are recorded in [PlannedRoad.clearedChunks] so each is done once.
 */
object RoadClearing {
    private class Job(val dimension: ResourceLocation, val chunk: Long)

    private val candidates = ConcurrentLinkedQueue<Job>()
    private val queue = ArrayDeque<Job>()

    fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? ServerLevel ?: return
        candidates.add(Job(level.dimension().location(), event.chunk.pos.toLong()))
    }

    /** A chunk whose road was just laid by the builder: worth a look while it is loaded. */
    fun offer(level: ServerLevel, chunk: Long) {
        candidates.add(Job(level.dimension().location(), chunk))
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        val storage = RoadPlanStorage.get(server)
        var taken = 0
        while (taken++ < 256) {
            val job = candidates.poll() ?: break
            if (storage.roadsInChunk(job.dimension, job.chunk).any { !it.provisional && !it.clearedChunks.contains(job.chunk) }) queue.addLast(job)
        }
        var budget = CHUNKS_PER_TICK
        while (budget-- > 0 && queue.isNotEmpty()) {
            val job = queue.removeFirst()
            val level = server.getLevel(job.dimension.asDimensionKey()) ?: continue
            if (!level.hasChunk(ChunkPos.getX(job.chunk), ChunkPos.getZ(job.chunk))) continue
            for (road in storage.roadsInChunk(job.dimension, job.chunk)) {
                if (road.provisional || road.clearedChunks.contains(job.chunk)) continue
                // Not done until the road is really there: a chunk loaded before the builder laid it gets another look.
                if (clear(level, road, job.chunk, protectedBoxes(storage, job.dimension, job.chunk)) >= 0) { road.clearedChunks.add(job.chunk); storage.setDirty() }
            }
        }
    }

    /** Village pieces and pit stops reaching near [chunk]: what the clearing keeps out of. */
    fun protectedBoxes(storage: RoadPlanStorage, dimension: ResourceLocation, chunk: Long): List<net.minecraft.world.level.levelgen.structure.BoundingBox> {
        val x0 = ChunkPos.getX(chunk) shl 4; val z0 = ChunkPos.getZ(chunk) shl 4
        fun near(b: net.minecraft.world.level.levelgen.structure.BoundingBox) =
            b.maxX() >= x0 - TREE_REACH && b.minX() <= x0 + 15 + TREE_REACH && b.maxZ() >= z0 - TREE_REACH && b.minZ() <= z0 + 15 + TREE_REACH
        return storage.townsIn(dimension).flatMap { t -> t.footprint + listOfNotNull(t.stop) }.filter(::near)
    }

    private fun inside(boxes: List<net.minecraft.world.level.levelgen.structure.BoundingBox>, x: Int, z: Int): Boolean =
        boxes.any { x >= it.minX() - 1 && x <= it.maxX() + 1 && z >= it.minZ() - 1 && z <= it.maxZ() + 1 }

    /** Clears [road]'s space in [chunk], keeping out of [keepOut]; returns blocks removed, or -1 when no road was found there. */
    fun clear(level: ServerLevel, road: PlannedRoad, chunk: Long, keepOut: List<net.minecraft.world.level.levelgen.structure.BoundingBox> = emptyList()): Int {
        val cx = ChunkPos.getX(chunk); val cz = ChunkPos.getZ(chunk)
        val x0 = cx shl 4; val z0 = cz shl 4
        val rules = RoadRules.current
        val points = road.points
        val logs = ArrayList<BlockPos>()
        var found = false
        var removed = 0
        val seen = HashSet<Long>()
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val reach = RADIUS.toInt() + 1
            if (maxOf(a.x, b.x) + reach < x0 || minOf(a.x, b.x) - reach > x0 + 15 || maxOf(a.z, b.z) + reach < z0 || minOf(a.z, b.z) - reach > z0 + 15) continue
            for (x in maxOf(x0, minOf(a.x, b.x) - reach)..minOf(x0 + 15, maxOf(a.x, b.x) + reach)) {
                for (z in maxOf(z0, minOf(a.z, b.z) - reach)..minOf(z0 + 15, maxOf(a.z, b.z) + reach)) {
                    if (!seen.add(BlockPos.asLong(x, 0, z)) || inside(keepOut, x, z)) continue
                    val dx = (b.x - a.x).toDouble(); val dz = (b.z - a.z).toDouble()
                    val len2 = dx * dx + dz * dz
                    val t = if (len2 == 0.0) 0.0 else (((x - a.x) * dx + (z - a.z) * dz) / len2).coerceIn(0.0, 1.0)
                    if (kotlin.math.hypot(x - (a.x + t * dx), z - (a.z + t * dz)) > RADIUS) continue
                    val lineY = Math.round(a.y + (b.y - a.y) * t).toInt()
                    // The road block in this column: the top one the rules call road, near the line.
                    var surface = Int.MIN_VALUE
                    for (y in lineY + 2 downTo lineY - 3) {
                        val state = level.getBlockState(BlockPos(x, y, z))
                        // The rules count logs as road (log bridges); a trunk standing on the road is not one.
                        if (state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.LEAVES)) continue
                        if (rules.classify(state) != null) { surface = y; break }
                    }
                    if (surface == Int.MIN_VALUE) continue
                    found = true
                    for (y in surface + 1..surface + HEADROOM) {
                        val pos = BlockPos(x, y, z)
                        val state = level.getBlockState(pos)
                        when {
                            state.isAir -> {}
                            state.`is`(BlockTags.LOGS) -> logs.add(pos)
                            naturalLeaves(state) || (state.canBeReplaced() && state.fluidState.isEmpty) -> {
                                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3); removed++
                            }
                        }
                    }
                }
            }
        }
        if (!found) return -1
        removed += fell(level, logs, keepOut)
        if (removed > 0) Postroad.LOGGER.debug("Cleared {} block(s) of trees off road {} in chunk {}", removed, road.id, ChunkPos(chunk))
        return removed
    }

    private fun naturalLeaves(state: BlockState): Boolean =
        state.`is`(BlockTags.LEAVES) && (!state.hasProperty(LeavesBlock.PERSISTENT) || !state.getValue(LeavesBlock.PERSISTENT))

    /**
     * The trees the [start] logs belong to: their connected logs, up to [MAX_LOGS] and no further than
     * [TREE_REACH] from where they were found. Only a tree with natural leaves on it is felled: logs
     * without are a building. Logs go with block updates, so the crown decays as after any felling.
     */
    private fun fell(level: ServerLevel, start: List<BlockPos>, keepOut: List<net.minecraft.world.level.levelgen.structure.BoundingBox>): Int {
        if (start.isEmpty()) return 0
        val done = HashSet<Long>()
        var removed = 0
        for (origin in start) {
            if (origin.asLong() in done) continue
            val tree = ArrayList<BlockPos>()
            val open = ArrayDeque<BlockPos>().also { it.add(origin) }
            done.add(origin.asLong())
            var leafy = false
            while (open.isNotEmpty() && tree.size < MAX_LOGS) {
                val p = open.removeFirst()
                tree.add(p)
                for (dy in -1..1) for (dz in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val n = p.offset(dx, dy, dz)
                    val state = level.getBlockState(n)
                    if (!leafy && naturalLeaves(state)) leafy = true
                    if (!state.`is`(BlockTags.LOGS) || !done.add(n.asLong())) continue
                    if (kotlin.math.abs(n.x - origin.x) > TREE_REACH || kotlin.math.abs(n.z - origin.z) > TREE_REACH || n.y < origin.y - 2 || inside(keepOut, n.x, n.z)) continue
                    open.add(n)
                }
            }
            if (!leafy) continue
            for (p in tree) { level.setBlock(p, Blocks.AIR.defaultBlockState(), 3); removed++ }
        }
        return removed
    }

    private val CHUNKS_PER_TICK get() = PostroadConfig.worksChunksPerTick
    /** Blocks over the road kept clear: a player and a horse. */
    private const val HEADROOM = 4
    private const val RADIUS = 2.5
    private const val MAX_LOGS = 400
    private const val TREE_REACH = 10
}
