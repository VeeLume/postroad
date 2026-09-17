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

    /**
     * Per status: a ticket strong enough to hold the chunk at the step being asked for, and no
     * stronger. Holding a structure-starts request at the carvers level would generate the terrain
     * anyway, which is exactly the cost that step is meant to avoid.
     */
    private fun ticketDistance(status: ChunkStatus): Int = 33 - net.minecraft.server.level.ChunkLevel.byStatus(status)

    private class Request(val dimension: ResourceLocation, val chunk: Long, val job: Job?, val status: ChunkStatus,
                          /** Run on the server thread with the finished chunk, before the job's own callback. */
                          val onChunk: ((net.minecraft.world.level.chunk.ChunkAccess) -> Unit)? = null)

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

    /**
     * No plan ever reaches this far: the pass radius is thousands of blocks, not millions. A chunk
     * beyond this is a coordinate that went wrong on the way here, and generating it would write a
     * region file in the far lands and hold a ticket on it.
     */
    private const val MAX_CHUNK = 100_000
    private var rejected = 0L

    /** True for a chunk position that could plausibly belong to a plan. */
    private fun sane(chunk: Long): Boolean {
        val x = ChunkPos.getX(chunk)
        val z = ChunkPos.getZ(chunk)
        return x > -MAX_CHUNK && x < MAX_CHUNK && z > -MAX_CHUNK && z < MAX_CHUNK
    }

    /** Queues [chunks] of [dimension]; [onDone] runs on the server thread once all of them are known (or failed). */
    fun request(
        dimension: ResourceLocation,
        chunks: Collection<Long>,
        status: ChunkStatus = ChunkStatus.CARVERS,
        onChunk: ((net.minecraft.world.level.chunk.ChunkAccess) -> Unit)? = null,
        onDone: ((ok: Int, failed: Int) -> Unit)? = null,
    ): Job? {
        val job = onDone?.let { Job(jobIds.incrementAndGet(), dimension, it) }
        var added = 0
        for (c in chunks) {
            if (!sane(c)) {
                // Log the first few with a stack trace: the caller that built this is the bug.
                if (rejected < 5) Postroad.LOGGER.error(
                    "Refusing to pre-generate chunk {}, {} (block {}, {}) — a plan never reaches that far; the request came from here",
                    ChunkPos.getX(c), ChunkPos.getZ(c), ChunkPos.getX(c) * 16, ChunkPos.getZ(c) * 16, Throwable("bad pregen coordinate"),
                )
                rejected++
                continue
            }
            if (KnownTerrain.has(dimension, c)) continue
            if (!queued.add(c)) { continue }
            queue.addLast(Request(dimension, c, job, status, onChunk))
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
        PregenMeasure.tick()
        // Work already asked for is always finished, even with planning off: the measurement drives
        // this directly, and a queue abandoned mid-flight would leave tickets held.
        if (!PostroadConfig.planEnabled && queue.isEmpty() && inFlight.get() == 0) return
        if (PostroadConfig.planPregenSortTicks > 0 && ++sinceSort >= PostroadConfig.planPregenSortTicks && queue.size > 1) { sinceSort = 0; sortByPlayers(server) }
        val cap = PostroadConfig.planPregenInFlight
        while (queue.isNotEmpty() && inFlight.get() < cap) {
            val req = queue.removeFirst()
            val level = server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, req.dimension))
            if (level == null) { finish(req, false); continue }
            start(level, req)
        }
    }

    private var sinceSort = 0

    /**
     * Players first: a road is laid only once its whole corridor is known, so the corridor (job) nearest a
     * player goes ahead of the rest, and inside it the chunks nearest the player. Requests of a dimension
     * without players, and those without a job, keep their order behind.
     */
    private fun sortByPlayers(server: MinecraftServer) {
        val players = server.playerList.players.groupBy({ it.level().dimension().location() }, { it.chunkPosition() })
        if (players.isEmpty()) return
        fun dist(req: Request): Long {
            val here = players[req.dimension] ?: return Long.MAX_VALUE
            val x = ChunkPos.getX(req.chunk); val z = ChunkPos.getZ(req.chunk)
            return here.minOf { p -> val dx = (p.x - x).toLong(); val dz = (p.z - z).toLong(); dx * dx + dz * dz }
        }
        val d = HashMap<Request, Long>(queue.size * 2)
        val nearestOfJob = HashMap<Int, Long>()
        for (req in queue) {
            val v = dist(req)
            d[req] = v
            req.job?.let { j -> nearestOfJob.merge(j.id, v) { a, b -> minOf(a, b) } }
        }
        val sorted = queue.sortedWith(compareBy<Request>({ r -> r.job?.let { nearestOfJob[it.id] } ?: d[r] }, { r -> r.job?.id ?: 0 }, { d[it] }))
        queue.clear()
        queue.addAll(sorted)
    }

    private fun start(level: ServerLevel, req: Request) {
        inFlight.incrementAndGet()
        val cx = ChunkPos.getX(req.chunk); val cz = ChunkPos.getZ(req.chunk)
        val pos = ChunkPos(cx, cz)
        val distance = ticketDistance(req.status)
        level.chunkSource.addRegionTicket(TICKET, pos, distance, pos)
        dispatcher.execute {
            try {
                level.chunkSource.getChunkFuture(cx, cz, req.status, true).whenCompleteAsync({ result, error ->
                    inFlight.decrementAndGet()
                    level.chunkSource.removeRegionTicket(TICKET, pos, distance, pos)
                    val chunk = if (error == null && result != null) result.orElse(null) else null
                    if (chunk == null) {
                        if (error != null) Postroad.LOGGER.warn("Pre-generation of chunk [{}, {}] failed: {}", cx, cz, error.toString())
                        finish(req, false)
                    } else {
                        if (req.status != ChunkStatus.STRUCTURE_STARTS) KnownTerrain.record(req.dimension, chunk, level)
                        req.onChunk?.let {
                            try { it(chunk) } catch (e: Exception) { Postroad.LOGGER.warn("Chunk callback for [{}, {}] failed", cx, cz, e) }
                        }
                        finish(req, true)
                    }
                }, level.server)
            } catch (e: Throwable) {
                Postroad.LOGGER.warn("Pre-generation request for chunk [{}, {}] failed: {}", cx, cz, e.toString())
                level.server.execute { inFlight.decrementAndGet(); level.chunkSource.removeRegionTicket(TICKET, pos, distance, pos); finish(req, false) }
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
