package io.github.veelume.packcore.depot

import io.github.veelume.packcore.loot.FreshLoot
import io.github.veelume.packcore.network.LedgerEntry
import io.github.veelume.packcore.network.Network
import io.github.veelume.packcore.registry.PackcoreDataMaps
import io.github.veelume.packcore.registry.PackcoreItems
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack

/** Server-side behaviour behind the depot block's clicks. */
object DepotInteraction {

    fun openStorage(level: ServerLevel, depot: DepotBlockEntity, player: Player): InteractionResult {
        val place = depot.place(level) ?: run {
            player.displayClientMessage(Component.translatable("message.packcore.depot.unregistered"), true)
            return InteractionResult.CONSUME
        }
        val container = Network.get(level.server).storageFor(place.id)
        val title = Component.translatable("container.packcore.depot", place.name)
        player.openMenu(SimpleMenuProvider({ id, inventory, _ -> ChestMenu.sixRows(id, inventory, container) }, title))
        return InteractionResult.CONSUME
    }

    fun showSummary(level: ServerLevel, depot: DepotBlockEntity, player: Player): InteractionResult {
        val place = depot.place(level) ?: run {
            player.displayClientMessage(Component.translatable("message.packcore.depot.unregistered"), true)
            return InteractionResult.CONSUME
        }
        val network = Network.get(level.server)
        val wallet = network.balance(Network.playerAccount(player.gameProfile.name))
        val fund = network.balance(Network.ROAD_FUND)
        player.displayClientMessage(
            Component.translatable("message.packcore.depot.summary", place.name, wallet, fund).withStyle(ChatFormatting.GOLD),
            false,
        )
        network.ledger.takeLast(5).forEach { player.displayClientMessage(describe(it), false) }
        return InteractionResult.CONSUME
    }

    fun useItem(level: ServerLevel, depot: DepotBlockEntity, player: Player, stack: ItemStack): ItemInteractionResult {
        if (stack.isEmpty) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        val place = depot.place(level) ?: run {
            player.displayClientMessage(Component.translatable("message.packcore.depot.unregistered"), true)
            return ItemInteractionResult.CONSUME
        }
        val network = Network.get(level.server)
        val day = FreshLoot.dayOf(level)
        val actor = player.gameProfile.name
        val account = Network.playerAccount(actor)

        if (stack.`is`(PackcoreItems.COIN.get())) {
            val amount = stack.count.toLong()
            stack.shrink(stack.count)
            network.credit(account, amount, day, actor, LedgerEntry.OP_PAY_IN, place.name)
            player.displayClientMessage(
                Component.translatable("message.packcore.depot.paid_in", amount, network.balance(account)),
                true,
            )
            return ItemInteractionResult.CONSUME
        }

        val rate = stack.itemHolder.getData(PackcoreDataMaps.BUYBACK)
        // Refusals go to chat, not the action bar: they carry the reason and are easy to miss otherwise.
        if (rate == null) {
            player.displayClientMessage(
                Component.translatable("message.packcore.depot.not_bought", stack.hoverName).withStyle(ChatFormatting.YELLOW),
                false,
            )
            return ItemInteractionResult.CONSUME
        }
        if (!FreshLoot.isFresh(stack, day)) {
            val key = if (FreshLoot.of(stack) == null) "message.packcore.depot.unmarked" else "message.packcore.depot.settled"
            player.displayClientMessage(
                Component.translatable(key, stack.hoverName).withStyle(ChatFormatting.YELLOW),
                false,
            )
            return ItemInteractionResult.CONSUME
        }
        val count = stack.count
        val amount = rate.toLong() * count
        val itemId = stack.itemHolder.registeredName
        stack.shrink(count)
        if (amount > 0) {
            network.credit(account, amount, day, actor, LedgerEntry.OP_BUYBACK, "$count×$itemId @ ${place.name}")
        }
        player.displayClientMessage(
            Component.translatable("message.packcore.depot.bought", count, stack.hoverName, amount, network.balance(account)),
            true,
        )
        return ItemInteractionResult.CONSUME
    }

    fun describe(entry: LedgerEntry): Component = Component.translatable(
        "message.packcore.ledger.entry",
        entry.day, entry.actor, entry.op, entry.amount, entry.account, entry.note,
    ).withStyle(ChatFormatting.GRAY)
}
