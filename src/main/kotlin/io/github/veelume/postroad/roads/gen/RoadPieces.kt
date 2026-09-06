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
 * A catalog piece: what lies between two consecutive planned points. `straight` pieces have four
 * rows along the facing and a [rise] of 0…4; the `diagonal` piece is a flat band between anchors
 * four blocks apart on both axes. Descents use a straight piece reversed.
 */
data class RoadPiece(val id: ResourceLocation, val shape: String, val rise: Int, val cost: Double, val rows: List<PieceRow>, val decor: List<PieceDecor>, val fit: PieceFit) {
    val isDiagonal: Boolean get() = shape == SHAPE_DIAGONAL

    companion object {
        const val SHAPE_STRAIGHT = "straight"
        const val SHAPE_DIAGONAL = "diagonal"
        const val ROLE_SURFACE = "surface"
        const val ROLE_STAIR = "stair"
        const val ROLE_SLAB = "slab"
        const val ROLE_POST = "post"
        const val ROLE_LAMP = "lamp"
        /** Rows per straight piece: the anchors are four blocks apart and the entry row belongs to the previous piece. */
        const val LENGTH = 4
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

/** The catalog: straight pieces by rise, and the diagonal piece. */
class PieceCatalog(val straights: Map<Int, RoadPiece>, val diagonal: RoadPiece?) {
    val maxRise: Int get() = straights.keys.maxOrNull() ?: 0

    /** The planner's step classes: one per straight rise, in the catalog's costs. */
    fun stepClasses(): List<StepClass> = straights.entries.sortedBy { it.key }.map { StepClass("rise${it.key}", it.key.toDouble(), it.value.cost) }

    /** Extra cost of a diagonal move (flat by construction), or null when the catalog has no diagonal piece. */
    val diagonalCost: Double? get() = diagonal?.cost

    /** The placement for the segment from [a] to [b] (planned points), or null when no piece fits it. */
    fun placement(a: BlockPos, b: BlockPos, index: Int): PiecePlacement? {
        val ddx = Integer.signum(b.x - a.x); val ddz = Integer.signum(b.z - a.z)
        val rise = b.y - a.y
        if (ddx != 0 && ddz != 0) {
            val d = diagonal ?: return null
            if (rise != 0) return null
            return PiecePlacement(d, a.below(), b.below(), ddx, ddz, false, index)
        }
        if (ddx == 0 && ddz == 0) return null
        val piece = straights[abs(rise)] ?: return null
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
            val r = ::PieceRow
            val straights = listOf(
                s(0, 0.0, r(0, "surface"), r(0, "surface"), r(0, "surface"), r(0, "surface")),
                s(1, 1.0, r(0, "surface"), r(0, "surface"), r(1, "slab"), r(1, "surface")),
                s(2, 4.0, r(1, "stair"), r(1, "surface"), r(2, "stair"), r(2, "surface")),
                s(3, 9.0, r(1, "stair"), r(2, "stair"), r(3, "stair"), r(3, "surface")),
                s(4, 14.0, r(1, "stair"), r(2, "stair"), r(3, "stair"), r(4, "stair")),
            ).associateBy { it.rise }
            val diagonal = RoadPiece(Postroad.id("diagonal"), RoadPiece.SHAPE_DIAGONAL, 0, 0.5, emptyList(), emptyList(), PieceFit())
            return PieceCatalog(straights, diagonal)
        }

        private val LAMPS = listOf(PieceDecor(4, 2, 1, RoadPiece.ROLE_POST, 6), PieceDecor(4, 2, 2, RoadPiece.ROLE_LAMP, 6))
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
        var diagonal: RoadPiece? = null
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
                when (shape) {
                    RoadPiece.SHAPE_STRAIGHT -> {
                        if (rows.size != RoadPiece.LENGTH) { Postroad.LOGGER.warn("Road piece {} needs {} rows, has {}; skipped", id, RoadPiece.LENGTH, rows.size); continue }
                        if (rows.last().level != rise) { Postroad.LOGGER.warn("Road piece {}: last row level {} is not its rise {}; skipped", id, rows.last().level, rise); continue }
                        straights[rise] = piece
                    }
                    RoadPiece.SHAPE_DIAGONAL -> diagonal = piece
                    else -> Postroad.LOGGER.warn("Road piece {}: unknown shape {}; skipped", id, shape)
                }
            } catch (e: Exception) {
                Postroad.LOGGER.warn("Invalid road piece {}: {}", id, e.message)
            }
        }
        if (straights[0] == null) { Postroad.LOGGER.error("Road pieces: no flat straight piece; using the built-in catalog"); current = PieceCatalog.builtIn(); return }
        current = PieceCatalog(straights, diagonal)
        Postroad.LOGGER.info("Loaded road pieces: straights for rises {}, diagonal {}", straights.keys.sorted(), diagonal != null)
    }
}
