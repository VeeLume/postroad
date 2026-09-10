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
    const val CELL_SIZE = 3
    /** The corridor map's cell; must be a multiple of [CELL_SIZE]. */
    const val COARSE_CELL = 12
    /**
     * Chunks either side of a provisional road that are generated before it is planned again.
     *
     * The shape pass plans over estimated heights, so the route it draws is roughly right but not
     * exactly where the real ground would put it. The corridor has to be wide enough to hold that
     * difference, or the replan finds no route on real terrain and the pair is dropped.
     */
    const val CORRIDOR_CHUNKS = 3
    const val MAX_REPLANS = 2
    /**
     * Half-width in blocks of the diamond opened for a dropped pair, multiplied by the try.
     *
     * A pair is dropped when no route exists on the ground that has been generated. Widening only
     * for that pair, and only after it has failed, spends chunks where they are demonstrably needed
     * instead of paying a wider corridor everywhere for the minority that need one.
     */
    const val DROP_RETRY_HALF = 64
    /** How many times a dropped pair is tried again, each with a wider diamond than the last. */
    const val MAX_DROP_TRIES = 3
    /** Clearance around a village piece (a house) when pieces are known. */
    const val PIECE_MARGIN = 2
    /** Widest a plan picture gets before a pixel starts standing for more than one cell. */
    const val MAX_PLAN_PIXELS = 900

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
        /** Structures tagged `#postroad:passable`: stored obstacles of these kinds (from before the tag) block nothing. */
        val passable: Set<ResourceLocation> = emptySet(),
        val discovered: LongOpenHashSet,
        val discoverTowns: Boolean = true,
        val dropped: Set<String> = emptySet(),
        /** Chunks generated since the last pass: provisional tiles over them are sampled again first. */
        val refresh: LongOpenHashSet = LongOpenHashSet(),
        /** Provisional roads this pass plans again; they are left out of the known roads and swapped on apply. */
        val replace: Set<String> = emptySet(),
        /**
         * Towns the pass must plan even if they lie outside its reach: the ends of the roads in
         * [replace]. A replan is centred on the road's midpoint, so a road longer than the reach
         * would otherwise leave its own endpoints out and the pair would go unplanned — which used
         * to keep the road at its estimated heights and lay it there.
         */
        val include: Set<String> = emptySet(),
        /**
         * Whether estimated ground is off limits. The first pass over a pair runs with this false:
         * it is only after a shape, so its heights may be guesses and its roads are provisional and
         * never laid. The replan, once the corridor along that shape is real, runs with it true and
         * so can only produce a road that stands on ground the world has.
         */
        val realTerrainOnly: Boolean = false,
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
        /** Chunks to generate per provisional dropped pair, to plan it again on real terrain. */
        val droppedCorridors: Map<String, LongOpenHashSet> = emptyMap(),
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

    /**
     * A pass asked for while another is pending. Its request is built only when it starts, so it sees the
     * roads and dropped pairs the earlier passes produced; a request built at scheduling time would plan
     * the same pairs again and, once applied, re-request their corridors — a loop (2026-09-09).
     */
    private class Deferred(val dimension: ResourceLocation, val center: BlockPos, val discoverTowns: Boolean, val refresh: LongOpenHashSet, val replace: Set<String>, val realTerrainOnly: Boolean)
    private val deferred = ArrayDeque<Deferred>()
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
        if (PostroadConfig.planAuto) spawnPlanned = schedule(overworld, overworld.sharedSpawnPos)
    }

    /**
     * Towns predicted before names were given to predicted towns, and any a pass stored while the
     * network was unavailable: name them now, so their signs stop reading "a village".
     */
    private fun nameKnownTowns(server: MinecraftServer, level: ServerLevel) {
        val storage = RoadPlanStorage.get(server)
        val network = Network.get(server)
        val dim = level.dimension().location()
        var n = 0
        for (town in storage.towns.values) {
            if (town.dimension != dim || network.places.containsKey(town.id)) continue
            io.github.veelume.postroad.network.PlaceResolver.predict(level, town.structure, town.id, town.pos)
            n++
        }
        if (n > 0) Postroad.LOGGER.info("Named {} predicted town(s) that had no place yet", n)
    }

    /**
     * Roads that went final on estimated heights in an earlier session, before [refit] existed: fit
     * them to the ground now, while none of them has been laid. A road that was planned on real
     * terrain moves no point, so this is a no-op for a healthy plan.
     */
    private fun refitFinalRoads(server: MinecraftServer, level: ServerLevel) {
        val storage = RoadPlanStorage.get(server)
        val dim = level.dimension().location()
        var n = 0
        for (road in storage.roadsIn(dim)) {
            if (road.provisional || road.builtChunks.isNotEmpty()) continue
            if (refit(storage, dim, road)) n++
        }
        if (n > 0) {
            storage.setDirty()
            RoadPlanSnapshot.publish(storage, dim)
            Postroad.LOGGER.info("Re-fitted {} road(s) to the real ground at start", n)
        }
    }

    /** Provisional roads left over from an earlier session: generate their corridors again and replan them. */
    private fun resumeProvisional(server: MinecraftServer, level: ServerLevel) {
        nameKnownTowns(server, level)
        refitFinalRoads(server, level)
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
        // Provisional dropped pairs: their corridors are not kept across sessions; the next pass simply plans
        // them again (and drops them again with a corridor if the terrain is still estimated).
        var m = 0
        for ((id, info) in storage.droppedDetails) if (info.provisional && info.tries < MAX_REPLANS && storage.retryDropped(id)) m++
        if (m > 0) Postroad.LOGGER.info("Resuming {} provisional dropped pair(s): planned again by the next pass", m)
    }

    fun onServerStopping(event: ServerStoppingEvent) {
        executor?.shutdownNow()
        executor = null
        RoadPlanSnapshot.clear()
        KnownTerrain.clear()
        ChunkPregen.reset()
        workers.clear()
        results.clear()
        deferred.clear()
        lastPass.clear()
        pending.set(0)
        spawnPlanned = false
        RoadBuilder.reset()
    }

    /**
     * Planning and building off, chunk generation still available. Measuring what the chunk system
     * can do is only honest with the planner quiet: its passes and its builder compete for exactly
     * the CPU and the worldgen workers the measurement is trying to size up.
     */
    @Volatile
    var planningPaused: Boolean = false
        private set

    fun pausePlanning(paused: Boolean) {
        planningPaused = paused
        if (paused) deferred.clear()
        Postroad.LOGGER.info("Road planning {}", if (paused) "paused (chunk generation still runs)" else "resumed")
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        while (true) {
            val result = results.poll() ?: break
            apply(server, result)
        }
        if (planningPaused) return
        if (pending.get() == 0 && deferred.isNotEmpty()) {
            val next = deferred.removeFirst()
            server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, next.dimension))?.let { schedule(it, next.center, next.discoverTowns, next.refresh, next.replace, next.realTerrainOnly) }
        }
        if (!PostroadConfig.planAuto) return
        if (executor == null || server.tickCount % 100 != 0 || pending.get() > 0 || deferred.isNotEmpty()) return
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
    fun schedule(level: ServerLevel, center: BlockPos, discoverTowns: Boolean = true, refresh: LongOpenHashSet = LongOpenHashSet(), replace: Set<String> = emptySet(), realTerrainOnly: Boolean = false): Boolean {
        val exec = executor ?: return false
        if (pending.get() > 0 || results.isNotEmpty()) {
            val dim = level.dimension().location()
            if (deferred.none { it.dimension == dim && it.center == center && it.discoverTowns == discoverTowns && it.replace == replace }) deferred.add(Deferred(dim, center, discoverTowns, refresh, replace, realTerrainOnly))
            return true
        }
        val request = request(level, center, discoverTowns, refresh, replace, realTerrainOnly)
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

    /**
     * A stored town as the planner sees it: its street stubs with the direction the street faces there when the
     * finder found any, else its street centres (older records, villages without terminators), and its reach.
     */
    fun townFor(t: PlannedTown, terrain: Terrain, margin: Int): Town {
        val stubs = t.exits.isNotEmpty()
        val cells = (if (stubs) t.exits else t.streets).map { s -> terrain.blockToCell(s.x, s.z) }
        val facings = if (stubs) t.facings.map { f -> if (f < 0) null else net.minecraft.core.Direction.from2DDataValue(f).let { Facing(it.stepX, it.stepZ) } } else emptyList()
        return Town(t.id, terrain.blockToCell(t.pos.x, t.pos.z), cells, reach = (maxOf(t.box.xSpan, t.box.zSpan) / 2 + margin) / CELL_SIZE + 1, facings = facings)
    }

    /** Snapshot for a pass, from config and storage, on the server thread. */
    fun request(level: ServerLevel, center: BlockPos, discoverTowns: Boolean = true, refresh: LongOpenHashSet = LongOpenHashSet(), replace: Set<String> = emptySet(), realTerrainOnly: Boolean = false): PassRequest {
        val storage = RoadPlanStorage.get(level.server)
        val dimension = level.dimension().location()
        return PassRequest(
            dimension, center,
            radius = PostroadConfig.planRadius,
            maxLink = PostroadConfig.planMaxLink,
            neighbours = PostroadConfig.planNeighbours,
            margin = PostroadConfig.planStructureMargin,
            costs = PlannerRules.current.copy(steps = RoadPieces.current.stepClasses(), diagonalSteps = RoadPieces.current.diagonalStepClasses(), turnLimits = RoadPieces.current.turnLimits()),
            knownTowns = storage.townsIn(dimension),
            knownRoads = storage.roadsIn(dimension).filter { it.id !in replace },
            knownObstacles = storage.obstaclesIn(dimension),
            passable = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).getTag(TownFinder.PASSABLE)
                .map { set -> set.mapNotNull { h -> h.unwrapKey().orElse(null)?.location() }.toSet() }.orElse(emptySet()),
            discovered = LongOpenHashSet(storage.discovered[dimension] ?: LongOpenHashSet()),
            discoverTowns = discoverTowns,
            dropped = HashSet(storage.droppedRoutes),
            refresh = refresh,
            replace = replace,
            include = replace.flatMapTo(HashSet()) { id -> storage.roads[id]?.let { listOf(it.from, it.to) } ?: emptyList() },
            realTerrainOnly = realTerrainOnly,
        )
    }

    fun workerFor(level: ServerLevel): Worker = workers.getOrPut(level.dimension().location()) {
        // "known" caches hold only tiles of generated terrain (Distant Horizons); estimates are never persisted.
        val cache = level.server.getWorldPath(LevelResource("postroad")).resolve("known$CELL_SIZE")
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
                        newTowns.add(PlannedTown(c.id, req.dimension, c.structure, BlockPos(centre.x, terrain.heightAt(cell.x, cell.z), centre.z), c.box, c.pieces, c.streets, c.exits.map { it.first }, c.exits.map { it.second.get2DDataValue() }))
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
            if (o.structure in req.passable) continue
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
            .filter { it.pos.distSqr(req.center) <= reach.toDouble() * reach || it.id in req.include }
            .map { townFor(it, terrain, req.margin).also { t -> townById[t.id] = t } }
        // An existing road's cells are at its stored heights now (built, or about to be), so the search
        // judges steps onto and along it by those, not by the terrain of its day.
        // A provisional road's heights are estimates; lending them would pass a wrong level from
        // road to road for ever (a replanned road takes the shared cells' heights, and so on).
        val existing = req.knownRoads.map { road ->
            val cells = road.points.map { terrain.blockToCell(it.x, it.z) }
            if (!road.provisional) for ((i, c) in cells.withIndex()) terrain.setHeight(c.x, c.z, road.points[i].y)
            PlannedRoute(road.id, townById[road.from] ?: Town(road.from, cells.first()), townById[road.to] ?: Town(road.to, cells.last()), cells)
        }
        val plan = RoadPlanner.planNetwork(terrain, towns, req.costs, req.neighbours, req.maxLink.toDouble() / CELL_SIZE, existing, coarse, COARSE_CELL / CELL_SIZE, req.dropped, req.realTerrainOnly)

        // 4. Back to blocks. A route over any estimated cell is provisional: its corridor is generated
        //    and the route planned again on the real terrain.
        val corridors = HashMap<String, LongOpenHashSet>()
        // Cells an existing road already occupies keep that road's stored height: two roads through one
        // cell must lay the same blocks, and the built one is the ground there now.
        val existingY = HashMap<Long, Int>()
        for (road in req.knownRoads) { if (road.provisional) continue; for (p in road.points) { val c = terrain.blockToCell(p.x, p.z); existingY.putIfAbsent(Terrain.key(c.x, c.z), p.y) } }
        val catalog = RoadPieces.current
        val newRoads = plan.routes.mapNotNull { route ->
            val points = route.cells.map { c -> val b = terrain.cellToBlock(c.x, c.z); existingY[Terrain.key(c.x, c.z)]?.let { BlockPos(b.x, it, b.z) } ?: b }
            // What cannot be built is not stored: every point must have a catalog piece.
            val bad = points.indices.firstOrNull { i -> catalog.needsAt(points, i)?.let { catalog.match(it) } == null }
            if (bad != null) {
                Postroad.LOGGER.warn("Road {} ({} -> {}) dropped: no piece at {} (needs {})", route.id, route.from.id, route.to.id, points[bad].toShortString(), catalog.needsAt(points, bad))
                return@mapNotNull null
            }
            val road = PlannedRoad(
                route.id, req.dimension, route.from.id, route.to.id, points,
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
        // Corridors of the provisional dropped pairs, in chunks: a water drop's route with the usual margin, an
        // unreachable pair's coarse corridor.
        val droppedCorridors = HashMap<String, LongOpenHashSet>()
        for (d in plan.dropped) {
            if (!d.estimated) continue
            val chunks = LongOpenHashSet()
            for (c in d.cells) {
                val b = terrain.cellToBlock(c.x, c.z)
                for (dz in -CORRIDOR_CHUNKS..CORRIDOR_CHUNKS) for (dx in -CORRIDOR_CHUNKS..CORRIDOR_CHUNKS) chunks.add(ChunkPos.asLong((b.x shr 4) + dx, (b.z shr 4) + dz))
            }
            for (c in d.corridor) {
                val x0 = c.x * COARSE_CELL; val z0 = c.z * COARSE_CELL
                for (bz in listOf(z0, z0 + COARSE_CELL - 1)) for (bx in listOf(x0, x0 + COARSE_CELL - 1)) chunks.add(ChunkPos.asLong(bx shr 4, bz shr 4))
            }
            droppedCorridors[d.id] = chunks
        }
        return PassResult(req.dimension, req.center, newTowns, newRoads, newObstacles, corridors, req.replace, newJunctions, discoveredNow, droppedCorridors,
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
            // Name it now. A junction sign is written when its chunk is built, which is long before
            // anyone walks into the village, and an unnamed place made the sign read "a village".
            io.github.veelume.postroad.network.PlaceResolver.predict(level, town.structure, town.id, town.pos)
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
            if (fresh == null) {
                if (result.dropped.none { it.id == id }) {
                    // The pass did not plan the pair at all (a town out of its reach, or no longer among the
                    // nearest). This used to keep the road and mark it final, which laid its estimated heights
                    // into the world — the roads found hanging two blocks over the landscape. An estimate is a
                    // shape, never geometry, so the road goes and the pair is left to a later pass.
                    storage.removeRoad(id)
                    network.removePath(id)
                    Postroad.LOGGER.info("Road {} ({} -> {}) dropped: its replan pass did not plan the pair, and its heights were only estimates", id, old.from, old.to)
                    continue
                }
                // No route for the pair on the real terrain: the estimate-based road goes with it.
                storage.removeRoad(id)
                network.removePath(id)
                Postroad.LOGGER.info("Road {} ({} -> {}) dropped: no route on the generated terrain", id, old.from, old.to)
                continue
            }
            fresh.replans = old.replans + 1
            storage.removeRoad(id)
            network.removePath(id)
            replanned++
        }
        var provisional = 0
        val added = HashSet<String>()
        for (road in result.newRoads) {
            if (storage.roads.containsKey(road.id)) continue
            added.add(road.id)
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
                if (id !in added) continue
                pendingCorridors[id] = chunks
                ChunkPregen.request(result.dimension, chunks) { ok, failed -> corridorDone(server, result.dimension, id, ok, failed) }
            }
        }
        for (j in result.newJunctions) {
            if (network.paths[j.joinedRoad] == null || network.paths[j.joiningRoad] == null) continue
            if (!storage.addJunction(PlannedJunction(result.dimension, j.pos, j.joinedRoad, j.joiningRoad))) continue
            network.addLink(PathLink(j.joiningRoad, j.joiningIndex, j.joinedRoad, j.joinedIndex))
            junctions++
        }
        if (roads > 0) RoadPlanSnapshot.publish(storage, result.dimension)
        storage.markDiscovered(result.dimension, result.discovered)
        // Dropped pairs judged on estimated terrain are provisional: generate what they searched, plan them again.
        val droppedInfo = LinkedHashMap<String, RoadPlanStorage.DroppedInfo>()
        for (d in result.dropped) droppedInfo[d.id] = RoadPlanStorage.DroppedInfo(d.from.id, d.to.id, d.reason, provisional = d.estimated && (storage.droppedDetails[d.id]?.tries ?: 0) < MAX_DROP_TRIES)
        storage.markDropped(droppedInfo)
        if (PostroadConfig.planPregenInFlight > 0) for (d in result.dropped) {
            if (droppedInfo[d.id]?.provisional != true) continue
            val chunks = LongOpenHashSet(result.droppedCorridors[d.id] ?: LongOpenHashSet())
            // Generating only what the search already looked at is why retries used to plateau: the
            // pair is dropped *because* that ground had no way through, so making it real changes
            // nothing. Each further try opens a diamond between the two towns instead, wider every
            // time — ground the search has never seen, and the only honest answer to "no route" when
            // the shape the estimate drew has already been proved wrong.
            val tries = storage.droppedDetails[d.id]?.tries ?: 0
            if (tries > 0) {
                val a = storage.towns[d.from.id]?.pos
                val b = storage.towns[d.to.id]?.pos
                if (a != null && b != null) chunks.addAll(Corridor.diamond(a, b, DROP_RETRY_HALF * tries))
            }
            if (chunks.isEmpty()) continue
            pendingDropped[d.id] = chunks
            Postroad.LOGGER.info("Dropped pair {} ({}): {} chunk(s) to generate for try {}", d.id, d.reason, chunks.size, tries + 1)
            ChunkPregen.request(result.dimension, chunks) { ok, failed -> droppedCorridorDone(server, result.dimension, d.id, ok, failed) }
        }
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
    /** The same for provisional dropped pairs. */
    private val pendingDropped = HashMap<String, LongOpenHashSet>()

    /** A dropped pair's corridor is generated: take it out of the skip set and plan around it again. */
    private fun droppedCorridorDone(server: MinecraftServer, dimension: ResourceLocation, pairId: String, ok: Int, failed: Int) {
        val corridor = pendingDropped.remove(pairId) ?: LongOpenHashSet()
        if (ok == 0 && failed > 0) { Postroad.LOGGER.warn("Corridor of dropped pair {} could not be generated ({} chunk(s) failed); it stays dropped", pairId, failed); return }
        val storage = RoadPlanStorage.get(server)
        if (storage.roads.containsKey(pairId)) return
        val info = storage.droppedDetails[pairId] ?: return
        if (!storage.retryDropped(pairId)) return
        val level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension)) ?: return
        val a = storage.towns[info.from]?.pos ?: return
        val b = storage.towns[info.to]?.pos ?: return
        val mid = BlockPos((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2)
        schedule(level, mid, discoverTowns = false, refresh = corridor)
        Postroad.LOGGER.info("Dropped pair {} ({} -> {}, {}): corridor generated, planning again (try {})", pairId, info.from, info.to, info.reason, info.tries)
    }

    /** A corridor job finished: replan only when the world actually produced chunks (a failed job would just repeat itself). */
    private fun corridorDone(server: MinecraftServer, dimension: ResourceLocation, roadId: String, ok: Int, failed: Int) {
        if (ok == 0 && failed > 0) {
            Postroad.LOGGER.warn("Corridor of road {} could not be generated ({} chunk(s) failed); it stays as planned", roadId, failed)
            pendingCorridors.remove(roadId)
            return
        }
        replan(server, dimension, roadId)
    }

    /**
     * Snaps a road's points to the ground the world actually has, where that ground is known.
     *
     * A road goes final with estimated heights whenever its replan cannot run, and since the
     * build-time refiner was replaced by the piece catalog the plan's y is laid verbatim — so an
     * estimate that reads two blocks high builds a road two blocks in the air. This is the backstop:
     * the route (x and z) is left alone, only the height moves, and the result is checked against the
     * catalog because moving a point changes the rise its neighbours must span. If the re-fitted line
     * has a point no piece can build, nothing is changed and the road stays as planned.
     *
     * A road that is already partly in the world is left alone: its built chunks would no longer meet
     * the unbuilt ones.
     */
    fun refit(storage: RoadPlanStorage, dimension: ResourceLocation, road: PlannedRoad): Boolean {
        if (road.builtChunks.isNotEmpty()) return false
        // A cell another road has already built is the ground there now: two roads through one cell must
        // lay the same blocks, so those points keep the height the world was given.
        val built = HashMap<Long, Int>()
        for (other in storage.roadsIn(dimension)) {
            if (other.id == road.id || other.builtChunks.isEmpty()) continue
            for (p in other.points) built.putIfAbsent(RoadPieceLayer.key(p.x, p.z), p.y)
        }
        var moved = 0
        val fitted = road.points.map { p ->
            if (built.containsKey(RoadPieceLayer.key(p.x, p.z))) return@map p
            val column = KnownTerrain.column(dimension, p.x, p.z) ?: return@map p
            if (column.water || column.lava || column.top == p.y) p else { moved++; BlockPos(p.x, column.top, p.z) }
        }
        if (moved == 0) return false
        val catalog = RoadPieces.current
        val bad = fitted.indices.firstOrNull { i -> catalog.needsAt(fitted, i)?.let { catalog.match(it) } == null }
        if (bad != null) {
            // Snapping a point changes the rise its neighbours must span, and the real relief can make
            // that steeper than any piece. Route those stretches again on the ground instead.
            val refined = RoadRefiner.refine(dimension, fitted, road.families)
            if (refined == null) {
                Postroad.LOGGER.warn("Road {} kept at its planned heights: no piece at {} and the refiner found no way round it", road.id, fitted[bad].toShortString())
                return false
            }
            road.points = refined.points
            road.families = refined.families
            return true
        }
        Postroad.LOGGER.info("Road {} re-fitted to the real ground: {} of {} point(s) moved", road.id, moved, fitted.size)
        road.points = fitted
        return true
    }

    private fun replan(server: MinecraftServer, dimension: ResourceLocation, roadId: String) {
        val storage = RoadPlanStorage.get(server)
        val corridor = pendingCorridors.remove(roadId) ?: LongOpenHashSet()
        val road = storage.roads[roadId] ?: return
        if (!road.provisional) return
        val level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension)) ?: return
        val touched = road.builtChunks.isNotEmpty() || road.chunks().any { road.id in RoadPlanSnapshot.laidRoads(it) }
        if (touched) {
            // Already partly in the world: its built chunks and its unbuilt ones must meet, so it
            // stays as it is. Only a road the feature laid before the plan settled can be in this
            // state, and it is the one case where estimated heights still reach the ground.
            road.provisional = false
            refit(storage, dimension, road)
            storage.setDirty()
            RoadPlanSnapshot.publish(storage, dimension)
            RoadBuilder.enqueueLoaded(level, road)
            return
        }
        if (road.replans >= MAX_REPLANS) {
            // Tried often enough and the ground still will not take it. Keeping it would mean laying
            // estimates, so the pair goes instead and a later pass may find it once more ground exists.
            storage.removeRoad(road.id)
            Network.get(server).removePath(road.id)
            Postroad.LOGGER.info("Road {} ({} -> {}) dropped after {} replan(s): no route on the real ground", road.id, road.from, road.to, road.replans)
            return
        }
        // The pass plans this pair again without the old road in the way; apply swaps the two.
        val mid = road.points[road.points.size / 2]
        schedule(level, mid, discoverTowns = false, refresh = corridor, replace = setOf(roadId), realTerrainOnly = true)
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
            "${storage.discovered.values.sumOf { it.size }} square(s) searched, ${storage.droppedRoutes.size} pair(s) dropped (${storage.droppedDetails.values.count { it.reason == "water" }} water, ${storage.droppedDetails.values.count { it.provisional }} provisional)")
        for ((dim, w) in workers) {
            lines.add("$dim: ${w.terrain.tileCount} fine + ${w.coarse.tileCount} coarse tile(s) in memory, ${w.sampler.sampled}/${w.coarseSampler.sampled} sampled, ${w.sampler.fromCache}/${w.coarseSampler.fromCache} from cache, " +
                "${w.sampler.chunkCells}/${w.coarseSampler.chunkCells} cell(s) from own chunks, ${w.sampler.knownCells - w.sampler.chunkCells}/${w.coarseSampler.knownCells - w.coarseSampler.chunkCells} from Distant Horizons, ${w.sampler.estimatedCells}/${w.coarseSampler.estimatedCells} estimated; " +
                "finder: ${w.finder.generated} layout(s) built, ${w.finder.prefiltered} skipped by biome, ${w.finder.obstaclesFound} obstacle(s), ${w.finder.passableFound} passable")
            val costly = w.finder.timing.entries.sortedByDescending { it.value[1] }.take(6)
            if (costly.isNotEmpty()) lines.add("  layouts by cost: " + costly.joinToString(", ") { "${it.key} ${it.value[0]}× ${it.value[1] / 1_000_000 / maxOf(1, it.value[0])} ms" } +
                (if (w.finder.skipped.isEmpty()) "" else "; always buried, skipped: ${w.finder.skipped.joinToString(", ")}"))
        }
        val built = storage.roads.values.sumOf { it.builtChunks.size }
        val total = storage.roads.values.sumOf { it.chunks().size }
        lines.add("worldgen: ${RoadPlanSnapshot.placements.size} chunk(s) in the snapshot, ${RoadPlanSnapshot.laid.size} chunk(s) laid by the road feature (${RoadPlanSnapshot.featureRuns.get()} run(s), ${RoadPlanSnapshot.piecesPlaced.get()} piece(s), ${RoadPlanSnapshot.noPiece.get()} without a piece); ${ChunkPregen.status()}; ${KnownTerrain.size(server.overworld().dimension().location())} chunk(s) of known terrain; ${storage.roads.values.count { it.provisional }} provisional road(s), $replanned replanned")
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
    /**
     * Plans one dropped pair again on the current terrain and draws the fine cells the search closed,
     * with the corridor's blocked/water flags and heights, to `<world>/postroad/trace_<pair>.png`.
     * A debugging aid: it says why a pair is unreachable where the log line cannot.
     */
    fun trace(level: ServerLevel, pairId: String): String {
        if (pending.get() > 0) return "A pass is running; try again when it is done."
        val worker = workers[level.dimension().location()] ?: return "No planner data for this dimension."
        val storage = RoadPlanStorage.get(level.server)
        val dd = storage.droppedDetails[pairId] ?: return "No dropped pair $pairId."
        val d = Triple(dd.from, dd.to, dd.reason)
        val req = request(level, BlockPos.ZERO)
        val terrain = worker.terrain; val coarse = worker.coarse
        fun town(id: String): Town? = storage.towns[id]?.let { townFor(it, terrain, req.margin) }
        val a = town(d.first) ?: return "Unknown town ${d.first}"
        val b = town(d.second) ?: return "Unknown town ${d.second}"
        val closed = LongOpenHashSet()
        val rejects = IntArray(RoadPlanner.REJECT_NAMES.size)
        RoadPlanner.trace.set(closed); RoadPlanner.traceEnds.set(null); RoadPlanner.traceRejects.set(rejects)
        val exitA = a.exitToward(b.cell); val exitB = b.exitToward(a.cell)
        val t0 = System.nanoTime()
        val route = try { RoadPlanner.routeTowns(terrain, coarse, COARSE_CELL / CELL_SIZE, a, b, req.costs) } finally { RoadPlanner.trace.set(null); RoadPlanner.traceRejects.set(null) }
        val ends = RoadPlanner.traceEnds.get()
        val ms = (System.nanoTime() - t0) / 1_000_000
        // Unreachable: what the wall is made of, and whether the piece grid's turn rules are it.
        val wall = if (route != null) "" else {
            val free = LongOpenHashSet(); RoadPlanner.trace.set(free)
            val freeRoute = try { RoadPlanner.routeTowns(terrain, coarse, COARSE_CELL / CELL_SIZE, a, b, req.costs, freeTurns = true) } finally { RoadPlanner.trace.set(null) }
            "; refused neighbours: " + RoadPlanner.REJECT_NAMES.indices.filter { rejects[it] > 0 }.joinToString(", ") { "${RoadPlanner.REJECT_NAMES[it]} ${rejects[it]}" } +
                "; without the turn rules: " + (if (freeRoute != null) "route of ${freeRoute.size} cells" else "still unreachable") + " (${free.size} closed)"
        }
        // Draw: the closed cells' bounding box plus both exits, 12 cells of margin, 4 px per cell.
        var minX = minOf(exitA.x, exitB.x); var maxX = maxOf(exitA.x, exitB.x); var minZ = minOf(exitA.z, exitB.z); var maxZ = maxOf(exitA.z, exitB.z)
        val iter = closed.iterator()
        while (iter.hasNext()) { val k = iter.nextLong(); val x = Terrain.keyX(k); val z = Terrain.keyZ(k); if (x < minX) minX = x; if (x > maxX) maxX = x; if (z < minZ) minZ = z; if (z > maxZ) maxZ = z }
        minX -= 12; minZ -= 12; maxX += 12; maxZ += 12
        val w = (maxX - minX + 1).coerceAtMost(600); val h = (maxZ - minZ + 1).coerceAtMost(600)
        val px = 4
        val image = java.awt.image.BufferedImage(w * px, h * px, java.awt.image.BufferedImage.TYPE_INT_RGB)
        var minH = Int.MAX_VALUE; var maxH = Int.MIN_VALUE
        for (z in 0 until h) for (x in 0 until w) { val hh = terrain.loadedHeightAt(minX + x, minZ + z) ?: continue; if (hh < minH) minH = hh; if (hh > maxH) maxH = hh }
        if (minH > maxH) { minH = 60; maxH = 120 }
        val g2 = image.createGraphics()
        for (z in 0 until h) for (x in 0 until w) {
            val cx = minX + x; val cz = minZ + z
            val hh = terrain.loadedHeightAt(cx, cz)
            val flags = terrain.loadedFlagsAt(cx, cz) ?: 0
            val rgb = when {
                hh == null -> 0x202020
                flags and Terrain.BLOCKED != 0 -> 0x803030
                flags and Terrain.LAVA != 0 -> 0xff8000
                flags and Terrain.WATER != 0 -> 0x2050a0
                else -> { val t = ((hh - minH).toDouble() / (maxH - minH).coerceAtLeast(1)).coerceIn(0.0, 1.0); ((50 + 150 * t).toInt() shl 16) or ((90 + 130 * t).toInt() shl 8) or (40 + 60 * t).toInt() }
            }
            g2.color = java.awt.Color(rgb)
            g2.fillRect(x * px, z * px, px, px)
            if (closed.contains(Terrain.key(cx, cz))) { g2.color = java.awt.Color.WHITE; g2.fillRect(x * px + 1, z * px + 1, px - 2, px - 2) }
        }
        g2.color = java.awt.Color.CYAN
        for (s in a.exits + b.exits) if (s.x in minX..maxX && s.z in minZ..maxZ) g2.fillRect((s.x - minX) * px, (s.z - minZ) * px, px, px)
        g2.color = java.awt.Color.MAGENTA
        for (c in listOf(exitA, exitB)) g2.fillRect((c.x - minX) * px - 2, (c.z - minZ) * px - 2, px + 4, px + 4)
        g2.color = java.awt.Color.YELLOW
        ends?.let { (f, t) -> for (c in f + t) g2.fillRect((c.x - minX) * px - 1, (c.z - minZ) * px - 1, px + 2, px + 2) }
        if (route != null) { g2.color = java.awt.Color.GREEN; for (c in route) g2.fillRect((c.x - minX) * px + 1, (c.z - minZ) * px + 1, px - 2, px - 2) }
        g2.dispose()
        val file = level.server.getWorldPath(LevelResource("postroad")).resolve("trace_$pairId.png")
        java.nio.file.Files.createDirectories(file.parent)
        javax.imageio.ImageIO.write(image, "png", file.toFile())
        // Step-class statistics on the closed cells' neighbourhood: how many cardinal steps exceed the largest rise.
        val limit = req.costs.steps.maxOf { it.upTo }
        var steps = 0; var tooSteep = 0
        val it2 = closed.iterator()
        while (it2.hasNext()) { val k = it2.nextLong(); val x = Terrain.keyX(k); val z = Terrain.keyZ(k); val hh = terrain.heightAt(x, z)
            for ((dx, dz) in listOf(1 to 0, 0 to 1)) { if (terrain.has(x + dx, z + dz, Terrain.BLOCKED)) continue; steps++; if (kotlin.math.abs(terrain.heightAt(x + dx, z + dz) - hh) > limit) tooSteep++ } }
        return "Pair $pairId ${d.first} -> ${d.second}: ${if (route != null) "route of ${route.size} cells" else "unreachable: ${RoadPlanner.lastFailure.get()}"}; facing exits $exitA / $exitB, ${ends?.first?.size ?: 0} start(s) / ${ends?.second?.size ?: 0} goal(s), ${closed.size} fine cell(s) closed in $ms ms, $tooSteep of $steps cardinal steps off closed cells exceed rise $limit$wall; drawn to $file (${minX * CELL_SIZE}, ${minZ * CELL_SIZE}) to (${maxX * CELL_SIZE}, ${maxZ * CELL_SIZE}), $px px per cell"
    }

    /**
     * Draws the plan over the terrain and writes a PNG.
     *
     * The terrain is *sampled*, not taken from whatever the last pass happened to leave in memory.
     * A picture that depends on what the session has done so far is worth little — after a restart
     * it came out almost blank, and after a re-run it showed guessed ground where the plan had
     * really been made on generated chunks. Sampling a thousand cells square is far too slow for the
     * server thread, so the whole thing runs on the planner's own thread, which also serialises it
     * against passes: they share that thread and the same terrain.
     */
    fun exportImage(level: ServerLevel, center: BlockPos, radius: Int): String {
        val exec = executor ?: return "The planner is off, so there is no terrain to draw."
        val dim = level.dimension().location()
        val worker = workerFor(level)
        val storage = RoadPlanStorage.get(level.server)
        // Everything the drawing needs, copied here on the server thread.
        val roads = storage.roadsIn(dim).map { it.points.toList() }
        val towns = storage.townsIn(dim).map { it.pos }
        val junctions = storage.junctions.filter { it.dimension == dim }.map { it.pos }
        val obstacles = storage.obstaclesIn(dim).map { it.box }
        val dropped = storage.droppedDetails.values.mapNotNull { d ->
            val a = storage.towns[d.from]?.pos
            val b = storage.towns[d.to]?.pos
            if (a == null || b == null) null else a to b
        }
        val file = level.server.getWorldPath(LevelResource("postroad")).resolve("plan_" + center.x + "_" + center.z + ".png")
        exec.execute {
            try {
                val ms = drawPlan(worker, center, radius, roads, towns, junctions, obstacles, dropped, file)
                Postroad.LOGGER.info("Plan drawn to {} in {} ms: {} road(s), {} town(s), {} dropped pair(s)", file, ms, roads.size, towns.size, dropped.size)
            } catch (e: Throwable) {
                Postroad.LOGGER.error("Drawing the plan failed", e)
            }
        }
        return "Drawing the plan around " + center.x + ", " + center.z + " on the planner thread; the log says when it lands in world/postroad/."
    }

    /** The drawing itself, on the planner thread: samples the terrain, then paints the plan over it. */
    private fun drawPlan(
        worker: Worker,
        center: BlockPos,
        radius: Int,
        roads: List<List<BlockPos>>,
        towns: List<BlockPos>,
        junctions: List<BlockPos>,
        obstacles: List<net.minecraft.world.level.levelgen.structure.BoundingBox>,
        dropped: List<Pair<BlockPos, BlockPos>>,
        file: java.nio.file.Path,
    ): Long {
        val started = System.nanoTime()
        val cells = radius * 2 / CELL_SIZE
        // One pixel per cell is 2.5 million samples at the radius a whole plan wants, which took
        // minutes and held the planner thread while it did. A picture wider than this shows nothing
        // more, so above it a pixel stands for several cells.
        val step = ((cells + MAX_PLAN_PIXELS - 1) / MAX_PLAN_PIXELS).coerceAtLeast(1)
        val size = cells / step
        val x0 = Math.floorDiv(center.x - radius, CELL_SIZE)
        val z0 = Math.floorDiv(center.z - radius, CELL_SIZE)
        val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)

        // Sampled once into arrays: the shading needs each cell's neighbours, and sampling is the
        // expensive part — a tile at a time behind the scenes, but a million cells all told.
        val heights = IntArray(size * size)
        val flags = IntArray(size * size)
        for (pz in 0 until size) for (px in 0 until size) {
            val cx = x0 + px * step
            val cz = z0 + pz * step
            heights[pz * size + px] = worker.terrain.heightAt(cx, cz)
            flags[pz * size + px] = worker.terrain.flagsAt(cx, cz)
        }
        var minH = Int.MAX_VALUE
        var maxH = Int.MIN_VALUE
        for (h in heights) { if (h < minH) minH = h; if (h > maxH) maxH = h }
        if (minH >= maxH) { minH = 60; maxH = 120 }

        fun heightAt(px: Int, pz: Int): Int? =
            if (px in 0 until size && pz in 0 until size) heights[pz * size + px] else null

        // Land colour by height, low to high: green flats, then yellow, brown, grey rock and snow.
        // A flat brown ramp made a mountain and a meadow look much the same, which is no use for
        // judging whether a road went round a mountain or over it.
        fun hypsometric(t: Double): Triple<Int, Int, Int> {
            val stops = listOf(
                0.00 to Triple(60, 120, 70),
                0.25 to Triple(110, 155, 80),
                0.45 to Triple(190, 185, 110),
                0.65 to Triple(160, 130, 90),
                0.82 to Triple(130, 120, 115),
                1.00 to Triple(240, 240, 245),
            )
            val hi = stops.indexOfFirst { it.first >= t }.coerceAtLeast(1)
            val low = stops[hi - 1]
            val high = stops[hi]
            val f = ((t - low.first) / (high.first - low.first).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
            return Triple(
                (low.second.first + (high.second.first - low.second.first) * f).toInt(),
                (low.second.second + (high.second.second - low.second.second) * f).toInt(),
                (low.second.third + (high.second.third - low.second.third) * f).toInt(),
            )
        }

        for (pz in 0 until size) for (px in 0 until size) {
            val h = heights[pz * size + px]
            val f = flags[pz * size + px]
            val rgb = when {
                f and Terrain.LAVA != 0 -> 0xc04010
                f and Terrain.BLOCKED != 0 -> 0x803030
                f and Terrain.WATER != 0 -> 0x2050a0
                else -> {
                    val t = ((h - minH).toDouble() / (maxH - minH).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                    val base = hypsometric(t)
                    var r = base.first
                    var g = base.second
                    var b = base.third
                    // Relief shading with the light in the north-west: the slope across the cell,
                    // which is what turns a height map into something a person reads as terrain.
                    val west = heightAt(px - 1, pz)
                    val east = heightAt(px + 1, pz)
                    val north = heightAt(px, pz - 1)
                    val south = heightAt(px, pz + 1)
                    if (west != null && east != null && north != null && south != null) {
                        val slope = ((west - east) + (north - south)) / 2.0
                        val shade = 1.0 + (slope / 6.0).coerceIn(-1.0, 1.0) * 0.45
                        r = (r * shade).toInt().coerceIn(0, 255)
                        g = (g * shade).toInt().coerceIn(0, 255)
                        b = (b * shade).toInt().coerceIn(0, 255)
                    }
                    // Ground the planner only guessed at: tinted violet, so a road crossing one is
                    // obvious. After a pipeline run there should be none under any road.
                    if (f and Terrain.ESTIMATED != 0) {
                        r = (r * 0.75 + 70).toInt().coerceIn(0, 255)
                        b = (b * 0.75 + 90).toInt().coerceIn(0, 255)
                        g = (g * 0.70).toInt().coerceIn(0, 255)
                    }
                    (r shl 16) or (g shl 8) or b
                }
            }
            image.setRGB(px, pz, rgb)
        }

        val g2 = image.createGraphics()
        fun cellX(x: Int) = (Math.floorDiv(x, CELL_SIZE) - x0) / step
        fun cellZ(z: Int) = (Math.floorDiv(z, CELL_SIZE) - z0) / step
        g2.color = java.awt.Color.RED
        for (pair in dropped) g2.drawLine(cellX(pair.first.x), cellZ(pair.first.z), cellX(pair.second.x), cellZ(pair.second.z))
        g2.color = java.awt.Color(160, 40, 40)
        for (b in obstacles) g2.drawRect(cellX(b.minX()), cellZ(b.minZ()), Math.floorDiv(b.xSpan, CELL_SIZE) / step, Math.floorDiv(b.zSpan, CELL_SIZE) / step)
        g2.color = java.awt.Color.WHITE
        for (points in roads) for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            g2.drawLine(cellX(a.x), cellZ(a.z), cellX(b.x), cellZ(b.z))
        }
        g2.color = java.awt.Color.YELLOW
        for (j in junctions) g2.fillRect(cellX(j.x) - 1, cellZ(j.z) - 1, 3, 3)
        g2.color = java.awt.Color.MAGENTA
        for (t in towns) g2.fillRect(cellX(t.x) - 2, cellZ(t.z) - 2, 5, 5)
        g2.dispose()

        java.nio.file.Files.createDirectories(file.parent)
        javax.imageio.ImageIO.write(image, "png", file.toFile())
        return (System.nanoTime() - started) / 1_000_000
    }
}
