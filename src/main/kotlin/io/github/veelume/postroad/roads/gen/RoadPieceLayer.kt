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
 * Lays one [PiecePlacement]: every block of the piece at its transformed position, the ground
 * under each road block fitted to the piece (cut, fill or deck), then the decoration where no
 * road block is. A placement's blocks follow from its anchor, base, transform and the piece's
 * definition alone, so any chunk laying its part of a piece lays the same blocks as any other.
 * The same code serves the road feature at generation time and the chunk-load builder.
 */
object RoadPieceLayer {
    private const val HEADROOM = 3

    /**
     * Lays [p] with [style]'s materials. [groundAt] gives a column's top solid block or
     * `Int.MIN_VALUE` where nothing may be placed (water, unloaded); [inArea] limits writing (a
     * chunk); [footprint] holds every road-block column of the road so decoration stays off it;
     * [catalog] enables terrain variants (bridge, tunnel) for the piece. Returns blocks placed.
     */
    fun lay(level: LevelAccessor, p0: PiecePlacement, style: RoadStyle, styles: RoadStyleSet, groundAt: (Int, Int) -> Int,
            inArea: (Int, Int) -> Boolean = { _, _ -> true }, footprint: ((Int, Int) -> Boolean)? = null, catalog: PieceCatalog? = null,
            protect: MutableSet<Long> = HashSet()): Int {
        var placed = 0
        val seed = if (level is WorldGenLevel) level.seed else 0L
        val p = if (catalog != null && p0.piece.terrain == null) variant(p0, catalog, groundAt) else p0
        val piece = p.piece
        for (b in piece.blocks) {
            val pos = p.world(b.x, b.y, b.z)
            if (!inArea(pos.x, pos.z)) continue
            val state = stateOf(b, p, style, seed, pos)
            placed += if (b.role in RoadPiece.ROAD_ROLES && !piece.fit.none) column(level, pos.x, pos.z, pos.y, state, piece.fit, style, styles, groundAt(pos.x, pos.z), protect)
                      else plain(level, pos, state, styles, protect)
        }
        placed += decorate(level, p, style, styles, groundAt, inArea, footprint, seed)
        return placed
    }

    /** The bridge or tunnel variant of the placement when the ground under it asks for one. */
    private fun variant(p: PiecePlacement, catalog: PieceCatalog, groundAt: (Int, Int) -> Int): PiecePlacement {
        var drop = 0; var cover = 0
        for (b in p.piece.blocks) {
            if (b.role !in RoadPiece.ROAD_ROLES) continue
            val pos = p.world(b.x, b.y, b.z)
            val g = groundAt(pos.x, pos.z)
            if (g == Int.MIN_VALUE) continue
            if (g < pos.y) drop = maxOf(drop, pos.y - g) else cover = maxOf(cover, g - pos.y)
        }
        val v = catalog.variantFor(p.piece, drop, cover)
        return if (v === p.piece) p else PiecePlacement(v, p.transform, p.x, p.base, p.z, p.index, p.roadId)
    }

    private fun stateOf(b: PieceBlock, p: PiecePlacement, style: RoadStyle, seed: Long, pos: BlockPos): BlockState = when (b.role) {
        // Rolled per 2x2 patch, not per block: an independent roll at every column is white noise,
        // which reads as confetti rather than as wear. Patches make the accent look like use.
        RoadPiece.ROLE_SURFACE -> style.surface.pick(Random(Mth.getSeed(pos.x shr 1, 0, pos.z shr 1) xor seed))
        RoadPiece.ROLE_EDGE -> style.edge.pick(Random(Mth.getSeed(pos.x shr 1, 0, pos.z shr 1) xor seed))
        RoadPiece.ROLE_STAIR -> style.stairs.trySetValue(StairBlock.FACING, b.facing?.let { p.facing(it) }?.let { Direction.fromDelta(it.dx, 0, it.dz) } ?: Direction.NORTH)
        RoadPiece.ROLE_SLAB -> style.slab
        RoadPiece.ROLE_AIR -> Blocks.AIR.defaultBlockState()
        RoadPiece.ROLE_POST -> style.post
        RoadPiece.ROLE_LAMP -> style.lamp
        else -> style.fill // fill, wall, pillar, brace and anything a tier does not name
    }

    /** A non-road block: placed where the world has air, plants or replaceable ground; air clears clearable blocks. */
    private fun plain(level: LevelAccessor, pos: BlockPos, state: BlockState, styles: RoadStyleSet, protect: MutableSet<Long>): Int {
        val cur = level.getBlockState(pos)
        if (protect.contains(pos.asLong())) return 0
        if (state.isAir) { if (cur.isAir || !styles.isClearable(cur)) return 0; level.setBlock(pos, state, 2 or 16); return 1 }
        if (!styles.isClearable(cur) && !styles.isReplaceable(cur)) return 0
        if (cur == state) return 0
        level.setBlock(pos, state, 2 or 16)
        protect.add(pos.asLong())
        return 1
    }

    /**
     * One road block at (x, z, top): the ground made to fit — cut down to it (up to [PieceFit.cut],
     * three of headroom kept clear), filled up to it (up to [PieceFit.fill]), or a deck on one
     * support over a deeper drop — then [state] at [top] and a solid block under it. Returns blocks changed.
     */
    fun column(level: LevelAccessor, x: Int, z: Int, top: Int, state: BlockState, fit: PieceFit, style: RoadStyle, styles: RoadStyleSet, ground: Int, protect: MutableSet<Long> = HashSet()): Int {
        if (ground == Int.MIN_VALUE || ground <= level.minBuildHeight || top <= level.minBuildHeight) return 0
        val topPos = BlockPos(x, top, z)
        // A road block another piece laid in this run is never cut, cleared or overwritten (two pieces share a joint column at different levels).
        if (protect.contains(topPos.asLong())) return 0
        val groundState = level.getBlockState(BlockPos(x, ground, z))
        if (!groundState.fluidState.isEmpty || !level.getFluidState(BlockPos(x, ground + 1, z)).isEmpty) return 0
        if (ground >= top && !styles.isReplaceable(groundState) && !protect.contains(BlockPos(x, ground, z).asLong())) return 0
        var changed = 0
        val air = Blocks.AIR.defaultBlockState()
        fun clear(from: Int, to: Int, stopAtUnclearable: Boolean): Boolean {
            for (y in from..to) {
                val pos = BlockPos(x, y, z)
                if (protect.contains(pos.asLong())) continue
                val s = level.getBlockState(pos)
                if (s.isAir) continue
                if (y <= ground && !styles.isReplaceable(s) && !styles.isClearable(s)) return false
                if (y > ground && !styles.isClearable(s)) { if (stopAtUnclearable) return true else continue }
                level.setBlock(pos, air, 2 or 16); changed++
            }
            return true
        }
        if (top < ground) {
            // Cut the ground down to the road, or — past the cap — only the passage above it: a rough
            // tunnel through the hill rather than a gap in the road.
            val to = if (ground - top > fit.cut) top + HEADROOM else ground + HEADROOM
            if (!clear(top + 1, to, true)) return changed
        } else if (top > ground) {
            val drop = top - ground - 1
            val from = if (drop > fit.fill) { if (!fit.deck) return 0; top - 1 } else ground + 1
            for (y in from until top) {
                val pos = BlockPos(x, y, z)
                if (protect.contains(pos.asLong())) continue
                if (!styles.isClearable(level.getBlockState(pos))) return changed
                level.setBlock(pos, style.fill, 2 or 16); changed++
            }
            clear(top + 1, top + HEADROOM, true)
        } else {
            clear(top + 1, top + HEADROOM, true)
        }
        if (top > ground && !styles.isClearable(level.getBlockState(topPos))) return changed
        if (level.getBlockState(topPos) != state) { level.setBlock(topPos, state, 2 or 16); changed++ }
        protect.add(topPos.asLong())
        // A solid block under every road block: sand and gravel fall, and a road on leaves or over a hollow sinks.
        val below = BlockPos(x, top - 1, z)
        val b = level.getBlockState(below)
        if (top - 1 > level.minBuildHeight && !protect.contains(below.asLong()) && !b.isSolid && b.fluidState.isEmpty && styles.isClearable(b)) { level.setBlock(below, style.fill, 2 or 16); changed++ }
        return changed
    }

    /**
     * Decoration: on every n-th piece of a road (`every`), sides alternating, only where no road
     * block lies and inside [inArea]. `reach: ground` repeats the block downward to solid ground
     * within `max`, and places nothing when the ground is further than that.
     */
    private fun decorate(level: LevelAccessor, p: PiecePlacement, style: RoadStyle, styles: RoadStyleSet, groundAt: (Int, Int) -> Int, inArea: (Int, Int) -> Boolean, footprint: ((Int, Int) -> Boolean)?, seed: Long): Int {
        var placed = 0
        for (d in p.piece.decor) {
            if (d.every > 1 && p.index % d.every != 0) continue
            val flip = (p.index / maxOf(1, d.every)) % 2 == 1
            val pos = p.world(d.x, d.y, if (flip) -d.z else d.z)
            if (!inArea(pos.x, pos.z) || footprint?.invoke(pos.x, pos.z) == true) continue
            val state = stateOf(d, p, style, seed, pos)
            if (d.reach == "ground") {
                val ground = groundAt(pos.x, pos.z)
                if (ground == Int.MIN_VALUE || ground >= pos.y || pos.y - ground - 1 > d.max) continue
                for (y in ground + 1..pos.y) { val at = BlockPos(pos.x, y, pos.z); if (!styles.isClearable(level.getBlockState(at))) break; level.setBlock(at, state, 3); placed++ }
            } else {
                // A block that needs something under it (a lamp on its post) is skipped over air.
                if (!level.getBlockState(pos.below()).isSolid && d.role == RoadPiece.ROLE_LAMP) continue
                if (!styles.isClearable(level.getBlockState(pos))) continue
                level.setBlock(pos, state, 3); placed++
            }
        }
        return placed
    }

    /** The blocks a placement owns as a box: min x, y, z, max x, y, z, inclusive; road and structure blocks, not decoration. */
    fun boundsOf(p: PiecePlacement): IntArray {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (b in p.piece.blocks) {
            val w = p.world(b.x, b.y, b.z)
            if (w.x < minX) minX = w.x; if (w.x > maxX) maxX = w.x; if (w.y < minY) minY = w.y; if (w.y > maxY) maxY = w.y; if (w.z < minZ) minZ = w.z; if (w.z > maxZ) maxZ = w.z
        }
        if (minX == Int.MAX_VALUE) return intArrayOf(p.x, p.base, p.z, p.x, p.base, p.z)
        return intArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /** The box a placement can touch including decoration and its reach: for chunk registration. */
    fun reachOf(p: PiecePlacement): IntArray {
        val b = boundsOf(p)
        for (d in p.piece.decor) for (flip in listOf(false, true)) {
            val w = p.world(d.x, d.y, if (flip) -d.z else d.z)
            if (w.x < b[0]) b[0] = w.x; if (w.x > b[3]) b[3] = w.x; if (w.z < b[2]) b[2] = w.z; if (w.z > b[5]) b[5] = w.z
        }
        return b
    }

    /** All road-block columns of the placements of a road, for keeping decoration off them. */
    fun footprintOf(placements: List<PiecePlacement>): it.unimi.dsi.fastutil.longs.LongOpenHashSet {
        val set = it.unimi.dsi.fastutil.longs.LongOpenHashSet()
        for (p in placements) for (b in p.piece.blocks) if (b.role in RoadPiece.ROAD_ROLES) { val w = p.world(b.x, b.y, b.z); set.add(key(w.x, w.z)) }
        return set
    }

    fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
}
