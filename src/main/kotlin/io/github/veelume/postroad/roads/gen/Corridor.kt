package io.github.veelume.postroad.roads.gen

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos

/**
 * The chunks to make real before a route between two towns is searched.
 *
 * The shape is a diamond: widest halfway between the towns, narrowing to the towns themselves. That
 * matches where a route needs freedom — the ends are pinned to the towns' street stubs, the middle
 * is where it must be able to go round a ridge or a lake. A rectangle would generate the same amount
 * of chunk work in places the route can never use.
 *
 * Sizing is the trade: too narrow and the only route out lies outside the generated ground, so the
 * pair fails and the corridor has to be widened and retried; too wide and chunks are generated that
 * no road will ever touch.
 */
object Corridor {

    /**
     * Chunk positions of the diamond between [a] and [b]. [halfWidth] is the half-width in blocks at
     * the midpoint, [endHalf] the half-width at each town, so a corridor never pinches to nothing
     * where the endpoints still need room to manoeuvre.
     */
    fun diamond(a: BlockPos, b: BlockPos, halfWidth: Int, endHalf: Int = 24): LongOpenHashSet {
        val out = LongOpenHashSet()
        val ax = a.x.toDouble(); val az = a.z.toDouble()
        val dx = (b.x - a.x).toDouble(); val dz = (b.z - a.z).toDouble()
        val lengthSq = dx * dx + dz * dz
        val pad = maxOf(halfWidth, endHalf) + 16
        val minX = minOf(a.x, b.x) - pad; val maxX = maxOf(a.x, b.x) + pad
        val minZ = minOf(a.z, b.z) - pad; val maxZ = maxOf(a.z, b.z) + pad
        for (cz in (minZ shr 4)..(maxZ shr 4)) {
            for (cx in (minX shr 4)..(maxX shr 4)) {
                // The chunk's centre decides; a chunk is 16 blocks, well under any useful width.
                val px = (cx shl 4) + 8.0
                val pz = (cz shl 4) + 8.0
                val allowed = if (lengthSq <= 0.0) {
                    maxOf(halfWidth, endHalf).toDouble()
                } else {
                    // t: how far along the line the point projects, clamped to the segment.
                    val t = (((px - ax) * dx + (pz - az) * dz) / lengthSq).coerceIn(0.0, 1.0)
                    // 1 at the middle, 0 at either end: the diamond's taper.
                    val taper = 1.0 - kotlin.math.abs(2.0 * t - 1.0)
                    endHalf + (halfWidth - endHalf) * taper
                }
                val t = if (lengthSq <= 0.0) 0.0 else (((px - ax) * dx + (pz - az) * dz) / lengthSq).coerceIn(0.0, 1.0)
                val nx = ax + dx * t
                val nz = az + dz * t
                val distance = kotlin.math.sqrt((px - nx) * (px - nx) + (pz - nz) * (pz - nz))
                if (distance <= allowed + 8.0) out.add(ChunkPos.asLong(cx, cz))
            }
        }
        return out
    }

    /** The chunks of a square around [center], for the area around a town itself. */
    fun square(center: BlockPos, radius: Int): LongOpenHashSet {
        val out = LongOpenHashSet()
        for (cz in ((center.z - radius) shr 4)..((center.z + radius) shr 4)) {
            for (cx in ((center.x - radius) shr 4)..((center.x + radius) shr 4)) out.add(ChunkPos.asLong(cx, cz))
        }
        return out
    }
}
