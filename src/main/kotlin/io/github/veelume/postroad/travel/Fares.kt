package io.github.veelume.postroad.travel

import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.roads.Tier
import kotlin.math.ceil

/** Coins for a journey: free below the free distance, then distance divided by road quality. */
object Fares {
    fun tierFactor(tier: Tier?): Double = when (tier) {
        Tier.PAVED -> PostroadConfig.tierFactorPaved
        Tier.GRAVEL -> PostroadConfig.tierFactorGravel
        Tier.DIRT, null -> PostroadConfig.tierFactorDirt
    }

    fun fare(length: Double, worstTier: Tier?): Long {
        val free = PostroadConfig.freeDistance
        if (length <= free) return 0L
        val perCoin = PostroadConfig.blocksPerCoin * tierFactor(worstTier)
        return ceil((length - free) / perCoin).toLong().coerceAtLeast(1L)
    }
}
