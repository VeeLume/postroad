package io.github.veelume.postroad.travel

import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.roads.Tier
import kotlin.math.ceil

/**
 * What a journey costs. Free below the free distance. Beyond it, free when the player's free journey
 * is ready, which then starts a [cooldown] by the route's length; otherwise the fare: distance divided
 * by road quality. Coins buy not waiting.
 */
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

    /** Ticks of cooldown a free journey of [length] starts; 0 within the free distance or with free journeys off. */
    fun cooldown(length: Double): Long {
        val perDay = PostroadConfig.cooldownBlocksPerDay
        if (length <= PostroadConfig.freeDistance || perDay <= 0) return 0L
        return ceil(length / perDay * TICKS_PER_DAY).toLong()
    }

    /** Whether a journey of [length] would use the free journey, given whether it is [ready]. */
    fun usesFreeJourney(length: Double, ready: Boolean): Boolean = ready && cooldown(length) > 0

    /** What the journey costs right now. */
    fun price(length: Double, worstTier: Tier?, ready: Boolean): Long =
        if (usesFreeJourney(length, ready)) 0L else fare(length, worstTier)

    const val TICKS_PER_DAY = 24000L
}
