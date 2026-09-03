package io.github.veelume.postroad.roads

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter

/** Looks at the ground around a point and says whether it is road, and how good. */
object RoadClassifier {

    /**
     * The tier of the ground at [pos] (the block the walker stands on), looking [radius] blocks
     * around it and one block up or down for terrain steps. Returns the most common tier among
     * the road blocks found, or null if none is road. Ties go to the better tier.
     */
    fun sample(level: BlockGetter, pos: BlockPos, radius: Int = RoadRules.current.sampleRadius, rules: RoadRuleSet = RoadRules.current): Tier? {
        val counts = IntArray(Tier.entries.size)
        val cursor = BlockPos.MutableBlockPos()
        for (dx in -radius..radius) for (dz in -radius..radius) {
            for (dy in 0 downTo -1) {
                cursor.set(pos.x + dx, pos.y + dy, pos.z + dz)
                val tier = rules.classify(level.getBlockState(cursor)) ?: continue
                counts[tier.ordinal]++
                break
            }
        }
        var best: Tier? = null
        var bestCount = 0
        for (tier in Tier.entries) {
            val c = counts[tier.ordinal]
            if (c > bestCount || (c == bestCount && c > 0 && best != null && tier.ordinal > best.ordinal)) {
                best = tier
                bestCount = c
            }
        }
        return best
    }

    /** Share of road samples and the majority tier over a whole walk. */
    fun evaluate(samples: List<Tier?>, rules: RoadRuleSet = RoadRules.current): WalkVerdict {
        if (samples.isEmpty()) return WalkVerdict(0.0, null, false)
        val road = samples.count { it != null }
        val share = road.toDouble() / samples.size
        val tier = samples.filterNotNull().groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Tier, Int>> { it.value }.thenBy { it.key.ordinal })?.key
        return WalkVerdict(share, tier, share >= rules.minRoadShare)
    }

    data class WalkVerdict(val roadShare: Double, val tier: Tier?, val isRoad: Boolean)
}
