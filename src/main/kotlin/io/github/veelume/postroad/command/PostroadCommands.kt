package io.github.veelume.postroad.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.veelume.postroad.depot.DepotInteraction
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.network.LedgerEntry
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.registry.PostroadDataMaps
import net.minecraft.commands.CommandSourceStack
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
