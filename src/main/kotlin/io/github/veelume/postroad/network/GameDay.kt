package io.github.veelume.postroad.network

import net.minecraft.world.level.Level

/** The shared world clock in whole days: what mail arrival, the ledger and recorded paths count in. */
object GameDay {
    /** In-game day index. Uses day time, so sleeping through a night advances it. */
    fun of(level: Level): Long = level.dayTime / 24000L
}
