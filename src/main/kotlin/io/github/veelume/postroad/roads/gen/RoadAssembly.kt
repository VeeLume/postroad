package io.github.veelume.postroad.roads.gen

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos

/**
 * A road's placements and what the builder derives from them: the road-block footprint and, per
 * chunk, the placements whose reach touches it. [placements] is null when some point has no piece.
 */
class AssembledRoad(val points: List<BlockPos>, val catalog: PieceCatalog, val placements: List<PiecePlacement>?) {
    val footprint: LongOpenHashSet = placements?.let { RoadPieceLayer.footprintOf(it) } ?: LongOpenHashSet()

    private val byChunk = Long2ObjectOpenHashMap<IntArrayList>().also { map ->
        placements?.forEachIndexed { i, p ->
            val r = RoadPieceLayer.reachOf(p)
            for (cz in (r[2] shr 4)..(r[5] shr 4)) for (cx in (r[0] shr 4)..(r[3] shr 4)) map.computeIfAbsent(ChunkPos.asLong(cx, cz)) { IntArrayList() }.add(i)
        }
    }

    /** Indices into [placements] of the placements reaching into [chunk], ascending. */
    fun inChunk(chunk: Long): IntArrayList = byChunk.get(chunk) ?: EMPTY

    private companion object { val EMPTY = IntArrayList() }
}

/**
 * Assembles each road once. A road's placements follow from its points and the catalog alone, and
 * both are replaced, never changed in place (a replan or a refit assigns new points, a reload a new
 * catalog), so an entry is good while both are the same objects. The builder used to assemble the
 * whole road again for every chunk it built and every tick it resumed one. Server thread only.
 */
object RoadAssembly {
    private val cache = HashMap<String, AssembledRoad>()

    /** True when [road] is assembled for its current points and the current catalog. */
    fun isCached(road: PlannedRoad): Boolean = cache[road.id]?.let { it.points === road.points && it.catalog === RoadPieces.current } == true

    fun of(road: PlannedRoad): AssembledRoad {
        val catalog = RoadPieces.current
        cache[road.id]?.let { if (it.points === road.points && it.catalog === catalog) return it }
        val points = road.points
        return AssembledRoad(points, catalog, catalog.assemble(points, road.id)).also { cache[road.id] = it }
    }

    /** Drops the entries of roads no longer in the plan. */
    fun retain(ids: Set<String>) { cache.keys.retainAll(ids) }

    fun clear() = cache.clear()
}
