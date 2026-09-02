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
 * The depot screen: the vanilla 6-row chest look with a tab bar above it. Tabs with fewer
 * slots use the freed rows for text and selectors. Every button is a [DepotActionPayload].
 */
class DepotScreen(menu: DepotMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<DepotMenu>(menu, inventory, title) {

    private lateinit var tabButtons: List<Button>
    private lateinit var prevPage: Button
    private lateinit var nextPage: Button
    private lateinit var homeButton: Button
    private lateinit var recipientPrev: Button
    private lateinit var recipientNext: Button
    private lateinit var destinationPrev: Button
    private lateinit var destinationNext: Button
    private lateinit var sendButton: Button

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
        tabButtons = DepotMenu.Tab.entries.mapIndexed { i, tab ->
            addRenderableWidget(
                Button.builder(Component.translatable("screen.postroad.depot.tab.${tab.name.lowercase()}"), action(DepotMenu.ACTION_TAB, i))
                    .bounds(leftPos + i * 44, topPos - 22, 44, 20)
                    .build(),
            )
        }
        prevPage = addRenderableWidget(Button.builder(Component.literal("<"), action(DepotMenu.ACTION_PAGE, -1)).bounds(leftPos + imageWidth - 34, topPos + 4, 12, 12).build())
        nextPage = addRenderableWidget(Button.builder(Component.literal(">"), action(DepotMenu.ACTION_PAGE, 1)).bounds(leftPos + imageWidth - 20, topPos + 4, 12, 12).build())
        homeButton = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.depot.set_home"), action(DepotMenu.ACTION_SET_HOME)).bounds(leftPos + imageWidth - 62, topPos + 4, 54, 12).build())

        val sendTop = topPos + 18 + 18 + 6 // below the outbox row
        recipientPrev = addRenderableWidget(Button.builder(Component.literal("<"), action(DepotMenu.ACTION_RECIPIENT, -1)).bounds(leftPos + 34, sendTop, 12, 14).build())
        recipientNext = addRenderableWidget(Button.builder(Component.literal(">"), action(DepotMenu.ACTION_RECIPIENT, 1)).bounds(leftPos + imageWidth - 20, sendTop, 12, 14).build())
        destinationPrev = addRenderableWidget(Button.builder(Component.literal("<"), action(DepotMenu.ACTION_DESTINATION, -1)).bounds(leftPos + 34, sendTop + 20, 12, 14).build())
        destinationNext = addRenderableWidget(Button.builder(Component.literal(">"), action(DepotMenu.ACTION_DESTINATION, 1)).bounds(leftPos + imageWidth - 20, sendTop + 20, 12, 14).build())
        sendButton = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.depot.send"), action(DepotMenu.ACTION_SEND)).bounds(leftPos + 8, topPos + 100, 160, 20).build())
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
            button.active = i != tab.ordinal && (menu.unlocked || (t != DepotMenu.Tab.NETWORK && t != DepotMenu.Tab.SEND))
        }
        val network = tab == DepotMenu.Tab.NETWORK
        prevPage.visible = network
        nextPage.visible = network
        prevPage.active = menu.pageCount > 1
        nextPage.active = menu.pageCount > 1
        homeButton.visible = tab == DepotMenu.Tab.TOWN
        homeButton.active = menu.state.homeName != menu.state.townName
        val send = tab == DepotMenu.Tab.SEND
        recipientPrev.visible = send
        recipientNext.visible = send
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

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val state = menu.state
        val header: Component = when (menu.tab) {
            DepotMenu.Tab.TOWN -> Component.translatable("screen.postroad.depot.title.town", state.townName)
            DepotMenu.Tab.NETWORK -> Component.translatable("screen.postroad.depot.title.network", state.pageNames.getOrElse(menu.page) { "?" }, menu.page + 1, menu.pageCount)
            DepotMenu.Tab.MAILBOX -> Component.translatable("screen.postroad.depot.title.mailbox", state.townName)
            DepotMenu.Tab.SEND -> Component.translatable("screen.postroad.depot.title.send", state.townName)
        }
        graphics.drawString(font, header, titleLabelX, titleLabelY, 0x404040, false)
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false)

        when (menu.tab) {
            DepotMenu.Tab.MAILBOX -> {
                var y = 17 + 3 * 18 + 4
                val lines = state.mailboxLines.ifEmpty { listOf(Component.translatable("screen.postroad.depot.no_other_mail").string) }
                for (line in lines.take(4)) {
                    graphics.drawString(font, font.plainSubstrByWidth(line, 158), 9, y, 0x404040, false)
                    y += 11
                }
            }
            DepotMenu.Tab.SEND -> {
                val top = 18 + 18 + 6
                graphics.drawString(font, Component.translatable("screen.postroad.depot.to"), 9, top + 3, 0x404040, false)
                graphics.drawCenteredString(font, state.recipients.getOrElse(state.recipientIndex) { "—" }, 101, top + 3, 0x202020)
                graphics.drawString(font, Component.translatable("screen.postroad.depot.at"), 9, top + 23, 0x404040, false)
                graphics.drawCenteredString(font, state.destinations.getOrElse(state.destinationIndex) { "—" }, 101, top + 23, 0x202020)
                if (state.homeName.isNotEmpty()) {
                    graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.postroad.depot.home_hint", state.homeName).string, 158), 9, top + 44, 0x606060, false)
                }
            }
            DepotMenu.Tab.TOWN -> if (!menu.unlocked) {
                // nothing extra; the tab bar shows what is locked
            }
            else -> Unit
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
    }
}
