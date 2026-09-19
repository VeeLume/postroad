package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.status.ChunkStatus
import it.unimi.dsi.fastutil.longs.LongOpenHashSet

/**
 * Times a block of chunk generation and what it does to the server's tick.
 *
 * The question this exists to answer: can the chunk system be driven hard enough to make real ground
 * ready ahead of a player, and at what cost to the tick? Everything else in the real-terrain pipeline
 * is a rearrangement of code we already have; this is the one number that comes from the machine and
 * the modpack rather than from the design.
 *
 * Tick period, not tick work, is what is sampled: a healthy server ticks every 50 ms, so a mean well
 * above that says generation is holding the server up — which is exactly the symptom that matters.
 */
object PregenMeasure {

    @Volatile
    var running: Boolean = false
        private set

    private var startedAt = 0L
    private var lastTickAt = 0L
    private var ticks = 0
    private var slowTicks = 0
    private var maxPeriodMs = 0.0
    private var totalPeriodMs = 0.0
    private var asked = 0
    private var label = ""

    /** Runs started this session; the caller uses it to pick untouched ground for each one. */
    var runs: Int = 0
        private set

    /** Starts a run over [chunks]; returns the message to show, or null if one is already running. */
    fun start(level: ServerLevel, chunks: LongOpenHashSet, status: ChunkStatus, what: String): String? {
        if (running) return null
        val dimension = level.dimension().location()
        val alreadyKnown = chunks.count { KnownTerrain.has(dimension, it) }
        asked = chunks.size - alreadyKnown
        if (asked <= 0) return "Every one of those ${chunks.size} chunk(s) is already known; pick an area that has not been generated."
        label = what
        startedAt = System.nanoTime()
        lastTickAt = 0L
        ticks = 0; slowTicks = 0; maxPeriodMs = 0.0; totalPeriodMs = 0.0
        tailing = false
        running = true
        runs++
        ChunkPregen.request(dimension, chunks, status) { ok, failed -> report(ok, failed) }
        return "Measuring $what: $asked chunk(s) at ${status.name}, ${chunks.size - asked} already known, " +
            "cap ${PostroadConfig.planPregenInFlight} in flight. The result goes to the log and to `/postroad roads measure`."
    }

    /** Sampled once per server tick while a run is going, and through its tail. */
    fun tick() {
        if (!running && !tailing) return
        val now = System.nanoTime()
        if (lastTickAt != 0L) {
            val periodMs = (now - lastTickAt) / 1_000_000.0
            ticks++
            totalPeriodMs += periodMs
            if (periodMs > maxPeriodMs) maxPeriodMs = periodMs
            // 50 ms is one tick; over 55 the server is behind rather than merely busy.
            if (periodMs > 55.0) slowTicks++
        }
        lastTickAt = now
        if (tailing) {
            if (ChunkPregen.heldCount == 0) drained++
            if (drained >= TAIL_GRACE_TICKS) reportTail()
        }
    }

    /**
     * The tail: the run's chunks are done, but their tickets are still held and go a few per tick, and
     * the chunk map unloads what they held on the ticks after that. This is where a run used to stall
     * for seconds when every ticket dropped at once; it is measured on its own so that shows.
     */
    @Volatile
    private var tailing = false
    private var tailStartedAt = 0L
    private var drained = 0
    /** Ticks sampled after the last ticket went, for the unloads it caused. */
    private const val TAIL_GRACE_TICKS = 100

    private var last: String = "no measurement has been run yet"

    private fun report(ok: Int, failed: Int) {
        running = false
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val perSecond = if (seconds > 0) ok / seconds else 0.0
        val meanPeriod = if (ticks > 0) totalPeriodMs / ticks else 0.0
        val slowShare = if (ticks > 0) 100.0 * slowTicks / ticks else 0.0
        last = "%s: %d chunk(s) in %.1f s = %.1f chunk(s)/s (%d failed); tick period mean %.1f ms, max %.0f ms, %.0f%% of %d tick(s) over 55 ms"
            .format(label, ok, seconds, perSecond, failed, meanPeriod, maxPeriodMs, slowShare, ticks)
        Postroad.LOGGER.info(last)
        tailStartedAt = System.nanoTime()
        ticks = 0; slowTicks = 0; maxPeriodMs = 0.0; totalPeriodMs = 0.0
        drained = 0
        tailing = true
    }

    private fun reportTail() {
        tailing = false
        val seconds = (System.nanoTime() - tailStartedAt) / 1_000_000_000.0 - TAIL_GRACE_TICKS * 0.05
        val meanPeriod = if (ticks > 0) totalPeriodMs / ticks else 0.0
        val slowShare = if (ticks > 0) 100.0 * slowTicks / ticks else 0.0
        val tail = "%s, tail: tickets released over %.1f s; tick period mean %.1f ms, max %.0f ms, %.0f%% of %d tick(s) over 55 ms (the last %d ticks are the grace for unloads)"
            .format(label, maxOf(0.0, seconds), meanPeriod, maxPeriodMs, slowShare, ticks, TAIL_GRACE_TICKS)
        Postroad.LOGGER.info(tail)
        last = "$last\n$tail"
    }

    fun status(): String = when {
        running -> "$label: running, ${ChunkPregen.queueSize} queued, ${ChunkPregen.inFlightCount} in flight of $asked asked, ${ChunkPregen.heldCount} held"
        tailing -> "$label: done, releasing ${ChunkPregen.heldCount} held ticket(s)"
        else -> last
    }
}
