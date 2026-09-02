package io.github.veelume.postroad.client

import io.github.veelume.postroad.menu.DepotActionPayload
import io.github.veelume.postroad.menu.DepotMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The depot screen: the vanilla 6-row chest look with a tab bar above it. The send tab uses
 * the rows below its outbox for the destination selector and the in-transit list. Every
 * button is a [DepotActionPayload].
 */
class DepotScreen(menu: DepotMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<DepotMenu>(menu, inventory, title) {

    private lateinit var tabButtons: List<Button>
    private lateinit var prevPage: Button
    private lateinit var nextPage: Button
    private lateinit var homeButton: Button
    private lateinit var destinationPrev: Button
    private lateinit var destinationNext: Button
    private lateinit var sendButton: Button
    private lateinit var dumpButton: Button

    init {
        imageWidth = 176
        imageHeight = 222
        inventoryLabelY = imageHeight - 94
    }

    private fun action(action: Int, value: Int = 0): Button.OnPress = Button.OnPress {
        PacketDistributor.sendToServer(DepotActionPayload(action, value))
    }

    override fun init() {
        super.init()
        val tabWidth = 58
        tabButtons = DepotMenu.Tab.entries.mapIndexed { i, tab ->
            addRenderableWidget(
                Button.builder(Component.translatable("screen.postroad.depot.tab.${tab.name.lowercase()}"), action(DepotMenu.ACTION_TAB, i))
                    .bounds(leftPos + i * tabWidth, topPos - 22, tabWidth - 2, 20)
                    .build(),
            )
        }
        dumpButton = addRenderableWidget(
            Button.builder(Component.translatable("screen.postroad.depot.dump"), action(DepotMenu.ACTION_DUMP))
                .bounds(leftPos + imageWidth - 56, topPos - 22, 56, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.postroad.depot.dump.tooltip")))
                .build(),
        )
        prevPage = addRenderableWidget(Button.builder(Component.literal("<"), action(DepotMenu.ACTION_PAGE, -1)).bounds(leftPos + imageWidth - 34, topPos + 4, 12, 12).build())
        nextPage = addRenderableWidget(Button.builder(Component.literal(">"), action(DepotMenu.ACTION_PAGE, 1)).bounds(leftPos + imageWidth - 20, topPos + 4, 12, 12).build())
        homeButton = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.depot.set_home"), action(DepotMenu.ACTION_SET_HOME)).bounds(leftPos + imageWidth - 70, topPos + 4, 32, 12).build())

        destinationPrev = addRenderableWidget(Button.builder(Component.literal("<"), action(DepotMenu.ACTION_DESTINATION, -1)).bounds(leftPos + 34, topPos + SEND_ROW, 12, 14).build())
        destinationNext = addRenderableWidget(Button.builder(Component.literal(">"), action(DepotMenu.ACTION_DESTINATION, 1)).bounds(leftPos + imageWidth - 20, topPos + SEND_ROW, 12, 14).build())
        sendButton = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.depot.send"), action(DepotMenu.ACTION_SEND)).bounds(leftPos + 8, topPos + 104, 160, 18).build())
        updateWidgets()
    }

    override fun containerTick() {
        super.containerTick()
        updateWidgets()
    }

    private fun updateWidgets() {
        val tab = menu.tab
        tabButtons.forEachIndexed { i, button ->
            val t = DepotMenu.Tab.entries[i]
            button.active = i != tab.ordinal && (menu.unlocked || t == DepotMenu.Tab.STORAGE)
        }
        val storage = tab == DepotMenu.Tab.STORAGE
        val paged = storage && menu.unlocked && menu.pageCount > 1
        prevPage.visible = paged
        nextPage.visible = paged
        homeButton.visible = storage && !menu.isRemotePage()
        dumpButton.visible = storage
        homeButton.active = menu.state.homeName != menu.state.townName
        val send = tab == DepotMenu.Tab.SEND
        destinationPrev.visible = send
        destinationNext.visible = send
        sendButton.visible = send
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight)
        val rows = menu.activeSlots / 9
        if (rows < 6) {
            graphics.fill(leftPos + 7, topPos + 17 + rows * 18, leftPos + 169, topPos + 17 + 6 * 18, 0xFFC6C6C6.toInt())
        }
        if (menu.isRemotePage()) {
            for (index in 0 until menu.activeSlots) {
                val slot = menu.slots[index]
                if (slot.hasItem() && !menu.mayMove(slot.item)) {
                    graphics.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0x99404040.toInt())
                }
            }
        }
    }

    private fun drawCentered(graphics: GuiGraphics, text: String, centerX: Int, y: Int, color: Int) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false)
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val state = menu.state
        val header: String = when (menu.tab) {
            DepotMenu.Tab.STORAGE -> {
                val name = state.pageNames.getOrElse(menu.page) { state.townName }
                val paged = menu.unlocked && menu.pageCount > 1
                // Buttons take the right of the title row; drop the counter, then trim, so nothing overlaps.
                val available = imageWidth - 8 - (if (paged) 76 else 42) - 4
                val marker = if (menu.isRemotePage()) "" else "» "
                val counter = if (paged) " (${menu.page + 1}/${menu.pageCount})" else ""
                val full = marker + name + counter
                if (font.width(full) <= available) full else font.plainSubstrByWidth(marker + name, available - font.width("…")) + "…"
            }
            DepotMenu.Tab.SEND -> Component.translatable("screen.postroad.depot.title.send", state.townName).string
        }
        graphics.drawString(font, header, titleLabelX, titleLabelY, TEXT, false)
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false)

        if (menu.tab == DepotMenu.Tab.SEND) {
            graphics.drawString(font, Component.translatable("screen.postroad.depot.to"), 9, SEND_ROW + 3, TEXT, false)
            drawCentered(graphics, state.destinationNames.getOrElse(state.destinationIndex) { "—" }, 101, SEND_ROW + 3, TEXT_STRONG)
            var y = SEND_ROW + 22
            val lines = state.transitLines.ifEmpty { listOf(Component.translatable("screen.postroad.depot.no_transit").string) }
            for (line in lines.take(4)) {
                graphics.drawString(font, font.plainSubstrByWidth(line, 158), 9, y, TEXT_MUTED, false)
                y += 10
            }
        }
    }

    override fun getTooltipFromContainerItem(stack: ItemStack): MutableList<Component> {
        val tooltip = super.getTooltipFromContainerItem(stack)
        val slot = hoveredSlot
        if (slot != null && slot.index < DepotMenu.GRID && menu.isRemotePage() && !menu.mayMove(stack)) {
            tooltip.add(Component.translatable("screen.postroad.depot.locked", menu.state.pageNames.getOrElse(menu.page) { "?" }))
        }
        return tooltip
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    companion object {
        private val TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png")
        private const val SEND_ROW = 18 + 18 + 8
        private const val TEXT = 0x404040
        private const val TEXT_STRONG = 0x202020
        private const val TEXT_MUTED = 0x606060
    }
}
