package io.github.veelume.postroad.client

import io.github.veelume.postroad.roads.ChartingActionPayload
import io.github.veelume.postroad.roads.ChartingClient
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/** The charting map's screen: start/finish, abort, remove path, sever, and the readout. */
class ChartingScreen : Screen(Component.translatable("screen.postroad.charting.title")) {

    private lateinit var mainButton: Button
    private lateinit var abortButton: Button
    private lateinit var removeButton: Button
    private lateinit var severButton: Button

    private fun send(action: Int): Button.OnPress = Button.OnPress {
        PacketDistributor.sendToServer(ChartingActionPayload(action))
        if (action != ChartingActionPayload.STATUS) onClose()
    }

    override fun init() {
        super.init()
        PacketDistributor.sendToServer(ChartingActionPayload(ChartingActionPayload.STATUS))
        val cx = width / 2
        val top = height / 2 - 40
        mainButton = addRenderableWidget(Button.builder(Component.empty(), Button.OnPress { onMain() }).bounds(cx - 100, top, 200, 20).build())
        abortButton = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.charting.abort"), send(ChartingActionPayload.ABORT)).bounds(cx - 100, top + 24, 200, 20).build())
        removeButton = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.charting.remove"), send(ChartingActionPayload.REMOVE_PATH)).bounds(cx - 100, top + 56, 98, 20).build())
        severButton = addRenderableWidget(Button.builder(Component.translatable("screen.postroad.charting.sever"), send(ChartingActionPayload.SEVER)).bounds(cx + 2, top + 56, 98, 20).build())
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), Button.OnPress { onClose() }).bounds(cx - 50, top + 90, 100, 20).build())
    }

    private fun onMain() {
        val active = ChartingClient.state.active
        PacketDistributor.sendToServer(ChartingActionPayload(if (active) ChartingActionPayload.FINISH else ChartingActionPayload.START))
        onClose()
    }

    override fun tick() {
        super.tick()
        val state = ChartingClient.state
        mainButton.message = Component.translatable(if (state.active) "screen.postroad.charting.finish" else "screen.postroad.charting.start")
        abortButton.visible = state.active
        removeButton.active = state.nearPath.isNotEmpty() && !state.active
        severButton.active = state.nearPath.isNotEmpty() && !state.active
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val state = ChartingClient.state
        val cx = width / 2
        val top = height / 2 - 40
        graphics.drawCenteredString(font, title, cx, top - 30, 0xFFFFFF)
        val line = when {
            state.active -> Component.translatable("screen.postroad.charting.readout", state.distance, state.roadShare,
                Component.translatable("tier.postroad.${state.tier.ifEmpty { "none" }}"))
            state.nearPath.isNotEmpty() -> Component.literal(state.nearPath)
            else -> Component.translatable("screen.postroad.charting.idle", state.pathCount)
        }
        graphics.drawCenteredString(font, line, cx, top - 16, 0xA0A0A0)
    }

    override fun isPauseScreen(): Boolean = false
}
