package io.github.veelume.postroad.depot

import io.github.veelume.postroad.advancement.PostroadAdvancements
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.network.LedgerEntry
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.network.Place
import io.github.veelume.postroad.registry.PostroadDataMaps
import io.github.veelume.postroad.registry.PostroadItems
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack

/** Server-side behaviour behind the depot block's clicks. */
object DepotInteraction {

    /** Everything a depot click has in common: the player is now known, and has used a depot. */
    private fun touch(level: ServerLevel, player: Player): Network {
        val network = Network.get(level.server)
        network.seenPlayer(player.gameProfile.name)
        (player as? ServerPlayer)?.let { PostroadAdvancements.award(it, PostroadAdvancements.DEPOT_USED) }
        return network
    }

    private fun unregistered(player: Player) {
        player.displayClientMessage(Component.translatable("message.postroad.depot.unregistered"), true)
    }

    fun openStorage(level: ServerLevel, depot: DepotBlockEntity, player: Player): InteractionResult {
        val place = depot.place(level) ?: run { unregistered(player); return InteractionResult.CONSUME }
        val network = touch(level, player)
        val container = network.storageFor(place.id)
        val title = Component.translatable("container.postroad.depot", place.name)
        player.openMenu(SimpleMenuProvider({ id, inventory, _ -> ChestMenu.sixRows(id, inventory, container) }, title))
        return InteractionResult.CONSUME
    }

    fun showSummary(level: ServerLevel, depot: DepotBlockEntity, player: Player): InteractionResult {
        val place = depot.place(level) ?: run { unregistered(player); return InteractionResult.CONSUME }
        val network = touch(level, player)
        val wallet = network.balance(Network.playerAccount(player.gameProfile.name))
        val fund = network.balance(Network.ROAD_FUND)
        player.displayClientMessage(
            Component.translatable("message.postroad.depot.summary", place.name, wallet, fund).withStyle(ChatFormatting.GOLD),
            false,
        )
        val home = network.homeOf(player.gameProfile.name)
        player.displayClientMessage(
            Component.translatable(
                if (network.postalUnlocked) "message.postroad.depot.network_on" else "message.postroad.depot.network_off",
                home?.name ?: Component.translatable("message.postroad.depot.no_home"),
            ).withStyle(ChatFormatting.GRAY),
            false,
        )
        network.ledger.takeLast(5).forEach { player.displayClientMessage(describe(it), false) }
        return InteractionResult.CONSUME
    }

    fun useItem(level: ServerLevel, depot: DepotBlockEntity, player: Player, stack: ItemStack): ItemInteractionResult {
        if (stack.isEmpty) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        val place = depot.place(level) ?: run { unregistered(player); return ItemInteractionResult.CONSUME }
        val network = touch(level, player)
        val day = FreshLoot.dayOf(level)
        val actor = player.gameProfile.name
        val account = Network.playerAccount(actor)

        if (stack.`is`(PostroadItems.POSTAL_CHARTER.get())) {
            return applyCharter(network, place, player, stack, day)
        }

        if (stack.`is`(PostroadItems.COIN.get())) {
            val amount = stack.count.toLong()
            stack.shrink(stack.count)
            network.credit(account, amount, day, actor, LedgerEntry.OP_PAY_IN, place.name)
            player.displayClientMessage(
                Component.translatable("message.postroad.depot.paid_in", amount, network.balance(account)),
                true,
            )
            return ItemInteractionResult.CONSUME
        }

        val rate = stack.itemHolder.getData(PostroadDataMaps.BUYBACK)
        // Refusals go to chat, not the action bar: they carry the reason and are easy to miss otherwise.
        if (rate == null) {
            player.displayClientMessage(
                Component.translatable("message.postroad.depot.not_bought", stack.hoverName).withStyle(ChatFormatting.YELLOW),
                false,
            )
            return ItemInteractionResult.CONSUME
        }
        if (!FreshLoot.isFresh(stack, day)) {
            val key = if (FreshLoot.of(stack) == null) "message.postroad.depot.unmarked" else "message.postroad.depot.settled"
            player.displayClientMessage(
                Component.translatable(key, stack.hoverName).withStyle(ChatFormatting.YELLOW),
                false,
            )
            return ItemInteractionResult.CONSUME
        }
        val count = stack.count
        val amount = rate.toLong() * count
        val itemId = stack.itemHolder.registeredName
        val name = stack.hoverName
        stack.shrink(count)
        if (amount > 0) {
            network.credit(account, amount, day, actor, LedgerEntry.OP_BUYBACK, "$count×$itemId @ ${place.name}")
        }
        player.displayClientMessage(
            Component.translatable("message.postroad.depot.bought", count, name, amount, network.balance(account)),
            true,
        )
        return ItemInteractionResult.CONSUME
    }

    private fun applyCharter(network: Network, place: Place, player: Player, stack: ItemStack, day: Long): ItemInteractionResult {
        if (network.postalUnlocked) {
            player.displayClientMessage(
                Component.translatable("message.postroad.charter.already").withStyle(ChatFormatting.YELLOW),
                false,
            )
            return ItemInteractionResult.CONSUME
        }
        stack.shrink(1)
        network.postalUnlocked = true
        network.record(LedgerEntry(day, player.gameProfile.name, LedgerEntry.OP_CHARTER, 0, "network", place.name))
        (player as? ServerPlayer)?.let { PostroadAdvancements.award(it, PostroadAdvancements.POSTAL_NETWORK) }
        player.server?.playerList?.broadcastSystemMessage(
            Component.translatable("message.postroad.charter.unlocked", player.gameProfile.name, place.name).withStyle(ChatFormatting.GOLD),
            false,
        )
        return ItemInteractionResult.CONSUME
    }

    fun describe(entry: LedgerEntry): Component = Component.translatable(
        "message.postroad.ledger.entry",
        entry.day, entry.actor, entry.op, entry.amount, entry.account, entry.note,
    ).withStyle(ChatFormatting.GRAY)
}
