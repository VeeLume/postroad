package io.github.veelume.postroad.roads

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import kotlin.math.sqrt

/**
 * A charted walk: one point every few blocks, each with the tier it was sampled as (null =
 * not road). Paths are the edges of the travel graph; nodes sit on their points.
 */
class RoadPath(
    val id: String,
    val dimension: ResourceLocation,
    val points: MutableList<BlockPos>,
    val tiers: MutableList<Tier?>,
    val recordedBy: String,
    val recordedDay: Long,
    /** False for generated roads nobody has walked yet; routing ignores those. */
    var charted: Boolean = true,
) {
    val length: Double get() = lengthBetween(0, points.size - 1)

    /** Polyline length between two point indices. */
    fun lengthBetween(from: Int, to: Int): Double {
        val a = minOf(from, to).coerceIn(0, points.size - 1)
        val b = maxOf(from, to).coerceIn(0, points.size - 1)
        var total = 0.0
        for (i in a until b) total += distance(points[i], points[i + 1])
        return total
    }

    /** Worst tier between two indices (null if any stretch is not road at all). */
    fun worstTierBetween(from: Int, to: Int): Tier? {
        val a = minOf(from, to).coerceIn(0, tiers.size - 1)
        val b = maxOf(from, to).coerceIn(0, tiers.size - 1)
        var worst: Tier? = null
        var any = false
        for (i in a..b) {
            val t = tiers[i] ?: continue
            if (!any || t.ordinal < worst!!.ordinal) worst = t
            any = true
        }
        return worst
    }

    /** Majority tier over the whole path. */
    val tier: Tier? get() = RoadClassifier.evaluate(tiers).tier

    fun nearestIndex(pos: BlockPos): Pair<Int, Double>? {
        var best = -1
        var bestDist = Double.MAX_VALUE
        for ((i, p) in points.withIndex()) {
            val d = distance(p, pos)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (best < 0) null else best to bestDist
    }

    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("Id", id)
        tag.putString("Dimension", dimension.toString())
        tag.putLongArray("Points", points.map { it.asLong() }.toLongArray())
        tag.putByteArray("Tiers", tiers.map { (it?.ordinal ?: -1).toByte() }.toByteArray())
        tag.putString("RecordedBy", recordedBy)
        tag.putLong("RecordedDay", recordedDay)
        tag.putBoolean("Charted", charted)
        return tag
    }

    companion object {
        fun distance(a: BlockPos, b: BlockPos): Double {
            val dx = (a.x - b.x).toDouble()
            val dy = (a.y - b.y).toDouble()
            val dz = (a.z - b.z).toDouble()
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun fromTag(tag: CompoundTag): RoadPath? {
            val dimension = ResourceLocation.tryParse(tag.getString("Dimension")) ?: return null
            val points = tag.getLongArray("Points").map { BlockPos.of(it) }.toMutableList()
            val tiers = tag.getByteArray("Tiers").map { b -> if (b < 0) null else Tier.entries.getOrNull(b.toInt()) }.toMutableList()
            if (points.isEmpty() || tiers.size != points.size) return null
            return RoadPath(tag.getString("Id"), dimension, points, tiers, tag.getString("RecordedBy"), tag.getLong("RecordedDay"),
                charted = !tag.contains("Charted") || tag.getBoolean("Charted"))
        }
    }
}

/** A junction: point [indexA] of [pathA] is the same place as point [indexB] of [pathB]. */
data class PathLink(val pathA: String, val indexA: Int, val pathB: String, val indexB: Int) {
    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("PathA", pathA); tag.putInt("IndexA", indexA); tag.putString("PathB", pathB); tag.putInt("IndexB", indexB)
        return tag
    }

    companion object {
        fun fromTag(tag: CompoundTag) = PathLink(tag.getString("PathA"), tag.getInt("IndexA"), tag.getString("PathB"), tag.getInt("IndexB"))
    }
}

/** Something you can travel to: a town's depot or a linked sign, sitting on a path point. */
data class RoadNode(
    val id: String,
    val kind: String,
    val dimension: ResourceLocation,
    val pos: BlockPos,
    val pathId: String,
    val pointIndex: Int,
    val name: String,
    val placeId: String?,
) {
    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("Id", id); tag.putString("Kind", kind); tag.putString("Dimension", dimension.toString()); tag.putLong("Pos", pos.asLong())
        tag.putString("Path", pathId); tag.putInt("Index", pointIndex); tag.putString("Name", name)
        placeId?.let { tag.putString("Place", it) }
        return tag
    }

    companion object {
        const val KIND_TOWN = "town"
        const val KIND_SIGN = "sign"

        fun fromTag(tag: CompoundTag): RoadNode? {
            val dimension = ResourceLocation.tryParse(tag.getString("Dimension")) ?: return null
            return RoadNode(
                tag.getString("Id"), tag.getString("Kind"), dimension, BlockPos.of(tag.getLong("Pos")),
                tag.getString("Path"), tag.getInt("Index"), tag.getString("Name"),
                if (tag.contains("Place")) tag.getString("Place") else null,
            )
        }
    }
}
