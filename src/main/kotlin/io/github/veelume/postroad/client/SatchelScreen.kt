package io.github.veelume.postroad.client

import io.github.veelume.postroad.registry.PostroadItems
import io.github.veelume.postroad.salvage.Salvage
import io.github.veelume.postroad.salvage.SatchelMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot

/**
 * A one-row vanilla chest with a sell button on the inventory label's line, showing the row's total.
 * Every slot, the player's inventory included, shows what the satchel pays for its stack; what it does
 * not buy is dimmed, so the choice is visible before anything is moved.
 */
class SatchelScreen(menu: SatchelMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<SatchelMenu>(menu, inventory, title) {

    private lateinit var sellButton: Button

    init {
        imageHeight = TOP_H + BOTTOM_H
        inventoryLabelY = imageHeight - 94
    }

    override fun init() {
        super.init()
        sellButton = addRenderableWidget(
            Button.builder(Component.translatable("screen.postroad.satchel.sell")) {
                minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, SatchelMenu.BUTTON_SELL)
            }
                .bounds(leftPos + imageWidth - 8 - BUTTON_W, topPos + inventoryLabelY - 2, BUTTON_W, 12)
                .tooltip(Tooltip.create(Component.translatable("screen.postroad.satchel.sell.tooltip")))
                .build(),
        )
        refresh()
    }

    override fun containerTick() {
        super.containerTick()
        refresh()
    }

    private fun refresh() {
        val total = menu.total()
        sellButton.active = total > 0
        sellButton.message = if (total > 0) Component.translatable("screen.postroad.satchel.sell_n", total)
        else Component.translatable("screen.postroad.satchel.sell")
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun renderSlot(graphics: GuiGraphics, slot: Slot) {
        super.renderSlot(graphics, slot)
        val stack = slot.item
        if (stack.isEmpty) return
        val coins = if (stack.`is`(PostroadItems.COIN.get())) stack.count.toLong() else Salvage.quote(stack)
        // Above the item and its count, which render at up to z 200.
        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 250f)
        if (coins == null) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, DIM)
        } else {
            graphics.pose().translate(slot.x.toFloat(), slot.y.toFloat(), 0f)
            graphics.pose().scale(0.75f, 0.75f, 1f)
            graphics.drawString(font, coins.toString(), 1, 1, PRICE, true)
        }
        graphics.pose().popPose()
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, TOP_H)
        graphics.blit(TEXTURE, leftPos, topPos + TOP_H, 0, 126, imageWidth, BOTTOM_H)
    }

    companion object {
        private val TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png")
        /** Header plus one row of the chest texture, then its inventory part, as vanilla's container screen cuts it. */
        private const val TOP_H = 17 + 18
        private const val BOTTOM_H = 96
        private const val BUTTON_W = 64
        private const val DIM = 0xA0303030.toInt()
        private const val PRICE = 0xFFE0A030.toInt()
    }
}
