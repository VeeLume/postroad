package io.github.veelume.postroad.salvage

import io.github.veelume.postroad.network.GameDay
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.registry.PostroadItems
import io.github.veelume.postroad.registry.PostroadMenus
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * One row of goods above the player's inventory. Nothing is kept: the row is a scratch space, sold by
 * [BUTTON_SELL] (vanilla's container-button packet) and handed back on close, like a crafting grid.
 * It takes only what [Salvage] buys, and coins, which are paid in. Both sides quote with [Salvage], so
 * the screen shows the server's price on every slot before anything is moved.
 */
class SatchelMenu(containerId: Int, val playerInventory: Inventory) : AbstractContainerMenu(PostroadMenus.SATCHEL.get(), containerId) {
    private val goods = SimpleContainer(SLOTS)

    init {
        for (col in 0 until SLOTS) {
            // Only what the satchel takes goes in, so the row never holds anything it will not sell.
            addSlot(object : Slot(goods, col, 8 + col * 18, 18) {
                override fun mayPlace(stack: ItemStack): Boolean = Salvage.takes(stack)
            })
        }
        for (row in 0 until 3) for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, INVENTORY_Y + row * 18))
        }
        for (col in 0 until 9) addSlot(Slot(playerInventory, col, 8 + col * 18, HOTBAR_Y))
    }

    /** Coins the row would bring: salvage plus coins to pay in. */
    fun total(): Long = (0 until SLOTS).sumOf { slot ->
        val stack = goods.getItem(slot)
        if (stack.`is`(PostroadItems.COIN.get())) stack.count.toLong() else Salvage.quote(stack) ?: 0L
    }

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        if (id != BUTTON_SELL) return false
        if (player is ServerPlayer) sell(player)
        return true
    }

    private fun sell(player: ServerPlayer) {
        val network = Network.get(player.server)
        val name = player.gameProfile.name
        val stacks = (0 until SLOTS).map { goods.getItem(it) }
        val sale = Salvage.sell(network, name, "satchel", stacks, GameDay.of(player.serverLevel()))
        if (sale.coins == 0L && sale.paidIn == 0L) {
            player.displayClientMessage(Component.translatable("message.postroad.satchel.nothing").withStyle(ChatFormatting.YELLOW), true)
            return
        }
        for (slot in 0 until SLOTS) if (Salvage.takes(goods.getItem(slot))) goods.setItem(slot, ItemStack.EMPTY)
        player.displayClientMessage(
            Component.translatable("message.postroad.satchel.sold", sale.items, sale.coins, sale.paidIn, network.balance(Network.playerAccount(name)))
                .withStyle(ChatFormatting.GOLD),
            true,
        )
        broadcastChanges()
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val copy = stack.copy()
        val moved = if (index < SLOTS) moveItemStackTo(stack, SLOTS, slots.size, true)
        else Salvage.takes(stack) && moveItemStackTo(stack, 0, SLOTS, false)
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return copy
    }

    override fun removed(player: Player) {
        super.removed(player)
        clearContainer(player, goods)
    }

    override fun stillValid(player: Player): Boolean = player.isAlive

    companion object {
        const val SLOTS = 9
        const val BUTTON_SELL = 0
        const val INVENTORY_Y = 49
        const val HOTBAR_Y = 107
    }
}
