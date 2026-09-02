package io.github.veelume.packcore.depot

import io.github.veelume.packcore.registry.PackcoreBlocks
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.function.Consumer

/**
 * Vanilla treats "sneaking with an item in hand" as a secondary use and never calls the
 * block's `useItemOn`, so pay-in and buyback would be unreachable in real play. This handler
 * runs before that decision, performs the depot interaction and cancels the event.
 */
object DepotEvents {
    fun register() {
        FORGE_BUS.addListener(PlayerInteractEvent.RightClickBlock::class.java, Consumer(::onRightClickBlock))
    }

    private fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        val player = event.entity
        if (!player.isShiftKeyDown || event.hand != InteractionHand.MAIN_HAND || event.itemStack.isEmpty) return
        val level = event.level
        if (!level.getBlockState(event.pos).`is`(PackcoreBlocks.DEPOT.get())) return

        if (level is ServerLevel) {
            val depot = level.getBlockEntity(event.pos) as? DepotBlockEntity ?: return
            DepotInteraction.useItem(level, depot, player, event.itemStack)
        }
        event.isCanceled = true
        event.cancellationResult = InteractionResult.sidedSuccess(level.isClientSide)
    }
}
