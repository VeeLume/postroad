package io.github.veelume.postroad.roads.gen

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** What a step costs. Data-driven later (`data/postroad/roads/planner.json`); these are the defaults. */
data class PlannerCosts(
    val base: Double = 1.0,
    /** Multiplied by (height difference per cell)², capped at [slopeCap]. */
    val slopePenalty: Double = 0.6,
    val slopeCap: Double = 12.0,
    val water: Double = 40.0,
    /** A cell already on a road costs base × this; well below 1 so routes merge. */
    val reuseFactor: Double = 0.15,
    /** Give up when the open set grows past this many cells. */
    val maxExpansions: Int = 2_000_000,
)

/** One planned road: the cells it runs over, in order, and where it came from. */
data class PlannedRoute(val from: Cell, val to: Cell, val cells: List<Cell>)

/**
 * A place where a route joined an existing road: the cell and the index of the earlier route
 * (in the plan's route list) it joined.
 */
data class Junction(val cell: Cell, val joinedRoute: Int, val joiningRoute: Int)

/** The whole plan for a grid: routes in planning order, the junctions between them. */
data class RoadPlan(val routes: List<PlannedRoute>, val junctions: List<Junction>)

/**
 * Cheapest-path planner over a [TerrainGrid]. Pure: takes the grid and towns, returns routes;
 * marks road cells on the grid as it goes so later routes can reuse them. Runs wherever the
 * caller wants — the background thread in play, the test thread in tests.
 */
object RoadPlanner {

    private val NEIGHBOURS = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
    )

    /** Cost of stepping into cell (x, z) from a neighbour at [fromHeight], or null if impassable. */
    private fun stepCost(grid: TerrainGrid, x: Int, z: Int, fromHeight: Int, diagonal: Boolean, costs: PlannerCosts): Double? {
        if (!grid.inBounds(x, z)) return null
        if (grid.has(x, z, TerrainGrid.BLOCKED)) return null
        if (grid.has(x, z, TerrainGrid.LAVA)) return null
        var cost = costs.base * (if (diagonal) SQRT2 else 1.0)
        if (grid.has(x, z, TerrainGrid.ROAD)) cost *= costs.reuseFactor
        val dh = abs(grid.heightAt(x, z) - fromHeight).toDouble()
        cost += min(costs.slopePenalty * dh * dh, costs.slopeCap)
        if (grid.has(x, z, TerrainGrid.WATER)) cost += costs.water
        return cost
    }

    /** The cell itself if passable, else the nearest passable cell within [maxRing] rings — the box edge. */
    fun resolveEndpoint(grid: TerrainGrid, cell: Cell, maxRing: Int = 32): Cell? {
        if (!grid.inBounds(cell.x, cell.z)) return null
        if (!grid.has(cell.x, cell.z, TerrainGrid.BLOCKED) && !grid.has(cell.x, cell.z, TerrainGrid.LAVA)) return cell
        for (r in 1..maxRing) {
            var best: Cell? = null
            var bestDist = Double.MAX_VALUE
            for (dz in -r..r) for (dx in -r..r) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != r) continue
                val x = cell.x + dx
                val z = cell.z + dz
                if (!grid.inBounds(x, z) || grid.has(x, z, TerrainGrid.BLOCKED) || grid.has(x, z, TerrainGrid.LAVA)) continue
                val d = cell.distanceTo(Cell(x, z))
                if (d < bestDist) { bestDist = d; best = Cell(x, z) }
            }
            if (best != null) return best
        }
        return null
    }

    /** A* from [fromTown] to [toTown]. Null if unreachable or the search blew its budget. */
    fun route(grid: TerrainGrid, fromTown: Cell, toTown: Cell, costs: PlannerCosts = PlannerCosts()): List<Cell>? {
        // Towns sit inside their structure boxes; the road ends at the box edge and the streets take over.
        val from = resolveEndpoint(grid, fromTown) ?: return null
        val to = resolveEndpoint(grid, toTown) ?: return null
        if (!grid.inBounds(from.x, from.z) || !grid.inBounds(to.x, to.z)) return null
        val n = grid.width * grid.height
        val g = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val parent = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        val start = grid.index(from.x, from.z)
        val goal = grid.index(to.x, to.z)
        g[start] = 0.0
        val open = PriorityQueue<Node>(compareBy { it.f })
        open.add(Node(start, from.distanceTo(to) * costs.base * costs.reuseFactor))
        var expansions = 0
        while (open.isNotEmpty()) {
            val node = open.poll()
            val i = node.index
            if (closed[i]) continue
            closed[i] = true
            if (i == goal) break
            if (++expansions > costs.maxExpansions) return null
            val cx = i % grid.width
            val cz = i / grid.width
            val h = grid.heightAt(cx, cz)
            for ((k, d) in NEIGHBOURS.withIndex()) {
                val nx = cx + d[0]
                val nz = cz + d[1]
                val step = stepCost(grid, nx, nz, h, k >= 4, costs) ?: continue
                val j = grid.index(nx, nz)
                if (closed[j]) continue
                val tentative = g[i] + step
                if (tentative < g[j]) {
                    g[j] = tentative
                    parent[j] = i
                    val heuristic = Cell(nx, nz).distanceTo(to) * costs.base * costs.reuseFactor
                    open.add(Node(j, tentative + heuristic))
                }
            }
        }
        if (parent[goal] < 0 && goal != start) return null
        val cells = ArrayList<Cell>()
        var cur = goal
        while (cur >= 0) {
            cells.add(Cell(cur % grid.width, cur / grid.width))
            if (cur == start) break
            cur = parent[cur]
        }
        cells.reverse()
        return cells
    }

    /**
     * Plans the network for [towns]: each town to its [neighbours] nearest within [maxLinkCells],
     * longest pairs first so trunks exist before spurs. Road cells are marked on the grid, so a later route joins an earlier
     * road where that is cheaper than a road of its own; the join points are the junctions.
     */
    fun planNetwork(grid: TerrainGrid, towns: List<Cell>, costs: PlannerCosts = PlannerCosts(), neighbours: Int = 3, maxLinkCells: Double = 225.0): RoadPlan {
        val pairs = LinkedHashSet<Pair<Int, Int>>()
        for ((i, a) in towns.withIndex()) {
            towns.withIndex()
                .filter { (j, b) -> j != i && a.distanceTo(b) <= maxLinkCells }
                .sortedBy { (_, b) -> a.distanceTo(b) }
                .take(neighbours)
                .forEach { (j, _) -> pairs.add(if (i < j) i to j else j to i) }
        }
        // Longest pairs first: trunks exist before the spurs that join them.
        val ordered = pairs.sortedByDescending { (i, j) -> towns[i].distanceTo(towns[j]) }

        val routes = ArrayList<PlannedRoute>()
        val junctions = ArrayList<Junction>()
        val owner = HashMap<Cell, Int>() // road cell → index of the route that owns it
        for ((i, j) in ordered) {
            val cells = route(grid, towns[i], towns[j], costs) ?: continue
            // Where the route first steps onto someone else's road, that is a junction.
            var previousOwner: Int? = null
            for ((k, cell) in cells.withIndex()) {
                val existing = owner[cell]
                if (existing != null && existing != routes.size && previousOwner != existing && k > 0) {
                    junctions.add(Junction(cell, existing, routes.size))
                }
                previousOwner = existing
            }
            for (cell in cells) {
                if (owner[cell] == null) {
                    owner[cell] = routes.size
                    grid.set(cell.x, cell.z, TerrainGrid.ROAD)
                }
            }
            routes.add(PlannedRoute(towns[i], towns[j], cells))
        }
        return RoadPlan(routes, junctions)
    }

    /** Cells of [route] that no earlier route owns — the part that is actually new road. */
    fun newCells(plan: RoadPlan, routeIndex: Int): List<Cell> {
        val earlier = HashSet<Cell>()
        for (r in 0 until routeIndex) earlier.addAll(plan.routes[r].cells)
        return plan.routes[routeIndex].cells.filter { it !in earlier }
    }

    private class Node(val index: Int, val f: Double)

    private val SQRT2 = sqrt(2.0)
}
