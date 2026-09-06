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
    /** One planned segment (a piece): its road, index along it, the two planned points, and their neighbours for decoration checks. */
    class Segment(val roadId: String, val index: Int, val a: BlockPos, val b: BlockPos, val prev: BlockPos?, val next: BlockPos?, val family: Int)

    @Volatile
    var segments: Map<Long, List<Segment>> = emptyMap()
        private set

    /** Chunks whose roads the feature laid during generation this session (the builder skips them). */
    val laid: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val featureRuns = AtomicInteger()
    val piecesPlaced = AtomicInteger()

    fun segmentsAt(chunkX: Int, chunkZ: Int): List<Segment> = segments[ChunkPos.asLong(chunkX, chunkZ)] ?: emptyList()

    /** Rebuilds the snapshot from the plan: every segment, in every chunk its footprint (two blocks either side) touches. */
    fun publish(storage: RoadPlanStorage, dimension: ResourceLocation) {
        val map = HashMap<Long, MutableList<Segment>>()
        for (road in storage.roadsIn(dimension)) {
            val points = road.points
            for (i in 1 until points.size) {
                val a = points[i - 1]; val b = points[i]
                val seg = Segment(road.id, i - 1, a, b, points.getOrNull(i - 2), points.getOrNull(i + 1), road.families.getOrElse(i - 1) { 0 }.toInt())
                val minX = (minOf(a.x, b.x) - FOOTPRINT) shr 4; val maxX = (maxOf(a.x, b.x) + FOOTPRINT) shr 4
                val minZ = (minOf(a.z, b.z) - FOOTPRINT) shr 4; val maxZ = (maxOf(a.z, b.z) + FOOTPRINT) shr 4
                for (cz in minZ..maxZ) for (cx in minX..maxX) map.getOrPut(ChunkPos.asLong(cx, cz)) { ArrayList() }.add(seg)
            }
        }
        segments = map
        Postroad.LOGGER.info("Road plan snapshot: {} chunk(s) with runs", map.size)
    }

    /** Blocks a piece may reach beyond its anchors: the half width plus decoration. */
    private const val FOOTPRINT = 3

    fun clear() {
        segments = emptyMap()
        laid.clear()
    }
}
