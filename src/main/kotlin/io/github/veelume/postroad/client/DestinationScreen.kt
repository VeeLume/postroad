package io.github.veelume.postroad.client

import io.github.veelume.postroad.menu.DepotActionPayload
import io.github.veelume.postroad.menu.DepotDestination
import io.github.veelume.postroad.menu.DepotMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Where a parcel goes: every destination of the open depot or mailbox on the road map, and as a list beside it
 * (the menu's order: own mailbox and home first, then nearest) with a search box over it. A row or a marker picks
 * the destination and goes back to [parent]; the depot menu stays open underneath the whole time.
 */
class DestinationScreen(private val parent: Screen, private val menu: DepotMenu) :
    Screen(Component.translatable("screen.postroad.destination.title")) {

    private var page = 0
    private val rows = ArrayList<Button>()
    private lateinit var prev: Button
    private lateinit var next: Button
    private lateinit var search: EditBox
    private lateinit var map: RoadMapWidget
    private var perPage = 7
    /** Indices into the menu's destinations that match the search, in its order. */
    private var shown: List<Int> = emptyList()

    private val destinations: List<DepotDestination> get() = menu.state.destinations

    override fun init() {
        super.init()
        rows.clear()
        val listX = width - LIST_W - MARGIN
        val mapW = listX - MARGIN * 2
        val mapH = height - TOP - BOTTOM
        val dimension = minecraft?.player?.level()?.dimension()?.location() ?: return
        map = addRenderableWidget(RoadMapWidget(MARGIN, TOP, mapW, mapH, dimension))
        map.markers = markers()
        map.onMarkerClick = { m -> m.id.toIntOrNull()?.let { pick(it) } }
        val here = dimension.toString()
        map.fit(destinations.filter { it.dimension == here }.map { it.x to it.z } + (menu.pos.x to menu.pos.z))

        val previous = if (::search.isInitialized) search.value else ""
        search = addRenderableWidget(EditBox(font, listX, TOP, LIST_W, 18, Component.translatable("screen.postroad.destination.search")))
        search.setHint(Component.translatable("screen.postroad.destination.search").withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
        search.value = previous
        search.setResponder { page = 0; refresh() }
        setInitialFocus(search)

        perPage = ((mapH - 24 - SEARCH_STEP) / ROW_STEP).coerceAtLeast(1)
        for (i in 0 until perPage) {
            rows += addRenderableWidget(Button.builder(Component.empty(), Button.OnPress { shown.getOrNull(page * perPage + i)?.let { pick(it) } })
                .bounds(listX, TOP + SEARCH_STEP + i * ROW_STEP, LIST_W, 20).build())
        }
        val pagerY = TOP + SEARCH_STEP + perPage * ROW_STEP + 2
        prev = addRenderableWidget(Button.builder(Component.literal("<"), Button.OnPress { page--; refresh() }).bounds(listX, pagerY, 20, 20).build())
        next = addRenderableWidget(Button.builder(Component.literal(">"), Button.OnPress { page++; refresh() }).bounds(listX + LIST_W - 20, pagerY, 20, 20).build())
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), Button.OnPress { onClose() }).bounds(listX + LIST_W - 68, height - BOTTOM + 6, 68, 20).build())
        refresh()
    }

    private fun markers(): List<RoadMapWidget.Marker> {
        val here = minecraft?.player?.level()?.dimension()?.location()?.toString()
        return destinations.withIndex().filter { it.value.dimension == here }.map { (i, d) ->
            val color = if (i == menu.state.destinationIndex) CHOSEN else when (d.kind) {
                DepotDestination.KIND_OWN_MAILBOX, DepotDestination.KIND_HOME -> MINE
                DepotDestination.KIND_MAILBOX -> MAILBOX
                else -> TOWN
            }
            val kind = if (d.kind == DepotDestination.KIND_MAILBOX || d.kind == DepotDestination.KIND_OWN_MAILBOX) RoadMapWidget.Kind.MAILBOX else RoadMapWidget.Kind.TOWN
            RoadMapWidget.Marker(i.toString(), d.x, d.z, color, label = d.name, kind = kind)
        } + RoadMapWidget.Marker(HERE, menu.pos.x, menu.pos.z, HERE_COLOR, active = false, label = menu.state.townName, kind = RoadMapWidget.Kind.HERE)
    }

    private fun timing(d: DepotDestination): Component =
        if (d.valuablesDays <= 0) Component.translatable("screen.postroad.destination.instant")
        else Component.translatable("screen.postroad.destination.days", d.valuablesDays)

    /** Name, what kind of place it is, how far, and how long unstackables take; bulk always arrives at once. */
    private fun details(d: DepotDestination): Component {
        val kind = Component.translatable(
            when (d.kind) {
                DepotDestination.KIND_OWN_MAILBOX -> "screen.postroad.destination.kind.own_mailbox"
                DepotDestination.KIND_HOME -> "screen.postroad.destination.kind.home"
                DepotDestination.KIND_MAILBOX -> "screen.postroad.destination.kind.mailbox"
                else -> "screen.postroad.destination.kind.town"
            },
        )
        return Component.literal(d.name).append("\n")
            .append(Component.translatable("screen.postroad.destination.details", kind, d.distance, timing(d)).withStyle(net.minecraft.ChatFormatting.GRAY))
    }

    private fun refresh() {
        val query = search.value.trim().lowercase()
        shown = destinations.indices.filter { query.isEmpty() || query in destinations[it].name.lowercase() }
        val pages = ((shown.size + perPage - 1) / perPage).coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        for (i in 0 until perPage) {
            val index = shown.getOrNull(page * perPage + i)
            val button = rows[i]
            button.visible = index != null
            if (index != null) {
                val d = destinations[index]
                val marker = if (index == menu.state.destinationIndex) "» " else ""
                val suffix = " · " + timing(d).string
                val name = font.plainSubstrByWidth(marker + d.name, LIST_W - 10 - font.width(suffix))
                button.message = Component.literal(name + suffix)
                button.tooltip = Tooltip.create(details(d))
            }
        }
        prev.visible = pages > 1
        next.visible = pages > 1
        prev.active = page > 0
        next.active = page < pages - 1
    }

    private fun pick(index: Int) {
        if (index !in destinations.indices) return
        PacketDistributor.sendToServer(DepotActionPayload(DepotMenu.ACTION_DESTINATION_SET, index))
        onClose()
    }

    /** Enter picks the first match, so typing a few letters and Enter is enough. */
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && shown.isNotEmpty()) {
            pick(shown.first())
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun tick() {
        super.tick()
        // The server closed the menu (walked away, block broken): nothing to go back to.
        if (minecraft?.player?.containerMenu !== menu) minecraft?.setScreen(null)
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        map.emphasised = rows.indexOfFirst { it.visible && it.isHovered }.takeIf { it >= 0 }?.let { shown.getOrNull(page * perPage + it)?.toString() }
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawString(font, Component.translatable("screen.postroad.destination.from", menu.state.townName), MARGIN, 8, 0xFFFFFF, true)
        graphics.drawString(font, Component.translatable("screen.postroad.destination.hint"), MARGIN, 20, 0xA0A0A0, true)
        if (shown.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.postroad.destination.none"), width - LIST_W / 2 - MARGIN, TOP + SEARCH_STEP + 4, 0xA0A0A0)
        }
        val marker = map.hoveredMarker
        val d = marker?.id?.toIntOrNull()?.let { destinations.getOrNull(it) }
        when {
            d != null -> graphics.renderTooltip(font, font.split(details(d), 240), mouseX, mouseY)
            map.hoveredTown != null -> graphics.renderTooltip(font, Component.literal(map.hoveredTown!!.name), mouseX, mouseY)
        }
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private const val MARGIN = 10
        private const val TOP = 36
        private const val BOTTOM = 34
        private const val LIST_W = 150
        private const val ROW_STEP = 22
        private const val SEARCH_STEP = 22
        private const val HERE = "here"
        private val TOWN = 0xFFE0B040.toInt()
        private val MAILBOX = 0xFF5080E0.toInt()
        private val MINE = 0xFFE07040.toInt()
        private val CHOSEN = 0xFF60D060.toInt()
        private val HERE_COLOR = 0xFFFFFFFF.toInt()
    }
}
