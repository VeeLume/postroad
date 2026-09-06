package io.github.veelume.postroad.roads.gen

import com.google.gson.Gson
import com.google.gson.JsonElement
import io.github.veelume.postroad.Postroad
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.GsonHelper
import net.minecraft.util.profiling.ProfilerFiller
import kotlin.math.abs

/** One row of a piece: the block level relative to the entry anchor and the role of its three blocks. */
data class PieceRow(val level: Int, val role: String)

/** Decoration relative to a piece: [along] rows from the entry, [side] blocks off the centre, [up] above the row. */
data class PieceDecor(val along: Int, val side: Int, val up: Int, val role: String, val every: Int)

/** How the ground is made to fit a piece: cut down to it up to [cut], fill up to [fill], a deck past that when [deck]. */
data class PieceFit(val cut: Int = 4, val fill: Int = 3, val deck: Boolean = true)

/**
 * A catalog piece: what lies between two consecutive planned points. `straight` pieces have three
 * rows along the facing and a [rise] of 0…3; `diagonal` pieces are the zigzag band between anchors
 * three blocks apart on both axes, six stamps long, rising by slab steps. Descents use a piece reversed.
 */
data class RoadPiece(val id: ResourceLocation, val shape: String, val rise: Int, val cost: Double, val rows: List<PieceRow>, val decor: List<PieceDecor>, val fit: PieceFit) {
    val isDiagonal: Boolean get() = shape == SHAPE_DIAGONAL

    companion object {
        const val SHAPE_STRAIGHT = "straight"
        const val SHAPE_DIAGONAL = "diagonal"
        /** The square at an anchor where the facing changes: entry and exit connectors on the same anchor. */
        const val SHAPE_CORNER = "corner"
        /** The square at a road's first and last anchor. */
        const val SHAPE_END = "end"
        const val ROLE_SURFACE = "surface"
        const val ROLE_STAIR = "stair"
        const val ROLE_SLAB = "slab"
        const val ROLE_POST = "post"
        const val ROLE_LAMP = "lamp"
        /** Rows per straight piece: the anchors are three blocks apart and the entry row belongs to the previous piece. */
        const val LENGTH = 3
        /** Stamps along a diagonal piece's zigzag: two per block of offset. */
        const val DIAGONAL_LENGTH = 6
        const val HALF_WIDTH = 1
    }
}

/**
 * A piece placed between two anchors. [entry] and [exit] are the anchor blocks at their surface
 * level (`planned y - 1`); [dx], [dz] the unit facing from entry to exit, which for a straight
 * piece is also the direction of ascent; [reversed] when the plan runs downhill through it, so
 * the plan's point order is exit → entry; [index] the segment's index along the road.
 */
class PiecePlacement(val piece: RoadPiece, val entry: BlockPos, val exit: BlockPos, val dx: Int, val dz: Int, val reversed: Boolean, val index: Int) {
    /** Centre block of row [k] (1..[RoadPiece.LENGTH]) and its surface level. */
    fun rowCentre(k: Int): BlockPos = BlockPos(entry.x + dx * k, entry.y + piece.rows[k - 1].level, entry.z + dz * k)
}

/**
 * The catalog: straight and diagonal pieces by rise, the corner square and the end square. Every
 * piece has an entry connector (the anchor it starts from, level 0) and an exit connector (the
 * anchor it ends on, at its rise); the assembler puts each piece's entry on the previous piece's
 * exit, so a road is a chain of connectors and every block between two anchors has exactly one owner.
 */
class PieceCatalog(val straights: Map<Int, RoadPiece>, val diagonals: Map<Int, RoadPiece>, corner: RoadPiece? = null, end: RoadPiece? = null) {
    val corner: RoadPiece = corner ?: SQUARE.copy(id = Postroad.id("corner"), shape = RoadPiece.SHAPE_CORNER)
    val end: RoadPiece = end ?: corner ?: SQUARE.copy(id = Postroad.id("end"), shape = RoadPiece.SHAPE_END)
    val maxRise: Int get() = straights.keys.maxOrNull() ?: 0

    /** The planner's step classes: one per straight rise, in the catalog's costs. */
    fun stepClasses(): List<StepClass> = straights.entries.sortedBy { it.key }.map { StepClass("rise${it.key}", it.key.toDouble(), it.value.cost) }

    /** The planner's diagonal classes: one per diagonal rise; empty means no diagonal moves. */
    fun diagonalStepClasses(): List<StepClass> = diagonals.entries.sortedBy { it.key }.map { StepClass("diagonal${it.key}", it.key.toDouble(), it.value.cost) }

    /** The placement for the segment from [a] to [b] (planned points), or null when no piece fits it. */
    fun placement(a: BlockPos, b: BlockPos, index: Int): PiecePlacement? {
        val ddx = Integer.signum(b.x - a.x); val ddz = Integer.signum(b.z - a.z)
        val rise = b.y - a.y
        if (ddx == 0 && ddz == 0) return null
        val piece = (if (ddx != 0 && ddz != 0) diagonals else straights)[abs(rise)] ?: return null
        return if (rise >= 0) PiecePlacement(piece, a.below(), b.below(), ddx, ddz, false, index)
        else PiecePlacement(piece, b.below(), a.below(), -ddx, -ddz, true, index)
    }

    /** Every segment of [points] as a placement; null when any segment has no piece (the planner promised otherwise). */
    fun assemble(points: List<BlockPos>): List<PiecePlacement>? {
        val out = ArrayList<PiecePlacement>(points.size)
        for (i in 1 until points.size) out.add(placement(points[i - 1], points[i], i - 1) ?: return null)
        return out
    }

    companion object {
        /** The catalog the data files describe; used until they load and when they are missing. */
        fun builtIn(): PieceCatalog {
            fun s(rise: Int, cost: Double, vararg rows: PieceRow) = RoadPiece(Postroad.id("straight_$rise"), RoadPiece.SHAPE_STRAIGHT, rise, cost, rows.toList(), LAMPS, PieceFit())
            fun d(rise: Int, cost: Double, vararg rows: PieceRow) = RoadPiece(Postroad.id("diagonal_$rise"), RoadPiece.SHAPE_DIAGONAL, rise, cost, rows.toList(), emptyList(), PieceFit())
            val r = ::PieceRow
            val straights = listOf(
                s(0, 0.0, r(0, "surface"), r(0, "surface"), r(0, "surface")),
                s(1, 1.0, r(1, "slab"), r(1, "surface"), r(1, "surface")),
                s(2, 4.0, r(1, "stair"), r(2, "stair"), r(2, "surface")),
                s(3, 9.0, r(1, "stair"), r(2, "stair"), r(3, "stair")),
            ).associateBy { it.rise }
            val diagonals = listOf(
                d(0, 0.5, r(0, "surface"), r(0, "surface"), r(0, "surface"), r(0, "surface"), r(0, "surface"), r(0, "surface")),
                d(1, 2.0, r(0, "surface"), r(0, "surface"), r(1, "slab"), r(1, "surface"), r(1, "surface"), r(1, "surface")),
                d(2, 6.0, r(0, "surface"), r(1, "slab"), r(1, "surface"), r(1, "surface"), r(2, "slab"), r(2, "surface")),
            ).associateBy { it.rise }
            return PieceCatalog(straights, diagonals)
        }

        private val LAMPS = listOf(PieceDecor(3, 2, 1, RoadPiece.ROLE_POST, 8), PieceDecor(3, 2, 2, RoadPiece.ROLE_LAMP, 8))
        private val SQUARE = RoadPiece(Postroad.id("corner"), RoadPiece.SHAPE_CORNER, 0, 0.0, listOf(PieceRow(0, RoadPiece.ROLE_SURFACE)), emptyList(), PieceFit())
    }
}

/** The catalog from the json files under `data/postroad/roads/pieces`; the built-in one until then. */
object RoadPieces : SimpleJsonResourceReloadListener(Gson(), "roads/pieces") {
    @Volatile
    var current: PieceCatalog = PieceCatalog.builtIn()
        private set

    override fun apply(objects: Map<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        if (objects.isEmpty()) { current = PieceCatalog.builtIn(); return }
        val straights = HashMap<Int, RoadPiece>()
        val diagonals = HashMap<Int, RoadPiece>()
        var corner: RoadPiece? = null
        var end: RoadPiece? = null
        for ((id, json) in objects) {
            try {
                val o = GsonHelper.convertToJsonObject(json, "road piece")
                val shape = GsonHelper.getAsString(o, "shape", RoadPiece.SHAPE_STRAIGHT) ?: RoadPiece.SHAPE_STRAIGHT
                val rise = GsonHelper.getAsInt(o, "rise", 0)
                val cost = GsonHelper.getAsDouble(o, "cost", 0.0)
                val rows = if (o.has("rows")) GsonHelper.getAsJsonArray(o, "rows").map { e -> val ro = e.asJsonObject; PieceRow(GsonHelper.getAsInt(ro, "level", 0), GsonHelper.getAsString(ro, "role", "surface") ?: "surface") } else emptyList()
                val decor = if (o.has("decor")) GsonHelper.getAsJsonArray(o, "decor").map { e -> val d = e.asJsonObject
                    PieceDecor(GsonHelper.getAsInt(d, "along", RoadPiece.LENGTH), GsonHelper.getAsInt(d, "side", 2), GsonHelper.getAsInt(d, "up", 1), GsonHelper.getAsString(d, "role", "post") ?: "post", GsonHelper.getAsInt(d, "every", 6)) } else emptyList()
                val fit = if (o.has("fit")) { val f = o.getAsJsonObject("fit"); PieceFit(GsonHelper.getAsInt(f, "cut", 4), GsonHelper.getAsInt(f, "fill", 3), GsonHelper.getAsBoolean(f, "deck", true)) } else PieceFit()
                val piece = RoadPiece(id, shape, rise, cost, rows, decor, fit)
                // Connectors, when declared: the exit must sit at the piece's rise.
                if (o.has("exit")) { val ex = o.getAsJsonObject("exit"); val lvl = GsonHelper.getAsInt(ex, "level", rise); if (lvl != rise) { Postroad.LOGGER.warn("Road piece {}: exit connector level {} is not its rise {}; skipped", id, lvl, rise); continue } }
                when (shape) {
                    RoadPiece.SHAPE_STRAIGHT -> {
                        if (rows.size != RoadPiece.LENGTH) { Postroad.LOGGER.warn("Road piece {} needs {} rows, has {}; skipped", id, RoadPiece.LENGTH, rows.size); continue }
                        if (rows.last().level != rise) { Postroad.LOGGER.warn("Road piece {}: last row level {} is not its rise {}; skipped", id, rows.last().level, rise); continue }
                        straights[rise] = piece
                    }
                    RoadPiece.SHAPE_DIAGONAL -> {
                        if (rows.size != RoadPiece.DIAGONAL_LENGTH) { Postroad.LOGGER.warn("Road piece {} needs {} rows, has {}; skipped", id, RoadPiece.DIAGONAL_LENGTH, rows.size); continue }
                        if (rows.last().level != rise) { Postroad.LOGGER.warn("Road piece {}: last row level {} is not its rise {}; skipped", id, rows.last().level, rise); continue }
                        diagonals[rise] = piece
                    }
                    RoadPiece.SHAPE_CORNER -> corner = piece
                    RoadPiece.SHAPE_END -> end = piece
                    else -> Postroad.LOGGER.warn("Road piece {}: unknown shape {}; skipped", id, shape)
                }
            } catch (e: Exception) {
                Postroad.LOGGER.warn("Invalid road piece {}: {}", id, e.message)
            }
        }
        if (straights[0] == null) { Postroad.LOGGER.error("Road pieces: no flat straight piece; using the built-in catalog"); current = PieceCatalog.builtIn(); return }
        current = PieceCatalog(straights, diagonals, corner, end)
        Postroad.LOGGER.info("Loaded road pieces: straights for rises {}, diagonals for rises {}, corner {}, end {}", straights.keys.sorted(), diagonals.keys.sorted(), corner != null, end != null)
    }
}
