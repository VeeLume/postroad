package io.github.veelume.postroad.roads.gen

/**
 * Terrain as a picture, from a grid of heights and [Terrain] flags: land coloured by height (green
 * flats, then yellow, brown, grey rock and snow), relief-shaded with the light in the north-west,
 * water blue, lava orange. Used by the plan export and by the road map's background.
 */
object TerrainShading {

    /** RGB per cell of a [width]×[height] grid, row by row. [tintEstimates] marks guessed ground violet (the export's debug view). */
    fun colors(heights: IntArray, flags: IntArray, width: Int, height: Int = width, tintEstimates: Boolean = false, showBlocked: Boolean = true): IntArray {
        var minH = Int.MAX_VALUE
        var maxH = Int.MIN_VALUE
        for ((i, h) in heights.withIndex()) {
            if (flags[i] and Terrain.WATER != 0) continue
            if (h < minH) minH = h; if (h > maxH) maxH = h
        }
        if (minH >= maxH) { minH = 60; maxH = 120 }

        fun heightAt(px: Int, pz: Int): Int? = if (px in 0 until width && pz in 0 until height) heights[pz * width + px] else null

        val out = IntArray(width * height)
        for (pz in 0 until height) for (px in 0 until width) {
            val h = heights[pz * width + px]
            val f = flags[pz * width + px]
            out[pz * width + px] = when {
                f and Terrain.LAVA != 0 -> 0xc04010
                showBlocked && f and Terrain.BLOCKED != 0 -> 0x803030
                f and Terrain.WATER != 0 -> 0x2050a0
                else -> {
                    val t = ((h - minH).toDouble() / (maxH - minH).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                    val base = hypsometric(t)
                    var r = base.first
                    var g = base.second
                    var b = base.third
                    // Relief shading with the light in the north-west: the slope across the cell,
                    // which is what turns a height map into something a person reads as terrain.
                    val west = heightAt(px - 1, pz)
                    val east = heightAt(px + 1, pz)
                    val north = heightAt(px, pz - 1)
                    val south = heightAt(px, pz + 1)
                    if (west != null && east != null && north != null && south != null) {
                        val slope = ((west - east) + (north - south)) / 2.0
                        val shade = 1.0 + (slope / 6.0).coerceIn(-1.0, 1.0) * 0.45
                        r = (r * shade).toInt().coerceIn(0, 255)
                        g = (g * shade).toInt().coerceIn(0, 255)
                        b = (b * shade).toInt().coerceIn(0, 255)
                    }
                    // Ground the planner only guessed at: tinted violet, so a road crossing one is
                    // obvious. After a pipeline run there should be none under any road.
                    if (tintEstimates && f and Terrain.ESTIMATED != 0) {
                        r = (r * 0.75 + 70).toInt().coerceIn(0, 255)
                        b = (b * 0.75 + 90).toInt().coerceIn(0, 255)
                        g = (g * 0.70).toInt().coerceIn(0, 255)
                    }
                    (r shl 16) or (g shl 8) or b
                }
            }
        }
        return out
    }

    // Land colour by height, low to high. A flat brown ramp made a mountain and a meadow look much
    // the same, which is no use for judging whether a road went round a mountain or over it.
    private fun hypsometric(t: Double): Triple<Int, Int, Int> {
        val stops = listOf(
            0.00 to Triple(60, 120, 70),
            0.25 to Triple(110, 155, 80),
            0.45 to Triple(190, 185, 110),
            0.65 to Triple(160, 130, 90),
            0.82 to Triple(130, 120, 115),
            1.00 to Triple(240, 240, 245),
        )
        val hi = stops.indexOfFirst { it.first >= t }.coerceAtLeast(1)
        val low = stops[hi - 1]
        val high = stops[hi]
        val f = ((t - low.first) / (high.first - low.first).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
        return Triple(
            (low.second.first + (high.second.first - low.second.first) * f).toInt(),
            (low.second.second + (high.second.second - low.second.second) * f).toInt(),
            (low.second.third + (high.second.third - low.second.third) * f).toInt(),
        )
    }
}
