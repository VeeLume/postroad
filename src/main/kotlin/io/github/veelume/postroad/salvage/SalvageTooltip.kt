package io.github.veelume.postroad.salvage

import io.github.veelume.postroad.loot.Looted
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.function.Consumer

/**
 * Client-only tooltip lines: whether an item is looted, and what the satchel pays for it, so nobody
 * has to try every item. The salvage data maps and the looted mark are synced to clients for this.
 */
object SalvageTooltip {
    fun register() {
        FORGE_BUS.addListener(ItemTooltipEvent::class.java, Consumer(::onTooltip))
    }

    private fun onTooltip(event: ItemTooltipEvent) {
        val stack = event.itemStack
        if (Looted.isLooted(stack)) {
            event.toolTip.add(Component.translatable("tooltip.postroad.looted").withStyle(ChatFormatting.GRAY))
        }
        val coins = Salvage.quote(stack) ?: return
        val key = if (stack.count > 1) "tooltip.postroad.salvage_stack" else "tooltip.postroad.salvage"
        event.toolTip.add(Component.translatable(key, coins).withStyle(ChatFormatting.GOLD))
    }
}
