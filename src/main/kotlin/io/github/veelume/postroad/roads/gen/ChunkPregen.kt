package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.status.ChunkPyramid
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
 *
 * The chunks are generated as a moving front: a batch is queued row by row across its short side,
 * and a finished chunk keeps its ticket until nothing queued lies within the generation radius of
 * it, so the neighbours a request needs are still in memory. In 1.21.1 both the encoding of a
 * chunk for unload and the decoding of one read back run on the server thread; a ticket dropped
 * the moment its chunk was done unloaded a 17x17 halo that the next request read straight back,
 * and that, not generation, was what the server thread spent its time on (VPS, 2026-09-19).
 */
object ChunkPregen {
    class Job(val id: Int, val dimension: ResourceLocation, val onDone: (ok: Int, failed: Int) -> Unit) {
        var remaining = 0
        var ok = 0
        var failed = 0
    }

    /**
     * Our own ticket holds each requested chunk at the carvers step until it is there and its
     * neighbourhood is done with. The chunk source's own ticket for a plain future lasts one tick,
     * which is enough when the caller waits on the main thread and not at all when it does not: the
     * generation was cancelled underneath.
     */
    private val TICKET: net.minecraft.server.level.TicketType<ChunkPos> = net.minecraft.server.level.TicketType.create("postroad_pregen", Comparator.comparingLong(ChunkPos::toLong))

    /**
     * Per status: a ticket strong enough to hold the chunk at the step being asked for, and no
     * stronger. Holding a structure-starts request at the carvers level would generate the terrain
     * anyway, which is exactly the cost that step is meant to avoid.
     */
    private fun ticketDistance(status: ChunkStatus): Int = 33 - net.minecraft.server.level.ChunkLevel.byStatus(status)

    /**
     * How far a request at [status] reaches for neighbours: the accumulated dependency radius of the
     * generation pyramid's step to it (8 for every step from structure references on, the structure
     * starts halo; 0 for structure starts themselves). A finished chunk's ticket is kept while any
     * pending request lies within this of it, because that request would otherwise read the chunk,
     * or the halo the ticket keeps, back from disk.
     */
    private fun needRadius(status: ChunkStatus): Int = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status).accumulatedDependencies().radius

    private class Request(val dimension: ResourceLocation, val chunk: Long, val job: Job?, val status: ChunkStatus,
                          /** Run on the server thread with the finished chunk, before the job's own callback. */
                          val onChunk: ((net.minecraft.world.level.chunk.ChunkAccess) -> Unit)? = null,
                          /** Queue order at the time of the request; the player sort keeps it within a job. */
                          val seq: Long)

    /** A finished chunk's ticket, held until [releaseHeld] finds nothing queued within [radius] of it. */
    private class Held(val level: ServerLevel, val pos: ChunkPos, val distance: Int, val radius: Int)

    private val queue = ArrayDeque<Request>()
    /**
     * Asked from the server thread, the chunk source waits for the chunk (vanilla joins on the main
     * thread); asked from any other thread it merely schedules and returns a future. So requests
     * go through this one thread and never touch the server thread until the chunk is ready.
     */
    private val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "postroad-pregen").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    /** Chunks queued or in flight: what a held ticket may still be needed for. */
    private val queued = HashSet<Long>()
    /** Finished chunks whose tickets are still held, oldest first (server thread only). */
    private val held = LinkedHashMap<Long, Held>()
    private val inFlight = AtomicInteger()
    private val jobIds = AtomicInteger()
    private var seqs = 0L

    var requested: Long = 0; private set
    var completed: Long = 0; private set
    var failures: Long = 0; private set

    val queueSize: Int get() = queue.size
    val inFlightCount: Int get() = inFlight.get()
    val heldCount: Int get() = held.size

    /**
     * No plan ever reaches this far: the pass radius is thousands of blocks, not millions. A chunk
     * beyond this is a coordinate that went wrong on the way here, and generating it would write a
     * region file in the far lands and hold a ticket on it.
     */
    private const val MAX_CHUNK = 100_000
    private var rejected = 0L

    /**
     * Held tickets released per tick, at most. Each release can hand the chunk map a halo of up to
     * 17x17 chunks to unload, and the map saves under its tick budget only while its unload queue
     * is short (past 2000 it saves regardless of time, which was the 5-7 s stall at the end of a
     * run when every ticket dropped at once). Completions arrive at well under one per tick, so
     * this only ever throttles the tail of a job.
     */
    private const val RELEASE_PER_TICK = 8

    /** Held tickets examined per tick; the oldest come first and, with the queue in spatial order, go first. */
    private const val SCAN_PER_TICK = 256

    /**
     * Memory guard: past this many held tickets the oldest go regardless of what is queued near
     * them. A corridor front of 8 rows never comes close; overlapping corridors of different jobs,
     * queued far apart, could.
     */
    private const val MAX_HELD = 1024

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
        val fresh = ArrayList<Long>()
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
            fresh.add(c)
        }
        for (c in spatialOrder(fresh)) queue.addLast(Request(dimension, c, job, status, onChunk, seqs++))
        val added = fresh.size
        if (job != null) {
            job.remaining = added
            if (added == 0) job.onDone(0, 0)
        }
        requested += added
        return job
    }

    /**
     * Row by row across the short side, walking the long side: a corridor is generated as a front of
     * short cross-sections, so what a request needs is what just finished and is still held, and
     * nothing is read back from disk. The hash sets the corridors arrive in scattered consecutive
     * requests over the whole corridor, and every one of them found its neighbours unloaded.
     */
    private fun spatialOrder(chunks: List<Long>): List<Long> {
        if (chunks.size < 2) return chunks
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
        for (c in chunks) {
            val x = ChunkPos.getX(c); val z = ChunkPos.getZ(c)
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        return if (maxX - minX >= maxZ - minZ) chunks.sortedWith(compareBy({ ChunkPos.getX(it) }, { ChunkPos.getZ(it) }))
        else chunks.sortedWith(compareBy({ ChunkPos.getZ(it) }, { ChunkPos.getX(it) }))
    }

    fun onServerTick(server: MinecraftServer) {
        PregenMeasure.tick()
        // Work already asked for is always finished, even with planning off: the measurement drives
        // this directly, and a queue abandoned mid-flight would leave tickets held.
        if (!PostroadConfig.planEnabled && queue.isEmpty() && inFlight.get() == 0 && held.isEmpty()) return
        if (PostroadConfig.planPregenSortTicks > 0 && ++sinceSort >= PostroadConfig.planPregenSortTicks && queue.size > 1) { sinceSort = 0; sortByPlayers(server) }
        val cap = PostroadConfig.planPregenInFlight
        while (queue.isNotEmpty() && inFlight.get() < cap) {
            val req = queue.removeFirst()
            val level = server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, req.dimension))
            if (level == null) { finish(req, false); continue }
            start(level, req)
        }
        releaseHeld()
    }

    private var sinceSort = 0

    /**
     * Players first: a road is laid only once its whole corridor is known, so the corridor (job) nearest a
     * player goes ahead of the rest. Inside a job the requests keep their spatial order — that order is
     * what keeps neighbours resident. Requests of a dimension without players, and those without a job,
     * keep their order behind.
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
        val sorted = queue.sortedWith(compareBy<Request>({ r -> r.job?.let { nearestOfJob[it.id] } ?: d[r] }, { r -> r.job?.id ?: 0 }, { r -> if (r.job != null) r.seq else d[r] }))
        queue.clear()
        queue.addAll(sorted)
    }

    private fun start(level: ServerLevel, req: Request) {
        inFlight.incrementAndGet()
        val cx = ChunkPos.getX(req.chunk); val cz = ChunkPos.getZ(req.chunk)
        val pos = ChunkPos(cx, cz)
        val distance = ticketDistance(req.status)
        level.chunkSource.addRegionTicket(TICKET, pos, distance, pos)
        val ticket = Held(level, pos, distance, needRadius(req.status))
        dispatcher.execute {
            try {
                level.chunkSource.getChunkFuture(cx, cz, req.status, true).whenCompleteAsync({ result, error ->
                    inFlight.decrementAndGet()
                    hold(req.chunk, ticket)
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
                level.server.execute { inFlight.decrementAndGet(); hold(req.chunk, ticket); finish(req, false) }
            }
        }
    }

    /**
     * Server thread. The ticket is kept, not removed: [releaseHeld] lets it go once nothing queued needs
     * the neighbourhood. A chunk requested again while its earlier ticket is still held (structure-starts
     * requests are not recorded, so the town scan may ask twice) keeps one ticket, the newer.
     */
    private fun hold(chunk: Long, ticket: Held) {
        held.put(chunk, ticket)?.let { release(it) }
    }

    /**
     * Lets go of held tickets, oldest first, whose neighbourhood no queued request needs any more —
     * at most [RELEASE_PER_TICK] per tick, so the unloads they cause stay under the chunk map's
     * tick budget instead of being forced through at the end of a job.
     */
    private fun releaseHeld() {
        if (held.isEmpty()) return
        var released = 0
        var scanned = 0
        var forced = held.size - MAX_HELD
        val it = held.entries.iterator()
        while (it.hasNext() && released < RELEASE_PER_TICK && scanned < SCAN_PER_TICK) {
            val (chunk, ticket) = it.next()
            scanned++
            if (forced > 0 || !neededNearby(chunk, ticket.radius)) {
                it.remove()
                release(ticket)
                released++
                forced--
            }
        }
    }

    private fun release(ticket: Held) {
        ticket.level.chunkSource.removeRegionTicket(TICKET, ticket.pos, ticket.distance, ticket.pos)
    }

    /** Whether a queued or in-flight request lies within [radius] of [chunk]. */
    private fun neededNearby(chunk: Long, radius: Int): Boolean {
        if (queued.isEmpty()) return false
        val x = ChunkPos.getX(chunk); val z = ChunkPos.getZ(chunk)
        val span = 2 * radius + 1
        if (queued.size <= span * span) {
            for (q in queued) if (kotlin.math.abs(ChunkPos.getX(q) - x) <= radius && kotlin.math.abs(ChunkPos.getZ(q) - z) <= radius) return true
            return false
        }
        for (dz in -radius..radius) for (dx in -radius..radius) if (queued.contains(ChunkPos.asLong(x + dx, z + dz))) return true
        return false
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

    /** Drops the queue and every held ticket. Tickets of chunks still in flight are held on completion and go on the following ticks, as nothing is queued near them any more. */
    fun reset() {
        queue.clear(); queued.clear(); inFlight.set(0)
        for (t in held.values) release(t)
        held.clear()
    }

    fun status(): String = "pre-generation: $completed chunk(s) done, $queueSize queued, ${inFlight.get()} in flight, ${held.size} held, $failures failed"
}
