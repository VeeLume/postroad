package io.github.veelume.packcore.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.veelume.packcore.depot.DepotInteraction
import io.github.veelume.packcore.loot.FreshLoot
import io.github.veelume.packcore.network.LedgerEntry
import io.github.veelume.packcore.network.Network
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/packcore places | balance [player] | ledger [count] | grant <account> <amount>`.
 * Reading is open to everyone; `grant` is op-only and exists for testing.
 */
object PackcoreCommands {

    fun register(event: RegisterCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("packcore")
                .then(Commands.literal("places").executes { places(it) })
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
            ctx.source.sendSuccess({ Component.translatable("command.packcore.places.none") }, false)
            return 0
        }
        ctx.source.sendSuccess({ Component.translatable("command.packcore.places.header", network.places.size) }, false)
        for (place in network.places.values) {
            ctx.source.sendSuccess({
                Component.translatable(
                    "command.packcore.places.entry",
                    place.name, place.type, place.culture, place.pos.x, place.pos.y, place.pos.z, place.dimension.toString(),
                )
            }, false)
        }
        return network.places.size
    }

    private fun balance(ctx: CommandContext<CommandSourceStack>, playerName: String): Int {
        val network = Network.get(ctx.source.server)
        val wallet = network.balance(Network.playerAccount(playerName))
        val fund = network.balance(Network.ROAD_FUND)
        ctx.source.sendSuccess({ Component.translatable("command.packcore.balance", playerName, wallet, fund) }, false)
        return wallet.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun ledger(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val network = Network.get(ctx.source.server)
        val entries = network.ledger.takeLast(count)
        if (entries.isEmpty()) {
            ctx.source.sendSuccess({ Component.translatable("command.packcore.ledger.none") }, false)
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
            { Component.translatable("command.packcore.grant", amount, resolved, network.balance(resolved)) },
            true,
        )
        return 1
    }
}
