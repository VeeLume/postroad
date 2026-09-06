package io.github.veelume.postroad.roads.gen

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import java.util.Random

/**
 * Lays one [PiecePlacement]: its rows' blocks at their levels, the ground under each block fitted
 * to the piece (cut, fill or deck), the decoration where nothing is in the way. Every block of a
 * placement follows from its anchors and its definition, so any chunk laying its part of a piece
 * lays the same blocks as any other. The same code serves the road feature at generation time
 * (a `WorldGenLevel`) and the chunk-load builder (a `ServerLevel`).
 */
object RoadPieceLayer {
    private const val HEADROOM = 3

    /**
     * Lays [p] with [style]'s materials. [groundAt] gives a column's top solid block or
     * `Int.MIN_VALUE` where nothing may be placed (water, unloaded); [inArea] limits writing (a
     * chunk); [footprint] holds every block column of the road so decoration stays off it. Returns
     * blocks placed.
     */
    fun lay(level: LevelAccessor, p: PiecePlacement, style: RoadStyle, styles: RoadStyleSet, groundAt: (Int, Int) -> Int,
            inArea: (Int, Int) -> Boolean = { _, _ -> true }, footprint: ((Int, Int) -> Boolean)? = null): Int {
        var placed = 0
        val seed = if (level is WorldGenLevel) level.seed else 0L
        if (p.piece.isDiagonal) {
            placed += layDiagonal(level, p, style, styles, groundAt, inArea, seed)
        } else {
            val ascent = Direction.fromDelta(p.dx, 0, p.dz) ?: Direction.NORTH
            for (k in 1..RoadPiece.LENGTH) {
                val row = p.piece.rows[k - 1]
                val centre = p.rowCentre(k)
                for (s in -RoadPiece.HALF_WIDTH..RoadPiece.HALF_WIDTH) {
                    // Perpendicular to the facing: (-dz, dx) is the right-hand side.
                    val x = centre.x - p.dz * s; val z = centre.z + p.dx * s
                    if (!inArea(x, z)) continue
                    val state = when (row.role) {
                        RoadPiece.ROLE_STAIR -> style.stairs.trySetValue(StairBlock.FACING, ascent)
                        RoadPiece.ROLE_SLAB -> style.slab
                        else -> (if (s == 0) style.surface else style.edge).pick(Random(Mth.getSeed(x, 0, z) xor seed))
                    }
                    placed += column(level, x, z, centre.y, state, p.piece.fit, style, styles, groundAt(x, z), stepOver = p.piece.rise == 0 && row.role == RoadPiece.ROLE_SURFACE)
                }
            }
            placed += decorate(level, p, style, styles, groundAt, inArea, footprint)
        }
        return placed
    }

    /**
     * One block of the road at (x, z), level [top]: the ground made to fit — cut down to it (up to
     * [PieceFit.cut], three of headroom kept clear), filled up to it (up to [PieceFit.fill]), or a
     * deck on one support over a deeper drop — then [state] at [top]. Returns blocks changed.
     */
    fun column(level: LevelAccessor, x: Int, z: Int, top0: Int, state: BlockState, fit: PieceFit, style: RoadStyle, styles: RoadStyleSet, ground: Int, stepOver: Boolean = false): Int {
        if (ground == Int.MIN_VALUE || ground <= level.minBuildHeight || top0 <= level.minBuildHeight) return 0
        // A flat road takes the ground's own top where it is one higher, instead of trenching a bump.
        val top = if (stepOver && ground == top0 + 1) ground else top0
        val groundState = level.getBlockState(BlockPos(x, ground, z))
        if (!groundState.fluidState.isEmpty || !level.getFluidState(BlockPos(x, ground + 1, z)).isEmpty) return 0
        if (ground >= top && !styles.isReplaceable(groundState)) return 0
        var changed = 0
        val air = Blocks.AIR.defaultBlockState()
        if (top < ground) {
            if (ground - top > fit.cut) return 0
            for (y in top + 1..ground + HEADROOM) {
                val pos = BlockPos(x, y, z)
                val s = level.getBlockState(pos)
                if (s.isAir) continue
                if (y <= ground && !styles.isReplaceable(s)) return changed
                if (y > ground && !styles.isClearable(s)) break
                level.setBlock(pos, air, 2 or 16); changed++
            }
        } else if (top > ground) {
            val drop = top - ground - 1
            val from = if (drop > fit.fill) { if (!fit.deck) return 0; top - 1 } else ground + 1
            for (y in from until top) {
                val pos = BlockPos(x, y, z)
                if (!styles.isClearable(level.getBlockState(pos))) return changed
                level.setBlock(pos, style.fill, 2 or 16); changed++
            }
            for (y in top + 1..top + HEADROOM) {
                val pos = BlockPos(x, y, z)
                val s = level.getBlockState(pos)
                if (s.isAir) continue
                if (!styles.isClearable(s)) break
                level.setBlock(pos, air, 2 or 16); changed++
            }
        } else {
            for (y in top + 1..top + HEADROOM) {
                val pos = BlockPos(x, y, z)
                val s = level.getBlockState(pos)
                if (s.isAir) continue
                if (!styles.isClearable(s)) break
                level.setBlock(pos, air, 2 or 16); changed++
            }
        }
        val topPos = BlockPos(x, top, z)
        if (top > ground && !styles.isClearable(level.getBlockState(topPos))) return changed
        if (level.getBlockState(topPos) != state) { level.setBlock(topPos, state, 2 or 16); changed++ }
        return changed
    }

    /**
     * The band between two anchors three apart on both axes: a zigzag of six 3×3 stamps, x first,
     * each stamp at its row's level — a slab row is the step up. Stamps own their centre; a ring
     * block belongs to the first stamp that reaches it.
     */
    private fun layDiagonal(level: LevelAccessor, p: PiecePlacement, style: RoadStyle, styles: RoadStyleSet, groundAt: (Int, Int) -> Int, inArea: (Int, Int) -> Boolean, seed: Long): Int {
        var placed = 0
        val owner = HashMap<Long, Int>()
        var cx = p.entry.x; var cz = p.entry.z
        val centres = ArrayList<IntArray>()
        for (k in 1..RoadPiece.LENGTH) { cx += p.dx; centres.add(intArrayOf(cx, cz)); cz += p.dz; centres.add(intArrayOf(cx, cz)) }
        for ((i, c) in centres.withIndex()) owner[key(c[0], c[1])] = i
        for ((i, c) in centres.withIndex()) for (oz in -1..1) for (ox in -1..1) owner.putIfAbsent(key(c[0] + ox, c[1] + oz), i)
        for ((i, c) in centres.withIndex()) {
            val row = p.piece.rows.getOrElse(i) { PieceRow(p.piece.rise, RoadPiece.ROLE_SURFACE) }
            val top = p.entry.y + row.level
            for (oz in -1..1) for (ox in -1..1) {
                val x = c[0] + ox; val z = c[1] + oz
                if (owner[key(x, z)] != i || !inArea(x, z)) continue
                val centreBlock = ox == 0 && oz == 0
                val state = if (row.role == RoadPiece.ROLE_SLAB) style.slab else (if (centreBlock) style.surface else style.edge).pick(Random(Mth.getSeed(x, 0, z) xor seed))
                placed += column(level, x, z, top, state, p.piece.fit, style, styles, groundAt(x, z), stepOver = p.piece.rise == 0)
            }
        }
        return placed
    }

    /**
     * A corner or an end: the anchor's own 3×3 square, flat at the anchor's level, laid after the
     * pieces so a rise ending on a corner ends on a landing. Called for every planned point where
     * the facing changes and for a road's two ends.
     */
    fun layCorner(level: LevelAccessor, anchor: BlockPos, style: RoadStyle, styles: RoadStyleSet, groundAt: (Int, Int) -> Int, inArea: (Int, Int) -> Boolean = { _, _ -> true }): Int {
        var placed = 0
        val seed = if (level is WorldGenLevel) level.seed else 0L
        for (oz in -1..1) for (ox in -1..1) {
            val x = anchor.x + ox; val z = anchor.z + oz
            if (!inArea(x, z)) continue
            val state = (if (ox == 0 && oz == 0) style.surface else style.edge).pick(Random(Mth.getSeed(x, 0, z) xor seed))
            placed += column(level, x, z, anchor.y, state, PieceFit(), style, styles, groundAt(x, z))
        }
        return placed
    }

    /** True when the road turns at point [i] of [points]: the facing into it differs from the facing out of it. */
    fun turnsAt(points: List<BlockPos>, i: Int): Boolean {
        if (i <= 0 || i >= points.size - 1) return true // the ends get a square too
        val a = points[i - 1]; val b = points[i]; val c = points[i + 1]
        return Integer.signum(b.x - a.x) != Integer.signum(c.x - b.x) || Integer.signum(b.z - a.z) != Integer.signum(c.z - b.z)
    }

    /** Decoration of a straight piece: posts and lamps off the side, only on solid ground and off every footprint. */
    private fun decorate(level: LevelAccessor, p: PiecePlacement, style: RoadStyle, styles: RoadStyleSet, groundAt: (Int, Int) -> Int, inArea: (Int, Int) -> Boolean, footprint: ((Int, Int) -> Boolean)?): Int {
        var placed = 0
        for (d in p.piece.decor) {
            if (d.every > 0 && p.index % d.every != 0) continue
            val side = if (p.index / maxOf(1, d.every) % 2 == 0) d.side else -d.side
            val centre = p.rowCentre(d.along.coerceIn(1, RoadPiece.LENGTH))
            val x = centre.x - p.dz * side; val z = centre.z + p.dx * side
            if (!inArea(x, z) || footprint?.invoke(x, z) == true) continue
            val ground = groundAt(x, z)
            if (ground == Int.MIN_VALUE) continue
            val pos = BlockPos(x, ground + d.up, z)
            val state = when (d.role) { RoadPiece.ROLE_LAMP -> style.lamp; RoadPiece.ROLE_POST -> style.post; else -> style.fill }
            if (!level.getBlockState(BlockPos(x, ground, z)).isSolid && d.up == 1) continue
            if (!styles.isClearable(level.getBlockState(pos))) continue
            level.setBlock(pos, state, 3); placed++
        }
        return placed
    }

    /** All block columns of the placements of a road, for keeping decoration off them. */
    fun footprintOf(placements: List<PiecePlacement>): it.unimi.dsi.fastutil.longs.LongOpenHashSet {
        val set = it.unimi.dsi.fastutil.longs.LongOpenHashSet()
        for (p in placements) {
            if (p.piece.isDiagonal) {
                var cx = p.entry.x; var cz = p.entry.z
                for (k in 1..RoadPiece.LENGTH) { cx += p.dx; stamp(set, cx, cz); cz += p.dz; stamp(set, cx, cz) }
            } else {
                for (k in 0..RoadPiece.LENGTH) for (s in -RoadPiece.HALF_WIDTH..RoadPiece.HALF_WIDTH) set.add(key(p.entry.x + p.dx * k - p.dz * s, p.entry.z + p.dz * k + p.dx * s))
            }
        }
        return set
    }

    private fun stamp(set: it.unimi.dsi.fastutil.longs.LongOpenHashSet, x: Int, z: Int) {
        for (oz in -1..1) for (ox in -1..1) set.add(key(x + ox, z + oz))
    }

    fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
}
