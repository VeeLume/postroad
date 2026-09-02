package io.github.veelume.postroad.loot

import io.github.veelume.postroad.registry.PostroadDataMaps
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.function.Consumer

/**
 * Client-only tooltip lines: freshness while the stamp still counts, and what the depot pays
 * for the item so nobody has to test every item at a depot. The buyback data map is synced
 * to clients for this.
 */
object FreshLootTooltip {
    fun register() {
        FORGE_BUS.addListener(ItemTooltipEvent::class.java, Consumer(::onTooltip))
    }

    private fun onTooltip(event: ItemTooltipEvent) {
        val stack = event.itemStack
        val level = event.entity?.level() ?: return
        val mark = FreshLoot.of(stack)
        val daysLeft = mark?.let { FreshLoot.daysLeft(it, FreshLoot.dayOf(level)) } ?: 0L
        val fresh = daysLeft > 0
        val rate = stack.itemHolder.getData(PostroadDataMaps.BUYBACK)

        if (fresh) {
            event.toolTip.add(
                Component.translatable("tooltip.postroad.fresh_loot", daysLeft).withStyle(ChatFormatting.GOLD),
            )
        }
        if (rate != null) {
            val key = if (fresh) "tooltip.postroad.buyback" else "tooltip.postroad.buyback_if_fresh"
            val style = if (fresh) ChatFormatting.GOLD else ChatFormatting.GRAY
            event.toolTip.add(Component.translatable(key, rate, rate.toLong() * stack.count).withStyle(style))
        }
    }
}
