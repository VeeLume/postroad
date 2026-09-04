package io.github.veelume.postroad.roads.gen

/**
 * What a road column carries where the road rises: nothing on the flat, a slab on a lone one-block
 * rise or on a diagonal step (stairs only face the four cardinals), stairs inside a run of rises.
 * One table for both builders: the chunk-load builder and the structure piece.
 */
object RoadShapes {
    const val FLAT = 0
    const val SLAB = 1
    const val STAIRS = 2

    /** [kind] and, for a rise, the higher neighbouring column the shape faces. */
    class Shape(val kind: Int, val higher: IntArray?)

    /** The shape at column [i] of [path], whose road heights are [target] (`Int.MIN_VALUE` = unknown). */
    fun at(path: List<IntArray>, target: IntArray, i: Int): Shape {
        val c = path[i]
        val up = if (i + 1 < path.size && target[i + 1] == target[i] + 1) path[i + 1] else null
        val down = if (i > 0 && target[i - 1] == target[i] + 1) path[i - 1] else null
        val higher = up ?: down ?: return Shape(FLAT, null)
        val inRun = (i > 0 && target[i - 1] != target[i]) && (i + 1 < path.size && target[i + 1] != target[i]) ||
            (up != null && i + 2 < path.size && target[i + 2] != target[i + 1]) ||
            (down != null && i >= 2 && target[i - 2] != target[i - 1])
        val diagonal = higher[0] != c[0] && higher[1] != c[1]
        return Shape(if (inRun && !diagonal) STAIRS else SLAB, higher)
    }
}
