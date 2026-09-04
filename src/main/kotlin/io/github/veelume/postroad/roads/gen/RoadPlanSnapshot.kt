package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The plan as worldgen sees it: planned road runs per chunk, published as an immutable map by
 * the server thread and read by the road feature on worker threads. A chunk with a run gets its
 * road laid while it generates; a chunk without one gets nothing.
 */
object RoadPlanSnapshot {
    /** A road's run through one chunk, with a few planned points either side as context for the flattening. */
    class Segment(val roadId: String, val points: List<BlockPos>, val before: List<BlockPos>, val after: List<BlockPos>, val family: Int)

    @Volatile
    var segments: Map<Long, List<Segment>> = emptyMap()
        private set

    /** Chunks whose roads the feature laid during generation this session (the builder skips them). */
    val laid: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val featureRuns = AtomicInteger()
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
                // The run reaches one point beyond the chunk at both ends: the entry segment from the previous
                // chunk and the exit segment into the next. Each chunk lays only its own blocks of them, so the
                // stretch between a chunk border and the nearest planned point is laid by whichever chunk owns it.
                val entry = maxOf(0, i - 1)
                val before = points.subList(maxOf(0, entry - RoadBuilder.CONTEXT_POINTS), entry)
                val after = points.subList(minOf(points.size, exit + 1), minOf(points.size, exit + 1 + RoadBuilder.CONTEXT_POINTS))
                map.getOrPut(chunk) { ArrayList() }.add(Segment(road.id, points.subList(entry, exit + 1), before, after, road.families.getOrElse(i) { 0 }.toInt()))
                i = last + 1
            }
        }
        segments = map
        Postroad.LOGGER.info("Road plan snapshot: {} chunk(s) with runs", map.size)
    }

    fun clear() {
        segments = emptyMap()
        laid.clear()
    }
}
