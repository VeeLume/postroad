package io.github.veelume.packcore.loot

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.function.Consumer

/** Client-only: one tooltip line while an item is still fresh. */
object FreshLootTooltip {
    fun register() {
        FORGE_BUS.addListener(ItemTooltipEvent::class.java, Consumer(::onTooltip))
    }

    private fun onTooltip(event: ItemTooltipEvent) {
        val mark = FreshLoot.of(event.itemStack) ?: return
        val level = event.entity?.level() ?: return
        val left = FreshLoot.daysLeft(mark, FreshLoot.dayOf(level))
        if (left > 0) {
            event.toolTip.add(
                Component.translatable("tooltip.packcore.fresh_loot", left).withStyle(ChatFormatting.GOLD),
            )
        }
    }
}
