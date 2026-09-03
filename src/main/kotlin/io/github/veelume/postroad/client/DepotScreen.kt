package io.github.veelume.postroad.client

import io.github.veelume.postroad.menu.DepotActionPayload
import io.github.veelume.postroad.menu.DepotMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The depot screen: one view. Vanilla 6-row chest look with a two-row button strip between the
 * grid and the inventory. Row one: Select, Loot, Dump, Take, Send. Row two: the destination.
 * Every button is a [DepotActionPayload]; marked stacks are highlighted from the synced state.
 */
class DepotScreen(menu: DepotMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<DepotMenu>(menu, inventory, title) {

    private lateinit var prevPage: Button
    private lateinit var nextPage: Button
    private lateinit var homeButton: Button
    private lateinit var selectButton: Button
    private lateinit var lootButton: Button
    private lateinit var dumpButton: Button
    private lateinit var takeButton: Button
    private lateinit var sendButton: Button
    private lateinit var destinationPrev: Button
    private lateinit var destinationNext: Button
    private lateinit var expressButton: Button
    private lateinit var sellButton: Button
    private lateinit var travelButton: Button

    init {
        imageWidth = 176
        imageHeight = GRID_BOTTOM + STRIP + 96
        inventoryLabelY = DepotMenu.INVENTORY_Y - 11
    }

    private fun action(action: Int, value: Int = 0): Button.OnPress = Button.OnPress {
        PacketDistributor.sendToServer(DepotActionPayload(action, value))
    }

    private fun button(key: String, action: Int, x: Int, y: Int, w: Int, h: Int, tooltip: String? = null): Button {
        val builder = Button.builder(Component.translatable(key), action(action)).bounds(leftPos + x, topPos + y, w, h)
        if (tooltip != null) builder.tooltip(Tooltip.create(Component.translatable(tooltip)))
        return addRenderableWidget(builder.build())
    }

    override fun init() {
        super.init()
        prevPage = addRenderableWidget(Button.builder(Component.literal("<"), action(DepotMenu.ACTION_PAGE, -1)).bounds(leftPos + imageWidth - 34, topPos + 4, 12, 12).build())
        nextPage = addRenderableWidget(Button.builder(Component.literal(">"), action(DepotMenu.ACTION_PAGE, 1)).bounds(leftPos + imageWidth - 20, topPos + 4, 12, 12).build())

        val row1 = GRID_BOTTOM + 3
        selectButton = button("screen.postroad.depot.select", DepotMenu.ACTION_SELECT_MODE, 7, row1, 40, 16, "screen.postroad.depot.select.tooltip")
        lootButton = button("screen.postroad.depot.loot", DepotMenu.ACTION_SELECT_LOOT, 49, row1, 32, 16, "screen.postroad.depot.loot.tooltip")
        dumpButton = button("screen.postroad.depot.dump", DepotMenu.ACTION_DUMP, 83, row1, 34, 16, "screen.postroad.depot.dump.tooltip")
        takeButton = button("screen.postroad.depot.take", DepotMenu.ACTION_TAKE_ALL, 119, row1, 34, 16, "screen.postroad.depot.take.tooltip")

        val row2 = row1 + 19
        destinationPrev = addRenderableWidget(Button.builder(Component.literal("<"), action(DepotMenu.ACTION_DESTINATION, -1)).bounds(leftPos + 7, topPos + row2, 12, 16).build())
        destinationNext = addRenderableWidget(Button.builder(Component.literal(">"), action(DepotMenu.ACTION_DESTINATION, 1)).bounds(leftPos + 105, topPos + row2, 12, 16).build())
        sendButton = button("screen.postroad.depot.send", DepotMenu.ACTION_SEND, 119, row2, 50, 16)

        val row3 = row2 + 19
        expressButton = button("screen.postroad.depot.express", DepotMenu.ACTION_EXPRESS, 7, row3, 56, 16, "screen.postroad.depot.express.tooltip")
        sellButton = button("screen.postroad.depot.sell", DepotMenu.ACTION_SELL, 65, row3, 40, 16, "screen.postroad.depot.sell.tooltip")
        homeButton = button("screen.postroad.depot.set_home", DepotMenu.ACTION_SET_HOME, 107, row3, 34, 16, "screen.postroad.depot.set_home.tooltip")
        travelButton = button("screen.postroad.depot.travel", DepotMenu.ACTION_TRAVEL, 143, row3, 26, 16, "screen.postroad.depot.travel.tooltip")
        updateWidgets()
    }

    override fun containerTick() {
        super.containerTick()
        updateWidgets()
    }

    private fun updateWidgets() {
        val state = menu.state
        val paged = menu.unlocked && menu.pageCount > 1
        prevPage.visible = paged
        nextPage.visible = paged
        homeButton.visible = !menu.isRemotePage()
        travelButton.visible = !menu.isRemotePage()
        homeButton.active = state.homeName != state.townName

        selectButton.message = Component.translatable(if (menu.selectMode) "screen.postroad.depot.select_done" else "screen.postroad.depot.select")
        val canSend = menu.unlocked && state.destinationNames.isNotEmpty()
        lootButton.active = canSend
        destinationPrev.visible = canSend
        destinationNext.visible = canSend
        destinationPrev.active = state.destinationNames.size > 1
        destinationNext.active = state.destinationNames.size > 1
        sendButton.visible = canSend
        sendButton.active = state.selected.isNotEmpty()
        sendButton.message = if (state.selected.isEmpty()) Component.translatable("screen.postroad.depot.send")
        else Component.translatable("screen.postroad.depot.send_n", state.selected.size)
        val timing = when {
            state.selected.isEmpty() -> ""
            state.valuablesDays > 0 && menu.express && state.expressCost > 0 -> Component.translatable("screen.postroad.depot.timing.express", state.expressCost).string
            state.valuablesDays > 0 -> Component.translatable("screen.postroad.depot.timing.days", state.valuablesDays).string
            else -> Component.translatable("screen.postroad.depot.timing.instant").string
        }
        val transit = if (state.transitLines.isEmpty()) "" else state.transitLines.joinToString("\n", prefix = "\n" + Component.translatable("screen.postroad.depot.in_transit").string + "\n")
        sendButton.tooltip = Tooltip.create(Component.literal((timing.ifEmpty { Component.translatable("screen.postroad.depot.send.tooltip").string }) + transit))

        expressButton.visible = canSend
        expressButton.active = state.expressCost > 0
        expressButton.message = when {
            state.expressCost > 0 && menu.express -> Component.translatable("screen.postroad.depot.express_on", state.expressCost)
            state.expressCost > 0 -> Component.translatable("screen.postroad.depot.express_off", state.expressCost)
            else -> Component.translatable("screen.postroad.depot.express")
        }
        sellButton.active = state.sellCount > 0
        sellButton.message = if (state.sellCount > 0) Component.translatable("screen.postroad.depot.sell_n", state.sellCount, state.sellValue)
        else Component.translatable("screen.postroad.depot.sell")
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Top: title + 6 rows. Middle: the button strip. Bottom: the inventory part of the texture.
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, GRID_BOTTOM)
        graphics.blit(TEXTURE, leftPos, topPos + GRID_BOTTOM, 0, 17, 7, STRIP)
        graphics.blit(TEXTURE, leftPos + imageWidth - 7, topPos + GRID_BOTTOM, imageWidth - 7, 17, 7, STRIP)
        graphics.fill(leftPos + 7, topPos + GRID_BOTTOM, leftPos + imageWidth - 7, topPos + GRID_BOTTOM + STRIP, PANEL)
        graphics.blit(TEXTURE, leftPos, topPos + GRID_BOTTOM + STRIP, 0, 126, imageWidth, 96)

        if (menu.isRemotePage()) {
            for (index in 0 until DepotMenu.GRID) {
                val slot = menu.slots[index]
                if (slot.hasItem() && !menu.mayMove(slot.item)) {
                    graphics.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, LOCKED)
                }
            }
        }
        for (index in menu.state.selected) {
            val slot = menu.slots.getOrNull(index) ?: continue
            graphics.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, SELECTED)
        }
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val state = menu.state
        val name = state.pageNames.getOrElse(menu.page) { state.townName }
        val paged = menu.unlocked && menu.pageCount > 1
        val walletWidth = 20 + font.width(state.wallet.toString())
        val available = imageWidth - 8 - (if (paged) 34 else 8) - walletWidth - 6
        val marker = if (menu.isRemotePage()) "" else "» "
        val counter = if (paged) " (${menu.page + 1}/${menu.pageCount})" else ""
        val full = marker + name + counter
        val header = if (font.width(full) <= available) full else font.plainSubstrByWidth(marker + name, available - font.width("…")) + "…"
        graphics.drawString(font, header, titleLabelX, titleLabelY, TEXT, false)
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false)

        val wallet = state.wallet.toString()
        val pagedTitle = menu.unlocked && menu.pageCount > 1
        val walletRight = if (pagedTitle) imageWidth - 34 - 4 else imageWidth - 8
        graphics.renderItem(COIN, walletRight - 16, 2)
        graphics.drawString(font, wallet, walletRight - 18 - font.width(wallet), 6, TEXT_STRONG, false)

        val row2 = GRID_BOTTOM + 3 + 19
        if (menu.unlocked && state.destinationNames.isNotEmpty()) {
            val destination = state.destinationNames.getOrElse(state.destinationIndex) { "—" }
            val text = font.plainSubstrByWidth(destination, 82)
            graphics.drawString(font, text, 62 - font.width(text) / 2, row2 + 4, TEXT_STRONG, false)
        } else if (!menu.unlocked) {
            graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.postroad.depot.not_chartered").string, 160), 9, row2 + 4, TEXT_MUTED, false)
        }
    }

    override fun getTooltipFromContainerItem(stack: ItemStack): MutableList<Component> {
        val tooltip = super.getTooltipFromContainerItem(stack)
        val slot = hoveredSlot
        if (slot != null && slot.index < DepotMenu.GRID && menu.isRemotePage() && !menu.mayMove(stack)) {
            tooltip.add(Component.translatable("screen.postroad.depot.locked", menu.state.pageNames.getOrElse(menu.page) { "?" }))
        }
        if (menu.selectMode && slot != null) {
            tooltip.add(Component.translatable(if (slot.index in menu.state.selected) "screen.postroad.depot.click_unmark" else "screen.postroad.depot.click_mark"))
        }
        return tooltip
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    companion object {
        private val TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png")
        private val COIN: ItemStack by lazy { ItemStack(io.github.veelume.postroad.registry.PostroadItems.COIN.get()) }
        private const val GRID_BOTTOM = 17 + 6 * 18 + 1
        private const val STRIP = 63
        private const val PANEL = 0xFFC6C6C6.toInt()
        private const val LOCKED = 0x99404040.toInt()
        private const val SELECTED = 0x8040C040.toInt()
        private const val TEXT = 0x404040
        private const val TEXT_STRONG = 0x202020
        private const val TEXT_MUTED = 0x606060
    }
}
