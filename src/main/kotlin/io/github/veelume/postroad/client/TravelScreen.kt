package io.github.veelume.postroad.client

import io.github.veelume.postroad.travel.TravelActionPayload
import io.github.veelume.postroad.travel.TravelClient
import io.github.veelume.postroad.travel.TravelEntry
import io.github.veelume.postroad.travel.TravelRenamePayload
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Travel: the road map with every reachable destination on it, and the same destinations as a short
 * list beside it, nearest first. A marker or a row travels; hovering either shows the price (the free
 * journey or the fare) and points the other out.
 */
class TravelScreen : Screen(Component.translatable("screen.postroad.travel.title")) {
    private var page = 0
    private val rows = ArrayList<Button>()
    private lateinit var prev: Button
    private lateinit var next: Button
    private lateinit var map: RoadMapWidget
    private var perPage = 7

    override fun init() {
        super.init()
        rows.clear()
        val state = TravelClient.state
        val listX = width - LIST_W - MARGIN
        val mapW = listX - MARGIN * 2
        val mapH = height - TOP - BOTTOM
        val dimension = minecraft?.player?.level()?.dimension()?.location() ?: return
        map = addRenderableWidget(RoadMapWidget(MARGIN, TOP, mapW, mapH, dimension))
        map.markers = markers()
        map.onMarkerClick = { m -> state.entries.firstOrNull { it.nodeId == m.id }?.let { go(it) } }
        map.fit(state.entries.map { it.x to it.z } + (state.fromX to state.fromZ))

        perPage = ((mapH - 24) / ROW_STEP).coerceAtLeast(1)
        for (i in 0 until perPage) {
            rows += addRenderableWidget(Button.builder(Component.empty(), Button.OnPress { pick(i) }).bounds(listX, TOP + i * ROW_STEP, LIST_W, 20).build())
        }
        val pagerY = TOP + perPage * ROW_STEP + 2
        prev = addRenderableWidget(Button.builder(Component.literal("<"), Button.OnPress { page--; refresh() }).bounds(listX, pagerY, 20, 20).build())
        next = addRenderableWidget(Button.builder(Component.literal(">"), Button.OnPress { page++; refresh() }).bounds(listX + LIST_W - 20, pagerY, 20, 20).build())

        val bottom = height - BOTTOM + 6
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), Button.OnPress { onClose() }).bounds(listX + LIST_W - 68, bottom, 68, 20).build())
        if (state.renamable) {
            val box = EditBox(font, MARGIN, bottom, 170, 20, Component.translatable("screen.postroad.travel.name"))
            box.setMaxLength(32)
            box.value = state.fromName
            box.setTooltip(Tooltip.create(Component.translatable("screen.postroad.travel.name_hint")))
            addRenderableWidget(box)
            addRenderableWidget(
                Button.builder(Component.translatable("screen.postroad.travel.rename"), Button.OnPress {
                    val name = box.value.trim()
                    if (name.isNotEmpty() && name != TravelClient.state.fromName) {
                        PacketDistributor.sendToServer(TravelRenamePayload(TravelClient.state.fromNodeId, name))
                    }
                }).bounds(MARGIN + 174, bottom, 66, 20).build(),
            )
        }
        refresh()
    }

    private fun affordable(e: TravelEntry): Boolean = TravelClient.state.let { e.fare <= it.wallet + it.fund }

    private fun markers(): List<RoadMapWidget.Marker> {
        val state = TravelClient.state
        return state.entries.map { e ->
            val color = when {
                e.fare == 0L -> FREE
                affordable(e) -> PAID
                else -> UNAFFORDABLE
            }
            RoadMapWidget.Marker(e.nodeId, e.x, e.z, color, active = affordable(e))
        } + RoadMapWidget.Marker(HERE, state.fromX, state.fromZ, HERE_COLOR, active = false)
    }

    private fun price(e: TravelEntry): Component =
        if (e.fare == 0L) Component.translatable("screen.postroad.travel.free") else Component.translatable("screen.postroad.travel.fare", e.fare)

    /** The full line for a destination: name, length, tier, price, and what the free journey would start. */
    private fun details(e: TravelEntry): List<Component> = listOfNotNull(
        Component.translatable("screen.postroad.travel.row", e.name, e.length, Component.translatable("tier.postroad.${e.tier}"), price(e)),
        if (e.cooldown > 0) Component.translatable("screen.postroad.travel.free_journey.tooltip", minutes(e.cooldown)) else null,
        if (!affordable(e)) Component.translatable("screen.postroad.travel.too_dear") else null,
    )

    private fun refresh() {
        val state = TravelClient.state
        val pages = ((state.entries.size + perPage - 1) / perPage).coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        for (i in 0 until perPage) {
            val entry = state.entries.getOrNull(page * perPage + i)
            val button = rows[i]
            button.visible = entry != null
            if (entry != null) {
                button.message = Component.translatable("screen.postroad.travel.list_row", entry.name, price(entry))
                button.active = affordable(entry)
                button.tooltip = Tooltip.create(details(entry).reduce { a, b -> a.copy().append("\n").append(b) })
            }
        }
        prev.visible = pages > 1
        next.visible = pages > 1
        prev.active = page > 0
        next.active = page < pages - 1
    }

    private fun pick(row: Int) {
        TravelClient.state.entries.getOrNull(page * perPage + row)?.let { go(it) }
    }

    private fun go(entry: TravelEntry) {
        if (!affordable(entry)) return
        PacketDistributor.sendToServer(TravelActionPayload(TravelClient.state.fromNodeId, entry.nodeId))
        onClose()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val state = TravelClient.state
        // Point the map at the row under the mouse.
        map.emphasised = rows.indexOfFirst { it.visible && it.isHovered }.takeIf { it >= 0 }?.let { state.entries.getOrNull(page * perPage + it)?.nodeId }
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawString(font, Component.translatable("screen.postroad.travel.from", state.fromName), MARGIN, 8, 0xFFFFFF, true)
        graphics.drawString(font, Component.translatable("screen.postroad.travel.wallet", state.wallet, state.fund), MARGIN, 20, 0xA0A0A0, true)
        if (state.freeJourneyIn >= 0) {
            val free = if (state.freeJourneyIn == 0L) Component.translatable("screen.postroad.travel.free_ready")
            else Component.translatable("screen.postroad.travel.free_in", minutes(state.freeJourneyIn))
            graphics.drawString(font, free, MARGIN + 200, 20, if (state.freeJourneyIn == 0L) 0x80FF80 else 0xA0A0A0, true)
        }
        graphics.drawString(font, Component.translatable("screen.postroad.travel.map_hint"), width - LIST_W - MARGIN * 2 - font.width(Component.translatable("screen.postroad.travel.map_hint")), 8, 0x808080, false)
        if (state.entries.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.postroad.travel.none"), width - LIST_W / 2 - MARGIN, TOP + 4, 0xA0A0A0)
        }
        val marker = map.hoveredMarker
        val entry = marker?.let { m -> state.entries.firstOrNull { it.nodeId == m.id } }
        when {
            entry != null -> graphics.renderComponentTooltip(font, details(entry), mouseX, mouseY)
            map.hoveredTown != null -> graphics.renderTooltip(font, Component.literal(map.hoveredTown!!.name), mouseX, mouseY)
        }
    }

    override fun isPauseScreen(): Boolean = false

    /** Real minutes for a span of ticks, rounded up. */
    private fun minutes(ticks: Long): Long = (ticks + 1199) / 1200

    companion object {
        private const val MARGIN = 10
        private const val TOP = 36
        private const val BOTTOM = 34
        private const val LIST_W = 150
        private const val ROW_STEP = 22
        private const val HERE = "here"
        private val FREE = 0xFF60D060.toInt()
        private val PAID = 0xFFE0B040.toInt()
        private val UNAFFORDABLE = 0xFFB04040.toInt()
        private val HERE_COLOR = 0xFFFFFFFF.toInt()
    }
}
