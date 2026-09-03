package io.github.veelume.postroad.depot

import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.network.LedgerEntry
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.registry.PostroadDataMaps
import net.minecraft.world.item.ItemStack

/** The depot's buyback rule in one place: fresh loot with a data-map rate, paid per unit. */
object Buyback {
    /** Coins the depot would pay for [stack] right now, or null if it does not buy it. */
    fun quote(stack: ItemStack, today: Long): Long? {
        if (stack.isEmpty) return null
        val rate = stack.itemHolder.getData(PostroadDataMaps.BUYBACK) ?: return null
        if (!FreshLoot.isFresh(stack, today)) return null
        return rate.toLong() * stack.count
    }

    /** Sells the whole [stack]; returns the coins credited, or null if refused. Caller clears the stack. */
    fun sell(network: Network, actor: String, placeName: String, stack: ItemStack, today: Long): Long? {
        val amount = quote(stack, today) ?: return null
        val itemId = stack.itemHolder.registeredName
        if (amount > 0) {
            network.credit(Network.playerAccount(actor), amount, today, actor, LedgerEntry.OP_BUYBACK, "${stack.count}×$itemId @ $placeName")
        }
        return amount
    }
}
