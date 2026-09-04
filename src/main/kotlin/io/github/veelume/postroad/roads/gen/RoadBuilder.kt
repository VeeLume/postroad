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
        /** Resume point: which road of the chunk's list and which point of it comes next. */
        var roadIndex = 0
        var pointIndex = 0
        var startedAt = 0L
        var blocks = 0
        var setBlockNanos = 0L
        var run: Run? = null
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
            if (generatedWithRoad(level, job.chunk)) {
                // Worldgen laid this chunk's roads as structure pieces; only the junction signs are left.
                for (road in storage.roadsInChunk(job.dimension, job.chunk)) if (road.builtChunks.add(job.chunk)) storage.setDirty()
            }
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

    /** True when the chunk has a road structure start of its own: generated with its road. */
    fun generatedWithRoad(level: ServerLevel, chunk: Long): Boolean =
        roadStart(level.getChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) != null

    /** The road structure start that began in [chunk], if any (starts referenced from neighbours do not count). */
    fun roadStart(chunk: net.minecraft.world.level.chunk.ChunkAccess): net.minecraft.world.level.levelgen.structure.StructureStart? =
        chunk.allStarts.entries.firstOrNull { it.key is RoadStructure }?.value

    private fun offer(job: Job) {
        if (queued.add(key(job))) queue.addLast(job)
    }

    private fun needsWork(storage: RoadPlanStorage, job: Job): Boolean =
        storage.roadsInChunk(job.dimension, job.chunk).any { !it.builtChunks.contains(job.chunk) } ||
            storage.junctions.any { !it.signPlaced && it.dimension == job.dimension && ChunkPos.asLong(it.pos.x shr 4, it.pos.z shr 4) == job.chunk }

    /** Queues every already-loaded chunk of [road]; called when a plan lands after the chunks did. */
    fun enqueueLoaded(level: ServerLevel, road: PlannedRoad) {
        val dim = level.dimension().location()
        for (c in road.chunks()) {
            if (road.builtChunks.contains(c)) continue
            if (level.hasChunk(ChunkPos.getX(c), ChunkPos.getZ(c))) offer(Job(dim, c))
        }
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
            if (road.builtChunks.contains(chunk)) { job.roadIndex++; job.pointIndex = 0; job.run = null; continue }
            val next = buildRoadInChunk(level, road, chunk, styles, boxes, job, deadline)
            if (next >= 0) { job.pointIndex = next; return false }
            road.builtChunks.add(chunk)
            storage.setDirty()
            job.roadIndex++; job.pointIndex = 0; job.run = null
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
     * Builds [road]'s share of [chunk]: the run from its first point in the chunk to the first point
     * after it (the exit, shared with the next chunk), refined block by block on the real terrain,
     * from [job]'s column index. Returns the next column to resume at, or -1 when the run is done.
     */
    private fun buildRoadInChunk(level: ServerLevel, road: PlannedRoad, chunk: Long, styles: RoadStyleSet, boxes: List<BoundingBox>, job: Job, deadline: Long): Int {
        val width = PostroadConfig.buildWidth
        val half = width / 2
        val lampInterval = PostroadConfig.buildLampInterval
        val points = road.points
        val first = points.indexOfFirst { chunkOf(it) == chunk }
        if (first < 0) return -1
        var last = first
        while (last + 1 < points.size && chunkOf(points[last + 1]) == chunk) last++
        val exit = minOf(last + 1, points.size - 1)
        if (job.run == null) {
            // The block path for this run, once per job: refined around obstacles, else the straight line.
            val waypoints = points.subList(first, exit + 1)
            val path = RoadRefiner.refine(level, waypoints, styles, boxes) ?: straight(waypoints)
            val hint = waypoints.first().y
            fun ground(c: IntArray): Int = if (level.hasChunk(c[0] shr 4, c[1] shr 4)) groundY(level, c[0], c[1], hint, styles) else Int.MIN_VALUE
            val terrain = path.map(::ground)
            // Context beyond the run for the flattening: the planned line a few points before and after,
            // where loaded. A plain longer than the window is then never a "bump", whatever the run's edge
            // happens to be, and neighbouring chunks decide alike.
            val before = if (first > 0) straight(points.subList(maxOf(0, first - CONTEXT_POINTS), first + 1)).dropLast(1).map(::ground) else emptyList()
            val after = if (exit + 1 < points.size) straight(points.subList(exit, minOf(points.size, exit + 1 + CONTEXT_POINTS))).drop(1).map(::ground) else emptyList()
            val flat = flatten(before + terrain + after).toList().subList(before.size, before.size + terrain.size)
            val run = Run(path, smooth(flat, null), first)
            // Every block of the strip belongs to one column: a column's own centre always, the rest to
            // the first column whose round stamp reaches it. Then each column places only its own blocks.
            for ((i, c) in path.withIndex()) for (dz in -half..half) for (dx in -half..half) {
                if (dx * dx + dz * dz > (half + 0.5) * (half + 0.5)) continue
                val k = ((c[0] + dx).toLong() shl 32) or ((c[1] + dz).toLong() and 0xffffffffL)
                if (dx == 0 && dz == 0) run.owner[k] = i else run.owner.putIfAbsent(k, i)
            }
            job.run = run
        }
        val run = job.run!!
        val path = run.path
        val target = run.target
        val style = styles.style(road.families.getOrElse(first) { 0 }.toInt())
        var placed = 0
        var i = job.pointIndex
        while (i < path.size) {
            if (i > job.pointIndex && System.nanoTime() > deadline) { job.blocks += placed; return i }
            val c = path[i]
            if (run.terrainKnown(i)) {
                // The rise block goes on the lower of two neighbouring columns, facing the higher one.
                val shape = RoadShapes.at(path, target, i)
                // The column's own blocks (round stamp, no checkerboard on diagonals), the whole cross-section
                // at its height and, on a rise, all of it carrying the slab or stairs.
                for (dz in -half..half) for (dx in -half..half) {
                    if (dx * dx + dz * dz > (half + 0.5) * (half + 0.5)) continue
                    val x = c[0] + dx; val z = c[1] + dz
                    val k = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
                    if (run.owner[k] != i) continue
                    val centre = dx == 0 && dz == 0
                    val palette = if (centre) style.surface else style.edge
                    val t0 = System.nanoTime()
                    placed += placeColumn(level, x, z, target[i], palette, style, styles, boxes, shape.kind, c, shape.higher ?: c)
                    job.setBlockNanos += System.nanoTime() - t0
                }
                // A lamppost every lampInterval columns, sides alternating, two blocks off the centre.
                if (lampInterval > 0 && i > 0 && i % lampInterval == 0) {
                    val prev = path[i - 1]
                    val dxp = (c[0] - prev[0]).toDouble(); val dzp = (c[1] - prev[1]).toDouble()
                    val len = sqrt(dxp * dxp + dzp * dzp).takeIf { it > 0 } ?: 1.0
                    val side = if ((i / lampInterval) % 2 == 0) 1 else -1
                    val lx = (c[0] - dzp / len * (half + 1) * side).roundToInt()
                    val lz = (c[1] + dxp / len * (half + 1) * side).roundToInt()
                    placed += placeLamppost(level, lx, lz, style, styles, boxes, target[i])
                }
            }
            i++
        }
        job.blocks += placed
        return -1
    }

    /** The straight block line through [waypoints], one block per step (the fallback when refining fails). */
    internal fun straight(waypoints: List<BlockPos>): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (w in 1 until waypoints.size) {
            val a = waypoints[w - 1]; val b = waypoints[w]
            val dx = (b.x - a.x).toDouble(); val dz = (b.z - a.z).toDouble()
            val steps = ceil(maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz))).toInt().coerceAtLeast(1)
            for (st in 0 until steps) {
                val t = st.toDouble() / steps
                out.add(intArrayOf((a.x + dx * t).roundToInt(), (a.z + dz * t).roundToInt()))
            }
        }
        out.add(intArrayOf(waypoints.last().x, waypoints.last().z))
        return out
    }

    /** One chunk's block path for a road, its smoothed heights, and which blocks the strip has covered. */
    class Run(val path: List<IntArray>, val target: IntArray, val firstPoint: Int) {
        /** Strip block → the column that places it. */
        val owner = it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
        fun terrainKnown(i: Int): Boolean = target[i] != Int.MIN_VALUE
    }

    /**
     * The road's height per column: the terrain, then limited so consecutive columns differ by at most
     * one block. Peaks are cut and dips filled toward the neighbours; the first column follows the
     * previous segment's last height when known so segments meet.
     */
    fun smooth(terrain: List<Int>, previousTop: Int?): IntArray {
        val n = terrain.size
        val t = IntArray(n) { terrain[it] }
        if (n == 0) return t
        if (previousTop != null && previousTop != Int.MIN_VALUE && t[0] != Int.MIN_VALUE) t[0] = previousTop
        // Forward and backward passes bound each column by its neighbour ± 1; repeat until stable.
        repeat(n + 2) {
            var changed = false
            for (i in 1 until n) {
                if (t[i] == Int.MIN_VALUE || t[i - 1] == Int.MIN_VALUE) continue
                val lo = t[i - 1] - 1; val hi = t[i - 1] + 1
                val v = t[i].coerceIn(lo, hi)
                if (v != t[i]) { t[i] = v; changed = true }
            }
            for (i in n - 2 downTo 0) {
                if (t[i] == Int.MIN_VALUE || t[i + 1] == Int.MIN_VALUE) continue
                // Do not move the anchored first column.
                if (i == 0 && previousTop != null) continue
                val lo = t[i + 1] - 1; val hi = t[i + 1] + 1
                val v = t[i].coerceIn(lo, hi)
                if (v != t[i]) { t[i] = v; changed = true }
            }
            if (!changed) return t
        }
        return t
    }

    /**
     * Removes terrain features shorter than [FLAT_WINDOW] blocks from a height profile: bumps are cut
     * (up to [MAX_CUT] deep), dips are filled (up to [MAX_FILL]) — a morphological opening of the
     * closing, clamped to what the builder may move. Unknown columns (unloaded) are left alone.
     */
    fun flatten(terrain: List<Int>, window: Int = FLAT_WINDOW): IntArray {
        val n = terrain.size
        val src = IntArray(n) { terrain[it] }
        if (n < 3) return src
        val r = window / 2
        // Pad with the edge values so the ends count as plateaus, not as dips or bumps.
        fun padded(a: IntArray): IntArray = IntArray(n + 2 * r) { i -> a[(i - r).coerceIn(0, n - 1)] }
        fun dilate(a: IntArray): IntArray = IntArray(a.size) { i -> var m = Int.MIN_VALUE; for (j in maxOf(0, i - r)..minOf(a.size - 1, i + r)) if (a[j] != Int.MIN_VALUE && a[j] > m) m = a[j]; if (m == Int.MIN_VALUE) a[i] else m }
        fun erode(a: IntArray): IntArray = IntArray(a.size) { i -> var m = Int.MAX_VALUE; for (j in maxOf(0, i - r)..minOf(a.size - 1, i + r)) if (a[j] != Int.MIN_VALUE && a[j] < m) m = a[j]; if (m == Int.MAX_VALUE) a[i] else m }
        val p = padded(src)
        val closed = erode(dilate(p))            // dips filled
        val opened = dilate(erode(closed))       // bumps cut
        return IntArray(n) { i ->
            val t = src[i]
            if (t == Int.MIN_VALUE) t
            else {
                val v = opened[i + r]
                when {
                    v > t + MAX_FILL -> t + MAX_FILL
                    v < t - MAX_CUT -> t - MAX_CUT
                    else -> v
                }
            }
        }
    }

    private const val FLAT_WINDOW = 12
    /** Planned points sampled either side of a run as context for the flattening. */
    internal const val CONTEXT_POINTS = 4
    private const val MAX_CUT = 4

    private const val MAX_FILL = 6
    private const val HEADROOM = 3

    /**
     * One column of road at height [top] (the walking surface's supporting block): fill up to it or cut
     * down to it, lay the surface, and shape a rise with a slab or stairs on the block above the lower
     * neighbour. Returns blocks changed.
     */
    private fun placeColumn(level: ServerLevel, x: Int, z: Int, top: Int, palette: Palette, style: RoadStyle, styles: RoadStyleSet, boxes: List<BoundingBox>,
                            shape: Int, from: IntArray, to: IntArray): Int {
        if (!level.hasChunk(x shr 4, z shr 4) || inBox(x, z, boxes)) return 0
        val ground = groundY(level, x, z, top, styles)
        return placeColumnAt(level, x, z, top, ground, palette, style, styles, shape, from, to)
    }

    /** [placeColumn] on any level, [ground] already known — the structure piece's way in. */
    internal fun placeColumnAt(level: LevelAccessor, x: Int, z: Int, top: Int, ground: Int, palette: Palette, style: RoadStyle, styles: RoadStyleSet,
                               shape: Int, from: IntArray, to: IntArray): Int {
        if (ground <= level.minBuildHeight || top <= level.minBuildHeight) return 0
        val groundState = level.getBlockState(BlockPos(x, ground, z))
        if (!groundState.fluidState.isEmpty || !level.getFluidState(BlockPos(x, ground + 1, z)).isEmpty) return 0
        if (!styles.isReplaceable(groundState)) return 0
        var changed = 0
        val air = Blocks.AIR.defaultBlockState()
        if (top < ground) {
            // Cut: everything from the new top up to the old ground plus headroom, if it may be removed.
            for (y in top + 1..ground + HEADROOM) {
                val p = BlockPos(x, y, z)
                val s = level.getBlockState(p)
                if (s.isAir) continue
                if (y <= ground && !styles.isReplaceable(s)) return changed
                if (y > ground && !styles.isClearable(s)) break
                level.setBlock(p, air, 2 or 16); changed++
            }
        } else if (top > ground) {
            if (top - ground > MAX_FILL) return 0
            for (y in ground + 1 until top) {
                val p = BlockPos(x, y, z)
                if (!styles.isClearable(level.getBlockState(p))) return changed
                level.setBlock(p, style.fill, 2 or 16); changed++
            }
            // The surface block itself sits at top; clear above it.
            for (y in top + 1..top + HEADROOM) {
                val p = BlockPos(x, y, z)
                val s = level.getBlockState(p)
                if (s.isAir) continue
                if (!styles.isClearable(s)) break
                level.setBlock(p, air, 2 or 16); changed++
            }
        } else {
            for (y in top + 1..top + 2) {
                val p = BlockPos(x, y, z)
                val s = level.getBlockState(p)
                if (s.isAir) continue
                if (!styles.isClearable(s)) break
                level.setBlock(p, air, 2 or 16); changed++
            }
        }
        val topPos = BlockPos(x, top, z)
        if (top > ground && !styles.isClearable(level.getBlockState(topPos))) return changed
        val chosen = palette.pick(randomAt(level, x, z))
        if (level.getBlockState(topPos) != chosen) { level.setBlock(topPos, chosen, 2 or 16); changed++ }
        // The rise: a slab or stairs on top of this column when the road climbs out of it.
        if (shape != RoadShapes.FLAT) {
            val above = BlockPos(x, top + 1, z)
            if (styles.isClearable(level.getBlockState(above))) {
                val state = if (shape == RoadShapes.SLAB) style.slab else {
                    val dir = net.minecraft.core.Direction.getNearest((to[0] - from[0]).toDouble(), 0.0, (to[1] - from[1]).toDouble())
                    val horizontal = if (dir.axis.isHorizontal) dir else net.minecraft.core.Direction.NORTH
                    style.stairs.trySetValue(net.minecraft.world.level.block.StairBlock.FACING, horizontal)
                }
                level.setBlock(above, state, 2 or 16); changed++
            }
        }
        return changed
    }

    private fun horizontal(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dz * dz)
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

    private fun randomAt(level: LevelAccessor, x: Int, z: Int): Random = Random(Mth.getSeed(x, 0, z) xor ((level as? WorldGenLevel)?.seed ?: 0L))

    private fun placeLamppost(level: ServerLevel, x: Int, z: Int, style: RoadStyle, styles: RoadStyleSet, boxes: List<BoundingBox>, hint: Int): Int {
        if (!level.hasChunk(x shr 4, z shr 4) || inBox(x, z, boxes)) return 0
        return placeLamppostAt(level, x, z, groundY(level, x, z, hint, styles), style, styles)
    }

    /** A lamppost on the ground block at [y]: the family's post with its lamp on top. */
    internal fun placeLamppostAt(level: LevelAccessor, x: Int, z: Int, y: Int, style: RoadStyle, styles: RoadStyleSet): Int {
        if (y <= level.minBuildHeight) return 0
        val ground = BlockPos(x, y, z)
        val state = level.getBlockState(ground)
        if (!state.fluidState.isEmpty || !state.isSolid) return 0
        if (!styles.isClearable(level.getBlockState(ground.above())) || !styles.isClearable(level.getBlockState(ground.above(2)))) return 0
        level.setBlock(ground.above(), style.post, 3)
        level.setBlock(ground.above(2), style.lamp, 3)
        return 2
    }

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
