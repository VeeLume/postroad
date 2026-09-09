package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The plan as worldgen sees it: the piece placements of every final road, per chunk, published
 * as an immutable map by the server thread and read by the road feature on worker threads. A
 * chunk with placements gets its roads laid while it generates; a chunk without gets nothing.
 */
object RoadPlanSnapshot {
    /** One placement with its road's biome family at that point, and the road's tier. */
    class Placed(val placement: PiecePlacement, val family: Int, val tier: Int)

    @Volatile
    var placements: Map<Long, List<Placed>> = emptyMap()
        private set

    /** Per chunk, the ids of the roads the feature laid there this session; the builder lays the others. */
    val laid = ConcurrentHashMap<Long, MutableSet<String>>()

    fun markLaid(chunk: Long, roadIds: Collection<String>) { laid.getOrPut(chunk) { ConcurrentHashMap.newKeySet() }.addAll(roadIds) }
    fun laidRoads(chunk: Long): Set<String> = laid[chunk] ?: emptySet()
    fun wasLaid(chunk: Long): Boolean = laid.containsKey(chunk)

    val featureRuns = AtomicInteger()
    val piecesPlaced = AtomicInteger()
    /** Roads the snapshot could not assemble (no piece for some point); checked at storage time, so this should stay 0. */
    val noPiece = AtomicInteger()

    fun placementsAt(chunkX: Int, chunkZ: Int): List<Placed> = placements[ChunkPos.asLong(chunkX, chunkZ)] ?: emptyList()

    /** Rebuilds the snapshot from the plan: every final road assembled, each placement in every chunk its blocks or decoration can reach. */
    fun publish(storage: RoadPlanStorage, dimension: ResourceLocation) {
        val catalog = RoadPieces.current
        val map = HashMap<Long, MutableList<Placed>>()
        var roads = 0
        val finalRoads = storage.roadsIn(dimension).filter { !it.provisional }
        // A point two roads share (a junction) gets one piece: the road with the smaller id lays it, the
        // other skips it there. Junction pieces come later; this keeps two pieces from stacking.
        val claims = anchorClaims(finalRoads)
        for (road in finalRoads) {
            // A provisional road's heights are estimates; laying it would fix the wrong road into the
            // world (and freeze it: a touched road is never replanned). It is laid once it is final.
            val placements = catalog.assemble(road.points, road.id) ?: run { noPiece.incrementAndGet(); Postroad.LOGGER.warn("Road {} has a point no piece fits; not in the snapshot", road.id); null } ?: continue
            roads++
            for (p in placements) {
                if (claims[RoadPieceLayer.key(p.x, p.z)] != road.id) continue
                val b = RoadPieceLayer.reachOf(p)
                val placed = Placed(p, road.families.getOrElse(p.index) { 0 }.toInt(), road.tier)
                for (cz in (b[2] shr 4)..(b[5] shr 4)) for (cx in (b[0] shr 4)..(b[3] shr 4)) map.getOrPut(ChunkPos.asLong(cx, cz)) { ArrayList() }.add(placed)
            }
        }
        placements = map
        Postroad.LOGGER.info("Road plan snapshot: {} road(s), {} chunk(s) with placements", roads, map.size)
    }

    /** Per anchor column shared by several roads, the id of the road that lays it: the smallest id. */
    fun anchorClaims(roads: List<PlannedRoad>): Map<Long, String> {
        val claims = HashMap<Long, String>()
        for (road in roads) for (pt in road.points) {
            val k = RoadPieceLayer.key(pt.x, pt.z)
            val cur = claims[k]
            if (cur == null || road.id < cur) claims[k] = road.id
        }
        return claims
    }

    fun clear() {
        placements = emptyMap()
        laid.clear()
    }
}
