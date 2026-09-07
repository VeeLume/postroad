package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.roads.RoadNode
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
import kotlin.math.ceil
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
        /** Resume point: which road of the chunk's list and which point of it comes next. */
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
        if (!PostroadConfig.planEnabled) { candidates.clear(); return }
        val storage = RoadPlanStorage.get(server)
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
            storage.junctions.any { !it.signPlaced && it.dimension == job.dimension && ChunkPos.asLong(it.pos.x shr 4, it.pos.z shr 4) == job.chunk }

    /** Queues every already-loaded chunk of [road]; called when a plan lands after the chunks did. */
    fun enqueueLoaded(level: ServerLevel, road: PlannedRoad) {
        val dim = level.dimension().location()
        for (c in road.chunks()) {
            if (road.builtChunks.contains(c)) continue
            if (level.hasChunk(ChunkPos.getX(c), ChunkPos.getZ(c))) offer(Job(dim, c))
        }
    }

    /**
     * `/postroad roads showcase`: every catalog piece laid as authored on its own stone platform in
     * the air near [origin], a lime block one beyond its first connector and red ones beyond the
     * others; the placements go to the piece debug layer with their names. Returns how many.
     */
    fun showcase(level: ServerLevel, origin: BlockPos): Int {
        val catalog = RoadPieces.current
        val styles = RoadStyles.current
        val style = styles.style(0)
        val baseY = (level.getHeight(Heightmap.Types.MOTION_BLOCKING, origin.x, origin.z) + 12).coerceAtMost(level.maxBuildHeight - 16)
        val stone = Blocks.STONE.defaultBlockState()
        val lime = Blocks.LIME_CONCRETE.defaultBlockState()
        val red = Blocks.RED_CONCRETE.defaultBlockState()
        val dim = level.dimension().location()
        RoadDebug.showcase.removeAll { it.first == dim }
        var n = 0
        var x0 = origin.x + 4
        val z0 = origin.z
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
        val roads = storage.roadsInChunk(dim, chunk)
        while (job.roadIndex < roads.size) {
            val road = roads[job.roadIndex]
            // Provisional roads wait for their replan; they are queued again when they turn final.
            if (road.provisional || road.builtChunks.contains(chunk)) { job.roadIndex++; job.pointIndex = 0; continue }
            val next = buildRoadInChunk(level, road, chunk, styles, boxes, job, deadline)
            if (next >= 0) { job.pointIndex = next; return false }
            road.builtChunks.add(chunk)
            storage.setDirty()
            job.roadIndex++; job.pointIndex = 0
        }
        for (junction in storage.junctions) {
            if (junction.signPlaced || junction.dimension != dim) continue
            if (ChunkPos.asLong(junction.pos.x shr 4, junction.pos.z shr 4) != chunk) continue
            job.blocks += placeJunctionSign(level, storage, junction, styles, boxes)
            junction.signPlaced = true
            storage.setDirty()
        }
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
        val claims = RoadPlanSnapshot.anchorClaims(RoadPlanStorage.get(level.server).roadsIn(level.dimension().location()).filter { !it.provisional })
        var placed = 0
        var i = job.pointIndex
        while (i < placements.size) {
            if (i > job.pointIndex && System.nanoTime() > deadline) { job.blocks += placed; return i }
            val p = placements[i]
            if (touches(p) && claims[RoadPieceLayer.key(p.x, p.z)] == road.id) {
                val style = styles.style(road.families.getOrElse(p.index) { 0 }.toInt())
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
    fun groundY(level: ServerLevel, x: Int, z: Int, hint: Int, styles: RoadStyleSet = RoadStyles.current): Int {
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
     * A signpost one block off the joined road at the junction: the family's post with a way sign
     * on it (Supplementaries' if present, else a plain sign). Registered as a sign node on the
     * joined road; arms point at the road's two towns.
     */
    private fun placeJunctionSign(level: ServerLevel, storage: RoadPlanStorage, junction: PlannedJunction, styles: RoadStyleSet, boxes: List<BoundingBox>): Int {
        val road = storage.roads[junction.roadA] ?: return 0
        val index = road.points.indexOfFirst { it.x == junction.pos.x && it.z == junction.pos.z }.takeIf { it >= 0 } ?: return 0
        val style = styles.style(road.families.getOrElse(index) { 0 }.toInt())
        // Off the road: perpendicular to the joined road's direction here.
        val before = road.points.getOrElse(index - 1) { road.points[index] }
        val after = road.points.getOrElse(index + 1) { road.points[index] }
        val dx = (after.x - before.x).toDouble()
        val dz = (after.z - before.z).toDouble()
        val len = sqrt(dx * dx + dz * dz).takeIf { it > 0 } ?: 1.0
        val offset = PostroadConfig.buildWidth / 2 + 2
        for (side in intArrayOf(1, -1)) {
            val x = (junction.pos.x - dz / len * offset * side).roundToInt()
            val z = (junction.pos.z + dx / len * offset * side).roundToInt()
            if (!level.hasChunk(x shr 4, z shr 4) || inBox(x, z, boxes)) continue
            val y = groundY(level, x, z, junction.pos.y, styles)
            if (y <= level.minBuildHeight) continue
            val ground = BlockPos(x, y, z)
            val state = level.getBlockState(ground)
            if (!state.fluidState.isEmpty || !state.isSolid) continue
            if (!styles.isClearable(level.getBlockState(ground.above())) || !styles.isClearable(level.getBlockState(ground.above(2)))) continue
            val signPos = ground.above(2)
            level.setBlock(ground.above(), style.post, 3)
            val waySign = BuiltInRegistries.BLOCK.getOptional(WAY_SIGN).orElse(null)
            val signState: BlockState = waySign?.defaultBlockState() ?: Blocks.OAK_SIGN.defaultBlockState()
            level.setBlock(signPos, signState, 3)
            if (waySign != null) mimic(level, signPos, style.post)
            val network = Network.get(level.server)
            val node = SignNodes.linkGenerated(level, signPos, road.id, index) ?: return 2
            level.getBlockEntity(signPos)?.let { entity ->
                if (entity !is net.minecraft.world.level.block.entity.SignBlockEntity) {
                    val targets = listOf(townTarget(network, storage, road.from, road, 0), townTarget(network, storage, road.to, road, road.points.size - 1))
                    SignWriter.pointWaySign(level, entity, network, node, viewer = null, explicit = targets)
                } else {
                    SignWriter.labelSign(level, entity, node.name, "")
                }
            }
            signsPlaced++
            return 3
        }
        return 0
    }

    /** A stand-in node for a road's town end: named after its place if the depot has registered, else "a village". */
    private fun townTarget(network: Network, storage: RoadPlanStorage, townId: String, road: PlannedRoad, index: Int): RoadNode {
        val name = network.places[townId]?.name ?: net.minecraft.network.chat.Component.translatable("sign.postroad.village").string
        return RoadNode("virtual/$townId", RoadNode.KIND_TOWN, road.dimension, road.points[index], road.id, index, name, townId)
    }

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
