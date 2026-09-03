package io.github.veelume.postroad.client

import io.github.veelume.postroad.travel.TravelActionPayload
import io.github.veelume.postroad.travel.TravelClient
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/** The travel list: reachable nodes by distance, fare shown, seven per page. */
class TravelScreen : Screen(Component.translatable("screen.postroad.travel.title")) {
    private var page = 0
    private var express = false
    private val rows = ArrayList<Button>()
    private lateinit var prev: Button
    private lateinit var next: Button
    private lateinit var expressButton: Button

    private val perPage = 7

    override fun init() {
        super.init()
        rows.clear()
        val state = TravelClient.state
        val cx = width / 2
        val top = height / 2 - 90
        for (i in 0 until perPage) {
            rows += addRenderableWidget(Button.builder(Component.empty(), Button.OnPress { pick(i) }).bounds(cx - 120, top + i * 22, 240, 20).build())
        }
        prev = addRenderableWidget(Button.builder(Component.literal("<"), Button.OnPress { page--; refresh() }).bounds(cx - 120, top + perPage * 22 + 4, 20, 20).build())
        next = addRenderableWidget(Button.builder(Component.literal(">"), Button.OnPress { page++; refresh() }).bounds(cx + 100, top + perPage * 22 + 4, 20, 20).build())
        expressButton = addRenderableWidget(
            Button.builder(Component.empty(), Button.OnPress { express = !express; refresh() })
                .bounds(cx - 96, top + perPage * 22 + 4, 120, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.postroad.travel.express.tooltip")))
                .build(),
        )
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), Button.OnPress { onClose() }).bounds(cx + 28, top + perPage * 22 + 4, 68, 20).build())
        refresh()
    }

    private fun refresh() {
        val state = TravelClient.state
        val pages = ((state.entries.size + perPage - 1) / perPage).coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        for (i in 0 until perPage) {
            val entry = state.entries.getOrNull(page * perPage + i)
            val button = rows[i]
            button.visible = entry != null
            if (entry != null) {
                val fare = if (entry.fare == 0L) Component.translatable("screen.postroad.travel.free") else Component.translatable("screen.postroad.travel.fare", entry.fare)
                button.message = Component.translatable("screen.postroad.travel.row", entry.name, entry.length, Component.translatable("tier.postroad.${entry.tier}"), fare)
                button.active = entry.fare <= state.wallet + state.fund
            }
        }
        prev.visible = pages > 1
        next.visible = pages > 1
        prev.active = page > 0
        next.active = page < pages - 1
        expressButton.message = Component.translatable(if (express) "screen.postroad.travel.express_on" else "screen.postroad.travel.express_off")
    }

    private fun pick(row: Int) {
        val state = TravelClient.state
        val entry = state.entries.getOrNull(page * perPage + row) ?: return
        PacketDistributor.sendToServer(TravelActionPayload(state.fromNodeId, entry.nodeId, express))
        onClose()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val state = TravelClient.state
        val cx = width / 2
        val top = height / 2 - 90
        graphics.drawCenteredString(font, Component.translatable("screen.postroad.travel.from", state.fromName), cx, top - 26, 0xFFFFFF)
        graphics.drawCenteredString(font, Component.translatable("screen.postroad.travel.wallet", state.wallet, state.fund), cx, top - 14, 0xA0A0A0)
        if (state.entries.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.postroad.travel.none"), cx, top + 40, 0xA0A0A0)
        }
    }

    override fun isPauseScreen(): Boolean = false
}
