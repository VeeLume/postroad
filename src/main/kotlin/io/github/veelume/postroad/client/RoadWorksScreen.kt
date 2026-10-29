package io.github.veelume.postroad.client

import io.github.veelume.postroad.map.MapOverlayClient
import io.github.veelume.postroad.roads.Tier
import io.github.veelume.postroad.roads.gen.RoadWorksActionPayload
import io.github.veelume.postroad.roads.gen.RoadWorksClient
import io.github.veelume.postroad.roads.gen.RoadWorksEntry
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Road works at a depot: the road map with the town's roads highlighted, one of them selected (click
 * it on the map or in the list), and what its next tier still needs. Coins and material go in from
 * here; the server answers every action with a fresh state, which [refresh] applies in place.
 */
class RoadWorksScreen : Screen(Component.translatable("screen.postroad.works.title")) {
    private lateinit var map: RoadMapWidget
    private val rows = ArrayList<Button>()
    private lateinit var pay1: Button
    private lateinit var pay10: Button
    private lateinit var payRest: Button
    private lateinit var deliver: Button
    private var selected: String? = null
    private var page = 0
    private var perPage = 4

    private val panelX get() = width - PANEL_W - MARGIN

    override fun init() {
        super.init()
        rows.clear()
        val state = RoadWorksClient.state
        val dimension = minecraft?.player?.level()?.dimension()?.location() ?: return
        map = addRenderableWidget(RoadMapWidget(MARGIN, TOP, panelX - MARGIN * 2, height - TOP - MARGIN, dimension))
        map.roadsPickable = true
        map.onRoadClick = { road -> selected = road.plannedId; refresh() }
        val ids = state.entries.map { it.roadId }.toSet()
        val roads = MapOverlayClient.state.roads.filter { it.plannedId in ids }
        map.fit(roads.flatMap { r -> r.xs.indices.map { r.xs[it] to r.zs[it] } })
        if (selected == null || state.entries.none { it.roadId == selected }) selected = state.entries.firstOrNull()?.roadId

        val x = panelX
        pay1 = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.works.pay", 1)) { fund(1) }.bounds(x, PAY_Y, 40, 20).build())
        pay10 = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.works.pay", 10)) { fund(10) }.bounds(x + 44, PAY_Y, 40, 20).build())
        payRest = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.works.pay_rest")) { fund(Long.MAX_VALUE) }.bounds(x + 88, PAY_Y, PANEL_W - 88, 20).build())
        deliver = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.works.deliver")) { send(RoadWorksActionPayload.DELIVER, 0) }.bounds(x, DELIVER_Y, PANEL_W, 20).build())

        perPage = ((height - LIST_Y - MARGIN - 24) / 22).coerceIn(1, 8)
        for (i in 0 until perPage) {
            rows += addRenderableWidget(Button.builder(Component.empty()) { state().entries.getOrNull(page * perPage + i)?.let { selected = it.roadId; refresh() } }
                .bounds(x, LIST_Y + i * 22, PANEL_W, 20).build())
        }
        addRenderableWidget(Button.builder(Component.literal("<")) { page--; refresh() }.bounds(x, LIST_Y + perPage * 22, 20, 20).build())
        addRenderableWidget(Button.builder(Component.literal(">")) { page++; refresh() }.bounds(x + PANEL_W - 20, LIST_Y + perPage * 22, 20, 20).build())
        refresh()
    }

    private fun state() = RoadWorksClient.state
    private fun current(): RoadWorksEntry? = state().entries.firstOrNull { it.roadId == selected }

    private fun fund(coins: Long) = send(RoadWorksActionPayload.FUND, coins)

    private fun send(action: Int, amount: Long) {
        val e = current() ?: return
        PacketDistributor.sendToServer(RoadWorksActionPayload(state().placeId, e.roadId, action, amount))
    }

    /** Applies the latest state: selection, buttons, list. Called by the client holder on every answer. */
    fun refresh() {
        val state = state()
        if (state.entries.none { it.roadId == selected }) selected = state.entries.firstOrNull()?.roadId
        map.highlighted = state.entries.map { it.roadId }.toSet()
        map.selectedRoad = selected
        val e = current()
        val open = e != null && e.tier < Tier.entries.size - 1
        val coinsLeft = if (e == null) 0L else e.coinsNeeded - e.coinsPaid
        val funds = state.wallet + state.fund
        for (b in listOf(pay1, pay10, payRest, deliver)) b.visible = open
        pay1.active = coinsLeft > 0 && funds >= 1
        pay10.active = coinsLeft > 0 && funds >= minOf(10L, coinsLeft)
        payRest.active = coinsLeft > 0 && funds >= coinsLeft
        payRest.message = Component.translatable("screen.postroad.works.pay_rest_n", coinsLeft.coerceAtLeast(0))
        deliver.active = e != null && e.materialDelivered < e.materialNeeded && e.materialAvailable > 0

        val pages = ((state.entries.size + perPage - 1) / perPage).coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        for (i in 0 until perPage) {
            val entry = state.entries.getOrNull(page * perPage + i)
            val row = rows[i]
            row.visible = entry != null
            if (entry != null) {
                val other = if (entry.fromName == state.townName) entry.toName else entry.fromName
                val mark = if (entry.roadId == selected) "▶ " else ""
                row.message = Component.translatable("screen.postroad.works.row", mark + other, tierName(entry.tier))
            }
        }
    }

    private fun tierName(tier: Int): Component = Component.translatable("tier.postroad.${Tier.entries.getOrElse(tier) { Tier.DIRT }.key}")

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val state = state()
        val x = panelX
        graphics.drawString(font, Component.translatable("screen.postroad.works.heading", state.townName), MARGIN, 8, 0xFFFFFF, true)
        graphics.drawString(font, Component.translatable("screen.postroad.travel.wallet", state.wallet, state.fund), MARGIN, 20, 0xA0A0A0, true)
        val e = current()
        if (e == null) {
            graphics.drawWordWrap(font, Component.translatable("screen.postroad.works.none"), x, TOP, PANEL_W, 0xA0A0A0)
        } else {
            graphics.drawString(font, Component.literal("${e.fromName} – ${e.toName}"), x, TOP, 0xFFFFFF, true)
            graphics.drawString(font, Component.translatable("screen.postroad.works.road", e.length, tierName(e.tier)), x, TOP + 12, 0xC0C0C0, true)
            if (e.tier >= Tier.entries.size - 1) {
                graphics.drawWordWrap(font, Component.translatable("screen.postroad.works.top"), x, TOP + 30, PANEL_W, 0x80FF80)
            } else {
                graphics.drawString(font, Component.translatable("screen.postroad.works.next", tierName(e.tier + 1)), x, TOP + 30, 0xE0B040, true)
                graphics.drawString(font, Component.translatable("screen.postroad.works.coins", e.coinsPaid, e.coinsNeeded), x, PAY_Y - 12, 0xFFFFFF, true)
                val item = ResourceLocation.tryParse(e.material)?.let { BuiltInRegistries.ITEM.get(it) }
                if (item != null) graphics.renderItem(ItemStack(item), x, DELIVER_Y - 22)
                graphics.drawString(font, Component.translatable("screen.postroad.works.material", e.materialDelivered, e.materialNeeded), x + 20, DELIVER_Y - 22, 0xFFFFFF, true)
                graphics.drawString(font, Component.translatable("screen.postroad.works.available", e.materialAvailable), x + 20, DELIVER_Y - 12, 0xA0A0A0, false)
            }
            if (e.pending > 0) graphics.drawString(font, Component.translatable("screen.postroad.works.pending", e.pending), x, DELIVER_Y + 24, 0xA0A0A0, false)
        }
        val hovered = map.hoveredRoad
        if (hovered != null) {
            val entry = state.entries.firstOrNull { it.roadId == hovered.plannedId }
            if (entry != null) graphics.renderTooltip(font, Component.translatable("screen.postroad.works.row", "${entry.fromName} – ${entry.toName}", tierName(entry.tier)), mouseX, mouseY)
        } else map.hoveredTown?.let { graphics.renderTooltip(font, Component.literal(it.name), mouseX, mouseY) }
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private const val MARGIN = 10
        private const val TOP = 36
        private const val PANEL_W = 170
        private const val PAY_Y = TOP + 58
        private const val DELIVER_Y = PAY_Y + 50
        private const val LIST_Y = DELIVER_Y + 40
    }
}
