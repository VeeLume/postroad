package io.github.veelume.postroad.roads.gen

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.veelume.postroad.Postroad
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.GsonHelper
import net.minecraft.util.profiling.ProfilerFiller
import kotlin.math.abs

/** A unit direction on the grid: cardinal or diagonal. */
data class Facing(val dx: Int, val dz: Int) {
    val isDiagonal: Boolean get() = dx != 0 && dz != 0
    override fun toString(): String = "($dx,$dz)"
}

/** Where another piece attaches: the piece's own edge block, the outward direction, and the level the road continues at beyond it. */
data class Connector(val x: Int, val y: Int, val z: Int, val facing: Facing, val level: Int)

/** One block of a piece: position relative to the anchor, material role, stair facing, and for decoration `every` and `reach`. */
data class PieceBlock(val x: Int, val y: Int, val z: Int, val role: String, val facing: Facing? = null, val every: Int = 1, val reach: String? = null, val max: Int = 32)

/** How the ground is reconciled per road-block column; [none] leaves it alone (bridges, tunnels). */
data class PieceFit(val cut: Int = 4, val fill: Int = 3, val deck: Boolean = true, val none: Boolean = false)

/** When a variant may replace the plain piece with the same connectors. */
data class TerrainCondition(val minDrop: Int = 0, val minCover: Int = 0)

/**
 * A catalog piece: a schematic anchored at its planned point (`[0, 0, 0]` = the anchor's road
 * block), authored once and placed by the assembler under one of eight transforms.
 */
data class RoadPiece(
    val id: ResourceLocation,
    val cost: Double,
    val costPerDrop: Double,
    val costPerCover: Double,
    val connectors: List<Connector>,
    val blocks: List<PieceBlock>,
    val decor: List<PieceBlock>,
    val fit: PieceFit,
    val terrain: TerrainCondition?,
) {
    /** The rise between its two connectors when it has exactly two; the planner's step for this piece. */
    val rise: Int get() = if (connectors.size == 2) abs(connectors[0].level - connectors[1].level) else 0
    val isStraight: Boolean get() = connectors.size == 2 && !connectors[0].facing.isDiagonal && !connectors[1].facing.isDiagonal && connectors[0].facing.dx == -connectors[1].facing.dx && connectors[0].facing.dz == -connectors[1].facing.dz
    val isDiagonal: Boolean get() = connectors.size == 2 && connectors[0].facing.isDiagonal && connectors[1].facing.isDiagonal && connectors[0].facing.dx == -connectors[1].facing.dx && connectors[0].facing.dz == -connectors[1].facing.dz
    val isFlat: Boolean get() = connectors.all { it.level == 0 }

    companion object {
        const val ROLE_SURFACE = "surface"
        const val ROLE_EDGE = "edge"
        const val ROLE_STAIR = "stair"
        const val ROLE_SLAB = "slab"
        const val ROLE_FILL = "fill"
        /** The fill block used as the walking surface (a cobblestone landing): a road role, so the column fit applies. */
        const val ROLE_PAVED = "paved"
        const val ROLE_WALL = "wall"
        const val ROLE_AIR = "air"
        const val ROLE_POST = "post"
        const val ROLE_LAMP = "lamp"
        /** Roles that are the road itself: their columns get the piece's fit. */
        val ROAD_ROLES = setOf(ROLE_SURFACE, ROLE_EDGE, ROLE_STAIR, ROLE_SLAB, ROLE_PAVED)
    }
}

/**
 * One of the eight grid transforms: [rotation] quarter turns (0..3, counter-clockwise seen from
 * above: +x → +z) after an optional [mirror] of z.
 */
data class Transform(val rotation: Int, val mirror: Boolean) {
    fun apply(x: Int, z: Int): Pair<Int, Int> {
        val z0 = if (mirror) -z else z
        return when (rotation and 3) {
            0 -> x to z0
            1 -> -z0 to x
            2 -> -x to -z0
            else -> z0 to -x
        }
    }

    fun apply(f: Facing): Facing = apply(f.dx, f.dz).let { Facing(it.first, it.second) }

    companion object {
        val ALL: List<Transform> = (0..3).flatMap { r -> listOf(Transform(r, false), Transform(r, true)) }
    }
}

/**
 * A piece placed in the world: [piece] under [transform], its anchor block at ([x], [z]) and its
 * level 0 at [base] (the previous point's height for a rise piece). [index] is the point's index
 * along the road. World position of a local block: `(x, base + ly, z) + transform(lx, lz)`.
 */
class PiecePlacement(val piece: RoadPiece, val transform: Transform, val x: Int, val base: Int, val z: Int, val index: Int, val roadId: String = "") {
    fun world(lx: Int, ly: Int, lz: Int): BlockPos { val (wx, wz) = transform.apply(lx, lz); return BlockPos(x + wx, base + ly, z + wz) }
    fun facing(f: Facing): Facing = transform.apply(f)
    /** The anchor's own road block. */
    val anchor: BlockPos get() = piece.blocks.firstOrNull { it.x == 0 && it.z == 0 && it.role in RoadPiece.ROAD_ROLES }?.let { BlockPos(x, base + it.y, z) } ?: BlockPos(x, base, z)
    /** Connectors in world terms: block position, outward facing, expected level. */
    fun connectors(): List<Triple<BlockPos, Facing, Int>> = piece.connectors.map { c -> Triple(world(c.x, c.y, c.z), facing(c.facing), base + c.level) }
}

/** One side a point needs a connector on: the outward facing and the world level the road continues at beyond it. */
data class Need(val facing: Facing, val level: Int)

/**
 * The catalog. [assemble] turns a road's points into placements: at each point the piece whose
 * connectors, under some transform, cover exactly the sides the point needs at the levels the
 * neighbours are at. A point's piece hosts the rise *into* it, so its base is the previous
 * point's height; the first point hosts nothing.
 */
class PieceCatalog(val pieces: List<RoadPiece>) {
    /** Planner step classes: one per straight rise present, in the catalog's costs. */
    fun stepClasses(): List<StepClass> = pieces.filter { it.isStraight && it.terrain == null }.groupBy { it.rise }.entries.sortedBy { it.key }
        .map { (r, ps) -> StepClass("rise$r", r.toDouble(), ps.minOf { it.cost }) }

    fun diagonalStepClasses(): List<StepClass> = pieces.filter { it.isDiagonal && it.terrain == null }.groupBy { it.rise }.entries.sortedBy { it.key }
        .map { (r, ps) -> StepClass("diagonal$r", r.toDouble(), ps.minOf { it.cost }) }

    val maxRise: Int get() = pieces.filter { it.isStraight }.maxOfOrNull { it.rise } ?: 0

    /**
     * The largest rise the catalog has per turn kind: key "<low side kind>-<high side kind>-<angle>"
     * with kinds `c` (cardinal) / `d` (diagonal) and the angle 0, 45 or 90 between the two sides'
     * lines of travel. The planner refuses a hosted rise its key cannot carry. Two-connector pieces
     * without a terrain condition only.
     */
    fun turnLimits(): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (p in pieces) {
            if (p.connectors.size != 2 || p.terrain != null) continue
            val (a, b) = p.connectors
            val (low, high) = if (a.level <= b.level) a to b else b to a
            val k = turnKey(low.facing, high.facing)
            out[k] = maxOf(out[k] ?: -1, high.level - low.level)
            if (a.level == b.level) { val k2 = turnKey(b.facing, a.facing); out[k2] = maxOf(out[k2] ?: -1, 0) }
        }
        return out
    }

    companion object Turns {
        val EMPTY = PieceCatalog(emptyList())

        /** The turn key for a tile whose two connectors face [low] (the lower side) and [high]. */
        fun turnKey(low: Facing, high: Facing): String {
            val dot = -low.dx * high.dx - low.dz * high.dz // between the line of travel in (−low) and out (high)
            val angle = when {
                dot > 0 && (low.isDiagonal == high.isDiagonal) -> 0
                dot == 0 -> 90
                else -> 45
            }
            return "${if (low.isDiagonal) "d" else "c"}-${if (high.isDiagonal) "d" else "c"}-$angle"
        }
    }

    /**
     * The piece and transform for a point with [needs]; null when the catalog has none. Pieces
     * whose connectors are exactly the needed sides win; a piece with spare connectors (the flat
     * square) is the fallback. Among matches, the cheapest.
     */
    fun match(needs: List<Need>): Pair<RoadPiece, Transform>? {
        var best: Pair<RoadPiece, Transform>? = null
        var bestKey = Triple(Int.MAX_VALUE, Double.MAX_VALUE, Int.MAX_VALUE)
        for (piece in pieces) {
            if (piece.terrain != null || piece.connectors.size < needs.size) continue
            for (t in Transform.ALL) {
                var base: Int? = null
                var ok = true
                for (n in needs) {
                    val c = piece.connectors.firstOrNull { t.apply(it.facing) == n.facing } ?: run { ok = false; null } ?: break
                    val b = n.level - c.level
                    if (base == null) base = b else if (base != b) { ok = false; break }
                }
                if (!ok) continue
                val key = Triple(piece.connectors.size - needs.size, piece.cost, t.rotation * 2 + (if (t.mirror) 1 else 0))
                if (key.first < bestKey.first || (key.first == bestKey.first && (key.second < bestKey.second || (key.second == bestKey.second && key.third < bestKey.third)))) { best = piece to t; bestKey = key }
            }
        }
        return best
    }

    /** Every point of [points] as a placement, or null when some point has no piece. */
    fun assemble(points: List<BlockPos>, roadId: String = ""): List<PiecePlacement>? {
        val needs = needs(points) ?: return null
        val out = ArrayList<PiecePlacement>(points.size)
        for (i in points.indices) {
            val (piece, t) = match(needs[i]) ?: return null
            val n0 = needs[i].first()
            val c0 = piece.connectors.first { t.apply(it.facing) == n0.facing }
            out.add(PiecePlacement(piece, t, points[i].x, n0.level - c0.level, points[i].z, i, roadId))
        }
        return out
    }

    /**
     * The sides every point needs. The rise between two points is hosted by one of their two
     * pieces, the higher point's when possible: its anchor column then lies on its own terrain.
     * A point cannot host two rises (a hilltop), so an assignment pass moves one of them to the
     * lower point, whose anchor then sits above its terrain by the rise: the least bad choice.
     * A road's first and last point get a virtual side in the road's direction at their own level.
     */
    fun needs(points: List<BlockPos>): List<List<Need>>? {
        val n = points.size
        if (n < 2) return null
        val facings = ArrayList<Facing>(n - 1)
        for (k in 0 until n - 1) {
            val a = points[k]; val b = points[k + 1]
            val f = Facing(Integer.signum(b.x - a.x), Integer.signum(b.z - a.z))
            if (f.dx == 0 && f.dz == 0) return null
            facings.add(f)
        }
        val dh = IntArray(n - 1) { points[it + 1].y - points[it].y }
        // Which end hosts rise k (between points k and k+1): true = point k+1. Dynamic programme over
        // the rises: the higher end costs nothing, the lower end costs the rise; no point hosts two.
        val inf = Int.MAX_VALUE / 4
        val cost = Array(n - 1) { IntArray(2) { inf } }
        val from = Array(n - 1) { IntArray(2) }
        fun penalty(k: Int, atNext: Boolean): Int = if (dh[k] == 0) 0 else if ((dh[k] > 0) == atNext) 0 else abs(dh[k])
        for (s in 0..1) cost[0][s] = penalty(0, s == 1)
        for (k in 1 until n - 1) for (s in 0..1) {
            val here = s == 1
            for (ps in 0..1) {
                val prevAtNext = ps == 1
                // Point k would host rise k−1 (hosted at its next end) and rise k (hosted at its previous end).
                if (dh[k - 1] != 0 && prevAtNext && dh[k] != 0 && !here) continue
                val c = cost[k - 1][ps]
                if (c >= inf) continue
                val total = c + penalty(k, here)
                if (total < cost[k][s]) { cost[k][s] = total; from[k][s] = ps }
            }
        }
        val hostAtNext = BooleanArray(n - 1)
        var s = if (cost[n - 2][0] <= cost[n - 2][1]) 0 else 1
        if (cost[n - 2][s] >= inf) return null
        for (k in n - 2 downTo 0) { hostAtNext[k] = s == 1; if (k > 0) s = from[k][s] }
        val out = ArrayList<List<Need>>(n)
        for (i in 0 until n) {
            val own = points[i].y - 1
            val inFacing = if (i > 0) Facing(-facings[i - 1].dx, -facings[i - 1].dz) else Facing(-facings[0].dx, -facings[0].dz)
            val outFacing = if (i < n - 1) facings[i] else facings[n - 2]
            // A side's level: the far point's when this piece hosts that rise, else this point's own.
            val inLevel = if (i > 0 && dh[i - 1] != 0 && hostAtNext[i - 1]) points[i - 1].y - 1 else own
            val outLevel = if (i < n - 1 && dh[i] != 0 && !hostAtNext[i]) points[i + 1].y - 1 else own
            out.add(listOf(Need(inFacing, inLevel), Need(outFacing, outLevel)))
        }
        return out
    }

    /** The sides point [i] of [points] needs; see [needs]. */
    fun needsAt(points: List<BlockPos>, i: Int): List<Need>? = needs(points)?.getOrNull(i)

    /** The variant of [piece] whose terrain condition holds for [drop] (ground below the road) or [cover] (ground above), else [piece]. */
    fun variantFor(piece: RoadPiece, drop: Int, cover: Int): RoadPiece {
        var best: RoadPiece? = null
        for (v in pieces) {
            val c = v.terrain ?: continue
            if (v.connectors.size != piece.connectors.size || v.connectors.zip(piece.connectors).any { (a, b) -> a.facing != b.facing || a.level != b.level }) continue
            if ((c.minDrop > 0 && drop >= c.minDrop) || (c.minCover > 0 && cover >= c.minCover)) {
                if (best == null || (c.minDrop + c.minCover) > ((best.terrain?.minDrop ?: 0) + (best.terrain?.minCover ?: 0))) best = v
            }
        }
        return best ?: piece
    }

}

/** The catalog from the json files under `data/postroad/roads/pieces`. */
object RoadPieces : SimpleJsonResourceReloadListener(Gson(), "roads/pieces") {
    @Volatile
    var current: PieceCatalog = PieceCatalog.EMPTY
        private set

    override fun apply(objects: Map<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val pieces = ArrayList<RoadPiece>()
        for ((id, json) in objects) {
            try {
                pieces.add(parse(id, GsonHelper.convertToJsonObject(json, "road piece")))
            } catch (e: Exception) {
                Postroad.LOGGER.warn("Invalid road piece {}: {}", id, e.message)
            }
        }
        if (pieces.none { it.isStraight && it.rise == 0 }) Postroad.LOGGER.error("Road pieces: no flat straight piece; roads cannot be laid")
        current = PieceCatalog(pieces)
        Postroad.LOGGER.info("Loaded {} road piece(s): {}", pieces.size, pieces.map { it.id.path }.sorted())
    }

    fun parse(id: ResourceLocation, o: JsonObject): RoadPiece {
        fun vec(e: JsonElement, name: String): IntArray { val a = GsonHelper.convertToJsonArray(e, name); return IntArray(a.size()) { a[it].asInt } }
        fun facing(e: JsonElement): Facing { val v = vec(e, "facing"); require(v.size == 2 && v[0] in -1..1 && v[1] in -1..1 && (v[0] != 0 || v[1] != 0)) { "facing must be a unit x/z vector" }; return Facing(v[0], v[1]) }
        val connectors = GsonHelper.getAsJsonArray(o, "connectors").map { e ->
            val c = e.asJsonObject; val at = vec(c.get("at"), "at"); require(at.size == 3) { "connector 'at' needs x, y, z" }
            Connector(at[0], at[1], at[2], facing(c.get("facing")), GsonHelper.getAsInt(c, "level", 0))
        }
        require(connectors.isNotEmpty()) { "a piece needs at least one connector" }
        fun blocks(name: String): List<PieceBlock> {
            if (!o.has(name)) return emptyList()
            val out = ArrayList<PieceBlock>()
            for (e in GsonHelper.getAsJsonArray(o, name)) {
                val b = e.asJsonObject
                val at = vec(b.get("at"), "at"); require(at.size == 3) { "block 'at' needs x, y, z" }
                val role = GsonHelper.getAsString(b, "role")
                val f = if (b.has("facing")) facing(b.get("facing")) else null
                val every = GsonHelper.getAsInt(b, "every", 1)
                val reach = if (b.has("reach")) GsonHelper.getAsString(b, "reach") else null
                val max = GsonHelper.getAsInt(b, "max", 32)
                // Shorthand: span across z, up repeats upward; both expand to plain blocks.
                val span = if (b.has("span")) vec(b.get("span"), "span") else intArrayOf(at[2], at[2])
                val up = GsonHelper.getAsInt(b, "up", 1)
                for (z in span[0]..span[1]) for (dy in 0 until up) out.add(PieceBlock(at[0], at[1] + dy, z, role, f, every, reach, max))
            }
            return out
        }
        val fit = when {
            !o.has("fit") -> PieceFit()
            o.get("fit").isJsonPrimitive && o.get("fit").asString == "none" -> PieceFit(none = true)
            else -> { val f = o.getAsJsonObject("fit"); PieceFit(GsonHelper.getAsInt(f, "cut", 4), GsonHelper.getAsInt(f, "fill", 3), GsonHelper.getAsBoolean(f, "deck", true)) }
        }
        val terrain = if (o.has("terrain")) { val t = o.getAsJsonObject("terrain"); TerrainCondition(GsonHelper.getAsInt(t, "minDrop", 0), GsonHelper.getAsInt(t, "minCover", 0)) } else null
        return RoadPiece(id, GsonHelper.getAsDouble(o, "cost", 0.0), GsonHelper.getAsDouble(o, "costPerDrop", 0.0), GsonHelper.getAsDouble(o, "costPerCover", 0.0),
            connectors, blocks("blocks"), blocks("decor"), fit, terrain)
    }
}
