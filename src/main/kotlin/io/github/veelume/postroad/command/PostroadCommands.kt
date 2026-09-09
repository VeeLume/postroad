package io.github.veelume.postroad.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.veelume.postroad.advancement.PostroadAdvancements
import io.github.veelume.postroad.depot.DepotInteraction
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.network.LedgerEntry
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.registry.PostroadDataMaps
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/postroad places | balance [player] | ledger [count] | grant <account> <amount>`.
 * Reading is open to everyone; `grant` is op-only and exists for testing.
 */
object PostroadCommands {

    fun register(event: RegisterCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("postroad")
                .then(Commands.literal("places").executes { places(it) })
                .then(Commands.literal("inspect").executes { inspect(it) })
                .then(Commands.literal("rates").executes { rates(it) })
                .then(Commands.literal("mail").executes { mail(it) })
                .then(
                    Commands.literal("home")
                        .executes { home(it, null) }
                        .then(
                            Commands.argument("town", StringArgumentType.greedyString())
                                .executes { home(it, StringArgumentType.getString(it, "town")) },
                        ),
                )
                .then(Commands.literal("unlock").requires { it.hasPermission(2) }.executes { unlock(it) })
                .then(
                    Commands.literal("chart")
                        .executes { chartStatus(it) }
                        .then(Commands.literal("abort").executes { chartAbort(it) }),
                )
                .then(Commands.literal("paths").executes { paths(it) })
                .then(
                    Commands.literal("roads")
                        .executes { roadsStatus(it) }
                        .then(Commands.literal("status").executes { roadsStatus(it) })
                        .then(Commands.literal("plan").requires { it.hasPermission(2) }.executes { roadsPlan(it) })
                        .then(Commands.literal("clear").requires { it.hasPermission(2) }.executes { roadsClear(it) })
                        .then(Commands.literal("rebuild").requires { it.hasPermission(2) }.executes { roadsRebuild(it) })
                        .then(Commands.literal("export").requires { it.hasPermission(2) }.executes { roadsExport(it) })
                        .then(Commands.literal("planner").requires { it.hasPermission(2) }
                            .then(Commands.literal("off").executes { roadsPlanner(it, false) })
                            .then(Commands.literal("on").executes { roadsPlanner(it, true) }))
                        .then(Commands.literal("pipeline").requires { it.hasPermission(2) }
                            .then(Commands.argument("radius", IntegerArgumentType.integer(16, 8192)).executes { roadsPipeline(it, IntegerArgumentType.getInteger(it, "radius")) }))
                        .then(Commands.literal("scan").requires { it.hasPermission(2) }
                            .then(Commands.argument("radius", IntegerArgumentType.integer(16, 8192)).executes { roadsScan(it, IntegerArgumentType.getInteger(it, "radius")) }))
                        .then(Commands.literal("measure").requires { it.hasPermission(2) }.executes { roadsMeasureStatus(it) }
                            .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4096))
                                .executes { roadsMeasure(it, IntegerArgumentType.getInteger(it, "radius"), "carvers") }
                                .then(Commands.argument("step", StringArgumentType.word())
                                    .executes { roadsMeasure(it, IntegerArgumentType.getInteger(it, "radius"), StringArgumentType.getString(it, "step")) })))
                        .then(Commands.literal("showcase").requires { it.hasPermission(2) }.executes { roadsShowcase(it) }
                            .then(Commands.argument("family", StringArgumentType.word()).executes { roadsShowcase(it, StringArgumentType.getString(it, "family")) }))
                        .then(Commands.literal("capture").requires { it.hasPermission(2) }.then(Commands.argument("piece", com.mojang.brigadier.arguments.StringArgumentType.word()).executes { ctx ->
                            ctx.source.sendSuccess({ Component.literal(io.github.veelume.postroad.roads.gen.RoadBuilder.capture(ctx.source.level, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "piece"), BlockPos.containing(ctx.source.position))) }, false); 1 }))
                        .then(Commands.literal("known").requires { it.hasPermission(2) }.then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes { roadsKnown(it, IntegerArgumentType.getInteger(it, "x"), IntegerArgumentType.getInteger(it, "z")) })))
                        .then(Commands.literal("trace").requires { it.hasPermission(2) }.then(Commands.argument("pair", com.mojang.brigadier.arguments.StringArgumentType.word()).executes { roadsTrace(it) }))
                        .then(
                            Commands.literal("debug").requires { it.hasPermission(2) }.executes { roadsDebug(it) }
                                .then(Commands.literal("terrain").executes { roadsDebugTerrain(it) })
                                .then(Commands.literal("pieces").executes { roadsDebugPieces(it) }),
                        )
                        .then(
                            Commands.literal("audit").requires { it.hasPermission(2) }
                                .then(
                                    Commands.argument("radius", IntegerArgumentType.integer(16, 4000))
                                        .executes { roadsAudit(it, IntegerArgumentType.getInteger(it, "radius"), false) }
                                        .then(Commands.literal("fix").executes { roadsAudit(it, IntegerArgumentType.getInteger(it, "radius"), true) }),
                                ),
                        )
                        .then(
                            Commands.literal("probe")
                                .executes { roadsProbe(it, BlockPos.containing(it.source.position)) }
                                .then(
                                    Commands.argument("x", IntegerArgumentType.integer()).then(
                                        Commands.argument("z", IntegerArgumentType.integer())
                                            .executes { roadsProbe(it, BlockPos(IntegerArgumentType.getInteger(it, "x"), 0, IntegerArgumentType.getInteger(it, "z"))) },
                                    ),
                                ),
                        ),
                )
                .then(Commands.literal("nodes").executes { nodes(it) })
                .then(
                    Commands.literal("balance")
                        .executes { balance(it, it.source.playerOrException.gameProfile.name) }
                        .then(
                            Commands.argument("player", StringArgumentType.word())
                                .executes { balance(it, StringArgumentType.getString(it, "player")) },
                        ),
                )
                .then(
                    Commands.literal("ledger")
                        .executes { ledger(it, 10) }
                        .then(
                            Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                .executes { ledger(it, IntegerArgumentType.getInteger(it, "count")) },
                        ),
                )
                .then(
                    Commands.literal("grant")
                        .requires { it.hasPermission(2) }
                        .then(
                            Commands.argument("account", StringArgumentType.string())
                                .then(
                                    Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes {
                                            grant(
                                                it,
                                                StringArgumentType.getString(it, "account"),
                                                LongArgumentType.getLong(it, "amount"),
                                            )
                                        },
                                ),
                        ),
                ),
        )
    }

    private fun places(ctx: CommandContext<CommandSourceStack>): Int {
        val network = Network.get(ctx.source.server)
        if (network.places.isEmpty()) {
            ctx.source.sendSuccess({ Component.translatable("command.postroad.places.none") }, false)
            return 0
        }
        ctx.source.sendSuccess({ Component.translatable("command.postroad.places.header", network.places.size) }, false)
        for (place in network.places.values) {
            ctx.source.sendSuccess({
                Component.translatable(
                    "command.postroad.places.entry",
                    place.name, place.type, place.culture, place.pos.x, place.pos.y, place.pos.z, place.dimension.toString(),
                )
            }, false)
        }
        return network.places.size
    }

    private fun mail(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val network = Network.get(ctx.source.server)
        val today = FreshLoot.dayOf(ctx.source.level)
        val parcels = network.parcelsFor(player.gameProfile.name)
        for (parcel in parcels) {
            val town = network.places[parcel.to]?.name ?: parcel.to
            val key = if (parcel.isDue(today)) "command.postroad.mail.held" else "command.postroad.mail.transit"
            ctx.source.sendSuccess({ Component.translatable(key, parcel.items.size, town, parcel.arrivalDay - today) }, false)
        }
        if (parcels.isEmpty()) ctx.source.sendSuccess({ Component.translatable("command.postroad.mail.none") }, false)
        return parcels.size
    }

    private fun home(ctx: CommandContext<CommandSourceStack>, town: String?): Int {
        val player = ctx.source.playerOrException
        val network = Network.get(ctx.source.server)
        if (town == null) {
            val home = network.homeOf(player.gameProfile.name)
            ctx.source.sendSuccess({
                if (home == null) Component.translatable("command.postroad.home.none")
                else Component.translatable("command.postroad.home.current", home.name)
            }, false)
            return if (home == null) 0 else 1
        }
        val place = network.placeByName(town)
        if (place == null || !network.hasDepot(place.id)) {
            ctx.source.sendFailure(Component.translatable("command.postroad.home.unknown", town))
            return 0
        }
        network.setHome(player.gameProfile.name, place.id)
        PostroadAdvancements.award(player, PostroadAdvancements.HOME_SET)
        ctx.source.sendSuccess({ Component.translatable("command.postroad.home.set", place.name) }, false)
        return 1
    }

    private fun chartStatus(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val session = io.github.veelume.postroad.roads.Charting.session(player)
        if (session == null) {
            ctx.source.sendSuccess({ Component.translatable("command.postroad.chart.idle") }, false)
            return 0
        }
        val tier = io.github.veelume.postroad.roads.RoadClassifier.evaluate(session.tiers).tier
        ctx.source.sendSuccess({
            Component.translatable("message.postroad.chart.readout", session.distance.toInt(), (session.roadShare * 100).toInt(),
                Component.translatable("tier.postroad.${tier?.key ?: "none"}"))
        }, false)
        return 1
    }

    private fun chartAbort(ctx: CommandContext<CommandSourceStack>): Int {
        io.github.veelume.postroad.roads.Charting.abort(ctx.source.playerOrException, "message.postroad.chart.aborted")
        return 1
    }

    private fun paths(ctx: CommandContext<CommandSourceStack>): Int {
        val network = Network.get(ctx.source.server)
        if (network.paths.isEmpty()) {
            ctx.source.sendSuccess({ Component.translatable("command.postroad.paths.none") }, false)
            return 0
        }
        for (path in network.paths.values) {
            val nodesOn = network.nodes.values.count { it.pathId == path.id }
            val linksOn = network.links.count { it.pathA == path.id || it.pathB == path.id }
            ctx.source.sendSuccess({
                Component.translatable("command.postroad.paths.entry", path.id, path.length.toInt(),
                    Component.translatable("tier.postroad.${path.tier?.key ?: "dirt"}"), network.knownPlayers[path.recordedBy] ?: path.recordedBy,
                    path.points.first().toShortString(), path.points.last().toShortString(), nodesOn, linksOn)
            }, false)
        }
        return network.paths.size
    }

    private fun roadsStatus(ctx: CommandContext<CommandSourceStack>): Int {
        for (line in io.github.veelume.postroad.roads.gen.RoadGen.status(ctx.source.server)) ctx.source.sendSuccess({ Component.literal(line) }, false)
        return 1
    }

    private fun roadsPlan(ctx: CommandContext<CommandSourceStack>): Int {
        val level = ctx.source.level
        val pos = BlockPos.containing(ctx.source.position)
        val queued = io.github.veelume.postroad.roads.gen.RoadGen.schedule(level, pos)
        ctx.source.sendSuccess({ Component.literal(if (queued) "Road plan pass queued around ${pos.toShortString()}." else "The road planner is off (config plan.enabled).") }, true)
        return if (queued) 1 else 0
    }

    /** One sampler per level for probes, so the calibration runs once instead of per probe. */
    private var probeSampler: Pair<ServerLevel, io.github.veelume.postroad.roads.gen.WorldTerrainSampler>? = null

    private fun samplerFor(level: ServerLevel): io.github.veelume.postroad.roads.gen.WorldTerrainSampler {
        probeSampler?.let { if (it.first === level) return it.second }
        return io.github.veelume.postroad.roads.gen.WorldTerrainSampler(level, null, io.github.veelume.postroad.roads.gen.RoadGen.CELL_SIZE).also { probeSampler = level to it }
    }

    /**
     * `/postroad roads audit <radius>`: every chunk with a planned run within [radius] blocks of the
     * caller that already exists on disk — does it have its own road structure start? Chunks are
     * read at structure-start status (not generated), so the audit is cheap and touches nothing.
     */
    private fun roadsAudit(ctx: CommandContext<CommandSourceStack>, radius: Int, fix: Boolean): Int {
        val level = ctx.source.level
        val storage = io.github.veelume.postroad.roads.gen.RoadPlanStorage.get(ctx.source.server)
        var unmarked = 0
        val centre = BlockPos.containing(ctx.source.position)
        val snapshot = io.github.veelume.postroad.roads.gen.RoadPlanSnapshot.placements
        var withStart = 0; var without = 0; var absent = 0
        // The road against the plan, and the ground beside the road against the plan, per anchor in generated chunks.
        // Heights are the first air above ground or liquid, read from the blocks (the heightmaps give the top
        // block's y): road minus plan should be 0 everywhere; beside minus plan is +1 where the road cuts one
        // block into the ground on that side, -1 where it stands one block proud of it.
        val bucketNames = listOf("<=-5", "-4", "-3", "-2", "-1", "0", "+1", "+2", "+3", "+4", ">=5")
        val onRoad = IntArray(11); val beside = IntArray(11)
        var waterPoints = 0; var besideSkipped = 0
        val cuts = ArrayList<String>()
        fun bucket(d: Int): Int = (d + 5).coerceIn(0, 10)
        fun firstAir(x: Int, z: Int): Pair<Int, Boolean>? {
            // Loads the chunk from disk when it is not loaded: after a restart nothing within the radius is.
            if (level.chunkSource.getChunk(x shr 4, z shr 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true) == null) return null
            var y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1 // the highest block of any kind
            var guard = 0
            while (y > level.minBuildHeight && guard++ < 64) {
                val st = level.getBlockState(BlockPos(x, y, z))
                if (!st.fluidState.isEmpty) return (y + 1) to true
                if (io.github.veelume.postroad.roads.gen.DhTerrain.isGround(st)) return (y + 1) to false
                y--
            }
            return (y + 1) to false
        }
        for ((key, runs) in snapshot) {
            val cx = net.minecraft.world.level.ChunkPos.getX(key); val cz = net.minecraft.world.level.ChunkPos.getZ(key)
            if (maxOf(kotlin.math.abs(cx * 16 + 8 - centre.x), kotlin.math.abs(cz * 16 + 8 - centre.z)) > radius) continue
            val chunk = level.chunkSource.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, true)
            if (chunk == null || !chunk.persistedStatus.isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS)) { absent++; continue }
            val start = if (io.github.veelume.postroad.roads.gen.RoadPlanSnapshot.wasLaid(key)) Unit else null
            if (chunk.persistedStatus.isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.FULL)) {
                val seen = HashSet<Long>()
                // Road columns of this chunk and its neighbours, so "beside" never lands on another road.
                val roadColumns = it.unimi.dsi.fastutil.longs.LongOpenHashSet()
                for (dz in -1..1) for (dx in -1..1) roadColumns.addAll(io.github.veelume.postroad.roads.gen.RoadPieceLayer.footprintOf(io.github.veelume.postroad.roads.gen.RoadPlanSnapshot.placementsAt(cx + dx, cz + dz).map { it.placement }))
                for (run in runs) run.placement.anchor.above().let { p ->
                    if ((p.x shr 4) != cx || (p.z shr 4) != cz || !seen.add(p.asLong())) return@let
                    val road = firstAir(p.x, p.z) ?: return@let
                    if (road.second) { waterPoints++; return@let }
                    onRoad[bucket(road.first - p.y)]++
                    var worst = 0; var worstAt = ""
                    for ((dx, dz) in listOf(3 to 0, -3 to 0, 0 to 3, 0 to -3)) {
                        val x = p.x + dx; val z = p.z + dz
                        if (roadColumns.contains(io.github.veelume.postroad.roads.gen.RoadPieceLayer.key(x, z))) { besideSkipped++; continue }
                        val g = firstAir(x, z) ?: continue
                        if (g.second) continue
                        val d = g.first - p.y
                        beside[bucket(d)]++
                        if (kotlin.math.abs(d) > kotlin.math.abs(worst)) { worst = d; worstAt = "($x, $z)" }
                    }
                    if (kotlin.math.abs(worst) >= 2 && cuts.size < 12) cuts.add("${p.toShortString()} planned ${p.y}: ground beside at $worstAt is ${if (worst > 0) "$worst higher" else "${-worst} lower"}")
                }
            }
            if (start != null) { withStart++; continue }
            if (!chunk.persistedStatus.isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES)) { absent++; continue } // still to come: the feature will lay it
            without++
            if (fix) {
                // Back to the chunk-load builder: a finished chunk the feature did not lay this session.
                for (road in storage.roadsInChunk(level.dimension().location(), key)) if (road.builtChunks.remove(key)) unmarked++
                if (level.hasChunk(cx, cz)) io.github.veelume.postroad.roads.gen.RoadBuilder.onChunkLoad(level, key)
            }
        }
        if (unmarked > 0) storage.setDirty()
        ctx.source.sendSuccess({ Component.literal("Audit within $radius: $withStart road chunk(s) laid by the feature this session, $without finished without it, $absent not past their features step yet${if (fix) "; $unmarked road-chunk mark(s) cleared for the builder" else ""}") }, false)
        val total = onRoad.sum()
        if (total > 0) {
            ctx.source.sendSuccess({ Component.literal("Road first air minus planned height over $total anchor(s), $waterPoints on water: " + bucketNames.indices.filter { onRoad[it] > 0 }.joinToString(", ") { "${bucketNames[it]}: ${onRoad[it]}" }) }, false)
            ctx.source.sendSuccess({ Component.literal("Ground 3 blocks beside the anchor minus planned height over ${beside.sum()} column(s) ($besideSkipped on other road columns skipped; +1 = the road cuts one block into that side, -1 = it stands one proud): " + bucketNames.indices.filter { beside[it] > 0 }.joinToString(", ") { "${bucketNames[it]}: ${beside[it]}" }) }, false)
            for (m in cuts) ctx.source.sendSuccess({ Component.literal("  2+: $m") }, false)
        }
        return 1
    }

    /** Sampler vs. world at one column: the planner's surface estimate against the generator and the real heightmap. */
    private fun roadsProbe(ctx: CommandContext<CommandSourceStack>, pos: BlockPos): Int {
        val level = ctx.source.level
        val sampler = samplerFor(level)
        val estimate = sampler.surface(pos.x, pos.z)
        val dh = io.github.veelume.postroad.roads.gen.DhTerrain.column(level, pos.x, pos.z)?.let { "${it.top}${if (it.water) " (water)" else ""}${if (it.lava) " (lava)" else ""}" } ?: "none"
        val base = level.chunkSource.generator.getBaseHeight(pos.x, pos.z, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, level.chunkSource.randomState())
        val real = if (level.hasChunk(pos.x shr 4, pos.z shr 4)) level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.x, pos.z) else -1
        val biome = level.chunkSource.generator.biomeSource.getNoiseBiome(pos.x shr 2, base shr 2, pos.z shr 2, level.chunkSource.randomState().sampler()).unwrapKey().map { it.location().toString() }.orElse("?")
        val loaded = level.hasChunk(pos.x shr 4, pos.z shr 4)
        val top = if (loaded) net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos(pos.x, real - 1, pos.z)).block).path else "?"
        val chunkKey = net.minecraft.world.level.ChunkPos.asLong(pos.x shr 4, pos.z shr 4)
        val storage = io.github.veelume.postroad.roads.gen.RoadPlanStorage.get(ctx.source.server)
        val roadsHere = storage.roadsInChunk(level.dimension().location(), chunkKey)
        val runs = io.github.veelume.postroad.roads.gen.RoadPlanSnapshot.placementsAt(pos.x shr 4, pos.z shr 4).size
        val known = io.github.veelume.postroad.roads.gen.KnownTerrain.column(level.dimension().location(), pos.x, pos.z)?.let { "${it.top}${if (it.water) " (water)" else ""}" } ?: "none"
        val tagged = level.chunkSource.generator.biomeSource.getNoiseBiome(pos.x shr 2, base shr 2, pos.z shr 2, level.chunkSource.randomState().sampler()).`is`(net.minecraft.tags.BiomeTags.IS_OVERWORLD)
        val laid = io.github.veelume.postroad.roads.gen.RoadPlanSnapshot.laidRoads(chunkKey)
        // Every structure whose start reaches this column: what the planner may have run into.
        val others = if (loaded) {
            val registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
            level.structureManager().getAllStructuresAt(BlockPos(pos.x, base, pos.z)).keys
                .mapNotNull { registry.getKey(it)?.toString() }.sorted().joinToString(", ").ifEmpty { "none" }
        } else "?"
        ctx.source.sendSuccess({ Component.literal("(${pos.x}, ${pos.z}): estimate $estimate, own chunk $known, DH $dh, generator base $base, real ${if (real < 0) "unloaded" else real.toString()}, top $top, sea ${level.chunkSource.generator.seaLevel}, biome $biome (overworld-tag $tagged); chunk: ${roadsHere.size} planned road(s), $runs snapshot run(s), laid by the feature this session: $laid, builder-built ${roadsHere.any { it.builtChunks.contains(chunkKey) }}; structures here: $others") }, false)
        return 1
    }

    private fun roadsDebugTerrain(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val on = io.github.veelume.postroad.roads.gen.RoadDebug.toggleTerrain(player)
        ctx.source.sendSuccess({ Component.literal(if (on) "Terrain debug layer on: a cross per planner cell at its estimated surface — green flat, yellow slabs (≤1), orange stairs (≤2), red steep (≤4), purple impassable; blue water, magenta blocked, white road. A red tick means the estimate floats above the real ground, a blue tick that it is buried. Faint crosses are estimates, solid ones generated terrain from Distant Horizons." else "Terrain debug layer off.") }, false)
        return 1
    }

    private fun roadsDebugPieces(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val on = io.github.veelume.postroad.roads.gen.RoadDebug.togglePieces(player)
        ctx.source.sendSuccess({ Component.literal(if (on) "Piece debug layer on: every placement the plan is laid from within 96 blocks, outlined — cyan straight, magenta diagonal, yellow corner, amber bend, orange square. Green cube = first connector, red cube = second, white line between them. Labels near you name the piece." else "Piece debug layer off.") }, false)
        return 1
    }

    private fun roadsPlanner(ctx: CommandContext<CommandSourceStack>, on: Boolean): Int {
        io.github.veelume.postroad.roads.gen.RoadGen.pausePlanning(!on)
        ctx.source.sendSuccess({ Component.literal(if (on) "Planning resumed." else "Planning and road building paused; chunk generation still runs, so a measurement has the machine to itself.") }, true)
        return 1
    }

    /** Towns, then ground, then one plan: the real-terrain pipeline end to end. */
    private fun roadsPipeline(ctx: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = ctx.source
        val started = io.github.veelume.postroad.roads.gen.RoadPipeline.run(source.level, BlockPos.containing(source.position), radius) { message ->
            source.sendSuccess({ Component.literal(message) }, true)
        }
        if (!started) {
            source.sendFailure(Component.literal("The pipeline is already running (${io.github.veelume.postroad.roads.gen.RoadPipeline.stage})."))
            return 0
        }
        source.sendSuccess({ Component.literal("Pipeline started within $radius blocks: finding towns, then generating their corridors, then one planning pass. Progress in the log.") }, true)
        return 1
    }

    /** Finds towns the real-terrain way: candidate chunks generated to structure starts, then read. */
    private fun roadsScan(ctx: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = ctx.source
        val center = BlockPos.containing(source.position)
        val n = io.github.veelume.postroad.roads.gen.TownScan.scan(source.level, center, radius) { r ->
            source.sendSuccess({
                Component.literal(
                    "Town scan done: %d candidate chunk(s) in %.1f s — %d new town(s), %d new obstacle(s)."
                        .format(r.candidates, r.seconds, r.towns, r.obstacles)
                )
            }, true)
        }
        when (n) {
            -1 -> { source.sendFailure(Component.literal("A town scan is already running.")); return 0 }
            0 -> { source.sendSuccess({ Component.literal("No chunk within $radius blocks can hold a structure start.") }, false); return 0 }
            else -> source.sendSuccess({ Component.literal("Scanning $n candidate chunk(s) within $radius blocks at structure-starts. Result follows when it finishes.") }, true)
        }
        return n
    }

    private fun roadsMeasureStatus(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess({ Component.literal(io.github.veelume.postroad.roads.gen.PregenMeasure.status()) }, false)
        return 1
    }

    /**
     * Times generating a square of [radius] blocks well away from anything already generated, so the
     * figure is real generation rather than a cache read. `step` is `carvers` (terrain, what the
     * planner needs) or `starts` (structure starts only, what town-finding needs).
     */
    private fun roadsMeasure(ctx: CommandContext<CommandSourceStack>, radius: Int, step: String): Int {
        val status = when (step.lowercase()) {
            "starts", "structure_starts" -> net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS
            "carvers" -> net.minecraft.world.level.chunk.status.ChunkStatus.CARVERS
            "surface" -> net.minecraft.world.level.chunk.status.ChunkStatus.SURFACE
            else -> {
                ctx.source.sendFailure(Component.literal("Unknown step '$step'. One of: starts, surface, carvers"))
                return 0
            }
        }
        val plannerQuiet = io.github.veelume.postroad.roads.gen.RoadGen.planningPaused || !io.github.veelume.postroad.PostroadConfig.planEnabled
        if (!plannerQuiet) {
            ctx.source.sendFailure(Component.literal("Pause the planner first (`/postroad roads planner off`, or plan.enabled = false in the config), or its passes and builder compete for the very workers being measured."))
            return 0
        }
        // Far from spawn and from any corridor, and a fresh patch per run, so every chunk is generated
        // for the first time: a second run over the same ground would time a disk load, not generation.
        val here = BlockPos.containing(ctx.source.position)
        val nth = io.github.veelume.postroad.roads.gen.PregenMeasure.runs
        val center = BlockPos(here.x + 200_000 + nth * 20_000, here.y, here.z + 200_000)
        val chunks = io.github.veelume.postroad.roads.gen.Corridor.square(center, radius)
        val message = io.github.veelume.postroad.roads.gen.PregenMeasure.start(ctx.source.level, chunks, status, "square r=$radius at ${center.x}, ${center.z} (${step.lowercase()})")
        if (message == null) {
            ctx.source.sendFailure(Component.literal("A measurement is already running; `/postroad roads measure` shows it."))
            return 0
        }
        ctx.source.sendSuccess({ Component.literal(message) }, true)
        return 1
    }

    private fun roadsShowcase(ctx: CommandContext<CommandSourceStack>, family: String? = null): Int {
        val index = family?.let { f -> io.github.veelume.postroad.roads.gen.Families.NAMES.indexOfFirst { it.equals(f, true) } } ?: 0
        if (index < 0) {
            ctx.source.sendFailure(Component.literal("Unknown family '$family'. One of: ${io.github.veelume.postroad.roads.gen.Families.NAMES.joinToString(", ")}"))
            return 0
        }
        val name = io.github.veelume.postroad.roads.gen.Families.NAMES[index]
        val n = io.github.veelume.postroad.roads.gen.RoadBuilder.showcase(ctx.source.level, BlockPos.containing(ctx.source.position), index)
        ctx.source.sendSuccess({ Component.literal("$n piece(s) laid in the air east of here in the $name palettes, 12 blocks apart, each as authored — one row per tier going south: dirt nearest, then gravel, then paved. Lime block = beyond the first connector, red = beyond the others; turn on `/postroad roads debug pieces` for outlines and names.") }, true)
        return n
    }

    private fun roadsDebug(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val on = io.github.veelume.postroad.roads.gen.RoadDebug.toggle(player)
        ctx.source.sendSuccess({ Component.literal(if (on) "Road debug view on: orange planned, white built, green charted; yellow junctions, magenta towns, cyan nodes." else "Road debug view off.") }, false)
        return 1
    }

    private fun roadsExport(ctx: CommandContext<CommandSourceStack>): Int {
        val level = ctx.source.level
        val pos = BlockPos.containing(ctx.source.position)
        val file = io.github.veelume.postroad.roads.gen.RoadGen.exportImage(level, pos, io.github.veelume.postroad.PostroadConfig.planRadius + io.github.veelume.postroad.PostroadConfig.planMaxLink)
        ctx.source.sendSuccess({ Component.literal(if (file != null) "Plan drawn to $file" else "Nothing to draw: no planner data for this dimension, or a pass is running.") }, true)
        return if (file != null) 1 else 0
    }

    /**
     * Measurement: generates the chunk at (x, z) to the carvers step, records its column tops the way
     * the corridor pre-generation does, finishes the chunk, records again, and reports the difference
     * per column. Says whether the planner's own-chunk heights are the finished world's.
     */
    /**
     * Measures where the finished ground moves away from what the corridor pre-generation records: the chunk is
     * generated status by status (noise, surface, carvers, features, full) and each step's column tops are
     * compared with the previous step's. Needs a chunk that is not generated yet; on a generated one every
     * status returns the finished chunk and the histograms are all zero.
     */
    private fun roadsKnown(ctx: CommandContext<CommandSourceStack>, x: Int, z: Int): Int {
        val level = ctx.source.level
        val cx = x shr 4; val cz = z shr 4
        val before = level.chunkSource.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, true)?.persistedStatus
        val statuses = listOf(
            net.minecraft.world.level.chunk.status.ChunkStatus.NOISE, net.minecraft.world.level.chunk.status.ChunkStatus.SURFACE,
            net.minecraft.world.level.chunk.status.ChunkStatus.CARVERS, net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES,
            net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
        )
        val tops = ArrayList<io.github.veelume.postroad.roads.gen.KnownTerrain.ChunkTops>()
        // Per status: the block at each column's top (the first non-blocking position) and the one under it,
        // read from that status's own chunk — what changed, not only where.
        val atTop = ArrayList<Array<String>>(); val underTop = ArrayList<Array<String>>()
        fun name(state: net.minecraft.world.level.block.state.BlockState) = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).path
        for (st in statuses) {
            val chunk = level.chunkSource.getChunk(cx, cz, st, true) ?: run { ctx.source.sendFailure(Component.literal("no chunk at ${st.name}")); return 0 }
            val t = io.github.veelume.postroad.roads.gen.KnownTerrain.tops(chunk)
            tops.add(t)
            atTop.add(Array(256) { i -> name(chunk.getBlockState(BlockPos(cx * 16 + (i and 15), t.top[i].toInt(), cz * 16 + (i shr 4)))) })
            underTop.add(Array(256) { i -> name(chunk.getBlockState(BlockPos(cx * 16 + (i and 15), t.top[i] - 1, cz * 16 + (i shr 4)))) })
        }
        val lines = ArrayList<String>()
        lines.add("Chunk ($cx, $cz) was $before" + (if (before != null && before != net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY) " - already generated, every status is the finished chunk; pick an ungenerated one" else ""))
        for (k in 1 until statuses.size) {
            val a = tops[k - 1]; val b = tops[k]
            val hist = java.util.TreeMap<Int, Int>()
            val examples = ArrayList<String>()
            for (lz in 0 until 16) for (lx in 0 until 16) {
                val i = (lz shl 4) or lx
                val d = b.top[i] - a.top[i]
                hist.merge(d, 1, Int::plus)
                if (d != 0 && examples.size < 4) {
                    val bx = cx * 16 + lx; val bz = cz * 16 + lz
                    examples.add("($bx, $bz) ${a.top[i]} -> ${b.top[i]}: column was ${underTop[k - 1][i]} / ${atTop[k - 1][i]} at ${a.top[i] - 1}/${a.top[i]}, now ${underTop[k][i]} / ${atTop[k][i]} at ${b.top[i] - 1}/${b.top[i]}")
                }
            }
            lines.add("${statuses[k].name} minus ${statuses[k - 1].name}: " + hist.entries.joinToString(", ") { "${it.key}: ${it.value}" } + (if (examples.isEmpty()) "" else "; e.g. " + examples.joinToString("; ")))
        }
        ctx.source.sendSuccess({ Component.literal(lines.joinToString("\n")) }, false)
        return 1
    }

    private fun roadsTrace(ctx: CommandContext<CommandSourceStack>): Int {
        val msg = io.github.veelume.postroad.roads.gen.RoadGen.trace(ctx.source.level, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "pair"))
        ctx.source.sendSuccess({ Component.literal(msg) }, false)
        return 1
    }

    private fun roadsRebuild(ctx: CommandContext<CommandSourceStack>): Int {
        val n = io.github.veelume.postroad.roads.gen.RoadBuilder.requeueLoaded(ctx.source.server)
        ctx.source.sendSuccess({ Component.literal("$n loaded road chunk(s) queued for building.") }, true)
        return n
    }

    private fun roadsClear(ctx: CommandContext<CommandSourceStack>): Int {
        val ok = io.github.veelume.postroad.roads.gen.RoadGen.clear(ctx.source.server)
        ctx.source.sendSuccess({ Component.literal(if (ok) "Road plan cleared; uncharted generated paths removed." else "A pass is running; try again in a moment.") }, true)
        return if (ok) 1 else 0
    }

    private fun nodes(ctx: CommandContext<CommandSourceStack>): Int {
        val network = Network.get(ctx.source.server)
        if (network.nodes.isEmpty()) {
            ctx.source.sendSuccess({ Component.translatable("command.postroad.nodes.none") }, false)
            return 0
        }
        for (node in network.nodes.values) {
            ctx.source.sendSuccess({ Component.translatable("command.postroad.nodes.entry", node.name, node.kind, node.pos.toShortString(), node.pathId) }, false)
        }
        return network.nodes.size
    }

    private fun unlock(ctx: CommandContext<CommandSourceStack>): Int {
        val network = Network.get(ctx.source.server)
        if (network.postalUnlocked) {
            ctx.source.sendSuccess({ Component.translatable("message.postroad.charter.already") }, false)
            return 0
        }
        network.postalUnlocked = true
        network.record(LedgerEntry(FreshLoot.dayOf(ctx.source.level), ctx.source.textName, LedgerEntry.OP_CHARTER, 0, "network", "command"))
        ctx.source.sendSuccess({ Component.translatable("message.postroad.charter.unlocked", ctx.source.textName, "command") }, true)
        return 1
    }

    /** Every item the depot buys, cheapest first. Literal text: it is data, and it must not depend on client lang files. */
    private fun rates(ctx: CommandContext<CommandSourceStack>): Int {
        val map = BuiltInRegistries.ITEM.getDataMap(PostroadDataMaps.BUYBACK)
        if (map.isEmpty()) {
            ctx.source.sendSuccess({ Component.literal("The depot buys nothing (buyback data map is empty).") }, false)
            return 0
        }
        val lines = map.entries
            .sortedWith(compareBy({ it.value }, { it.key.location().toString() }))
            .map { (key, coins) -> "$coins × ${key.location()}" }
        ctx.source.sendSuccess({ Component.literal("Depot buys ${lines.size} items (coins per unit, fresh loot only):") }, false)
        lines.chunked(6).forEach { chunk ->
            ctx.source.sendSuccess({ Component.literal(chunk.joinToString("  ·  ")) }, false)
        }
        return lines.size
    }

    /** What the depot would make of the item in the main hand. */
    private fun inspect(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val stack = player.mainHandItem
        if (stack.isEmpty) {
            ctx.source.sendSuccess({ Component.translatable("command.postroad.inspect.empty") }, false)
            return 0
        }
        val today = FreshLoot.dayOf(ctx.source.level)
        val mark = FreshLoot.of(stack)
        val freshness: Component = when {
            mark == null -> Component.translatable("command.postroad.inspect.unmarked")
            FreshLoot.daysLeft(mark, today) > 0 ->
                Component.translatable("command.postroad.inspect.fresh", mark.origin, FreshLoot.daysLeft(mark, today))
            else -> Component.translatable("command.postroad.inspect.settled", mark.origin)
        }
        val rate = stack.itemHolder.getData(PostroadDataMaps.BUYBACK)
        val price: Component = if (rate == null) {
            Component.translatable("command.postroad.inspect.no_rate")
        } else {
            Component.translatable("command.postroad.inspect.rate", rate, rate.toLong() * stack.count)
        }
        ctx.source.sendSuccess({ Component.translatable("command.postroad.inspect", stack.count, stack.hoverName, freshness, price) }, false)
        return 1
    }

    private fun balance(ctx: CommandContext<CommandSourceStack>, playerName: String): Int {
        val network = Network.get(ctx.source.server)
        val wallet = network.balance(Network.playerAccount(playerName))
        val fund = network.balance(Network.ROAD_FUND)
        ctx.source.sendSuccess({ Component.translatable("command.postroad.balance", playerName, wallet, fund) }, false)
        return wallet.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun ledger(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val network = Network.get(ctx.source.server)
        val entries = network.ledger.takeLast(count)
        if (entries.isEmpty()) {
            ctx.source.sendSuccess({ Component.translatable("command.postroad.ledger.none") }, false)
            return 0
        }
        for (entry in entries) {
            ctx.source.sendSuccess({ DepotInteraction.describe(entry) }, false)
        }
        return entries.size
    }

    private fun grant(ctx: CommandContext<CommandSourceStack>, account: String, amount: Long): Int {
        val network = Network.get(ctx.source.server)
        val resolved = if (account.contains(':')) account else Network.playerAccount(account)
        network.credit(
            resolved, amount, FreshLoot.dayOf(ctx.source.level),
            ctx.source.textName, LedgerEntry.OP_GRANT, "command",
        )
        ctx.source.sendSuccess(
            { Component.translatable("command.postroad.grant", amount, resolved, network.balance(resolved)) },
            true,
        )
        return 1
    }
}
