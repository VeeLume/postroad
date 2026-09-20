package io.github.veelume.postroad.client

import io.github.veelume.postroad.menu.DepotActionPayload
import io.github.veelume.postroad.menu.DepotMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.Rect2i
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The depot screen: a plain vanilla 6-row chest with the depot's controls in a panel attached to
 * its right. The body stays 176x222 so the screen fits at every GUI scale; the column hangs outside
 * that rectangle, which is all a recipe viewer can see of a container screen — [columnArea] hands it
 * to them (see `compat/PostroadEmiPlugin`) and [hasClickedOutside] keeps a click on the column from
 * throwing the carried stack away.
 *
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
        imageWidth = BODY_W
        imageHeight = BODY_H
        inventoryLabelY = DepotMenu.INVENTORY_Y - 12
    }

    /** The control column in window coordinates, for anything that has to keep clear of it. */
    fun columnArea(): Rect2i = Rect2i(leftPos + COLUMN_X, topPos + COLUMN_TOP, COLUMN_W, COLUMN_H)

    private fun action(action: Int, value: Int = 0): Button.OnPress = Button.OnPress {
        PacketDistributor.sendToServer(DepotActionPayload(action, value))
    }

    private fun button(key: String, action: Int, x: Int, y: Int, w: Int, tooltip: String? = null): Button {
        val builder = Button.builder(Component.translatable(key), action(action)).bounds(leftPos + x, topPos + y, w, BH)
        if (tooltip != null) builder.tooltip(Tooltip.create(Component.translatable(tooltip)))
        return addRenderableWidget(builder.build())
    }

    private fun arrow(label: String, action: Int, value: Int, x: Int, y: Int, w: Int): Button =
        addRenderableWidget(Button.builder(Component.literal(label), action(action, value)).bounds(leftPos + x, topPos + y, w, BH).build())

    override fun init() {
        super.init()
        // Body and column are centred together, the way the recipe book shifts a vanilla screen.
        leftPos -= COLUMN_SPAN / 2

        prevPage = arrow("<", DepotMenu.ACTION_PAGE, -1, BX, PAGE_Y, HALF)
        nextPage = arrow(">", DepotMenu.ACTION_PAGE, 1, BX + HALF + 2, PAGE_Y, HALF)

        dumpButton = button("screen.postroad.depot.dump", DepotMenu.ACTION_DUMP, BX, MOVE_Y, HALF, "screen.postroad.depot.dump.tooltip")
        takeButton = button("screen.postroad.depot.take", DepotMenu.ACTION_TAKE_ALL, BX + HALF + 2, MOVE_Y, HALF, "screen.postroad.depot.take.tooltip")

        selectButton = button("screen.postroad.depot.select", DepotMenu.ACTION_SELECT_MODE, BX, SELECT_Y, BW, "screen.postroad.depot.select.tooltip")
        lootButton = button("screen.postroad.depot.loot", DepotMenu.ACTION_SELECT_LOOT, BX, LOOT_Y, BW, "screen.postroad.depot.loot.tooltip")

        destinationPrev = arrow("<", DepotMenu.ACTION_DESTINATION, -1, BX, DEST_Y, ARROW)
        destinationNext = arrow(">", DepotMenu.ACTION_DESTINATION, 1, BX + BW - ARROW, DEST_Y, ARROW)
        expressButton = button("screen.postroad.depot.express", DepotMenu.ACTION_EXPRESS, BX, EXPRESS_Y, BW, "screen.postroad.depot.express.tooltip")
        sendButton = button("screen.postroad.depot.send", DepotMenu.ACTION_SEND, BX, SEND_Y, BW)
        sellButton = button("screen.postroad.depot.sell", DepotMenu.ACTION_SELL, BX, SELL_Y, BW, "screen.postroad.depot.sell.tooltip")

        homeButton = button("screen.postroad.depot.set_home", DepotMenu.ACTION_SET_HOME, BX, TOWN_Y, HALF, "screen.postroad.depot.set_home.tooltip")
        travelButton = button("screen.postroad.depot.travel", DepotMenu.ACTION_TRAVEL, BX + HALF + 2, TOWN_Y, HALF, "screen.postroad.depot.travel.tooltip")
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

        // The column is narrower than some place names, so the full one lives in these tooltips.
        val destination = state.destinationNames.getOrElse(state.destinationIndex) { "" }
        val named = if (canSend) Component.translatable("screen.postroad.depot.destination", destination).string else ""
        if (canSend) {
            val tooltip = Tooltip.create(Component.literal(named))
            destinationPrev.tooltip = tooltip
            destinationNext.tooltip = tooltip
        }
        val timing = when {
            state.selected.isEmpty() -> ""
            state.valuablesDays > 0 && menu.express && state.expressCost > 0 -> Component.translatable("screen.postroad.depot.timing.express", state.expressCost).string
            state.valuablesDays > 0 -> Component.translatable("screen.postroad.depot.timing.days", state.valuablesDays).string
            else -> Component.translatable("screen.postroad.depot.timing.instant").string
        }
        val transit = if (state.transitLines.isEmpty()) "" else state.transitLines.joinToString("\n", prefix = "\n" + Component.translatable("screen.postroad.depot.in_transit").string + "\n")
        val head = if (canSend) named + "\n" else ""
        sendButton.tooltip = Tooltip.create(Component.literal(head + (timing.ifEmpty { Component.translatable("screen.postroad.depot.send.tooltip").string }) + transit))

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
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight)
        panel(graphics, leftPos + COLUMN_X, topPos + COLUMN_TOP, COLUMN_W, COLUMN_H)

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

    /** The column as a vanilla panel: the chest frame's corners and edges around a flat middle. */
    private fun panel(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        val c = FRAME
        val right = (BODY_W - c).toFloat()
        val bottom = (BODY_H - c).toFloat()
        graphics.fill(x + c, y + c, x + w - c, y + h - c, PANEL)
        graphics.blit(TEXTURE, x, y, c, c, 0f, 0f, c, c, 256, 256)
        graphics.blit(TEXTURE, x + w - c, y, c, c, right, 0f, c, c, 256, 256)
        graphics.blit(TEXTURE, x, y + h - c, c, c, 0f, bottom, c, c, 256, 256)
        graphics.blit(TEXTURE, x + w - c, y + h - c, c, c, right, bottom, c, c, 256, 256)
        graphics.blit(TEXTURE, x + c, y, w - 2 * c, c, c.toFloat(), 0f, 1, c, 256, 256)
        graphics.blit(TEXTURE, x + c, y + h - c, w - 2 * c, c, c.toFloat(), bottom, 1, c, 256, 256)
        graphics.blit(TEXTURE, x, y + c, c, h - 2 * c, 0f, c.toFloat(), c, 1, 256, 256)
        graphics.blit(TEXTURE, x + w - c, y + c, c, h - 2 * c, right, c.toFloat(), c, 1, 256, 256)
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val state = menu.state
        val name = state.pageNames.getOrElse(menu.page) { state.townName }
        val paged = menu.unlocked && menu.pageCount > 1
        val wallet = state.wallet.toString()
        val available = imageWidth - 8 - (20 + font.width(wallet)) - 6
        val marker = if (menu.isRemotePage()) "" else "» "
        val counter = if (paged) " (${menu.page + 1}/${menu.pageCount})" else ""
        val full = marker + name + counter
        val header = if (font.width(full) <= available) full else font.plainSubstrByWidth(marker + name, available - font.width("…")) + "…"
        graphics.drawString(font, header, titleLabelX, titleLabelY, TEXT, false)
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false)

        val walletRight = imageWidth - 8
        graphics.renderItem(COIN, walletRight - 16, 2)
        graphics.drawString(font, wallet, walletRight - 18 - font.width(wallet), 6, TEXT_STRONG, false)

        if (menu.unlocked && state.destinationNames.isNotEmpty()) {
            val destination = state.destinationNames.getOrElse(state.destinationIndex) { "—" }
            val text = font.plainSubstrByWidth(destination, NAME_W)
            graphics.drawString(font, text, BX + BW / 2 - font.width(text) / 2, DEST_Y + 5, TEXT_STRONG, false)
        } else if (!menu.unlocked) {
            font.split(Component.translatable("screen.postroad.depot.not_chartered"), BW).forEachIndexed { line, text ->
                graphics.drawString(font, text, BX, DEST_Y + line * 10, TEXT_MUTED, false)
            }
        }
    }

    /** A click on the column is not a click outside the screen, so it never throws the carried stack. */
    override fun hasClickedOutside(mouseX: Double, mouseY: Double, guiLeft: Int, guiTop: Int, mouseButton: Int): Boolean {
        if (!super.hasClickedOutside(mouseX, mouseY, guiLeft, guiTop, mouseButton)) return false
        return !columnArea().contains(mouseX.toInt(), mouseY.toInt())
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

        /** The vanilla 6-row chest, to the pixel: six rows, the inventory, nothing added. */
        private const val BODY_W = 176
        private const val BODY_H = 222
        private const val FRAME = 4

        /** The control column, attached to the body's right frame and overlapping it. */
        private const val COLUMN_W = 110
        private const val COLUMN_OVERLAP = 4
        private const val COLUMN_X = BODY_W - COLUMN_OVERLAP
        private const val COLUMN_SPAN = COLUMN_W - COLUMN_OVERLAP
        private const val COLUMN_TOP = 4
        private const val COLUMN_H = BODY_H - 8

        private const val BX = COLUMN_X + 6
        private const val BW = COLUMN_W - 12
        private const val BH = 18
        private const val HALF = (BW - 2) / 2
        private const val ARROW = 14
        private const val NAME_W = BW - 2 * ARROW - 4

        // Rows: 2 px apart inside a group, 8 px between groups; the last ends 2 px above the frame.
        private const val PAGE_Y = 10
        private const val MOVE_Y = 36
        private const val SELECT_Y = 62
        private const val LOOT_Y = 82
        private const val DEST_Y = 108
        private const val EXPRESS_Y = 128
        private const val SEND_Y = 148
        private const val SELL_Y = 168
        private const val TOWN_Y = 194

        private const val PANEL = 0xFFC6C6C6.toInt()
        private const val LOCKED = 0x99404040.toInt()
        private const val SELECTED = 0x8040C040.toInt()
        private const val TEXT = 0x404040
        private const val TEXT_STRONG = 0x202020
        private const val TEXT_MUTED = 0x606060
    }
}
