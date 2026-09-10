package io.github.veelume.postroad.roads.gen

import net.minecraft.core.BlockPos

/**
 * The height profile of a planned road, tidied before it becomes geometry.
 *
 * The route's heights are the ground's, cell for cell, and the ground wobbles by a block for no
 * reason a road should care about: a single low cell between two level ones became a piece down
 * and a piece straight back up (`--\/--`), which is not how a road crosses a dip. A road fills a
 * short hollow and shaves a short hump, and the pieces already can — every one of them fills up
 * to [FILL] and cuts up to [CUT]. So a run of at most [MAX_RUN] points lying below (or above)
 * both of its neighbours is brought to the nearer neighbour's level.
 *
 * Levelling only ever shrinks the rise between two points, so a profile the catalog could build
 * stays one it can build.
 */
object RoadProfile {
    const val FILL = 3
    const val CUT = 4
    const val MAX_RUN = 2

    /**
     * [points] with short dips filled and short humps shaved. Points whose index is in [frozen]
     * keep their height — a point shared with a road already in the world lays the same blocks
     * twice, so its level is not ours to change.
     */
    fun level(points: List<BlockPos>, frozen: Set<Int> = emptySet()): List<BlockPos> {
        if (points.size < 3) return points
        val y = IntArray(points.size) { points[it].y }
        var i = 1
        while (i < points.size - 1) {
            var moved = false
            // Longest run first: a two-cell hollow is one hollow, not two one-cell ones.
            for (len in MAX_RUN downTo 1) {
                val j = i + len - 1
                if (j >= points.size - 1) continue
                if ((i..j).any { it in frozen }) continue
                val before = y[i - 1]
                val after = y[j + 1]
                val lowest = (i..j).minOf { y[it] }
                val highest = (i..j).maxOf { y[it] }
                val target = when {
                    highest < before && highest < after -> minOf(before, after).takeIf { it - lowest <= FILL }
                    lowest > before && lowest > after -> maxOf(before, after).takeIf { highest - it <= CUT }
                    else -> null
                } ?: continue
                for (k in i..j) y[k] = target
                i = j + 1
                moved = true
                break
            }
            if (!moved) i++
        }
        return points.mapIndexed { k, p -> if (y[k] == p.y) p else BlockPos(p.x, y[k], p.z) }
    }
}
