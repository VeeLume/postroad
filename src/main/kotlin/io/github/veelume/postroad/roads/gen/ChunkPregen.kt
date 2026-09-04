package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generates the chunks the planner wants to see, ahead of any player, through the chunk system:
 * each request is a chunk future up to the carvers step, the worldgen worker pool does the work,
 * and this object keeps at most `plan.pregenInFlight` of them going so the server thread's share
 * stays small. A chunk that already exists on disk at that step is merely loaded. When a chunk is
 * ready its heightmaps are copied into [KnownTerrain] on the server thread. Jobs group chunks so
 * the planner can be told when a whole corridor is known.
 */
object ChunkPregen {
    class Job(val id: Int, val dimension: ResourceLocation, val onDone: (ok: Int, failed: Int) -> Unit) {
        var remaining = 0
        var ok = 0
        var failed = 0
    }

    /**
     * Our own ticket holds each requested chunk at the carvers step until it is there. The chunk
     * source's own ticket for a plain future lasts one tick, which is enough when the caller waits
     * on the main thread and not at all when it does not: the generation was cancelled underneath.
     */
    private val TICKET: net.minecraft.server.level.TicketType<ChunkPos> = net.minecraft.server.level.TicketType.create("postroad_pregen", Comparator.comparingLong(ChunkPos::toLong))
    private val TICKET_DISTANCE: Int = 33 - net.minecraft.server.level.ChunkLevel.byStatus(ChunkStatus.CARVERS)

    private class Request(val dimension: ResourceLocation, val chunk: Long, val job: Job?)

    private val queue = ArrayDeque<Request>()
    /**
     * Asked from the server thread, the chunk source waits for the chunk (vanilla joins on the main
     * thread); asked from any other thread it merely schedules and returns a future. So requests
     * go through this one thread and never touch the server thread until the chunk is ready.
     */
    private val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "postroad-pregen").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val queued = HashSet<Long>()
    private val inFlight = AtomicInteger()
    private val jobIds = AtomicInteger()

    var requested: Long = 0; private set
    var completed: Long = 0; private set
    var failures: Long = 0; private set

    val queueSize: Int get() = queue.size
    val inFlightCount: Int get() = inFlight.get()

    /** Queues [chunks] of [dimension]; [onDone] runs on the server thread once all of them are known (or failed). */
    fun request(dimension: ResourceLocation, chunks: Collection<Long>, onDone: ((ok: Int, failed: Int) -> Unit)? = null): Job? {
        val job = onDone?.let { Job(jobIds.incrementAndGet(), dimension, it) }
        var added = 0
        for (c in chunks) {
            if (KnownTerrain.has(dimension, c)) continue
            if (!queued.add(c)) { continue }
            queue.addLast(Request(dimension, c, job))
            added++
        }
        if (job != null) {
            job.remaining = added
            if (added == 0) job.onDone(0, 0)
        }
        requested += added
        return job
    }

    fun onServerTick(server: MinecraftServer) {
        if (!PostroadConfig.planEnabled) return
        val cap = PostroadConfig.planPregenInFlight
        while (queue.isNotEmpty() && inFlight.get() < cap) {
            val req = queue.removeFirst()
            val level = server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, req.dimension))
            if (level == null) { finish(req, false); continue }
            start(level, req)
        }
    }

    private fun start(level: ServerLevel, req: Request) {
        inFlight.incrementAndGet()
        val cx = ChunkPos.getX(req.chunk); val cz = ChunkPos.getZ(req.chunk)
        val pos = ChunkPos(cx, cz)
        level.chunkSource.addRegionTicket(TICKET, pos, TICKET_DISTANCE, pos)
        dispatcher.execute {
            try {
                level.chunkSource.getChunkFuture(cx, cz, ChunkStatus.CARVERS, true).whenCompleteAsync({ result, error ->
                    inFlight.decrementAndGet()
                    level.chunkSource.removeRegionTicket(TICKET, pos, TICKET_DISTANCE, pos)
                    val chunk = if (error == null && result != null) result.orElse(null) else null
                    if (chunk == null) {
                        if (error != null) Postroad.LOGGER.warn("Pre-generation of chunk [{}, {}] failed: {}", cx, cz, error.toString())
                        finish(req, false)
                    } else {
                        KnownTerrain.record(req.dimension, chunk)
                        finish(req, true)
                    }
                }, level.server)
            } catch (e: Throwable) {
                Postroad.LOGGER.warn("Pre-generation request for chunk [{}, {}] failed: {}", cx, cz, e.toString())
                level.server.execute { inFlight.decrementAndGet(); level.chunkSource.removeRegionTicket(TICKET, pos, TICKET_DISTANCE, pos); finish(req, false) }
            }
        }
    }

    private fun finish(req: Request, ok: Boolean) {
        queued.remove(req.chunk)
        if (ok) completed++ else failures++
        val job = req.job ?: return
        if (ok) job.ok++ else job.failed++
        if (--job.remaining <= 0) {
            try { job.onDone(job.ok, job.failed) } catch (e: Exception) { Postroad.LOGGER.warn("Pre-generation job {} callback failed", job.id, e) }
        }
    }

    fun reset() {
        queue.clear(); queued.clear(); inFlight.set(0)
    }

    fun status(): String = "pre-generation: $completed chunk(s) done, $queueSize queued, ${inFlight.get()} in flight, $failures failed"
}
