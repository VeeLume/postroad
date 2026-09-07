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
    /**
     * Height change per 4-block cell, classed: flat, slabs (one rise per four blocks), stairs (one rise per two
     * blocks — stair, landing, stair), steep (up to one per block: the stairs road with its cut and fill
     * absorbing the rest; dear, so it is used for short stretches only) — each class up to
     * [StepClass.upTo] blocks costs [StepClass.cost] extra per cell; more than the last class is impassable.
     * Existing road cells carry no step cost, so a second route rides an existing stair section.
     */
    val steps: List<StepClass> = listOf(StepClass("flat", 0.0, 0.0), StepClass("slabs", 1.0, 1.0), StepClass("stairs", 2.0, 6.0), StepClass("steep", 4.0, 12.0)),
    /** Per turn kind, the largest rise the catalog can build (see `PieceCatalog.turnLimits`); empty means no limit. */
    val turnLimits: IntArray = IntArray(0),
    /** Diagonal moves: one class per diagonal piece rise (slab steps), in the catalog's costs. Empty: no diagonal moves. */
    val diagonalSteps: List<StepClass> = listOf(StepClass("diagonal0", 0.0, 0.5), StepClass("diagonal1", 1.0, 2.0), StepClass("diagonal2", 2.0, 6.0)),
    /** Per block a cell sits above the higher town or below the lower one (beyond [bandMargin]), per cell. */
    val bandPenalty: Double = 0.08,
    val bandMargin: Double = 6.0,
    val water: Double = 40.0,
    /** A cell already on a road costs base × this; well below 1 so routes merge. */
    val reuseFactor: Double = 0.15,
    /**
     * The A* heuristic is straight-line distance × base × this. [reuseFactor] would be admissible
     * (a road all the way is the cheapest imaginable) but explores nearly everything; 0.5 still
     * finds a merge whenever riding the road saves more than half the remaining distance, and
     * explores a corridor instead of a field.
     */
    val heuristicWeight: Double = 0.5,
    /** Give up when the search has expanded this many cells. */
    val maxExpansions: Int = 2_000_000,
    /** A route with more consecutive water cells than this is dropped: no road between islands until bridges exist. */
    val maxWaterRun: Int = 6,
)

/** One class of height change per cell: its name, the largest change it covers, and what it costs per cell. */
data class StepClass(val name: String, val upTo: Double, val cost: Double)

/**
 * A town the planner links: an id the network will know it by, the cell it sits on, its street cells
 * as exits, and [reach], the half-extent of its box in cells — the corridor of a hierarchical route
 * covers the whole box so a route can get from an exit inside it to the corridor outside.
 */
data class Town(val id: String, val cell: Cell, val exits: List<Cell> = emptyList(), val reach: Int = 0) {
    /** The exit (a street cell) facing [other], else the town cell itself. */
    fun exitToward(other: Cell): Cell {
        if (exits.isEmpty()) return cell
        val dx = (other.x - cell.x).toDouble(); val dz = (other.z - cell.z).toDouble()
        return exits.maxByOrNull { (it.x - cell.x) * dx + (it.z - cell.z) * dz } ?: cell
    }
}

/** One planned road: the cells it runs over, in order, and the towns it links. */
data class PlannedRoute(val id: String, val from: Town, val to: Town, val cells: List<Cell>)

/** A place where a route joined an existing road: the cell and the ids of the two routes. */
data class Junction(val cell: Cell, val joinedRoute: String, val joiningRoute: String)

/** Routes in planning order and the junctions between them. */
/** A pair the planner gave up on and why: "water" (long crossing) or "unreachable" (no passable route). */
data class DroppedPair(val id: String, val from: Town, val to: Town, val reason: String)

data class RoadPlan(val routes: List<PlannedRoute>, val junctions: List<Junction>, val dropped: List<DroppedPair> = emptyList())

/**
 * Cheapest-path planner over a [Terrain]. Pure: takes terrain and towns, returns routes; marks
 * road cells on the terrain as it goes so later routes can reuse them. Runs wherever the caller
 * wants — the planner thread in play, the test thread in tests.
 */
object RoadPlanner {
    /** Why the last [route] / [routeHierarchical] on this thread returned null; for the dropped-pair log line. */
    val lastFailure = ThreadLocal<String>()
    /** When set, every cell the fine search (cell size 1 in slope terms) closes is added here; for `/postroad roads trace`. */
    val trace = ThreadLocal<LongOpenHashSet?>()
    /** The last resolved endpoints of a traced fine search. */
    val traceEnds = ThreadLocal<Pair<Cell, Cell>?>()

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
    private fun stepCost(terrain: Terrain, x: Int, z: Int, fromHeight: Int, diagonal: Boolean, costs: PlannerCosts, slopeDivisor: Double, band: Band?): Double? {
        if (!terrain.inBounds(x, z)) return null
        if (terrain.has(x, z, Terrain.BLOCKED)) return null
        if (terrain.has(x, z, Terrain.LAVA)) return null
        val h = terrain.heightAt(x, z)
        val onRoad = terrain.has(x, z, Terrain.ROAD)
        var cost = costs.base * (if (diagonal) SQRT2 else 1.0)
        if (diagonal && slopeDivisor == 1.0) {
            // On the piece grid a diagonal move is a diagonal piece: slab steps up to the catalog's largest rise.
            val dh = abs(h - fromHeight).toDouble()
            val cls = costs.diagonalSteps.firstOrNull { dh <= it.upTo } ?: return null
            if (onRoad) cost = (cost + cls.cost) * costs.reuseFactor else cost += cls.cost
        } else {
            val dh = abs(h - fromHeight).toDouble() / (slopeDivisor * (if (diagonal) SQRT2 else 1.0))
            // The step class of this change, or impassable beyond the last class — on new ground and on an
            // existing road alike: a road's cells were judged on the heights of its day (perhaps estimates),
            // and this route's heights may differ. Riding a road only discounts the cost.
            val cls = costs.steps.firstOrNull { dh <= it.upTo } ?: return null
            if (onRoad) cost = (cost + cls.cost) * costs.reuseFactor else cost += cls.cost
        }
        if (terrain.has(x, z, Terrain.WATER)) cost += costs.water
        if (band != null && !onRoad) {
            val above = h - band.high
            val below = band.low - h
            if (above > 0) cost += costs.bandPenalty * above
            if (below > 0) cost += costs.bandPenalty * below
        }
        return cost
    }

    /** The elevation band a route should stay in: between its towns' heights, with a margin. */
    class Band(val low: Double, val high: Double)

    private fun passable(terrain: Terrain, x: Int, z: Int): Boolean =
        terrain.inBounds(x, z) && !terrain.has(x, z, Terrain.BLOCKED) && !terrain.has(x, z, Terrain.LAVA)

    /** How far (blocks) an endpoint may be moved to leave a town's box and reach the corridor: past any village's half-width. */
    const val ENDPOINT_REACH = 160

    /**
     * The cell itself if passable, else the first passable cell walking from it toward [toward] (a town
     * inside its box leaves the box on the side facing the other town), else the nearest passable cell
     * within [maxRing] rings ([ENDPOINT_REACH] blocks by default, whatever the cell size).
     */
    fun resolveEndpoint(terrain: Terrain, cell: Cell, maxRing: Int = maxOf(32, ENDPOINT_REACH / terrain.cellSize), toward: Cell? = null, exclude: Set<Cell> = emptySet()): Cell? {
        // The cell itself may be out of bounds (a town inside its box, outside a corridor); only candidates must be in.
        if (passable(terrain, cell.x, cell.z) && cell !in exclude) return cell
        if (toward != null) {
            val dx = (toward.x - cell.x).toDouble()
            val dz = (toward.z - cell.z).toDouble()
            val len = sqrt(dx * dx + dz * dz)
            if (len > 0) {
                var t = 1.0
                while (t <= maxRing) {
                    val x = Math.round(cell.x + dx / len * t).toInt()
                    val z = Math.round(cell.z + dz / len * t).toInt()
                    if (passable(terrain, x, z) && Cell(x, z) !in exclude) return Cell(x, z)
                    t += 1.0
                }
            }
        }
        for (r in 1..maxRing) {
            var best: Cell? = null
            var bestDist = Double.MAX_VALUE
            for (dz in -r..r) for (dx in -r..r) {
                if (maxOf(abs(dx), abs(dz)) != r) continue
                val x = cell.x + dx
                val z = cell.z + dz
                if (!passable(terrain, x, z) || Cell(x, z) in exclude) continue
                val d = cell.distanceTo(Cell(x, z))
                if (d < bestDist) { bestDist = d; best = Cell(x, z) }
            }
            if (best != null) return best
        }
        return null
    }

    /**
     * A* from [fromTown] to [toTown]. Null if unreachable or the search blew its budget.
     * [slopeDivisor] scales height differences to fine-cell units: the coarse grid passes its cell ratio (12 / 3 = 4).
     */
    fun route(terrain: Terrain, fromTown: Cell, toTown: Cell, costs: PlannerCosts = PlannerCosts(), slopeDivisor: Double = 1.0): List<Cell>? {
        // Towns sit inside their structure boxes; the road ends where the line to the other town leaves the box.
        // A resolved endpoint can sit on an island — a pocket of passable cells walled in by structure boxes —
        // which the search reveals by dying after a few cells; then the endpoint is resolved again past it.
        val triedFrom = HashSet<Cell>(); val triedTo = HashSet<Cell>()
        repeat(ENDPOINT_TRIES) {
            val from = resolveEndpoint(terrain, fromTown, toward = toTown, exclude = triedFrom) ?: run { lastFailure.set("no passable cell within reach of $fromTown (cell ${terrain.cellSize})"); return null }
            val to = resolveEndpoint(terrain, toTown, toward = fromTown, exclude = triedTo) ?: run { lastFailure.set("no passable cell within reach of $toTown (cell ${terrain.cellSize})"); return null }
            if (slopeDivisor == 1.0) traceEnds.set(from to to)
            val found = search(terrain, from, to, costs, slopeDivisor)
            if (found != null) return found
            if (lastExpansions.get() <= ISLAND_CELLS) triedFrom.add(from) else triedTo.add(to)
        }
        return null
    }

    private const val ENDPOINT_TRIES = 4
    /** A search that dies within this many cells started on an island. */
    private const val ISLAND_CELLS = 64
    private val lastExpansions = ThreadLocal.withInitial { 0 }

    private fun search(terrain: Terrain, from: Cell, to: Cell, costs: PlannerCosts, slopeDivisor: Double): List<Cell>? {
        val hFrom = terrain.heightAt(from.x, from.z).toDouble()
        val hTo = terrain.heightAt(to.x, to.z).toDouble()
        val band = Band(min(hFrom, hTo) - costs.bandMargin, maxOf(hFrom, hTo) + costs.bandMargin)
        val g = Long2DoubleOpenHashMap().apply { defaultReturnValue(Double.POSITIVE_INFINITY) }
        val parent = Long2LongOpenHashMap().apply { defaultReturnValue(Long.MIN_VALUE) }
        val closed = LongOpenHashSet()
        val start = from.key
        val goal = to.key
        g.put(start, 0.0)
        val open = PriorityQueue<Node>(compareBy { it.f })
        open.add(Node(start, from.distanceTo(to) * costs.base * costs.heuristicWeight))
        var expansions = 0
        while (open.isNotEmpty()) {
            val node = open.poll()
            val i = node.key
            if (!closed.add(i)) continue
            if (slopeDivisor == 1.0) trace.get()?.add(i)
            if (i == goal) break
            if (++expansions > costs.maxExpansions) { lastExpansions.set(expansions); lastFailure.set("budget of ${costs.maxExpansions} expansions spent between $from and $to (cell ${terrain.cellSize})"); return null }
            val cx = Terrain.keyX(i)
            val cz = Terrain.keyZ(i)
            val h = terrain.heightAt(cx, cz)
            val gi = g.get(i)
            // The direction this cell was reached from: a road cannot turn back on itself (pieces turn at
            // most 90°), so neighbours behind the line of travel are not moves.
            val pi = parent.get(i)
            val inDx = if (pi == Long.MIN_VALUE) 0 else Integer.signum(cx - Terrain.keyX(pi))
            val inDz = if (pi == Long.MIN_VALUE) 0 else Integer.signum(cz - Terrain.keyZ(pi))
            val hParent = if (pi == Long.MIN_VALUE) h else terrain.heightAt(Terrain.keyX(pi), Terrain.keyZ(pi))
            for ((k, d) in NEIGHBOURS.withIndex()) {
                if (inDx * d[0] + inDz * d[1] < 0) continue
                val nx = cx + d[0]
                val nz = cz + d[1]
                // This cell's piece is known now (in from the parent, out to the neighbour): the rises it hosts —
                // the climb into it and a descent out of it — must be ones the catalog has for that turn.
                if (pi != Long.MIN_VALUE && costs.turnLimits.isNotEmpty() && slopeDivisor == 1.0 && terrain.inBounds(nx, nz)) {
                    val hn = terrain.heightAt(nx, nz)
                    // Sides face outward: toward the parent (−in direction) and toward the neighbour.
                    if (h > hParent && costs.turnLimits[PieceCatalog.turnIndex(-inDx, -inDz, d[0], d[1])] < h - hParent) continue
                    if (hn < h && costs.turnLimits[PieceCatalog.turnIndex(d[0], d[1], -inDx, -inDz)] < h - hn) continue
                }
                val step = stepCost(terrain, nx, nz, h, k >= 4, costs, slopeDivisor, band) ?: continue
                val j = Terrain.key(nx, nz)
                if (closed.contains(j)) continue
                val tentative = gi + step
                if (tentative < g.get(j)) {
                    g.put(j, tentative)
                    parent.put(j, i)
                    val heuristic = Cell(nx, nz).distanceTo(to) * costs.base * costs.heuristicWeight
                    open.add(Node(j, tentative + heuristic))
                }
            }
        }
        lastExpansions.set(expansions)
        if (goal != start && parent.get(goal) == Long.MIN_VALUE) { lastFailure.set("search from $from exhausted after $expansions cell(s) without reaching $to (cell ${terrain.cellSize})"); return null }
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
     * Two-level route: a corridor on the [coarse] map (cells [ratio] times the fine cell, weak heuristic so it
     * finds existing roads and rides them), then the fine route inside that corridor with the usual costs.
     * The fine map is only sampled inside the corridor, which is what makes this cheap.
     */
    fun routeHierarchical(fine: Terrain, coarse: Terrain, ratio: Int, from: Cell, to: Cell, costs: PlannerCosts = PlannerCosts(), fromReach: Int = 0, toReach: Int = 0): List<Cell>? {
        val cf = Cell(Math.floorDiv(from.x, ratio), Math.floorDiv(from.z, ratio))
        val ct = Cell(Math.floorDiv(to.x, ratio), Math.floorDiv(to.z, ratio))
        val coarsePath = route(coarse, cf, ct, costs.copy(heuristicWeight = costs.reuseFactor), slopeDivisor = ratio.toDouble()) ?: run { lastFailure.set("coarse: " + lastFailure.get()); return null }
        val allowed = LongOpenHashSet()
        // The corridor: CORRIDOR_HALF blocks either side of the coarse path, whatever the coarse cell is,
        val half = maxOf(1, Math.ceilDiv(CORRIDOR_HALF, coarse.cellSize))
        for (c in coarsePath) for (dz in -half..half) for (dx in -half..half) allowed.add(Terrain.key(c.x + dx, c.z + dz))
        // and each town's whole box, so an exit inside it can reach the corridor outside.
        for ((c, reach) in listOf(cf to fromReach, ct to toReach)) {
            val r = half + Math.ceilDiv(reach, ratio)
            for (dz in -r..r) for (dx in -r..r) allowed.add(Terrain.key(c.x + dx, c.z + dz))
        }
        return route(CorridorTerrain(fine, ratio, allowed), from, to, costs)
    }

    /** Half the corridor's width in blocks: room for an obstacle box and its margin without walling the corridor off. */
    const val CORRIDOR_HALF = 24

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
        coarse: Terrain? = null,
        ratio: Int = 4,
        skip: Set<String> = emptySet(),
    ): RoadPlan {
        fun markRoad(cell: Cell) {
            terrain.set(cell.x, cell.z, Terrain.ROAD)
            coarse?.set(Math.floorDiv(cell.x, ratio), Math.floorDiv(cell.z, ratio), Terrain.ROAD)
        }
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
                if (owner.putIfAbsent(cell, route.id) == null) markRoad(cell)
            }
        }
        val known = existing.mapTo(HashSet()) { it.id }
        known.addAll(skip)

        val routes = ArrayList<PlannedRoute>()
        val junctions = ArrayList<Junction>()
        val dropped = ArrayList<DroppedPair>()
        for ((i, j) in ordered) {
            val id = routeId(towns[i].id, towns[j].id)
            if (id in known) continue
            val a = towns[i].exitToward(towns[j].cell)
            val b = towns[j].exitToward(towns[i].cell)
            val raw = (if (coarse != null) routeHierarchical(terrain, coarse, ratio, a, b, costs, towns[i].reach, towns[j].reach)
                else route(terrain, a, b, costs))
            if (raw == null) {
                io.github.veelume.postroad.Postroad.LOGGER.info("Pair {} unreachable from exit {} to exit {}: {}", id, a, b, lastFailure.get())
                dropped.add(DroppedPair(id, towns[i], towns[j], "unreachable")); known.add(id); continue
            }
            if (longestWaterRun(terrain, raw) > costs.maxWaterRun) { dropped.add(DroppedPair(id, towns[i], towns[j], "water")); known.add(id); continue }
            // Snapping splices another road's cells in; those were judged on that road's heights, so the
            // result is checked on today's terrain and the raw route kept when a step no class allows.
            val snapped = snapExcursions(raw, owner, EXCURSION_MAX)
            val cells = if (stepsFeasible(terrain, snapped, costs)) snapped else raw
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
                if (owner.putIfAbsent(cell, id) == null) markRoad(cell)
            }
            routes.add(PlannedRoute(id, towns[i], towns[j], cells))
            known.add(id)
        }
        return RoadPlan(routes, junctions, dropped)
    }

    /**
     * A route that leaves an existing road and rejoins the same road within [maxLen] cells is snapped
     * onto it: the excursion is replaced by the road's own cells between the two points (found by a
     * short search over that road's cells). Otherwise every bend would become a little ring.
     */
    fun snapExcursions(cells: List<Cell>, owner: Map<Cell, String>, maxLen: Int): List<Cell> {
        val out = ArrayList<Cell>(cells.size)
        var i = 0
        while (i < cells.size) {
            val here = owner[cells[i]]
            if (here == null) { out.add(cells[i]); i++; continue }
            // On a road at i: look ahead for the next cell on the same road after leaving it.
            var j = i + 1
            while (j < cells.size && owner[cells[j]] == here) j++          // still on it
            if (j >= cells.size) { out.addAll(cells.subList(i, cells.size)); break }
            val leave = j                                                    // first cell off the road
            var back = leave
            while (back < cells.size && owner[cells[back]] != here && back - leave < maxLen) back++
            if (back < cells.size && owner[cells[back]] == here) {
                // Excursion of (back - leave) cells: bridge along the road instead.
                val bridge = roadPath(cells[leave - 1], cells[back], here, owner) ?: run { out.addAll(cells.subList(i, back)); i = back; return@run null }
                if (bridge != null) {
                    out.addAll(cells.subList(i, leave))
                    out.addAll(bridge.subList(1, bridge.size - 1))
                    i = back
                }
            } else {
                out.addAll(cells.subList(i, leave))
                i = leave
            }
        }
        return out
    }

    /** True when every step between consecutive [cells] is within the last step class on [terrain]. */
    fun stepsFeasible(terrain: Terrain, cells: List<Cell>, costs: PlannerCosts): Boolean {
        val limit = costs.steps.maxOfOrNull { it.upTo } ?: return true
        val diagonalLimit = costs.diagonalSteps.maxOfOrNull { it.upTo } ?: -1.0
        for (k in 1 until cells.size) {
            val a = cells[k - 1]; val b = cells[k]
            val diagonal = a.x != b.x && a.z != b.z
            val dh = abs(terrain.heightAt(b.x, b.z) - terrain.heightAt(a.x, a.z)).toDouble()
            if (dh > (if (diagonal) diagonalLimit else limit)) return false
            // No turn sharper than 90°: the piece grid has no such piece.
            if (k >= 2) { val p = cells[k - 2]; if ((a.x - p.x) * (b.x - a.x) + (a.z - p.z) * (b.z - a.z) < 0) return false }
        }
        return true
    }

    /** BFS over the cells of one road from [a] to [b]; null if they are not connected within a short distance. */
    private fun roadPath(a: Cell, b: Cell, road: String, owner: Map<Cell, String>): List<Cell>? {
        val parent = HashMap<Cell, Cell>()
        val queue = ArrayDeque<Cell>()
        queue.add(a); parent[a] = a
        var steps = 0
        while (queue.isNotEmpty() && steps++ < 4000) {
            val c = queue.removeFirst()
            if (c == b) {
                val path = ArrayList<Cell>()
                var cur = b
                while (true) { path.add(cur); if (cur == a) break; cur = parent[cur]!! }
                return path.reversed()
            }
            for (d in NEIGHBOURS) {
                val n = Cell(c.x + d[0], c.z + d[1])
                if (owner[n] != road || parent.containsKey(n)) continue
                parent[n] = c
                queue.add(n)
            }
        }
        return null
    }

    private const val EXCURSION_MAX = 16

    /** Longest stretch of consecutive water cells on a route. */
    fun longestWaterRun(terrain: Terrain, cells: List<Cell>): Int {
        var run = 0
        var best = 0
        for (c in cells) {
            run = if (terrain.has(c.x, c.z, Terrain.WATER)) run + 1 else 0
            if (run > best) best = run
        }
        return best
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
