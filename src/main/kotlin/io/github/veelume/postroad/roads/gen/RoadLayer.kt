package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.PostroadConfig
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Lays one chunk's run of a road into a chunk that is being generated, after the surface and
 * before vegetation: the profile along the strip flattened and smoothed to one block per column,
 * columns cut or filled, slabs and stairs on rises, lampposts at intervals. Water columns are
 * skipped (no bridges yet). The same shaping the chunk-load builder does on finished terrain.
 */
object RoadLayer {
    /**
     * Lays [run] inside [chunk] of [level]; returns the blocks it placed. Tests hand in their own
     * [groundAt] (the arena's heightmaps count the harness's barriers) and no [chunk] clip.
     */
    fun lay(level: WorldGenLevel, run: RoadPlanSnapshot.Segment, chunk: ChunkPos?, groundAt: ((Int, Int) -> Int)? = null): Int {
        val points = run.points
        if (points.size < 2) return 0
        val styles = RoadStyles.current
        val style = styles.style(run.family)
        val half = PostroadConfig.buildWidth / 2
        val lampInterval = PostroadConfig.buildLampInterval
        val path = RoadBuilder.straight(points)
        // The generated ground per column; water columns count as unknown and are left alone.
        fun ground(x: Int, z: Int): Int {
            if (groundAt != null) return groundAt(x, z)
            if (!level.hasChunk(x shr 4, z shr 4)) return Int.MIN_VALUE
            val y = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1
            if (y <= level.minBuildHeight) return Int.MIN_VALUE
            if (!level.getFluidState(BlockPos(x, y + 1, z)).isEmpty) return Int.MIN_VALUE
            return y
        }
        fun ground(c: IntArray): Int = ground(c[0], c[1])
        val terrain = path.map(::ground)
        val beforeH = if (run.before.isNotEmpty()) RoadBuilder.straight(run.before + points.first()).dropLast(1).map(::ground) else emptyList()
        val afterH = if (run.after.isNotEmpty()) RoadBuilder.straight(listOf(points.last()) + run.after).drop(1).map(::ground) else emptyList()
        val flat = RoadBuilder.flatten(beforeH + terrain + afterH).toList().subList(beforeH.size, beforeH.size + terrain.size)
        val target = RoadBuilder.pace(RoadBuilder.smooth(flat, null), path)
        // Every strip block belongs to one column: its own centre, else the first stamp that reaches it.
        val owner = it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
        fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
        // Own centre first, then the blocks straight beside a column (its row of the strip), then diagonals:
        // a rise's slab or stairs then span the whole width of that row.
        for ((i, c) in path.withIndex()) owner[key(c[0], c[1])] = i
        for (ring in 0..1) for ((i, c) in path.withIndex()) for (dz in -half..half) for (dx in -half..half) {
            if (dx * dx + dz * dz > (half + 0.5) * (half + 0.5)) continue
            val diagonal = dx != 0 && dz != 0
            if ((ring == 0) == diagonal) continue
            owner.putIfAbsent(key(c[0] + dx, c[1] + dz), i)
        }
        fun inChunk(x: Int, z: Int): Boolean = chunk == null || ((x shr 4) == chunk.x && (z shr 4) == chunk.z)
        var placed = 0
        for ((i, c) in path.withIndex()) {
            if (target[i] == Int.MIN_VALUE) continue
            val shape = RoadShapes.at(path, target, i)
            for (dz in -half..half) for (dx in -half..half) {
                if (dx * dx + dz * dz > (half + 0.5) * (half + 0.5)) continue
                val x = c[0] + dx; val z = c[1] + dz
                if (owner[key(x, z)] != i || !inChunk(x, z)) continue
                val g = ground(x, z)
                if (g == Int.MIN_VALUE) continue
                val centre = dx == 0 && dz == 0
                placed += RoadBuilder.placeColumnAt(level, x, z, target[i], g, if (centre) style.surface else style.edge, style, styles, shape.kind, c, shape.higher ?: c)
            }
            placed += RoadBuilder.embank(level, c, half, target[i], style, styles) { x, z -> if (inChunk(x, z)) ground(x, z) else Int.MIN_VALUE }
            if (lampInterval > 0 && i > 0 && i % lampInterval == 0) {
                val prev = path[i - 1]
                val dxp = (c[0] - prev[0]).toDouble(); val dzp = (c[1] - prev[1]).toDouble()
                val len = Math.sqrt(dxp * dxp + dzp * dzp).takeIf { it > 0 } ?: 1.0
                val side = if ((i / lampInterval) % 2 == 0) 1 else -1
                val lx = Math.round(c[0] - dzp / len * (half + 1) * side).toInt()
                val lz = Math.round(c[1] + dxp / len * (half + 1) * side).toInt()
                if (inChunk(lx, lz)) placed += RoadBuilder.placeLamppostAt(level, lx, lz, ground(lx, lz), style, styles)
            }
        }
        return placed
    }
}
