package io.github.veelume.postroad.roads.gen

import net.minecraft.core.BlockPos
import kotlin.math.abs
import kotlin.math.max

/**
 * What a plan costs to walk and to build, in numbers a change can be judged by.
 *
 * Road and junction counts turned out to be nearly blind to route quality: two plans of the same
 * seed both came to 55 roads while sharing only 38 % of their road cells. Every metric said "no
 * change" while the routes had in fact moved almost everywhere. These are aimed at what the terrain
 * costs are actually for, so a cost weight can be judged by a number that moves when routes move.
 *
 *  - [climb] is every block a traveller goes up or down, added together.
 *  - [wastedClimb] is that minus the height the road actually had to gain — the up-and-down-again a
 *    real road spends money to avoid, and the thing "roads avoid unnecessary elevation changes" means.
 *  - [crossFall] is how far the ground falls *across* the road, added over its length: the cut and
 *    fill a builder would have to move, and what `PlannerCosts.crossSlope` charges for.
 *  - [maxStep] and [steepSteps] say whether the gentleness is real or an average hiding a wall.
 */
object PlanMetrics {

    class Metrics(
        val roads: Int,
        val cells: Int,
        val climb: Int,
        val wastedClimb: Int,
        val crossFall: Double,
        val maxStep: Int,
        val steepSteps: Int,
    ) {
        /** Per cell, so plans of different length can be compared. */
        val climbPerCell: Double get() = if (cells > 0) climb.toDouble() / cells else 0.0
        val crossFallPerCell: Double get() = if (cells > 0) crossFall / cells else 0.0

        override fun toString(): String =
            "%d road(s), %d cell(s); climb %d (%.3f per cell), wasted %d, cross-fall %.1f (%.3f per cell), max step %d, %d step(s) over 1"
                .format(roads, cells, climb, climbPerCell, wastedClimb, crossFall, crossFallPerCell, maxStep, steepSteps)
    }

    /**
     * Measures [routes], each a list of points in world blocks. [terrain] is only needed for
     * [Metrics.crossFall]; without it that stays zero and the rest still holds.
     */
    fun of(routes: List<List<BlockPos>>, terrain: Terrain? = null): Metrics {
        var cells = 0
        var climb = 0
        var wasted = 0
        var crossFall = 0.0
        var maxStep = 0
        var steep = 0
        for (points in routes) {
            if (points.size < 2) continue
            cells += points.size
            var roadClimb = 0
            for (i in 1 until points.size) {
                val step = abs(points[i].y - points[i - 1].y)
                roadClimb += step
                maxStep = max(maxStep, step)
                if (step > 1) steep++
            }
            climb += roadClimb
            // What the road had to gain is the difference between its ends; everything else is spent
            // going up only to come down again.
            wasted += roadClimb - abs(points.last().y - points.first().y)
            if (terrain != null) crossFall += crossFallOf(points, terrain)
        }
        return Metrics(routes.count { it.size >= 2 }, cells, climb, wasted, crossFall, maxStep, steep)
    }

    /** How far the ground falls across the road, added along it, in blocks. */
    private fun crossFallOf(points: List<BlockPos>, terrain: Terrain): Double {
        var total = 0.0
        val size = terrain.cellSize
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = Integer.signum(b.x - a.x)
            val dz = Integer.signum(b.z - a.z)
            if (dx == 0 && dz == 0) continue
            // The two cells either side of the direction of travel, a cell out from the road.
            val left = terrain.blockToCell(b.x - dz * size, b.z + dx * size)
            val right = terrain.blockToCell(b.x + dz * size, b.z - dx * size)
            if (!terrain.inBounds(left.x, left.z) || !terrain.inBounds(right.x, right.z)) continue
            total += abs(terrain.heightAt(left.x, left.z) - terrain.heightAt(right.x, right.z)) / 2.0
        }
        return total
    }
}
