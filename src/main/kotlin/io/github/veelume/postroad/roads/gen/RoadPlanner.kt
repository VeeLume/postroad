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
data class Town(val id: String, val cell: Cell, val exits: List<Cell> = emptyList(), val reach: Int = 0,
                /** Per exit, the direction the street faces at that stub (null: a street centre, no direction). */
                val facings: List<Facing?> = emptyList()) {
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
/**
 * A pair the planner gave up on and why: "water" (long crossing) or "unreachable" (no passable route).
 * [estimated]: the judgement rested on estimated cells, so it is provisional — [cells] (the route a water
 * drop found) or [corridor] (the coarse cells the search was confined to) say what to generate before the
 * pair is planned again on real terrain.
 */
data class DroppedPair(val id: String, val from: Town, val to: Town, val reason: String, val cells: List<Cell> = emptyList(), val corridor: List<Cell> = emptyList(), val estimated: Boolean = false)

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
    /** The last resolved endpoints of a traced fine search: its starts and its goals. */
    val traceEnds = ThreadLocal<Pair<List<Cell>, List<Cell>>?>()
    /**
     * When set, the fine search counts every neighbour it refused, by [REJECT_NAMES] index: what the wall
     * around an unreachable pair is made of. A neighbour is counted each time a closed cell looks at it.
     */
    val traceRejects = ThreadLocal<IntArray?>()
    /** The coarse corridor of the last [routeTowns] on this thread (empty without a coarse map): what an unreachable pair searched. */
    private val lastCorridor = ThreadLocal<List<Cell>>()
    val REJECT_NAMES = arrayOf("turned back", "turn limit (rise in)", "turn limit (descent out)", "outside the corridor", "blocked", "lava", "too steep", "too steep (diagonal)", "cuts a road's corner")
    private const val R_TURN_BACK = 0; private const val R_TURN_RISE = 1; private const val R_TURN_DROP = 2
    private const val R_BOUNDS = 3; private const val R_BLOCKED = 4; private const val R_LAVA = 5; private const val R_STEEP = 6; private const val R_STEEP_DIAGONAL = 7

    /**
     * Ground the world has not generated is not ground to plan a road on.
     *
     * With this set, a cell carrying [Terrain.ESTIMATED] is impassable, so a route can only be found
     * over heights that are real. A pair whose corridor was too narrow then comes back unreachable —
     * an honest failure that says "generate more ground here" — instead of a road laid on a guess
     * that is wrong by anything up to the estimator's spread. It is the whole point of planning after
     * the ground exists: without it a route simply walks out of the corridor and guesses again.
     */
    @Volatile
    var realTerrainOnly: Boolean = false
        private set
    private const val R_ROAD_CORNER = 8

    /**
     * A diagonal move from one road cell to another that skips a road cell beside it: the road goes round
     * that corner, and a route riding the road must too, or the two lay different pieces over the same
     * anchors (a diagonal inside a corner — the "double route").
     */
    private fun cutsRoadCorner(terrain: Terrain, x: Int, z: Int, dx: Int, dz: Int): Boolean =
        dx != 0 && dz != 0 && terrain.has(x, z, Terrain.ROAD) && terrain.inBounds(x + dx, z + dz) && terrain.has(x + dx, z + dz, Terrain.ROAD) &&
            ((terrain.inBounds(x + dx, z) && terrain.has(x + dx, z, Terrain.ROAD)) || (terrain.inBounds(x, z + dz) && terrain.has(x, z + dz, Terrain.ROAD)))

    private val NEIGHBOURS = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
    )

    /** Stable id for the road between two towns, whichever way round they are given. */
    fun routeId(a: String, b: String): String {
        val key = if (a <= b) "$a|$b" else "$b|$a"
        return "g" + Integer.toHexString(key.hashCode()).padStart(8, '0')
    }

    /**
     * Cost of stepping into cell (x, z) from a neighbour at [fromHeight], or null if impassable. On the coarse
     * map ([slopeDivisor] = the cell ratio) a cell stands for that many fine cells, so the terrain extras —
     * step class, water, band — are paid that many times; the base cost is per cell on either map, which keeps
     * the coarse search's weighing of distance against terrain the same as the fine search's.
     */
    private fun stepCost(terrain: Terrain, x: Int, z: Int, fromHeight: Int, diagonal: Boolean, costs: PlannerCosts, slopeDivisor: Double, band: Band?, rejects: IntArray? = null): Double? {
        if (!terrain.inBounds(x, z)) { rejects?.let { it[R_BOUNDS]++ }; return null }
        if (terrain.has(x, z, Terrain.BLOCKED)) { rejects?.let { it[R_BLOCKED]++ }; return null }
        if (realTerrainOnly && terrain.has(x, z, Terrain.ESTIMATED)) { rejects?.let { it[R_BLOCKED]++ }; return null }
        if (terrain.has(x, z, Terrain.LAVA)) { rejects?.let { it[R_LAVA]++ }; return null }
        val h = terrain.heightAt(x, z)
        val onRoad = terrain.has(x, z, Terrain.ROAD)
        var cost = costs.base * (if (diagonal) SQRT2 else 1.0)
        if (diagonal && slopeDivisor == 1.0) {
            // On the piece grid a diagonal move is a diagonal piece: slab steps up to the catalog's largest rise.
            val dh = abs(h - fromHeight).toDouble()
            val cls = costs.diagonalSteps.firstOrNull { dh <= it.upTo } ?: run { rejects?.let { it[R_STEEP_DIAGONAL]++ }; return null }
            if (onRoad) cost = (cost + cls.cost) * costs.reuseFactor else cost += cls.cost
        } else {
            // On the coarse map: the slope per fine cell picks the class, and the class is paid per fine cell.
            val dh = abs(h - fromHeight).toDouble() / (slopeDivisor * (if (diagonal) SQRT2 else 1.0))
            // The step class of this change, or impassable beyond the last class — on new ground and on an
            // existing road alike: a road's cells were judged on the heights of its day (perhaps estimates),
            // and this route's heights may differ. Riding a road only discounts the cost.
            val cls = costs.steps.firstOrNull { dh <= it.upTo } ?: run { rejects?.let { it[R_STEEP]++ }; return null }
            if (onRoad) cost = (cost + cls.cost * slopeDivisor) * costs.reuseFactor else cost += cls.cost * slopeDivisor
        }
        if (terrain.has(x, z, Terrain.WATER)) cost += costs.water * slopeDivisor
        if (band != null && !onRoad) {
            val above = h - band.high
            val below = band.low - h
            if (above > 0) cost += costs.bandPenalty * above * slopeDivisor
            if (below > 0) cost += costs.bandPenalty * below * slopeDivisor
        }
        return cost
    }

    /** The elevation band a route should stay in: between its towns' heights, with a margin. */
    class Band(val low: Double, val high: Double)

    private fun passable(terrain: Terrain, x: Int, z: Int): Boolean =
        terrain.inBounds(x, z) && !terrain.has(x, z, Terrain.BLOCKED) && !terrain.has(x, z, Terrain.LAVA) &&
            !(realTerrainOnly && terrain.has(x, z, Terrain.ESTIMATED))

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
    fun route(terrain: Terrain, fromTown: Cell, toTown: Cell, costs: PlannerCosts = PlannerCosts(), slopeDivisor: Double = 1.0, freeTurns: Boolean = false): List<Cell>? {
        // Towns sit inside their structure boxes; the road ends where the line to the other town leaves the box.
        // A resolved endpoint can sit on an island — a pocket of passable cells walled in by structure boxes —
        // which the search reveals by dying after a few cells; then the endpoint is resolved again past it.
        val triedFrom = HashSet<Cell>(); val triedTo = HashSet<Cell>()
        repeat(ENDPOINT_TRIES) {
            val from = resolveEndpoint(terrain, fromTown, toward = toTown, exclude = triedFrom) ?: run { lastFailure.set("no passable cell within reach of $fromTown (cell ${terrain.cellSize})"); return null }
            val to = resolveEndpoint(terrain, toTown, toward = fromTown, exclude = triedTo) ?: run { lastFailure.set("no passable cell within reach of $toTown (cell ${terrain.cellSize})"); return null }
            if (slopeDivisor == 1.0) traceEnds.set(listOf(from) to listOf(to))
            val found = search(terrain, listOf(from), listOf(to), costs, slopeDivisor, freeTurns)
            if (found != null) return found
            if (lastExpansions.get() <= ISLAND_CELLS) triedFrom.add(from) else triedTo.add(to)
        }
        return null
    }

    private const val ENDPOINT_TRIES = 4
    /** A search that dies within this many cells started on an island. */
    private const val ISLAND_CELLS = 64
    private val lastExpansions = ThreadLocal.withInitial { 0 }

    /** [freeTurns]: ignore the piece grid's turn rules (turning back, turn limits) — for finding out whether they are the wall. */
    /**
     * A* from any of [from] to any of [to], one state per cell. The moves out of a cell depend on the
     * direction it was entered in (no turning back, the turn limits), which a per-cell closed set ignores:
     * a cell is closed on its first, cheapest arrival. That is complete here because turns of up to 90° are
     * allowed: whatever the arrival, the cell before it can always sidestep into the neighbour the closed
     * cell may not reach, and the turn limits only refuse rises the step classes refuse anyway (every turn
     * kind of the catalog carries the classes' largest rise). Measured 2026-09-09 with `/postroad roads
     * trace`: switching the turn rules off changed no unreachable pair except two that need a hairpin, which
     * no piece builds. Should the catalog lose a turn kind, the state must become (cell, direction entered);
     * not before — it costs up to nine expansions per cell.
     *
     * [freeTurns]: ignore the piece grid's turn rules (turning back, turn limits) — for finding out whether they are the wall.
     */
    private fun search(terrain: Terrain, from: List<Cell>, to: List<Cell>, costs: PlannerCosts, slopeDivisor: Double, freeTurns: Boolean = false): List<Cell>? {
        val heights = (from + to).map { terrain.heightAt(it.x, it.z).toDouble() }
        val band = Band(heights.min() - costs.bandMargin, heights.max() + costs.bandMargin)
        val g = Long2DoubleOpenHashMap().apply { defaultReturnValue(Double.POSITIVE_INFINITY) }
        val parent = Long2LongOpenHashMap().apply { defaultReturnValue(Long.MIN_VALUE) }
        val closed = LongOpenHashSet()
        val goals = LongOpenHashSet(); for (c in to) goals.add(c.key)
        fun heuristic(x: Int, z: Int): Double {
            var best = Double.MAX_VALUE
            for (t in to) { val d = Cell(x, z).distanceTo(t); if (d < best) best = d }
            return best * costs.base * costs.heuristicWeight
        }
        val open = PriorityQueue<Node>(compareBy { it.f })
        for (c in from) { g.put(c.key, 0.0); open.add(Node(c.key, heuristic(c.x, c.z))) }
        var expansions = 0
        var goal = Long.MIN_VALUE
        val rejects = if (slopeDivisor == 1.0) traceRejects.get() else null
        while (open.isNotEmpty()) {
            val node = open.poll()
            val i = node.key
            if (!closed.add(i)) continue
            if (slopeDivisor == 1.0) trace.get()?.add(i)
            if (goals.contains(i)) { goal = i; break }
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
                val nx = cx + d[0]
                val nz = cz + d[1]
                val j = Terrain.key(nx, nz)
                if (closed.contains(j)) continue
                if (!freeTurns) {
                    if (inDx * d[0] + inDz * d[1] < 0) { rejects?.let { it[R_TURN_BACK]++ }; continue }
                    // This cell's piece is known now (in from the parent, out to the neighbour): the rises it hosts —
                    // the climb into it and a descent out of it — must be ones the catalog has for that turn.
                    if (pi != Long.MIN_VALUE && costs.turnLimits.isNotEmpty() && slopeDivisor == 1.0 && terrain.inBounds(nx, nz)) {
                        val hn = terrain.heightAt(nx, nz)
                        // Sides face outward: toward the parent (−in direction) and toward the neighbour.
                        if (h > hParent && costs.turnLimits[PieceCatalog.turnIndex(-inDx, -inDz, d[0], d[1])] < h - hParent) { rejects?.let { it[R_TURN_RISE]++ }; continue }
                        if (hn < h && costs.turnLimits[PieceCatalog.turnIndex(d[0], d[1], -inDx, -inDz)] < h - hn) { rejects?.let { it[R_TURN_DROP]++ }; continue }
                    }
                }
                if (k >= 4 && slopeDivisor == 1.0 && cutsRoadCorner(terrain, cx, cz, d[0], d[1])) { rejects?.let { it[R_ROAD_CORNER]++ }; continue }
                val step = stepCost(terrain, nx, nz, h, k >= 4, costs, slopeDivisor, band, rejects) ?: continue
                val tentative = gi + step
                if (tentative < g.get(j)) {
                    g.put(j, tentative)
                    parent.put(j, i)
                    open.add(Node(j, tentative + heuristic(nx, nz)))
                }
            }
        }
        lastExpansions.set(expansions)
        if (goal == Long.MIN_VALUE) { lastFailure.set("search from ${from.size} start(s) near ${from.first()} exhausted after $expansions cell(s) without reaching ${to.size} goal(s) near ${to.first()} (cell ${terrain.cellSize})"); return null }
        val cells = ArrayList<Cell>()
        var cur = goal
        while (true) {
            cells.add(Cell.of(cur))
            val p = parent.get(cur)
            if (p == Long.MIN_VALUE) break
            cur = p
        }
        cells.reverse()
        return cells
    }

    /**
     * The road between two towns: from any of [a]'s exits to any of [b]'s, whichever pair is cheapest to
     * join — a town on a ledge is entered from the side a road can reach, not the side that faces the other
     * town. With [coarse], a corridor is found first as in [routeHierarchical], between the towns' exits on
     * the coarse map, and widened to cover both boxes.
     */
    fun routeTowns(fine: Terrain, coarse: Terrain?, ratio: Int, a: Town, b: Town, costs: PlannerCosts = PlannerCosts(), freeTurns: Boolean = false): List<Cell>? {
        val startEnds = ends(fine, a, b.cell) ?: run { lastFailure.set("no passable cell within reach of ${a.id}'s exits (cell ${fine.cellSize})"); return null }
        val goalEnds = ends(fine, b, a.cell) ?: run { lastFailure.set("no passable cell within reach of ${b.id}'s exits (cell ${fine.cellSize})"); return null }
        val starts = startEnds.map { it.goal }; val goals = goalEnds.map { it.goal }
        traceEnds.set(starts to goals); lastCorridor.set(emptyList())
        if (coarse == null) return search(fine, starts, goals, costs, 1.0, freeTurns)?.let { withStubs(it, startEnds, goalEnds) }
        fun onCoarse(t: Town) = Town(t.id, Cell(Math.floorDiv(t.cell.x, ratio), Math.floorDiv(t.cell.z, ratio)), t.exits.map { Cell(Math.floorDiv(it.x, ratio), Math.floorDiv(it.z, ratio)) }.distinct(), t.reach)
        val ca = onCoarse(a); val cb = onCoarse(b)
        val cs = endpoints(coarse, ca, cb.cell) ?: run { lastFailure.set("coarse: no passable cell within reach of ${a.id}'s exits"); return null }
        val cg = endpoints(coarse, cb, ca.cell) ?: run { lastFailure.set("coarse: no passable cell within reach of ${b.id}'s exits"); return null }
        val coarsePath = search(coarse, cs, cg, costs.copy(heuristicWeight = costs.reuseFactor), ratio.toDouble()) ?: run { lastFailure.set("coarse: " + lastFailure.get()); return null }
        val allowed = corridor(coarse, coarsePath, listOf(ca.cell to a.reach, cb.cell to b.reach), ratio)
        lastCorridor.set(allowed.map { Cell.of(it) })
        return search(CorridorTerrain(fine, ratio, allowed), starts, goals, costs, 1.0, freeTurns)?.let { withStubs(it, startEnds, goalEnds) }
    }

    /** An end of a route: the cell the search starts or stops at, and the street stub behind it when the town has one. */
    private class End(val goal: Cell, val stub: Cell?, val facing: Facing?)

    /**
     * A town's road ends. A street stub with a direction is entered along that direction: the search runs
     * to the cell just outside the stub and the stub itself is added afterwards ([withStubs]). Other exits
     * resolve to a passable cell as before.
     */
    private fun ends(terrain: Terrain, town: Town, toward: Cell): List<End>? {
        val out = ArrayList<End>()
        val cells = if (town.exits.isEmpty()) listOf(town.cell) else town.exits
        for ((i, e) in cells.withIndex()) {
            val f = town.facings.getOrNull(i)
            if (f != null) {
                val outside = Cell(e.x + f.dx, e.z + f.dz)
                if (passable(terrain, outside.x, outside.z) && passable(terrain, e.x, e.z)) { out.add(End(outside, e, f)); continue }
            }
            resolveEndpoint(terrain, e, toward = toward)?.let { out.add(End(it, null, null)) }
        }
        val seen = HashSet<Cell>()
        return out.filter { seen.add(it.goal) }.ifEmpty { null }
    }

    /** The stubs on both ends of [cells], when the road can turn into them without a turn past 90°. */
    private fun withStubs(cells: List<Cell>, starts: List<End>, goals: List<End>): List<Cell> {
        if (cells.size < 2) return cells
        var out = cells
        starts.firstOrNull { it.goal == out.first() && it.stub != null }?.let { s ->
            val dx = out[1].x - out[0].x; val dz = out[1].z - out[0].z
            if (s.facing!!.dx * dx + s.facing.dz * dz >= 0 && s.stub!! !in out) out = listOf(s.stub) + out
        }
        goals.firstOrNull { it.goal == out.last() && it.stub != null }?.let { g ->
            val n = out.size; val dx = out[n - 1].x - out[n - 2].x; val dz = out[n - 1].z - out[n - 2].z
            if (-(g.facing!!.dx) * dx - g.facing.dz * dz >= 0 && g.stub!! !in out) out = out + g.stub
        }
        return out
    }

    /** A town's exits resolved to passable cells (its own cell when it has no exits); null when none resolves. */
    private fun endpoints(terrain: Terrain, town: Town, toward: Cell): List<Cell>? {
        val cells = (if (town.exits.isEmpty()) listOf(town.cell) else town.exits).mapNotNull { resolveEndpoint(terrain, it, toward = toward) }.distinct()
        return cells.ifEmpty { null }
    }

    /**
     * The corridor on the coarse map: [CORRIDOR_HALF] blocks either side of [path], whatever the coarse cell
     * is, plus a disc around each of [discs] (a cell and a reach in fine cells) — each town's whole box, so
     * a route can get from an exit inside it to the corridor outside.
     */
    private fun corridor(coarse: Terrain, path: List<Cell>, discs: List<Pair<Cell, Int>>, ratio: Int): LongOpenHashSet {
        val allowed = LongOpenHashSet()
        val half = maxOf(1, Math.ceilDiv(CORRIDOR_HALF, coarse.cellSize))
        for (c in path) for (dz in -half..half) for (dx in -half..half) allowed.add(Terrain.key(c.x + dx, c.z + dz))
        for ((c, reach) in discs) {
            val r = half + Math.ceilDiv(reach, ratio)
            for (dz in -r..r) for (dx in -r..r) allowed.add(Terrain.key(c.x + dx, c.z + dz))
        }
        return allowed
    }

    /**
     * Two-level route: a corridor on the [coarse] map (cells [ratio] times the fine cell, weak heuristic so it
     * finds existing roads and rides them), then the fine route inside that corridor with the usual costs.
     * The fine map is only sampled inside the corridor, which is what makes this cheap.
     */
    fun routeHierarchical(fine: Terrain, coarse: Terrain, ratio: Int, from: Cell, to: Cell, costs: PlannerCosts = PlannerCosts(), fromReach: Int = 0, toReach: Int = 0, freeTurns: Boolean = false): List<Cell>? {
        val cf = Cell(Math.floorDiv(from.x, ratio), Math.floorDiv(from.z, ratio))
        val ct = Cell(Math.floorDiv(to.x, ratio), Math.floorDiv(to.z, ratio))
        val coarsePath = route(coarse, cf, ct, costs.copy(heuristicWeight = costs.reuseFactor), slopeDivisor = ratio.toDouble()) ?: run { lastFailure.set("coarse: " + lastFailure.get()); return null }
        val allowed = corridor(coarse, coarsePath, listOf(cf to fromReach, ct to toReach), ratio)
        return route(CorridorTerrain(fine, ratio, allowed), from, to, costs, freeTurns = freeTurns)
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
        /** Estimated ground is impassable: only heights the world really has may carry a route. */
        realTerrain: Boolean = false,
    ): RoadPlan {
        // Set here rather than left standing, so a caller that plans without a pass — a test, the
        // refiner — never inherits the last pass's rule.
        realTerrainOnly = realTerrain
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
            val raw = routeTowns(terrain, coarse, ratio, towns[i], towns[j], costs)
            if (raw == null) {
                io.github.veelume.postroad.Postroad.LOGGER.info("Pair {} ({} -> {}) unreachable: {}", id, towns[i].id, towns[j].id, lastFailure.get())
                // Judged on estimates? On the coarse corridor when there was one, else along the line between the towns.
                val corridor = lastCorridor.get() ?: emptyList()
                val estimated = if (coarse != null && corridor.isNotEmpty()) corridor.any { coarse.inBounds(it.x, it.z) && coarse.has(it.x, it.z, Terrain.ESTIMATED) }
                    else lineCells(towns[i].cell, towns[j].cell).any { terrain.inBounds(it.x, it.z) && terrain.has(it.x, it.z, Terrain.ESTIMATED) }
                dropped.add(DroppedPair(id, towns[i], towns[j], "unreachable", corridor = corridor, estimated = estimated)); known.add(id); continue
            }
            if (longestWaterRun(terrain, raw) > costs.maxWaterRun) {
                dropped.add(DroppedPair(id, towns[i], towns[j], "water", cells = raw, estimated = raw.any { terrain.has(it.x, it.z, Terrain.ESTIMATED) })); known.add(id); continue
            }
            // Snapping splices another road's cells in; those were judged on that road's heights, so the
            // result is checked on today's terrain and the raw route kept when a step no class allows.
            val snapped = snapExcursions(raw, owner, EXCURSION_MAX) { window -> stepsFeasible(terrain, window, costs) }
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

    /** The cells on the straight line from [a] to [b], one per step of the longer axis. */
    fun lineCells(a: Cell, b: Cell): List<Cell> {
        val n = maxOf(abs(b.x - a.x), abs(b.z - a.z), 1)
        return (0..n).map { k -> Cell(a.x + Math.round((b.x - a.x) * k.toDouble() / n).toInt(), a.z + Math.round((b.z - a.z) * k.toDouble() / n).toInt()) }.distinct()
    }

    /**
     * A route that leaves an existing road and rejoins the same road within [maxLen] cells is snapped
     * onto it: the excursion is replaced by the road's own cells between the two points (found by a
     * short search over that road's cells). Otherwise every bend would become a little ring.
     */
    fun snapExcursions(cells: List<Cell>, owner: Map<Cell, String>, maxLen: Int, accept: (List<Cell>) -> Boolean = { true }): List<Cell> {
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
                // [accept] judges the bridge with its seams (a cell either side), so one bad seam keeps only
                // this excursion, not every snap of the route.
                val bridge = roadPath(cells[leave - 1], cells[back], here, owner)?.takeIf { br ->
                    val window = ArrayList<Cell>()
                    if (leave >= 2) window.add(cells[leave - 2])
                    window.addAll(br)
                    if (back + 1 < cells.size) window.add(cells[back + 1])
                    accept(window)
                }
                if (bridge == null) { out.addAll(cells.subList(i, back)); i = back }
                else {
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
                // Round the road's corners, not across them: the bridge must be the road's own cells in order.
                if (d[0] != 0 && d[1] != 0 && (owner[Cell(c.x + d[0], c.z)] == road || owner[Cell(c.x, c.z + d[1])] == road)) continue
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
