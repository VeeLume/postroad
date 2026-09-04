package io.github.veelume.postroad.roads.gen

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** What a step costs. Loaded from `data/postroad/roads/planner.json` ([PlannerRules]); these are the defaults. */
data class PlannerCosts(
    val base: Double = 1.0,
    /** Multiplied by (height difference per cell)², capped at [slopeCap]. */
    val slopePenalty: Double = 0.6,
    val slopeCap: Double = 12.0,
    val water: Double = 40.0,
    /** A cell already on a road costs base × this; well below 1 so routes merge. */
    val reuseFactor: Double = 0.15,
    /** Give up when the search has expanded this many cells. */
    val maxExpansions: Int = 2_000_000,
)

/** A town the planner links: an id the network will know it by, and the cell it sits on. */
data class Town(val id: String, val cell: Cell)

/** One planned road: the cells it runs over, in order, and the towns it links. */
data class PlannedRoute(val id: String, val from: Town, val to: Town, val cells: List<Cell>)

/** A place where a route joined an existing road: the cell and the ids of the two routes. */
data class Junction(val cell: Cell, val joinedRoute: String, val joiningRoute: String)

/** Routes in planning order and the junctions between them. */
data class RoadPlan(val routes: List<PlannedRoute>, val junctions: List<Junction>)

/**
 * Cheapest-path planner over a [Terrain]. Pure: takes terrain and towns, returns routes; marks
 * road cells on the terrain as it goes so later routes can reuse them. Runs wherever the caller
 * wants — the planner thread in play, the test thread in tests.
 */
object RoadPlanner {

    private val NEIGHBOURS = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
    )

    /** Stable id for the road between two towns, whichever way round they are given. */
    fun routeId(a: String, b: String): String {
        val key = if (a <= b) "$a|$b" else "$b|$a"
        return "g" + Integer.toHexString(key.hashCode()).padStart(8, '0')
    }

    /** Cost of stepping into cell (x, z) from a neighbour at [fromHeight], or null if impassable. */
    private fun stepCost(terrain: Terrain, x: Int, z: Int, fromHeight: Int, diagonal: Boolean, costs: PlannerCosts): Double? {
        if (!terrain.inBounds(x, z)) return null
        if (terrain.has(x, z, Terrain.BLOCKED)) return null
        if (terrain.has(x, z, Terrain.LAVA)) return null
        var cost = costs.base * (if (diagonal) SQRT2 else 1.0)
        if (terrain.has(x, z, Terrain.ROAD)) cost *= costs.reuseFactor
        val dh = abs(terrain.heightAt(x, z) - fromHeight).toDouble()
        cost += min(costs.slopePenalty * dh * dh, costs.slopeCap)
        if (terrain.has(x, z, Terrain.WATER)) cost += costs.water
        return cost
    }

    private fun passable(terrain: Terrain, x: Int, z: Int): Boolean =
        terrain.inBounds(x, z) && !terrain.has(x, z, Terrain.BLOCKED) && !terrain.has(x, z, Terrain.LAVA)

    /** The cell itself if passable, else the nearest passable cell within [maxRing] rings — the box edge. */
    fun resolveEndpoint(terrain: Terrain, cell: Cell, maxRing: Int = 32): Cell? {
        if (!terrain.inBounds(cell.x, cell.z)) return null
        if (passable(terrain, cell.x, cell.z)) return cell
        for (r in 1..maxRing) {
            var best: Cell? = null
            var bestDist = Double.MAX_VALUE
            for (dz in -r..r) for (dx in -r..r) {
                if (maxOf(abs(dx), abs(dz)) != r) continue
                val x = cell.x + dx
                val z = cell.z + dz
                if (!passable(terrain, x, z)) continue
                val d = cell.distanceTo(Cell(x, z))
                if (d < bestDist) { bestDist = d; best = Cell(x, z) }
            }
            if (best != null) return best
        }
        return null
    }

    /** A* from [fromTown] to [toTown]. Null if unreachable or the search blew its budget. */
    fun route(terrain: Terrain, fromTown: Cell, toTown: Cell, costs: PlannerCosts = PlannerCosts()): List<Cell>? {
        // Towns sit inside their structure boxes; the road ends at the box edge and the streets take over.
        val from = resolveEndpoint(terrain, fromTown) ?: return null
        val to = resolveEndpoint(terrain, toTown) ?: return null
        val g = Long2DoubleOpenHashMap().apply { defaultReturnValue(Double.POSITIVE_INFINITY) }
        val parent = Long2LongOpenHashMap().apply { defaultReturnValue(Long.MIN_VALUE) }
        val closed = LongOpenHashSet()
        val start = from.key
        val goal = to.key
        g.put(start, 0.0)
        val open = PriorityQueue<Node>(compareBy { it.f })
        open.add(Node(start, from.distanceTo(to) * costs.base * costs.reuseFactor))
        var expansions = 0
        while (open.isNotEmpty()) {
            val node = open.poll()
            val i = node.key
            if (!closed.add(i)) continue
            if (i == goal) break
            if (++expansions > costs.maxExpansions) return null
            val cx = Terrain.keyX(i)
            val cz = Terrain.keyZ(i)
            val h = terrain.heightAt(cx, cz)
            val gi = g.get(i)
            for ((k, d) in NEIGHBOURS.withIndex()) {
                val nx = cx + d[0]
                val nz = cz + d[1]
                val step = stepCost(terrain, nx, nz, h, k >= 4, costs) ?: continue
                val j = Terrain.key(nx, nz)
                if (closed.contains(j)) continue
                val tentative = gi + step
                if (tentative < g.get(j)) {
                    g.put(j, tentative)
                    parent.put(j, i)
                    val heuristic = Cell(nx, nz).distanceTo(to) * costs.base * costs.reuseFactor
                    open.add(Node(j, tentative + heuristic))
                }
            }
        }
        if (goal != start && parent.get(goal) == Long.MIN_VALUE) return null
        val cells = ArrayList<Cell>()
        var cur = goal
        while (true) {
            cells.add(Cell.of(cur))
            if (cur == start) break
            cur = parent.get(cur)
            if (cur == Long.MIN_VALUE) return null
        }
        cells.reverse()
        return cells
    }

    /**
     * Plans roads for [towns]: each town to its [neighbours] nearest within [maxLinkCells],
     * longest pairs first so trunks exist before spurs. Pairs that already have a road in
     * [existing] are skipped. Road cells are marked on the terrain, so a later route joins an
     * earlier road where that is cheaper than a road of its own; the join points are the
     * junctions. Returns only what is new.
     */
    fun planNetwork(
        terrain: Terrain,
        towns: List<Town>,
        costs: PlannerCosts = PlannerCosts(),
        neighbours: Int = 3,
        maxLinkCells: Double = 225.0,
        existing: List<PlannedRoute> = emptyList(),
    ): RoadPlan {
        val pairs = LinkedHashSet<Pair<Int, Int>>()
        for ((i, a) in towns.withIndex()) {
            towns.withIndex()
                .filter { (j, b) -> j != i && a.cell.distanceTo(b.cell) <= maxLinkCells }
                .sortedBy { (_, b) -> a.cell.distanceTo(b.cell) }
                .take(neighbours)
                .forEach { (j, _) -> pairs.add(if (i < j) i to j else j to i) }
        }
        // Longest pairs first: trunks exist before the spurs that join them.
        val ordered = pairs.sortedByDescending { (i, j) -> towns[i].cell.distanceTo(towns[j].cell) }

        val owner = HashMap<Cell, String>() // road cell → id of the route that owns it
        for (route in existing) {
            for (cell in route.cells) {
                if (owner.putIfAbsent(cell, route.id) == null) terrain.set(cell.x, cell.z, Terrain.ROAD)
            }
        }
        val known = existing.mapTo(HashSet()) { it.id }

        val routes = ArrayList<PlannedRoute>()
        val junctions = ArrayList<Junction>()
        for ((i, j) in ordered) {
            val id = routeId(towns[i].id, towns[j].id)
            if (id in known) continue
            val cells = route(terrain, towns[i].cell, towns[j].cell, costs) ?: continue
            // A junction is where the route's own new cells meet an existing road: stepping onto one, or
            // off one. Road-to-road steps pass through junctions recorded when those roads met, and the
            // route's two ends are towns, not junctions.
            var previousOwner: String? = null
            for ((k, cell) in cells.withIndex()) {
                val current = owner[cell]
                if (k > 0 && k < cells.size - 1) {
                    if (current != null && previousOwner == null) addJunction(junctions, cell, current, id)
                    if (current == null && previousOwner != null && k > 1) addJunction(junctions, cells[k - 1], previousOwner, id)
                }
                previousOwner = current
            }
            for (cell in cells) {
                if (owner.putIfAbsent(cell, id) == null) terrain.set(cell.x, cell.z, Terrain.ROAD)
            }
            routes.add(PlannedRoute(id, towns[i], towns[j], cells))
            known.add(id)
        }
        return RoadPlan(routes, junctions)
    }

    private fun addJunction(junctions: MutableList<Junction>, cell: Cell, joined: String, joining: String) {
        if (junctions.none { it.cell == cell }) junctions.add(Junction(cell, joined, joining))
    }

    /** Cells of route [routeIndex] that no earlier route in [plan] owns — the part that is actually new road. */
    fun newCells(plan: RoadPlan, routeIndex: Int): List<Cell> {
        val earlier = HashSet<Cell>()
        for (r in 0 until routeIndex) earlier.addAll(plan.routes[r].cells)
        return plan.routes[routeIndex].cells.filter { it !in earlier }
    }

    private class Node(val key: Long, val f: Double)

    private val SQRT2 = sqrt(2.0)
}
