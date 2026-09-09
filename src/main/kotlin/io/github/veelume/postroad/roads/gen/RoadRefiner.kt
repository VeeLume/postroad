package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation

/**
 * Fixes the stretches of a planned road that do not survive being put on the real ground.
 *
 * A road planned over estimated terrain carries estimated heights. Snapping each point down to the
 * ground it actually stands on (see `RoadGen.refit`) is not enough on its own: the rise between two
 * neighbouring points changes with them, and where the real relief differs from the estimate the new
 * step is steeper than any piece in the catalog can span. A point cannot fix that alone, because the
 * constraint lives *between* points.
 *
 * So for each stretch that fails, this routes again — a small A* on the real terrain inside a
 * corridor around the planned line, using the same step classes and costs the planner uses, so what
 * comes out is buildable by construction. The line may move sideways by a few blocks, which is
 * exactly the freedom a height-only fix lacks.
 *
 * This is the successor of the build-time refiner that was lost when the piece catalog replaced the
 * per-column shaper; the difference is that it runs once when a road goes final, not per chunk.
 */
object RoadRefiner {

    /** How far either side of the planned line the new route may wander, in blocks. */
    const val CORRIDOR = 9

    /** A failing stretch longer than this is left alone: it is a bad plan, not a local mismatch. */
    private const val MAX_RUN = 24

    class Result(val points: List<BlockPos>, val families: ByteArray)

    /**
     * [points] with every unbuildable stretch routed again on the ground [dimension] really has.
     * Returns null when nothing could be fixed; the caller then keeps the road as planned.
     */
    fun refine(dimension: ResourceLocation, points: List<BlockPos>, families: ByteArray): Result? {
        val catalog = RoadPieces.current
        val costs = PlannerRules.current.copy(
            steps = catalog.stepClasses(),
            diagonalSteps = catalog.diagonalStepClasses(),
            turnLimits = catalog.turnLimits(),
        )
        var current = points.toMutableList()
        var currentFamilies = families
        var repaired = 0
        // Each repair changes the indices after it, so find the next failure from scratch each time.
        var guard = 0
        while (guard++ < 16) {
            val bad = current.indices.firstOrNull { i -> catalog.needsAt(current, i)?.let { catalog.match(it) } == null } ?: break
            val run = runAround(current, bad, catalog)
            val from = (run.first - 1).coerceAtLeast(0)
            val to = (run.last + 1).coerceAtMost(current.size - 1)
            if (to - from > MAX_RUN || to <= from) return null
            val fixed = reroute(dimension, current, from, to, costs) ?: return null
            currentFamilies = spliceFamilies(currentFamilies, from, to, fixed.size)
            val next = ArrayList<BlockPos>(current.size - (to - from + 1) + fixed.size)
            next.addAll(current.subList(0, from))
            next.addAll(fixed)
            next.addAll(current.subList(to + 1, current.size))
            current = next
            repaired++
        }
        if (repaired == 0) return null
        // Everything must be buildable now, or the repair was not one.
        val stillBad = current.indices.firstOrNull { i -> catalog.needsAt(current, i)?.let { catalog.match(it) } == null }
        if (stillBad != null) return null
        Postroad.LOGGER.info("Refiner: {} stretch(es) routed again on the real ground, {} point(s) -> {}", repaired, points.size, current.size)
        return Result(current, currentFamilies)
    }

    /** The run of consecutive failing points around [at]. */
    private fun runAround(points: List<BlockPos>, at: Int, catalog: PieceCatalog): IntRange {
        fun bad(i: Int) = catalog.needsAt(points, i)?.let { catalog.match(it) } == null
        var first = at
        var last = at
        while (first > 0 && bad(first - 1)) first--
        while (last < points.size - 1 && bad(last + 1)) last++
        return first..last
    }

    /** A* between the two anchors on the real ground, inside a corridor around the planned stretch. */
    private fun reroute(dimension: ResourceLocation, points: List<BlockPos>, from: Int, to: Int, costs: PlannerCosts): List<BlockPos>? {
        val cell = RoadGen.CELL_SIZE
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
        for (i in from..to) {
            minX = minOf(minX, points[i].x); maxX = maxOf(maxX, points[i].x)
            minZ = minOf(minZ, points[i].z); maxZ = maxOf(maxZ, points[i].z)
        }
        // The origin stays on the global cell lattice, so the cells here are the planner's cells and
        // a spliced point lands where a point of this road would have landed anyway.
        val originX = Math.floorDiv(minX - CORRIDOR, cell) * cell
        val originZ = Math.floorDiv(minZ - CORRIDOR, cell) * cell
        val width = (maxX + CORRIDOR - originX) / cell + 1
        val height = (maxZ + CORRIDOR - originZ) / cell + 1
        if (width <= 0 || height <= 0 || width.toLong() * height > 40_000) return null

        val grid = TerrainGrid(originX, originZ, cell, width, height)
        var known = 0
        for (cz in 0 until height) for (cx in 0 until width) {
            val bx = originX + cx * cell + cell / 2
            val bz = originZ + cz * cell + cell / 2
            val column = KnownTerrain.column(dimension, bx, bz)
            if (column == null) {
                // Unknown ground is not something to route over blind.
                grid.set(cx, cz, Terrain.BLOCKED)
                continue
            }
            grid.setHeight(cx, cz, column.top)
            if (column.water) grid.set(cx, cz, Terrain.WATER)
            if (column.lava) grid.set(cx, cz, Terrain.LAVA)
            if (column.blocked) grid.set(cx, cz, Terrain.BLOCKED)
            known++
        }
        if (known < width * height / 2) return null

        val a = grid.blockToCell(points[from].x, points[from].z)
        val b = grid.blockToCell(points[to].x, points[to].z)
        if (!grid.inBounds(a.x, a.z) || !grid.inBounds(b.x, b.z)) return null
        // The anchors are road, whatever the ground says: they are shared with the rest of the line.
        grid.clear(a.x, a.z, Terrain.BLOCKED); grid.clear(b.x, b.z, Terrain.BLOCKED)
        val route = RoadPlanner.route(grid, a, b, costs) ?: return null
        return route.map { grid.cellToBlock(it.x, it.z) }
    }

    /** Families for a spliced line: the replaced stretch takes the family of its first point. */
    private fun spliceFamilies(families: ByteArray, from: Int, to: Int, size: Int): ByteArray {
        val family = families.getOrElse(from) { 0 }
        val out = ByteArray(families.size - (to - from + 1) + size)
        var k = 0
        for (i in 0 until from) out[k++] = families[i]
        repeat(size) { out[k++] = family }
        for (i in to + 1 until families.size) out[k++] = families[i]
        return out
    }
}
