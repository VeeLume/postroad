package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.roads.PathLink
import io.github.veelume.postroad.roads.RoadPath
import io.github.veelume.postroad.roads.Tier
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generated roads, the scheduling half: one daemon thread (`postroad-planner`) discovers towns,
 * samples terrain and plans roads for a square around spawn and around players who have moved;
 * the server thread hands it snapshots and applies its results. Nothing here touches chunks.
 *
 * A pass is a pure function of its [PassRequest] plus the worker's terrain; results come back
 * as a [PassResult] and are applied under [apply], which is also how the tests drive it.
 */
object RoadGen {
    const val GENERATED_BY = "postroad"
    const val CELL_SIZE = 4
    /** The corridor map's cell; must be a multiple of [CELL_SIZE]. */
    const val COARSE_CELL = 16
    /** Chunks either side of a provisional road that are generated before it is planned again. */
    const val CORRIDOR_CHUNKS = 1
    const val MAX_REPLANS = 2
    /** Clearance around a village piece (a house) when pieces are known. */
    const val PIECE_MARGIN = 2

    /** Everything a pass needs, copied on the server thread. */
    class PassRequest(
        val dimension: ResourceLocation,
        val center: BlockPos,
        val radius: Int,
        val maxLink: Int,
        val neighbours: Int,
        val margin: Int,
        val costs: PlannerCosts,
        val knownTowns: List<PlannedTown>,
        val knownRoads: List<PlannedRoad>,
        val knownObstacles: List<PlannedObstacle> = emptyList(),
        val discovered: LongOpenHashSet,
        val discoverTowns: Boolean = true,
        val dropped: Set<String> = emptySet(),
        /** Chunks generated since the last pass: provisional tiles over them are sampled again first. */
        val refresh: LongOpenHashSet = LongOpenHashSet(),
        /** Provisional roads this pass plans again; they are left out of the known roads and swapped on apply. */
        val replace: Set<String> = emptySet(),
    )

    class JunctionResult(val pos: BlockPos, val joinedRoad: String, val joinedIndex: Int, val joiningRoad: String, val joiningIndex: Int)

    class PassResult(
        val dimension: ResourceLocation,
        val center: BlockPos,
        val newTowns: List<PlannedTown>,
        val newRoads: List<PlannedRoad>,
        val newObstacles: List<PlannedObstacle> = emptyList(),
        /** Chunks along the provisional roads' corridors, to generate so they can be replanned on real terrain. */
        val corridors: Map<String, LongOpenHashSet> = emptyMap(),
        val replaced: Set<String> = emptySet(),
        val newJunctions: List<JunctionResult>,
        val discovered: List<Long>,
        val millis: Long,
        val tilesSampled: Int,
        val discoverMillis: Long = 0,
        val chunksChecked: Int = 0,
        val coarseTilesSampled: Int = 0,
        val dropped: List<DroppedPair> = emptyList(),
    )

    /** The worker's view of one dimension; only the planner thread touches it after creation. */
    class Worker(val terrain: TiledTerrain, val sampler: WorldTerrainSampler, val coarse: TiledTerrain, val coarseSampler: WorldTerrainSampler, val finder: TownFinder)

    private var executor: ExecutorService? = null
    private val workers = ConcurrentHashMap<ResourceLocation, Worker>()
    private val results = ConcurrentLinkedQueue<PassResult>()
    private val pending = AtomicInteger()
    private val lastPass = HashMap<UUID, BlockPos>()
    private var spawnPlanned = false
    var passesRun: Int = 0
        private set

    val pendingPasses: Int get() = pending.get()

    // ---- lifecycle ------------------------------------------------------------------------------

    fun onServerStarted(event: ServerStartedEvent) {
        if (!PostroadConfig.planEnabled) return
        executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "postroad-planner").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
        val overworld = event.server.overworld()
        overworld.chunkSource.getGeneratorState().ensureStructuresGenerated()
        RoadPlanSnapshot.publish(RoadPlanStorage.get(event.server), overworld.dimension().location())
        resumeProvisional(event.server, overworld)
        spawnPlanned = schedule(overworld, overworld.sharedSpawnPos)
    }

    /** Provisional roads left over from an earlier session: generate their corridors again and replan them. */
    private fun resumeProvisional(server: MinecraftServer, level: ServerLevel) {
        if (PostroadConfig.planPregenInFlight <= 0) return
        val storage = RoadPlanStorage.get(server)
        val dim = level.dimension().location()
        var n = 0
        for (road in storage.roadsIn(dim)) {
            if (!road.provisional || road.replans >= MAX_REPLANS) continue
            val chunks = LongOpenHashSet()
            for (p in road.points) for (dz in -CORRIDOR_CHUNKS..CORRIDOR_CHUNKS) for (dx in -CORRIDOR_CHUNKS..CORRIDOR_CHUNKS) chunks.add(ChunkPos.asLong((p.x shr 4) + dx, (p.z shr 4) + dz))
            pendingCorridors[road.id] = chunks
            ChunkPregen.request(dim, chunks) { ok, failed -> corridorDone(server, dim, road.id, ok, failed) }
            n++
        }
        if (n > 0) Postroad.LOGGER.info("Resuming {} provisional road(s): corridors queued for generation", n)
    }

    fun onServerStopping(event: ServerStoppingEvent) {
        executor?.shutdownNow()
        executor = null
        RoadPlanSnapshot.clear()
        KnownTerrain.clear()
        ChunkPregen.reset()
        workers.clear()
        results.clear()
        lastPass.clear()
        pending.set(0)
        spawnPlanned = false
        RoadBuilder.reset()
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        while (true) {
            val result = results.poll() ?: break
            apply(server, result)
        }
        if (executor == null || server.tickCount % 100 != 0 || pending.get() > 0) return
        val overworld = server.overworld()
        if (!spawnPlanned) spawnPlanned = schedule(overworld, overworld.sharedSpawnPos)
        val repass = PostroadConfig.planRepassDistance.toDouble()
        for (player in overworld.players()) {
            val pos = player.blockPosition()
            val last = lastPass[player.uuid]
            if (last != null && last.distSqr(pos) < repass * repass) continue
            lastPass[player.uuid] = pos
            schedule(overworld, pos)
            break // one pass at a time; the next player is picked up on the next check
        }
    }

    /**
     * Samples the tiles holding [cells] on the planner thread if they are not in memory yet (the
     * debug view's way to see terrain the passes never touched). Cheap when the disk cache has them.
     */
    fun prefetch(level: ServerLevel, terrain: TiledTerrain, cells: Collection<Cell>) {
        val exec = executor ?: return
        if (cells.isEmpty()) return
        exec.execute {
            try { for (c in cells) terrain.heightAt(c.x, c.z) } catch (e: Throwable) { Postroad.LOGGER.warn("Terrain prefetch failed: {}", e.toString()) }
        }
    }

    // ---- scheduling -----------------------------------------------------------------------------

    /** Queues a pass around [center]; false if the planner is off. */
    fun schedule(level: ServerLevel, center: BlockPos, discoverTowns: Boolean = true, refresh: LongOpenHashSet = LongOpenHashSet(), replace: Set<String> = emptySet()): Boolean {
        val exec = executor ?: return false
        val request = request(level, center, discoverTowns, refresh, replace)
        val worker = workerFor(level)
        pending.incrementAndGet()
        exec.execute {
            try {
                results.add(runPass(worker, request))
            } catch (e: Throwable) {
                Postroad.LOGGER.error("Road plan pass at {} failed", center.toShortString(), e)
            } finally {
                pending.decrementAndGet()
            }
        }
        return true
    }

    /** Snapshot for a pass, from config and storage, on the server thread. */
    fun request(level: ServerLevel, center: BlockPos, discoverTowns: Boolean = true, refresh: LongOpenHashSet = LongOpenHashSet(), replace: Set<String> = emptySet()): PassRequest {
        val storage = RoadPlanStorage.get(level.server)
        val dimension = level.dimension().location()
        return PassRequest(
            dimension, center,
            radius = PostroadConfig.planRadius,
            maxLink = PostroadConfig.planMaxLink,
            neighbours = PostroadConfig.planNeighbours,
            margin = PostroadConfig.planStructureMargin,
            costs = PlannerRules.current,
            knownTowns = storage.townsIn(dimension),
            knownRoads = storage.roadsIn(dimension).filter { it.id !in replace },
            knownObstacles = storage.obstaclesIn(dimension),
            discovered = LongOpenHashSet(storage.discovered[dimension] ?: LongOpenHashSet()),
            discoverTowns = discoverTowns,
            dropped = HashSet(storage.droppedRoutes),
            refresh = refresh,
            replace = replace,
        )
    }

    fun workerFor(level: ServerLevel): Worker = workers.getOrPut(level.dimension().location()) {
        // "known" caches hold only tiles of generated terrain (Distant Horizons); estimates are never persisted.
        val cache = level.server.getWorldPath(LevelResource("postroad")).resolve("known")
        val sampler = WorldTerrainSampler(level, cache, CELL_SIZE)
        val coarseSampler = WorldTerrainSampler(level, level.server.getWorldPath(LevelResource("postroad")).resolve("known$COARSE_CELL"), COARSE_CELL)
        Worker(TiledTerrain(CELL_SIZE, sampler), sampler, TiledTerrain(COARSE_CELL, coarseSampler), coarseSampler, TownFinder(level, sampler::surface))
    }

    // ---- the pass (planner thread, or the test thread) -----------------------------------------

    fun runPass(worker: Worker, req: PassRequest): PassResult {
        val t0 = System.nanoTime()
        val terrain = worker.terrain
        val coarse = worker.coarse
        // Terrain is frozen for the pass: stale estimates are dropped now, never while the search runs.
        terrain.dropStaleProvisional(); coarse.dropStaleProvisional()
        if (!req.refresh.isEmpty()) { terrain.dropProvisionalTouching(req.refresh); coarse.dropProvisionalTouching(req.refresh) }
        val sampledBefore = worker.sampler.sampled
        val coarseBefore = worker.coarseSampler.sampled

        // 1. Towns: search every discovery square in the radius that has not been searched.
        val newTowns = ArrayList<PlannedTown>()
        val newObstacles = ArrayList<PlannedObstacle>()
        val discoveredNow = ArrayList<Long>()
        var chunksChecked = 0
        if (req.discoverTowns) {
            val known = req.knownTowns.mapTo(HashSet()) { it.id }
            val knownObstacles = req.knownObstacles.mapTo(HashSet()) { it.key }
            val sq = RoadPlanStorage.DISCOVERY_SQUARE
            for (sz in Math.floorDiv(req.center.z - req.radius, sq)..Math.floorDiv(req.center.z + req.radius, sq)) {
                for (sx in Math.floorDiv(req.center.x - req.radius, sq)..Math.floorDiv(req.center.x + req.radius, sq)) {
                    val key = ChunkPos.asLong(sx, sz)
                    if (req.discovered.contains(key)) continue
                    val chunkFrom = ChunkPos(sx * sq shr 4, sz * sq shr 4)
                    val perSide = sq / 16
                    for (cz in chunkFrom.z until chunkFrom.z + perSide) for (cx in chunkFrom.x until chunkFrom.x + perSide) {
                        chunksChecked++
                        val found = worker.finder.find(cx, cz)
                        for (o in found.obstacles) {
                            val obstacle = PlannedObstacle(req.dimension, o.structure, o.chunk.toLong(), o.box)
                            if (knownObstacles.add(obstacle.key)) newObstacles.add(obstacle)
                        }
                        val c = found.town ?: continue
                        if (c.id in known) continue
                        known.add(c.id)
                        val centre = c.box.center
                        val cell = terrain.blockToCell(centre.x, centre.z)
                        newTowns.add(PlannedTown(c.id, req.dimension, c.structure, BlockPos(centre.x, terrain.heightAt(cell.x, cell.z), centre.z), c.box, c.pieces, c.streets))
                    }
                    discoveredNow.add(key)
                    pace(PACE_SQUARE_MS)
                }
            }
        }

        val discoverMillis = (System.nanoTime() - t0) / 1_000_000

        // 2. Terrain: bounds for this pass, structure footprints impassable.
        val reach = req.radius + req.maxLink
        terrain.bounds = CellBox(
            Math.floorDiv(req.center.x - reach, CELL_SIZE), Math.floorDiv(req.center.z - reach, CELL_SIZE),
            Math.floorDiv(req.center.x + reach, CELL_SIZE), Math.floorDiv(req.center.z + reach, CELL_SIZE),
        )
        coarse.bounds = CellBox(
            Math.floorDiv(req.center.x - reach, COARSE_CELL), Math.floorDiv(req.center.z - reach, COARSE_CELL),
            Math.floorDiv(req.center.x + reach, COARSE_CELL), Math.floorDiv(req.center.z + reach, COARSE_CELL),
        )
        // Obstacles: every predicted surface structure that is not a town, with the configured margin.
        for (o in req.knownObstacles + newObstacles) {
            val b = o.box
            val min = terrain.blockToCell(b.minX() - req.margin, b.minZ() - req.margin)
            val max = terrain.blockToCell(b.maxX() + req.margin, b.maxZ() + req.margin)
            terrain.block(CellBox(min.x, min.z, max.x, max.z))
            val cmin = coarse.blockToCell(b.minX() - req.margin, b.minZ() - req.margin)
            val cmax = coarse.blockToCell(b.maxX() + req.margin, b.maxZ() + req.margin)
            coarse.block(CellBox(cmin.x, cmin.z, cmax.x, cmax.z))
        }
        val allTowns = req.knownTowns + newTowns
        for (town in allTowns) for (b in town.footprint) {
            // Pieces when known (streets stay open), the whole box otherwise; the margin shrinks with pieces.
            val margin = if (town.pieces.isEmpty()) req.margin else PIECE_MARGIN
            val min = terrain.blockToCell(b.minX() - margin, b.minZ() - margin)
            val max = terrain.blockToCell(b.maxX() + margin, b.maxZ() + margin)
            terrain.block(CellBox(min.x, min.z, max.x, max.z))
            val cmin = coarse.blockToCell(b.minX() - margin, b.minZ() - margin)
            val cmax = coarse.blockToCell(b.maxX() + margin, b.maxZ() + margin)
            coarse.block(CellBox(cmin.x, cmin.z, cmax.x, cmax.z))
        }

        // 3. Plan: towns in reach, existing roads as reusable cells.
        val townById = HashMap<String, Town>()
        val towns = allTowns
            .filter { it.pos.distSqr(req.center) <= reach.toDouble() * reach }
            .map { Town(it.id, terrain.blockToCell(it.pos.x, it.pos.z), it.streets.map { s -> terrain.blockToCell(s.x, s.z) }).also { t -> townById[t.id] = t } }
        val existing = req.knownRoads.map { road ->
            val cells = road.points.map { terrain.blockToCell(it.x, it.z) }
            PlannedRoute(road.id, townById[road.from] ?: Town(road.from, cells.first()), townById[road.to] ?: Town(road.to, cells.last()), cells)
        }
        val plan = RoadPlanner.planNetwork(terrain, towns, req.costs, req.neighbours, req.maxLink.toDouble() / CELL_SIZE, existing, coarse, COARSE_CELL / CELL_SIZE, req.dropped)

        // 4. Back to blocks. A route over any estimated cell is provisional: its corridor is generated
        //    and the route planned again on the real terrain.
        val corridors = HashMap<String, LongOpenHashSet>()
        val newRoads = plan.routes.map { route ->
            val road = PlannedRoad(
                route.id, req.dimension, route.from.id, route.to.id,
                route.cells.map { terrain.cellToBlock(it.x, it.z) },
                ByteArray(route.cells.size) { terrain.family(route.cells[it].x, route.cells[it].z).toByte() },
            )
            if (route.cells.any { terrain.has(it.x, it.z, Terrain.ESTIMATED) }) {
                road.provisional = true
                val chunks = LongOpenHashSet()
                for (c in route.cells) {
                    val b = terrain.cellToBlock(c.x, c.z)
                    for (dz in -CORRIDOR_CHUNKS..CORRIDOR_CHUNKS) for (dx in -CORRIDOR_CHUNKS..CORRIDOR_CHUNKS) chunks.add(ChunkPos.asLong((b.x shr 4) + dx, (b.z shr 4) + dz))
                }
                corridors[road.id] = chunks
            }
            road
        }
        val cellsOf = HashMap<String, List<Cell>>()
        for (r in existing) cellsOf[r.id] = r.cells
        for (r in plan.routes) cellsOf[r.id] = r.cells
        val newJunctions = plan.junctions.mapNotNull { j ->
            val joined = cellsOf[j.joinedRoute]?.indexOf(j.cell) ?: -1
            val joining = cellsOf[j.joiningRoute]?.indexOf(j.cell) ?: -1
            if (joined < 0 || joining < 0) null
            else JunctionResult(terrain.cellToBlock(j.cell.x, j.cell.z), j.joinedRoute, joined, j.joiningRoute, joining)
        }
        return PassResult(req.dimension, req.center, newTowns, newRoads, newObstacles, corridors, req.replace, newJunctions, discoveredNow,
            (System.nanoTime() - t0) / 1_000_000, worker.sampler.sampled - sampledBefore, discoverMillis, chunksChecked, worker.coarseSampler.sampled - coarseBefore, plan.dropped)
    }

    // ---- applying results (server thread) ------------------------------------------------------

    fun apply(server: MinecraftServer, result: PassResult) {
        val storage = RoadPlanStorage.get(server)
        val network = Network.get(server)
        val level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, result.dimension)) ?: server.overworld()
        val day = FreshLoot.dayOf(level)
        var towns = 0
        var roads = 0
        var junctions = 0
        for (town in result.newTowns) {
            if (storage.towns.containsKey(town.id)) continue
            storage.addTown(town)
            towns++
        }
        var obstacles = 0
        for (o in result.newObstacles) {
            if (storage.obstacles.containsKey(o.key)) continue
            storage.addObstacle(o)
            obstacles++
        }
        // Replanned roads: the old provisional version goes only when the pass produced a new one for the same pair.
        for (id in result.replaced) {
            val old = storage.roads[id] ?: continue
            val fresh = result.newRoads.firstOrNull { it.id == id }
            if (fresh == null) { old.provisional = false; storage.setDirty(); continue }
            fresh.replans = old.replans + 1
            storage.removeRoad(id)
            network.removePath(id)
            replanned++
        }
        var provisional = 0
        for (road in result.newRoads) {
            if (storage.roads.containsKey(road.id)) continue
            storage.addRoad(road)
            network.addPath(RoadPath(road.id, road.dimension, road.points.toMutableList(), MutableList(road.points.size) { Tier.PAVED },
                GENERATED_BY, day, charted = false))
            RoadBuilder.enqueueLoaded(level, road)
            roads++
            if (road.provisional) provisional++
        }
        // Provisional roads: generate their corridors, then plan them again on what the world really is.
        if (PostroadConfig.planPregenInFlight > 0) {
            for ((id, chunks) in result.corridors) {
                if (!storage.roads.containsKey(id)) continue
                pendingCorridors[id] = chunks
                ChunkPregen.request(result.dimension, chunks) { ok, failed -> corridorDone(server, result.dimension, id, ok, failed) }
            }
        }
        for (j in result.newJunctions) {
            if (network.paths[j.joinedRoad] == null || network.paths[j.joiningRoad] == null) continue
            storage.addJunction(PlannedJunction(result.dimension, j.pos, j.joinedRoad, j.joiningRoad))
            network.addLink(PathLink(j.joiningRoad, j.joiningIndex, j.joinedRoad, j.joinedIndex))
            junctions++
        }
        if (roads > 0) RoadPlanSnapshot.publish(storage, result.dimension)
        storage.markDiscovered(result.dimension, result.discovered)
        storage.markDropped(result.dropped.map { Triple(it.id, it.from.id to it.to.id, it.reason) })
        passesRun++
        Postroad.LOGGER.info("Road plan pass at {}: {} new town(s), {} new obstacle(s), {} new road(s) ({} provisional, {} corridor chunk(s) to generate), {} junction(s), {} pair(s) dropped for water; {} chunk(s) checked in {} ms, {} coarse + {} fine tile(s) sampled, {} ms total",
            result.center.toShortString(), towns, obstacles, roads, provisional, result.corridors.values.sumOf { it.size }, junctions, result.dropped.size, result.chunksChecked, result.discoverMillis, result.coarseTilesSampled, result.tilesSampled, result.millis)
    }

    /**
     * A provisional road's corridor is generated: drop the road if nothing of it has been built or
     * laid yet, and plan its pair again on the real terrain. A road that comes back provisional
     * twice keeps its last version.
     */
    /** Corridor chunks requested per provisional road, handed to its replan pass as the tiles to sample again. */
    private val pendingCorridors = HashMap<String, LongOpenHashSet>()

    /** A corridor job finished: replan only when the world actually produced chunks (a failed job would just repeat itself). */
    private fun corridorDone(server: MinecraftServer, dimension: ResourceLocation, roadId: String, ok: Int, failed: Int) {
        if (ok == 0 && failed > 0) {
            Postroad.LOGGER.warn("Corridor of road {} could not be generated ({} chunk(s) failed); it stays as planned", roadId, failed)
            pendingCorridors.remove(roadId)
            return
        }
        replan(server, dimension, roadId)
    }

    private fun replan(server: MinecraftServer, dimension: ResourceLocation, roadId: String) {
        val storage = RoadPlanStorage.get(server)
        val corridor = pendingCorridors.remove(roadId) ?: LongOpenHashSet()
        val road = storage.roads[roadId] ?: return
        if (!road.provisional) return
        val level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension)) ?: return
        val touched = road.builtChunks.isNotEmpty() || road.chunks().any { RoadPlanSnapshot.laid.contains(it) }
        if (touched || road.replans >= MAX_REPLANS) {
            road.provisional = false
            storage.setDirty()
            return
        }
        // The pass plans this pair again without the old road in the way; apply swaps the two.
        val mid = road.points[road.points.size / 2]
        schedule(level, mid, discoverTowns = false, refresh = corridor, replace = setOf(roadId))
        Postroad.LOGGER.info("Road {} ({} -> {}): corridor generated, planning again (attempt {})", roadId, road.from, road.to, road.replans + 1)
    }

    var replanned: Int = 0
        private set

    /** Drops the plan and every generated path nobody has charted. Refused while a pass runs. */
    fun clear(server: MinecraftServer): Boolean {
        if (pending.get() > 0) return false
        RoadPlanStorage.get(server).clear()
        val network = Network.get(server)
        val generated = network.paths.values.filter { it.recordedBy == GENERATED_BY && !it.charted }.map { it.id }
        for (id in generated) network.removePath(id)
        workers.clear()
        lastPass.clear()
        spawnPlanned = false
        RoadBuilder.reset()
        RoadPlanSnapshot.clear()
        ChunkPregen.reset()
        pendingCorridors.clear()
        return true
    }

    fun status(server: MinecraftServer): List<String> {
        val storage = RoadPlanStorage.get(server)
        val lines = ArrayList<String>()
        lines.add("Planner ${if (executor != null) "on" else "off"}; ${passesRun} pass(es) applied, ${pending.get()} pending; ${DhTerrain.status(server.overworld())}")
        lines.add("${storage.towns.size} predicted town(s), ${storage.obstacles.size} obstacle(s), ${storage.roads.size} planned road(s), ${storage.junctions.size} junction(s), " +
            "${storage.discovered.values.sumOf { it.size }} square(s) searched, ${storage.droppedRoutes.size} pair(s) dropped for water")
        for ((dim, w) in workers) {
            lines.add("$dim: ${w.terrain.tileCount} fine + ${w.coarse.tileCount} coarse tile(s) in memory, ${w.sampler.sampled}/${w.coarseSampler.sampled} sampled, ${w.sampler.fromCache}/${w.coarseSampler.fromCache} from cache, " +
                "${w.sampler.chunkCells}/${w.coarseSampler.chunkCells} cell(s) from own chunks, ${w.sampler.knownCells - w.sampler.chunkCells}/${w.coarseSampler.knownCells - w.coarseSampler.chunkCells} from Distant Horizons, ${w.sampler.estimatedCells}/${w.coarseSampler.estimatedCells} estimated; " +
                "finder: ${w.finder.generated} layout(s) built, ${w.finder.prefiltered} skipped by biome, ${w.finder.obstaclesFound} obstacle(s)")
            val costly = w.finder.timing.entries.sortedByDescending { it.value[1] }.take(6)
            if (costly.isNotEmpty()) lines.add("  layouts by cost: " + costly.joinToString(", ") { "${it.key} ${it.value[0]}× ${it.value[1] / 1_000_000 / maxOf(1, it.value[0])} ms" } +
                (if (w.finder.skipped.isEmpty()) "" else "; always buried, skipped: ${w.finder.skipped.joinToString(", ")}"))
        }
        val built = storage.roads.values.sumOf { it.builtChunks.size }
        val total = storage.roads.values.sumOf { it.chunks().size }
        lines.add("worldgen: ${RoadPlanSnapshot.segments.size} chunk(s) in the snapshot, ${RoadPlanSnapshot.featureRuns.get()} chunk(s) laid by the road feature (${RoadPlanSnapshot.piecesPlaced.get()} run(s)); ${ChunkPregen.status()}; ${KnownTerrain.size(server.overworld().dimension().location())} chunk(s) of known terrain; ${storage.roads.values.count { it.provisional }} provisional road(s), $replanned replanned")
        lines.add("$built of $total road chunk(s) built; builder: ${RoadBuilder.chunksBuilt} chunk(s), ${RoadBuilder.blocksPlaced} block(s), ${RoadBuilder.signsPlaced} sign(s) this session, ${RoadBuilder.queueSize} queued")
        return lines
    }

    fun isOverworld(level: ServerLevel): Boolean = level.dimension() == Level.OVERWORLD

    /** The planner thread yields between work items so its allocation and disk reads do not pile onto the server's tick. */
    private fun pace(ms: Long) {
        if (ms <= 0 || Thread.currentThread().name != "postroad-planner") return
        try { Thread.sleep(ms) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private const val PACE_SQUARE_MS = 20L

    /**
     * Draws the plan around [center] as a PNG: coarse heights as the ground (fine where sampled), water
     * blue, town boxes red, roads white, junctions yellow, towns magenta. One pixel per fine cell.
     * Server thread, and only while no pass runs (it reads the worker's tiles). Returns the file, or null.
     */
    fun exportImage(level: ServerLevel, center: BlockPos, radius: Int): java.nio.file.Path? {
        if (pending.get() > 0) return null
        val worker = workers[level.dimension().location()] ?: return null
        val storage = RoadPlanStorage.get(level.server)
        val dim = level.dimension().location()
        val size = radius * 2 / CELL_SIZE
        val x0 = Math.floorDiv(center.x - radius, CELL_SIZE)
        val z0 = Math.floorDiv(center.z - radius, CELL_SIZE)
        val ratio = COARSE_CELL / CELL_SIZE
        val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
        // Height range for the shade, from whatever is loaded.
        var minH = Int.MAX_VALUE; var maxH = Int.MIN_VALUE
        for (pz in 0 until size) for (px in 0 until size) {
            val h = worker.terrain.loadedHeightAt(x0 + px, z0 + pz) ?: worker.coarse.loadedHeightAt(Math.floorDiv(x0 + px, ratio), Math.floorDiv(z0 + pz, ratio)) ?: continue
            if (h < minH) minH = h
            if (h > maxH) maxH = h
        }
        if (minH > maxH) { minH = 60; maxH = 120 }
        for (pz in 0 until size) for (px in 0 until size) {
            val cx = x0 + px; val cz = z0 + pz
            val fine = worker.terrain.loadedHeightAt(cx, cz)
            val h = fine ?: worker.coarse.loadedHeightAt(Math.floorDiv(cx, ratio), Math.floorDiv(cz, ratio))
            val flags = (if (fine != null) worker.terrain.loadedFlagsAt(cx, cz) else worker.coarse.loadedFlagsAt(Math.floorDiv(cx, ratio), Math.floorDiv(cz, ratio))) ?: 0
            val rgb = when {
                h == null -> 0x202020
                flags and Terrain.BLOCKED != 0 -> 0x803030
                flags and Terrain.WATER != 0 -> 0x2050a0
                else -> {
                    val t = ((h - minH).toDouble() / (maxH - minH).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                    val g = (90 + 130 * t).toInt(); val r = (50 + 150 * t).toInt(); val b = (40 + 60 * t).toInt()
                    (r shl 16) or (g shl 8) or b
                }
            }
            image.setRGB(px, pz, if (fine == null && h != null) (rgb shr 1) and 0x7f7f7f else rgb)
        }
        val g2 = image.createGraphics()
        g2.color = java.awt.Color.WHITE
        for (road in storage.roadsIn(dim)) {
            for (i in 1 until road.points.size) {
                val a = road.points[i - 1]; val b = road.points[i]
                g2.drawLine(Math.floorDiv(a.x, CELL_SIZE) - x0, Math.floorDiv(a.z, CELL_SIZE) - z0, Math.floorDiv(b.x, CELL_SIZE) - x0, Math.floorDiv(b.z, CELL_SIZE) - z0)
            }
        }
        g2.color = java.awt.Color.RED
        for ((_, d) in storage.droppedDetails) {
            val a = storage.towns[d.first]?.pos ?: continue
            val b = storage.towns[d.second]?.pos ?: continue
            g2.drawLine(Math.floorDiv(a.x, CELL_SIZE) - x0, Math.floorDiv(a.z, CELL_SIZE) - z0, Math.floorDiv(b.x, CELL_SIZE) - x0, Math.floorDiv(b.z, CELL_SIZE) - z0)
        }
        g2.color = java.awt.Color.YELLOW
        for (j in storage.junctions) if (j.dimension == dim) g2.fillRect(Math.floorDiv(j.pos.x, CELL_SIZE) - x0 - 1, Math.floorDiv(j.pos.z, CELL_SIZE) - z0 - 1, 3, 3)
        g2.color = java.awt.Color(160, 40, 40)
        for (o in storage.obstaclesIn(dim)) {
            val b = o.box
            g2.drawRect(Math.floorDiv(b.minX(), CELL_SIZE) - x0, Math.floorDiv(b.minZ(), CELL_SIZE) - z0, Math.floorDiv(b.xSpan, CELL_SIZE), Math.floorDiv(b.zSpan, CELL_SIZE))
        }
        g2.color = java.awt.Color.MAGENTA
        for (t in storage.townsIn(dim)) g2.fillRect(Math.floorDiv(t.pos.x, CELL_SIZE) - x0 - 2, Math.floorDiv(t.pos.z, CELL_SIZE) - z0 - 2, 5, 5)
        g2.dispose()
        val file = level.server.getWorldPath(LevelResource("postroad")).resolve("plan_${center.x}_${center.z}.png")
        java.nio.file.Files.createDirectories(file.parent)
        javax.imageio.ImageIO.write(image, "png", file.toFile())
        return file
    }
}
