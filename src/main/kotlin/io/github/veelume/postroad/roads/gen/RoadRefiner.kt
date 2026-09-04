package io.github.veelume.postroad.roads.gen

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The block-level pass: once a chunk exists, the planned line through it (4-block points) is
 * re-routed block by block on the real terrain inside a corridor around it — around tree trunks,
 * bumps and puddles the planner could not see — from the run's first point to its exit point, so
 * neighbouring chunks meet exactly at the shared point.
 */
object RoadRefiner {
    const val CORRIDOR = 6.0
    private const val MAX_EXPANSIONS = 20_000

    private val NEIGHBOURS = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
    )

    /** What a column costs to step onto, from the real world; null = impassable. */
    class Column(val ground: Int, val passable: Boolean)

    /**
     * Routes from [waypoints].first() to [waypoints].last() on the real terrain within [CORRIDOR]
     * blocks of the polyline. Returns the block path (adjacent (x, z) pairs, both ends included),
     * or null when nothing fits — the caller then lays the straight line as before.
     */
    fun refine(level: ServerLevel, waypoints: List<BlockPos>, styles: RoadStyleSet, boxes: List<BoundingBox>): List<IntArray>? {
        if (waypoints.size < 2) return null
        val start = waypoints.first()
        val goal = waypoints.last()
        val columns = HashMap<Long, Column>()
        fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
        val hint = waypoints.first().y
        fun column(x: Int, z: Int): Column = columns.getOrPut(key(x, z)) { sample(level, x, z, styles, boxes, hint) }
        fun inCorridor(x: Int, z: Int): Boolean {
            var best = Double.MAX_VALUE
            for (i in 1 until waypoints.size) {
                val d = distanceToSegment(x + 0.5, z + 0.5, waypoints[i - 1], waypoints[i])
                if (d < best) best = d
            }
            return best <= CORRIDOR
        }

        val g = Long2DoubleOpenHashMap().apply { defaultReturnValue(Double.POSITIVE_INFINITY) }
        val parent = Long2LongOpenHashMap().apply { defaultReturnValue(Long.MIN_VALUE) }
        val closed = LongOpenHashSet()
        val s = key(start.x, start.z)
        val e = key(goal.x, goal.z)
        g.put(s, 0.0)
        val open = PriorityQueue<Node>(compareBy { it.f })
        open.add(Node(s, heuristic(start.x, start.z, goal)))
        var expansions = 0
        while (open.isNotEmpty()) {
            val n = open.poll()
            if (!closed.add(n.key)) continue
            if (n.key == e) break
            if (++expansions > MAX_EXPANSIONS) return null
            val cx = (n.key shr 32).toInt(); val cz = n.key.toInt()
            val here = column(cx, cz)
            val gi = g.get(n.key)
            for ((k, d) in NEIGHBOURS.withIndex()) {
                val nx = cx + d[0]; val nz = cz + d[1]
                val nk = key(nx, nz)
                if (closed.contains(nk) || !inCorridor(nx, nz)) continue
                val col = column(nx, nz)
                // The goal may sit in an unloaded chunk or on odd ground; it is always allowed.
                if (!col.passable && nk != e) continue
                var step = if (k >= 4) SQRT2 else 1.0
                if (here.ground != Int.MIN_VALUE && col.ground != Int.MIN_VALUE) {
                    val dh = abs(col.ground - here.ground)
                    step += when (dh) { 0 -> 0.0; 1 -> 2.5; 2 -> 6.0; else -> 30.0 }
                    if (dh > 0 && k >= 4) step += 2.0 // a climb on a diagonal cannot carry stairs
                }
                val tentative = gi + step
                if (tentative < g.get(nk)) {
                    g.put(nk, tentative); parent.put(nk, n.key)
                    open.add(Node(nk, tentative + heuristic(nx, nz, goal)))
                }
            }
        }
        if (s != e && parent.get(e) == Long.MIN_VALUE) return null
        val path = ArrayList<IntArray>()
        var cur = e
        while (true) {
            path.add(intArrayOf((cur shr 32).toInt(), cur.toInt()))
            if (cur == s) break
            cur = parent.get(cur)
            if (cur == Long.MIN_VALUE) return null
        }
        path.reverse()
        return path
    }

    private fun heuristic(x: Int, z: Int, goal: BlockPos): Double {
        val dx = (goal.x - x).toDouble(); val dz = (goal.z - z).toDouble()
        return sqrt(dx * dx + dz * dz)
    }

    /** One column of the real world: its ground height and whether a road may cross it. */
    fun sample(level: ServerLevel, x: Int, z: Int, styles: RoadStyleSet, boxes: List<BoundingBox>, hint: Int): Column {
        if (!level.hasChunk(x shr 4, z shr 4)) return Column(Int.MIN_VALUE, true) // unknown: assume fine
        if (boxes.any { x >= it.minX() - 1 && x <= it.maxX() + 1 && z >= it.minZ() - 1 && z <= it.maxZ() + 1 }) return Column(Int.MIN_VALUE, false)
        val ground = RoadBuilder.groundY(level, x, z, hint, styles)
        if (ground <= level.minBuildHeight) return Column(ground, false)
        val pos = BlockPos(x, ground, z)
        val state = level.getBlockState(pos)
        if (!state.fluidState.isEmpty || !level.getFluidState(pos.above()).isEmpty) return Column(ground, false)
        if (!styles.isReplaceable(state)) return Column(ground, false) // a trunk, a rock, someone's wall
        return Column(ground, true)
    }

    private fun distanceToSegment(px: Double, pz: Double, a: BlockPos, b: BlockPos): Double {
        val ax = a.x + 0.5; val az = a.z + 0.5; val bx = b.x + 0.5; val bz = b.z + 0.5
        val dx = bx - ax; val dz = bz - az
        val len2 = dx * dx + dz * dz
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (pz - az) * dz) / len2).coerceIn(0.0, 1.0)
        val qx = ax + t * dx; val qz = az + t * dz
        return sqrt((px - qx) * (px - qx) + (pz - qz) * (pz - qz))
    }

    private class Node(val key: Long, val f: Double)
    private val SQRT2 = sqrt(2.0)
}
