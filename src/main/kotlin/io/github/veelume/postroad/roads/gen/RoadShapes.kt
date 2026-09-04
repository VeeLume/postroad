package io.github.veelume.postroad.roads.gen

/**
 * What a road column carries where the road rises: nothing on the flat, a slab on a lone rise
 * or on a diagonal step (stairs only face the four cardinals), stairs where rises come every
 * other column — the stairs road: stair, landing, stair. One table for both builders.
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
        // The rise this column carries sits between i and i+1 (up) or i-1 and i (down). Another rise of the
        // same sense within two columns either side makes it part of a staircase.
        fun rise(j: Int): Int = if (j in 1 until path.size && target[j] != Int.MIN_VALUE && target[j - 1] != Int.MIN_VALUE) (target[j] - target[j - 1]).coerceIn(-1, 1) else 0
        val at = if (up != null) i + 1 else i
        val sense = rise(at)
        val inRun = sense != 0 && (rise(at - 2) == sense || rise(at + 2) == sense || rise(at - 1) == sense || rise(at + 1) == sense)
        val diagonal = higher[0] != c[0] && higher[1] != c[1]
        return Shape(if (inRun && !diagonal) STAIRS else SLAB, higher)
    }
}
