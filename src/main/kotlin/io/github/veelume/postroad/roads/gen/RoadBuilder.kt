package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.roads.Tier
import io.github.veelume.postroad.travel.SignNodes
import io.github.veelume.postroad.travel.SignWriter
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtUtils
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lays planned roads into chunks as they load, on the server thread under a block budget per
 * tick. A chunk's share of a road is the segments that start at the road points inside it;
 * each segment is a strip of `build.width` blocks on the actual surface, ground blocks the
 * styles file allows replaced, plants above cleared. Lampposts every `build.lampInterval`
 * blocks on alternating sides; a signpost with a way sign at every junction.
 *
 * Chunk load events may arrive off the server thread, so they only drop a key into a queue;
 * everything that reads the plan or touches blocks happens in the tick handler.
 */
object RoadBuilder {
    private class Job(val dimension: ResourceLocation, val chunk: Long) {
        /** Road blocks laid in this chunk's job, protected from the clearing of later pieces. */
        val protect = HashSet<Long>()
        /** The chunk's road ids, fixed when the job starts. */
        var roadIds: List<String>? = null
        /** Resume point: which road of [roadIds] and which point of it comes next. */
        var roadIndex = 0
        var pointIndex = 0
        var startedAt = 0L
        var blocks = 0
        var setBlockNanos = 0L
    }

    private val candidates = ConcurrentLinkedQueue<Job>()
    private val queue = ArrayDeque<Job>()
    private val queued = HashSet<String>()

    var chunksBuilt: Int = 0
        private set
    var blocksPlaced: Long = 0
        private set
    var signsPlaced: Int = 0
        private set

    val queueSize: Int get() = queue.size

    // ---- events ---------------------------------------------------------------------------------

    fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? ServerLevel ?: return
        candidates.add(Job(level.dimension().location(), event.chunk.pos.toLong()))
    }

    /** Offers a loaded chunk as if it had just loaded (the audit's fix). */
    fun onChunkLoad(level: ServerLevel, chunk: Long) {
        candidates.add(Job(level.dimension().location(), chunk))
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        if (!PostroadConfig.planEnabled || RoadGen.planningPaused) { candidates.clear(); return }
        val storage = RoadPlanStorage.get(server)
        sweep(server, storage)
        // Filter candidates against the plan; most loaded chunks carry no road.
        var taken = 0
        while (taken < 256) {
            val job = candidates.poll() ?: break
            taken++
            if (needsWork(storage, job)) offer(job)
        }
        if (queue.isEmpty()) return
        val deadline = System.nanoTime() + PostroadConfig.buildMillisPerTick * 1_000_000L
        while (queue.isNotEmpty() && System.nanoTime() < deadline) {
            val job = queue.first()
            val level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, job.dimension))
            if (level == null || !level.hasChunk(ChunkPos.getX(job.chunk), ChunkPos.getZ(job.chunk))) {
                queue.removeFirst(); queued.remove(key(job)); continue
            }
            // Roads the feature laid while the chunk generated are done; the builder lays the rest.
            val laidHere = RoadPlanSnapshot.laidRoads(job.chunk)
            if (laidHere.isNotEmpty()) for (road in storage.roadsInChunk(job.dimension, job.chunk)) if (road.id in laidHere && road.builtChunks.add(job.chunk)) storage.setDirty()
            if (job.startedAt == 0L) job.startedAt = System.nanoTime()
            val finished = buildChunkStep(level, storage, job, deadline)
            if (!finished) break // resumes next tick where it stopped
            queue.removeFirst(); queued.remove(key(job))
            val total = (System.nanoTime() - job.startedAt) / 1_000_000
            if (total > 100) Postroad.LOGGER.info("Road chunk {}: {} block(s) over {} ms wall, {} ms of it in setBlock",
                ChunkPos(job.chunk), job.blocks, total, job.setBlockNanos / 1_000_000)
        }
    }

    private fun key(job: Job) = "${job.dimension}|${job.chunk}"

    /** True when the road feature laid this chunk's roads while it generated (this session). */
    fun generatedWithRoad(level: ServerLevel, chunk: Long): Boolean = RoadPlanSnapshot.wasLaid(chunk)

    private fun offer(job: Job) {
        if (queued.add(key(job))) queue.addLast(job)
    }

    private fun needsWork(storage: RoadPlanStorage, job: Job): Boolean =
        storage.roadsInChunk(job.dimension, job.chunk).any { !it.provisional && !it.builtChunks.contains(job.chunk) } ||
            storage.junctions.any { it.fork && !it.signPlaced && it.dimension == job.dimension && ChunkPos.asLong(it.pos.x shr 4, it.pos.z shr 4) == job.chunk } ||
            PitStops.pending(storage, job.dimension, job.chunk)

    /** Queues every already-loaded chunk of [road]; called when a plan lands after the chunks did. */
    fun enqueueLoaded(level: ServerLevel, road: PlannedRoad) {
        val dim = level.dimension().location()
        for (c in road.chunks()) {
            if (!level.hasChunk(ChunkPos.getX(c), ChunkPos.getZ(c))) continue
            // A built chunk may still owe a junction sign or a pit stop; the tick filter decides.
            if (road.builtChunks.contains(c)) candidates.add(Job(dim, c)) else offer(Job(dim, c))
        }
    }

    private var sweepIn = 0

    /**
     * Signs and stops are built when a chunk loads. A junction or a stop that becomes due while its chunk
     * is already loaded (a later pass joined a built road, a town became settled) would wait for the next
     * load, so every `build.sweepSeconds` the loaded chunks that owe one are offered again.
     */
    private fun sweep(server: MinecraftServer, storage: RoadPlanStorage) {
        if (--sweepIn > 0) return
        sweepIn = PostroadConfig.buildSweepTicks
        val due = HashSet<Pair<ResourceLocation, Long>>()
        for (j in storage.junctions) if (j.fork && !j.signPlaced) due.add(j.dimension to ChunkPos.asLong(j.pos.x shr 4, j.pos.z shr 4))
        due.addAll(PitStops.dueChunks(storage))
        // Road chunks a final road still owes: a job that ended early, a chunk that loaded while the road was provisional.
        for (road in storage.roads.values) if (!road.provisional) for (c in road.chunks()) if (!road.builtChunks.contains(c)) due.add(road.dimension to c)
        for ((dim, c) in due) {
            val level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim)) ?: continue
            if (level.hasChunk(ChunkPos.getX(c), ChunkPos.getZ(c))) candidates.add(Job(dim, c))
        }
    }


    /**
     * `/postroad roads showcase`: every catalog piece laid as authored on its own stone platform in
     * the air near [origin], a lime block one beyond its first connector and red ones beyond the
     * others; the placements go to the piece debug layer with their names. Returns how many.
     */
    fun showcase(level: ServerLevel, origin: BlockPos, family: Int = 0): Int {
        val catalog = RoadPieces.current
        val styles = RoadStyles.current
        val baseY = (level.getHeight(Heightmap.Types.MOTION_BLOCKING, origin.x, origin.z) + 12).coerceAtMost(level.maxBuildHeight - 16)
        val stone = Blocks.STONE.defaultBlockState()
        val lime = Blocks.LIME_CONCRETE.defaultBlockState()
        val red = Blocks.RED_CONCRETE.defaultBlockState()
        val dim = level.dimension().location()
        RoadDebug.showcase.removeAll { it.first == dim }
        var n = 0
        // One row per tier, 14 blocks apart, so the three palettes of a family can be compared side by side.
        for (tier in Tier.entries.indices) {
            val style = styles.style(family, tier)
            var x0 = origin.x + 4
            val z0 = origin.z + tier * 14
            for (piece in catalog.pieces.sortedBy { it.id.path }) {
                // Platform: two layers of stone under the whole piece, its top at baseY; the piece's level 0 is the platform top.
                for (z in z0 - 5..z0 + 5) for (x in x0 - 5..x0 + 5) for (y in baseY - 1..baseY) level.setBlock(BlockPos(x, y, z), stone, 3)
                for (z in z0 - 5..z0 + 5) for (x in x0 - 5..x0 + 5) for (y in baseY + 1..baseY + 8) level.setBlock(BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3)
                val p = PiecePlacement(piece, Transform(0, false), x0, baseY, z0, 0, "showcase")
                RoadPieceLayer.lay(level, p, style, styles, { x, z -> groundY(level, x, z, baseY + 1, styles) })
                for ((k, c) in p.connectors().withIndex()) {
                    val (pos, f, lvl) = c
                    level.setBlock(BlockPos(pos.x + f.dx, lvl, pos.z + f.dz), if (k == 0) lime else red, 3)
                }
                RoadDebug.showcase.add(dim to p)
                n++
                x0 += 12
            }
        }
        return n
    }

    /**
     * `/postroad roads capture <id>`: reads the showcase piece [id] back out of the world — every block on
     * its platform above the platform's top, mapped to a role by the temperate palette (dirt path →
     * surface, coarse dirt and gravel → edge, stairs with their facing, slabs, cobblestone → fill unless a
     * road block stands above it, fence → post, lantern → lamp) — and writes it as the piece's json to
     * `<world>/postroad/pieces/<id>.json`, keeping the current piece's connectors, cost and fit. So a piece
     * can be adjusted by hand in the showcase and captured, instead of transcribed. Returns a summary.
     */
    fun capture(level: ServerLevel, id: String, near: BlockPos): String {
        val piece = RoadPieces.current.pieces.firstOrNull { it.id.path == id } ?: return "No catalog piece '$id'."
        // The platform is found by its lime marker (one beyond the first connector), nearest to [near]: the
        // showcase list does not survive a restart, and re-laying the showcase would erase the adjustments.
        var lime: BlockPos? = null
        for (dz in -20..20) for (dx in -20..20) for (dy in -10..10) {
            val pos = near.offset(dx, dy, dz)
            if (level.getBlockState(pos).block == Blocks.LIME_CONCRETE && (lime == null || pos.distSqr(near) < lime.distSqr(near))) lime = pos
        }
        val marker = lime ?: return "No lime marker block within 20 blocks of ${near.toShortString()}; stand next to the piece."
        val c0 = piece.connectors.first()
        val p = PiecePlacement(piece, Transform(0, false), marker.x - c0.x - c0.facing.dx, marker.y - c0.level, marker.z - c0.z - c0.facing.dz, 0, "capture")
        val blocks = ArrayList<Map<String, Any>>()
        val decor = ArrayList<Map<String, Any>>()
        var skipped = 0
        val roadBlocks = HashSet<Long>()
        fun key(x: Int, z: Int) = RoadPieceLayer.key(x, z)
        // First pass: which columns hold a road block at which level (to drop the fill the layer puts under them).
        val roadTop = HashMap<Long, Int>()
        for (dz in -4..4) for (dx in -4..4) for (dy in 0..8) {
            val s = level.getBlockState(BlockPos(p.x + dx, p.base + dy, p.z + dz))
            val b = s.block
            if (b == Blocks.DIRT_PATH || b == Blocks.COARSE_DIRT || b == Blocks.GRAVEL || b is net.minecraft.world.level.block.StairBlock || b is net.minecraft.world.level.block.SlabBlock) roadTop[key(dx, dz)] = maxOf(roadTop[key(dx, dz)] ?: Int.MIN_VALUE, dy)
        }
        for (dz in -4..4) for (dx in -4..4) for (dy in 0..8) {
            val pos = BlockPos(p.x + dx, p.base + dy, p.z + dz)
            val s = level.getBlockState(pos)
            val b = s.block
            if (s.isAir || b == Blocks.STONE || b == Blocks.LIME_CONCRETE || b == Blocks.RED_CONCRETE) continue
            val at = listOf(dx, dy, dz)
            when {
                b == Blocks.DIRT_PATH -> blocks.add(mapOf("at" to at, "role" to "surface"))
                b == Blocks.COARSE_DIRT || b == Blocks.GRAVEL -> blocks.add(mapOf("at" to at, "role" to "edge"))
                b is net.minecraft.world.level.block.StairBlock -> { val f = s.getValue(net.minecraft.world.level.block.StairBlock.FACING); blocks.add(mapOf("at" to at, "role" to "stair", "facing" to listOf(f.stepX, f.stepZ))) }
                b is net.minecraft.world.level.block.SlabBlock -> blocks.add(mapOf("at" to at, "role" to "slab"))
                // Cobblestone: fill under a road block is the layer's own work; exposed at the top of its column it is a landing (`paved`); with structure above it, structure (`fill`).
                b == Blocks.COBBLESTONE -> {
                    if ((roadTop[key(dx, dz)] ?: Int.MIN_VALUE) > dy) skipped++
                    else blocks.add(mapOf("at" to at, "role" to (if (level.getBlockState(pos.above()).isAir) "paved" else "fill")))
                }
                b is net.minecraft.world.level.block.FenceBlock -> decor.add(mapOf("at" to at, "role" to "post", "every" to 8))
                b is net.minecraft.world.level.block.LanternBlock -> decor.add(mapOf("at" to at, "role" to "lamp", "every" to 8))
                else -> { skipped++; Postroad.LOGGER.warn("capture {}: unknown block {} at {}; skipped", id, b, pos.toShortString()) }
            }
        }
        val json = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val out = linkedMapOf<String, Any>(
            "id" to id, "cost" to piece.cost,
            "connectors" to piece.connectors.map { c -> mapOf("at" to listOf(c.x, c.y, c.z), "facing" to listOf(c.facing.dx, c.facing.dz), "level" to c.level) },
            "blocks" to blocks,
        )
        if (decor.isNotEmpty()) out["decor"] = decor
        out["fit"] = if (piece.fit.none) "none" else mapOf("cut" to piece.fit.cut, "fill" to piece.fit.fill, "deck" to piece.fit.deck)
        val file = level.server.getWorldPath(net.minecraft.world.level.storage.LevelResource("postroad")).resolve("pieces").resolve("$id.json")
        java.nio.file.Files.createDirectories(file.parent)
        java.nio.file.Files.writeString(file, json.toJson(out) + "\n")
        return "Captured $id at anchor (${p.x}, ${p.base}, ${p.z}): ${blocks.size} block(s), ${decor.size} decoration(s), $skipped skipped (fill under road blocks, unknown) → $file"
    }

    /** `/postroad roads rebuild`: re-queues every loaded, unbuilt road chunk. Returns how many. */
    fun requeueLoaded(server: MinecraftServer): Int {
        val storage = RoadPlanStorage.get(server)
        var n = 0
        for (road in storage.roads.values) {
            val level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, road.dimension)) ?: continue
            for (c in road.chunks()) {
                if (road.builtChunks.contains(c) || !level.hasChunk(ChunkPos.getX(c), ChunkPos.getZ(c))) continue
                offer(Job(road.dimension, c)); n++
            }
        }
        return n
    }

    // ---- building -------------------------------------------------------------------------------

    /** Builds everything planned for [chunk] in one go (tests, rebuild); returns blocks placed. */
    fun buildChunk(level: ServerLevel, storage: RoadPlanStorage, chunk: Long): Int {
        val job = Job(level.dimension().location(), chunk)
        while (!buildChunkStep(level, storage, job, Long.MAX_VALUE)) { /* no deadline: finishes in one call */ }
        return job.blocks
    }

    /**
     * Builds [job]'s chunk from its resume point until done or [deadline]; true when the chunk is
     * finished. Progress lives on the job so a heavy chunk spreads over as many ticks as it needs.
     */
    private fun buildChunkStep(level: ServerLevel, storage: RoadPlanStorage, job: Job, deadline: Long): Boolean {
        val dim = level.dimension().location()
        val chunk = job.chunk
        val styles = RoadStyles.current
        val boxes = storage.townsIn(dim).flatMap { it.footprint }
        // The chunk's roads as they were when the job began: a road added or swapped in meanwhile would shift
        // the resume index past one still to build. Later roads are the next job's (the sweep offers them).
        val ids = job.roadIds ?: storage.roadsInChunk(dim, chunk).map { it.id }.also { job.roadIds = it }
        while (job.roadIndex < ids.size) {
            val road = storage.roads[ids[job.roadIndex]] ?: run { job.roadIndex++; job.pointIndex = 0; null } ?: continue
            // Provisional roads wait for their replan; they are queued again when they turn final.
            if (road.provisional || road.builtChunks.contains(chunk)) { job.roadIndex++; job.pointIndex = 0; continue }
            val next = buildRoadInChunk(level, road, chunk, styles, boxes, job, deadline)
            if (next >= 0) { job.pointIndex = next; return false }
            road.builtChunks.add(chunk)
            storage.setDirty()
            job.roadIndex++; job.pointIndex = 0
        }
        for (junction in storage.junctions) {
            // Only where the roads part: a merge links two paths but has nothing to point at.
            if (junction.signPlaced || !junction.fork || junction.dimension != dim) continue
            if (ChunkPos.asLong(junction.pos.x shr 4, junction.pos.z shr 4) != chunk) continue
            // Forks a few blocks apart are one place on the ground: one signpost for all of them, and none
            // beside a post one of them already has.
            fun close(p: BlockPos) = abs(p.x - junction.pos.x) <= PostroadConfig.buildJunctionMerge && abs(p.z - junction.pos.z) <= PostroadConfig.buildJunctionMerge
            val cluster = storage.junctions.filter { it.dimension == dim && it.fork && !it.signPlaced && close(it.pos) }
            // Signed already: a junction post nearby (a road-end post does not count; it gives way below).
            val endPosts = storage.roadsIn(dim).flatMap { it.endPosts.values }.toSet()
            val signed = Network.get(level.server).nodes.values.any { n ->
                n.kind == io.github.veelume.postroad.roads.RoadNode.KIND_SIGN && n.dimension == dim && close(n.pos) &&
                    endPosts.none { it.x == n.pos.x && it.z == n.pos.z }
            }
            if (!signed) {
                val n = placeJunctionSign(level, storage, junction, cluster, styles, boxes)
                // A road-end post put up before this fork existed repeats what the fork's sign now says.
                if (n > 0) PitStops.removeEndPostsNear(level, storage, junction.pos)
                job.blocks += n
            }
            cluster.forEach { it.signPlaced = true }
            storage.setDirty()
        }
        job.blocks += PitStops.build(level, storage, chunk, styles, boxes)
        if (job.blocks > 0) { chunksBuilt++; blocksPlaced += job.blocks }
        return true
    }

    private fun chunkOf(p: BlockPos): Long = ChunkPos.asLong(p.x shr 4, p.z shr 4)

    /**
     * Builds [road]'s pieces that reach into [chunk], from [job]'s piece index, clipped to the chunk.
     * Returns the next piece to resume at, or -1 when the road is done here.
     */
    private fun buildRoadInChunk(level: ServerLevel, road: PlannedRoad, chunk: Long, styles: RoadStyleSet, boxes: List<BoundingBox>, job: Job, deadline: Long): Int {
        val catalog = RoadPieces.current
        val placements = catalog.assemble(road.points, road.id) ?: run {
            Postroad.LOGGER.warn("Road {} has a point no piece fits; not built", road.id)
            return -1
        }
        val footprint = RoadPieceLayer.footprintOf(placements)
        val cx = ChunkPos.getX(chunk); val cz = ChunkPos.getZ(chunk)
        fun touches(p: PiecePlacement): Boolean {
            val r = RoadPieceLayer.reachOf(p)
            return cx in (r[0] shr 4)..(r[3] shr 4) && cz in (r[2] shr 4)..(r[5] shr 4)
        }
        // A shared anchor (a junction) belongs to the road with the smaller id; see RoadPlanSnapshot.anchorClaims.
        // The published map, not one worked out again here: computing it twice let a shared anchor
        // change hands between the snapshot and the build, and both roads laid a piece on it.
        val claims = RoadPlanSnapshot.claims
        var placed = 0
        var i = job.pointIndex
        while (i < placements.size) {
            if (i > job.pointIndex && System.nanoTime() > deadline) { job.blocks += placed; return i }
            val p = placements[i]
            if (touches(p) && claims[RoadPieceLayer.key(p.x, p.z)] == road.id) {
                val style = styles.style(road.families.getOrElse(p.index) { 0 }.toInt(), road.tier)
                val t0 = System.nanoTime()
                placed += RoadPieceLayer.lay(level, p, style, styles,
                    { x, z -> if (level.hasChunk(x shr 4, z shr 4) && !inBox(x, z, boxes)) groundY(level, x, z, p.base + 1, styles) else Int.MIN_VALUE },
                    { x, z -> (x shr 4) == cx && (z shr 4) == cz }, { x, z -> footprint.contains(RoadPieceLayer.key(x, z)) }, catalog, job.protect)
                job.setBlockNanos += System.nanoTime() - t0
            }
            i++
        }
        job.blocks += placed
        return -1
    }

    private fun inBox(x: Int, z: Int, boxes: List<BoundingBox>): Boolean =
        boxes.any { x >= it.minX() - 1 && x <= it.maxX() + 1 && z >= it.minZ() - 1 && z <= it.maxZ() + 1 }

    /**
     * Ground at (x, z): scanning down from [hint] + [SCAN] blocks, the first block that is not air,
     * a plant, leaves or a barrier. The heightmap would do, but it counts leaves' logs, barriers
     * (the game-test harness encases tests in them) and anything odd above the road.
     */
    fun groundY(level: net.minecraft.world.level.LevelAccessor, x: Int, z: Int, hint: Int, styles: RoadStyleSet = RoadStyles.current): Int {
        val cursor = BlockPos.MutableBlockPos(x, 0, z)
        var y = minOf(hint + SCAN, level.maxBuildHeight - 1)
        val floor = maxOf(hint - SCAN, level.minBuildHeight)
        while (y >= floor) {
            cursor.setY(y)
            val s = level.getBlockState(cursor)
            if (!s.isAir && !s.`is`(Blocks.BARRIER) && s.block !is net.minecraft.world.level.block.LeavesBlock && !styles.isClearable(s)) return y
            y--
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
    }

    private const val SCAN = 16

    /**
     * A signpost beside the joined road at the junction, on the side away from the branch: the family's
     * post with a way sign on it (Supplementaries' if present, else a plain sign), standing on the ground
     * there whatever the road's level. A way sign has two arms, so a fork's third direction gets a second
     * way sign on top. The lower sign is a node on the joined road, the upper one on the joining road.
     */
    private fun placeJunctionSign(level: ServerLevel, storage: RoadPlanStorage, junction: PlannedJunction, cluster: List<PlannedJunction>,
                                  styles: RoadStyleSet, boxes: List<BoundingBox>): Int {
        val road = storage.roads[junction.roadA] ?: return 0
        val index = pointIndexAt(road, junction.pos) ?: return 0
        val style = styles.style(road.families.getOrElse(index) { 0 }.toInt(), road.tier)
        val network = Network.get(level.server)
        val roadIds = (listOf(junction) + cluster).flatMap { listOf(it.roadA, it.roadB) }.distinct()
        val arms = junctionArms(network, storage, junction.pos, roadIds)
        // Where the roads only braid (split for a cell and meet again) nothing branches: no sign.
        if (arms.size < 3) return 0
        val before = road.points.getOrElse(index - 1) { road.points[index] }
        val after = road.points.getOrElse(index + 1) { road.points[index] }
        // Two arms per way sign, in order (the joined road's first); each sign a node on its first arm's road.
        val groups = arms.chunked(2).mapNotNull { group ->
            val roadId = group.first().first
            val groupIndex = storage.roads[roadId]?.let { pointIndexAt(it, junction.pos, CLUSTER_REACH_SQ) } ?: return@mapNotNull null
            SignGroup(roadId, groupIndex, group.map { it.second })
        }.ifEmpty { listOf(SignGroup(road.id, index, emptyList())) }
        return erectSignpost(level, styles, style, junction.pos, before, after, groups, boxes, emptyList())
    }

    /** How far a cluster's roads may have their point from the sign, squared: `build.junctionMerge` on both axes. */
    private val CLUSTER_REACH_SQ: Double get() = 2.0 * PostroadConfig.buildJunctionMerge * PostroadConfig.buildJunctionMerge

    /** One way-sign block of a signpost: the road and point it is a node on, and its arms. */
    class SignGroup(val roadId: String, val index: Int, val arms: List<SignWriter.Arm>)

    /**
     * A signpost standing on the ground beside [at], off the road whose direction there runs from [before]
     * to [after]: the family's post with one way sign per group stacked on it (a plain sign when
     * Supplementaries is missing), faces toward [at]. The side the arms point to least is tried first, so the
     * post does not stand in a branch; spots inside [boxes] or [avoid] are skipped. Returns blocks placed, 0
     * when neither side had room.
     */
    internal fun erectSignpost(level: ServerLevel, styles: RoadStyleSet, style: RoadStyle, at: BlockPos, before: BlockPos, after: BlockPos,
                               groups: List<SignGroup>, boxes: List<BoundingBox>, avoid: List<BoundingBox>,
                               /** Told the lowest sign's position once the post stands. */
                               placedAt: (BlockPos) -> Unit = {}): Int {
        if (groups.isEmpty()) return 0
        val dx = (after.x - before.x).toDouble()
        val dz = (after.z - before.z).toDouble()
        val len = sqrt(dx * dx + dz * dz).takeIf { it > 0 } ?: 1.0
        val offset = PostroadConfig.buildWidth / 2 + 2
        val arms = groups.flatMap { it.arms }
        fun armward(side: Int): Double = arms.maxOfOrNull { arm ->
            val ax = (arm.aim.x - at.x).toDouble(); val az = (arm.aim.z - at.z).toDouble()
            (-dz * side * ax + dx * side * az) / (sqrt(ax * ax + az * az).takeIf { it > 0 } ?: 1.0)
        } ?: 0.0
        // Plants and leaves give way to the post; anything else is no room.
        fun free(pos: BlockPos): Boolean = level.getBlockState(pos).let { styles.isClearable(it) || it.block is net.minecraft.world.level.block.LeavesBlock }
        val sides = intArrayOf(1, -1).sortedBy { armward(it) }
        // A side where the whole stack fits first; only then one that takes the lower signs alone.
        for (whole in booleanArrayOf(true, false)) for (side in sides) {
            val x = (at.x - dz / len * offset * side).roundToInt()
            val z = (at.z + dx / len * offset * side).roundToInt()
            if (!level.hasChunk(x shr 4, z shr 4) || inBox(x, z, boxes) || inBox(x, z, avoid)) continue
            val y = groundY(level, x, z, at.y, styles)
            if (y <= level.minBuildHeight) continue
            val ground = BlockPos(x, y, z)
            val state = level.getBlockState(ground)
            if (!state.fluidState.isEmpty || !state.isSolid) continue
            // Never on top of another post: its way sign reads as ground to the scan.
            if (BuiltInRegistries.BLOCK.getKey(state.block) == WAY_SIGN || state.block is net.minecraft.world.level.block.FenceBlock ||
                state.block is net.minecraft.world.level.block.WallBlock || state.block is net.minecraft.world.level.block.SignBlock) continue
            if (!free(ground.above()) || !free(ground.above(2))) continue
            if (whole && (1 until groups.size).any { !free(ground.above(2 + it)) }) continue
            val signPos = ground.above(2)
            level.setBlock(ground.above(), style.post, 3)
            placedAt(signPos)
            val waySign = BuiltInRegistries.BLOCK.getOptional(WAY_SIGN).orElse(null)
            if (waySign == null) {
                level.setBlock(signPos, Blocks.OAK_SIGN.defaultBlockState(), 3)
                val node = SignNodes.linkGenerated(level, signPos, groups[0].roadId, groups[0].index) ?: return 2
                (level.getBlockEntity(signPos) as? net.minecraft.world.level.block.entity.SignBlockEntity)?.let { SignWriter.labelSign(level, it, node.name, "") }
                signsPlaced++
                return 3
            }
            var placed = 2
            for ((k, group) in groups.withIndex()) {
                val pos = signPos.above(k)
                // A group above the first needs headroom; without it the post keeps what fits.
                if (k > 0 && !free(pos)) {
                    Postroad.LOGGER.info("Signpost at {}: no headroom for {} of its {} way signs", signPos.toShortString(), groups.size - k, groups.size)
                    break
                }
                level.setBlock(pos, waySign.defaultBlockState(), 3)
                mimic(level, pos, style.post)
                placed++
                SignNodes.linkGenerated(level, pos, group.roadId, group.index) ?: continue
                if (group.arms.isNotEmpty()) level.getBlockEntity(pos)?.let { SignWriter.setArms(level, it, pos, group.arms, viewer = at) }
            }
            signsPlaced++
            return placed
        }
        return 0
    }

    /** The index of [road]'s point at [pos]'s column, or the nearest point within a piece's reach. */
    internal fun pointIndexAt(road: PlannedRoad, pos: BlockPos, reachSq: Double = ARM_JOIN_SQ): Int? {
        road.points.indexOfFirst { it.x == pos.x && it.z == pos.z }.takeIf { it >= 0 }?.let { return it }
        return road.points.indices.minByOrNull { road.points[it].distSqr(pos) }?.takeIf { road.points[it].distSqr(pos) <= reachSq }
    }

    /**
     * The directions [roadIds] leave [at] in, each with the town that way (by road id), in the order of
     * [roadIds] (the joined road first). Roads sharing a stretch leave in one direction, which keeps the
     * first road's arm. For a cluster of forks the aim is taken past the cluster.
     */
    private fun junctionArms(network: Network, storage: RoadPlanStorage, at: BlockPos, roadIds: List<String>): List<Pair<String, SignWriter.Arm>> {
        val arms = ArrayList<Pair<String, SignWriter.Arm>>()
        for (roadId in roadIds) {
            val road = storage.roads[roadId] ?: continue
            val index = pointIndexAt(road, at, CLUSTER_REACH_SQ) ?: continue
            val last = road.points.size - 1
            val aimAhead = if (roadIds.size > 2) ARM_AIM_CLUSTER else ARM_AIM
            val ends = listOfNotNull(
                (road.from to road.points[maxOf(0, index - aimAhead)]).takeIf { index > 0 },
                (road.to to road.points[minOf(last, index + aimAhead)]).takeIf { index < last },
            )
            for ((town, aim) in ends) {
                val ax = (aim.x - at.x).toDouble(); val az = (aim.z - at.z).toDouble()
                val alen = sqrt(ax * ax + az * az).takeIf { it > 0 } ?: continue
                val same = arms.any { (_, other) ->
                    val ox = (other.aim.x - at.x).toDouble(); val oz = (other.aim.z - at.z).toDouble()
                    (ax * ox + az * oz) / (alen * sqrt(ox * ox + oz * oz)) > ARM_SAME_COS
                }
                if (same) continue
                val name = network.places[town]?.name ?: net.minecraft.network.chat.Component.translatable("sign.postroad.village").string
                arms.add(roadId to SignWriter.Arm(name, aim))
            }
        }
        return arms
    }

    /** Plan points out an arm aims at (pieces are 3 blocks apart, so about 12 blocks along the road). */
    private const val ARM_AIM = 4
    private const val ARM_AIM_CLUSTER = 8
    /** Two arms within 45° of each other point the same way. */
    private val ARM_SAME_COS = cos(Math.toRadians(45.0))
    private const val ARM_JOIN_SQ = 18.0

    /** Sets the fence a way sign renders around, through its tile NBT (Moonlight's mimic tile). */
    private fun mimic(level: ServerLevel, pos: BlockPos, post: BlockState) {
        val entity = level.getBlockEntity(pos) ?: return
        try {
            val registries = level.registryAccess()
            val tag = entity.saveWithoutMetadata(registries)
            tag.put("Mimic", NbtUtils.writeBlockState(post))
            entity.loadWithComponents(tag, registries)
            entity.setChanged()
            level.sendBlockUpdated(pos, entity.blockState, entity.blockState, 3)
        } catch (e: Exception) {
            Postroad.LOGGER.warn("Could not set way-sign post: {}", e.toString())
        }
    }

    fun reset() {
        candidates.clear(); queue.clear(); queued.clear()
    }

    val WAY_SIGN: ResourceLocation = ResourceLocation.fromNamespaceAndPath("supplementaries", "way_sign")
}
