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
                        .then(Commands.literal("debug").requires { it.hasPermission(2) }.executes { roadsDebug(it) })
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

    /** Sampler vs. world at one column: the planner's surface estimate against the generator and the real heightmap. */
    private fun roadsProbe(ctx: CommandContext<CommandSourceStack>, pos: BlockPos): Int {
        val level = ctx.source.level
        val sampler = io.github.veelume.postroad.roads.gen.WorldTerrainSampler(level, null, 4)
        val estimate = sampler.surface(pos.x, pos.z)
        val base = level.chunkSource.generator.getBaseHeight(pos.x, pos.z, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, level.chunkSource.randomState())
        val real = if (level.hasChunk(pos.x shr 4, pos.z shr 4)) level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.x, pos.z) else -1
        val biome = level.chunkSource.generator.biomeSource.getNoiseBiome(pos.x shr 2, base shr 2, pos.z shr 2, level.chunkSource.randomState().sampler()).unwrapKey().map { it.location().toString() }.orElse("?")
        ctx.source.sendSuccess({ Component.literal("(${pos.x}, ${pos.z}): estimate $estimate, generator base $base, real ${if (real < 0) "unloaded" else real.toString()}, sea ${level.chunkSource.generator.seaLevel}, biome $biome") }, false)
        return 1
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
