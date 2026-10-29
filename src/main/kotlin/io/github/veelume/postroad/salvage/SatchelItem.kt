package io.github.veelume.postroad.salvage

import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/** Use to open the satchel's grid ([SatchelMenu]): what goes in is sold where you stand. */
class SatchelItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (!level.isClientSide) {
            player.openMenu(SimpleMenuProvider({ id, inventory, _ -> SatchelMenu(id, inventory) }, Component.translatable("container.postroad.satchel")))
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }
}
