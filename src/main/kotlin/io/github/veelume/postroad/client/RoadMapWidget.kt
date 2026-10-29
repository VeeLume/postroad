package io.github.veelume.postroad.client

import io.github.veelume.postroad.map.MapOverlayClient
import io.github.veelume.postroad.map.MapRoad
import io.github.veelume.postroad.map.MapTown
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A schematic map of the network: every road the overlay knows (coloured by tier, faint when
 * uncharted), towns with a depot, the player, and [markers] a screen wants to offer. Drag pans,
 * the wheel zooms about the cursor; [hoveredMarker] / [hoveredRoad] say what is under the mouse,
 * and a click hands it to [onMarkerClick] or [onRoadClick]. Shared by the travel screen and road works.
 */
class RoadMapWidget(x: Int, y: Int, width: Int, height: Int, private val dimension: ResourceLocation) :
    AbstractWidget(x, y, width, height, Component.empty()) {

    /** Something to pick on the map: a destination, say. [id] is the screen's own; an inactive one shows but is not clicked. */
    data class Marker(val id: String, val x: Int, val z: Int, val color: Int, val active: Boolean = true)

    var markers: List<Marker> = emptyList()
    /** Plan ids of roads drawn wider and outlined. */
    var highlighted: Set<String> = emptySet()
    var selectedRoad: String? = null
    /** A marker the screen wants emphasised (the row under the mouse in its list). */
    var emphasised: String? = null
    var onMarkerClick: (Marker) -> Unit = {}
    var onRoadClick: (MapRoad) -> Unit = {}
    /** Whether roads are pickable at all; only [highlighted] ones when set. */
    var roadsPickable = false

    var hoveredMarker: Marker? = null; private set
    var hoveredRoad: MapRoad? = null; private set
    var hoveredTown: MapTown? = null; private set

    private var centerX = 0.0
    private var centerZ = 0.0
    /** Screen pixels per block. */
    private var scale = 0.05
    private var dragged = false

    /** Centres and zooms so that every point fits with a margin. */
    fun fit(points: List<Pair<Int, Int>>) {
        if (points.isEmpty()) return
        val minX = points.minOf { it.first }; val maxX = points.maxOf { it.first }
        val minZ = points.minOf { it.second }; val maxZ = points.maxOf { it.second }
        centerX = (minX + maxX) / 2.0; centerZ = (minZ + maxZ) / 2.0
        val spanX = max(maxX - minX, 200).toDouble(); val spanZ = max(maxZ - minZ, 200).toDouble()
        scale = min((width - 24) / spanX, (height - 24) / spanZ).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun sx(worldX: Double): Float = (x + width / 2.0 + (worldX - centerX) * scale).toFloat()
    private fun sz(worldZ: Double): Float = (y + height / 2.0 + (worldZ - centerZ) * scale).toFloat()

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val state = MapOverlayClient.state
        val roads = state.roads.filter { it.dimension == dimension }
        val towns = state.towns.filter { it.dimension == dimension }
        updateHover(mouseX, mouseY, roads, towns)

        graphics.fill(x, y, x + width, y + height, BACKGROUND)
        graphics.enableScissor(x, y, x + width, y + height)
        val pose = graphics.pose().last().pose()
        val buffer = graphics.bufferSource().getBuffer(RenderType.gui())
        fun line(r: MapRoad, widthPx: Float, color: Int) {
            for (i in 0 until r.xs.size - 1) {
                val ax = sx(r.xs[i].toDouble()); val az = sz(r.zs[i].toDouble())
                val bx = sx(r.xs[i + 1].toDouble()); val bz = sz(r.zs[i + 1].toDouble())
                val len = hypot((bx - ax).toDouble(), (bz - az).toDouble()).toFloat()
                if (len < 0.01f) continue
                val nx = -(bz - az) / len * widthPx / 2; val nz = (bx - ax) / len * widthPx / 2
                buffer.addVertex(pose, ax + nx, az + nz, 0f).setColor(color)
                buffer.addVertex(pose, ax - nx, az - nz, 0f).setColor(color)
                buffer.addVertex(pose, bx - nx, bz - nz, 0f).setColor(color)
                buffer.addVertex(pose, bx + nx, bz + nz, 0f).setColor(color)
            }
        }
        val lineWidth = (2.5 * scale / 0.05).coerceIn(1.5, 4.0).toFloat()
        // Faint first, so charted roads and highlights are drawn over them.
        for (r in roads.sortedBy { (if (it.charted) 1 else 0) + (if (it.plannedId in highlighted) 2 else 0) + (if (it.plannedId == selectedRoad) 4 else 0) }) {
            val emphasis = r.plannedId.isNotEmpty() && (r.plannedId in highlighted || r.plannedId == selectedRoad || r === hoveredRoad)
            if (emphasis) line(r, lineWidth + if (r.plannedId == selectedRoad || r === hoveredRoad) 4f else 2.5f, if (r.plannedId == selectedRoad) SELECTED else HIGHLIGHT)
            val base = TIER_COLORS.getOrElse(r.tier) { TIER_COLORS[0] }
            line(r, lineWidth, if (r.charted || emphasis) base else (base and 0x00FFFFFF) or UNCHARTED_ALPHA)
        }
        graphics.flush()

        for (t in towns) {
            val tx = sx(t.pos.x.toDouble()).toInt(); val tz = sz(t.pos.z.toDouble()).toInt()
            graphics.fill(tx - 2, tz - 2, tx + 3, tz + 3, if (t === hoveredTown) 0xFFFFFFFF.toInt() else TOWN)
            if (scale >= LABEL_SCALE) graphics.drawString(Minecraft.getInstance().font, t.name, tx + 4, tz - 4, 0xFFD8D8D8.toInt(), true)
        }
        for (m in markers) {
            val mx = sx(m.x.toDouble()).toInt(); val mz = sz(m.z.toDouble()).toInt()
            val big = m === hoveredMarker || m.id == emphasised
            val r = if (big) 5 else 3
            graphics.fill(mx - r - 1, mz - r - 1, mx + r + 2, mz + r + 2, 0xFF000000.toInt())
            graphics.fill(mx - r, mz - r, mx + r + 1, mz + r + 1, if (m.active) m.color else (m.color and 0x00FFFFFF) or 0x80000000.toInt())
        }
        Minecraft.getInstance().player?.let { p ->
            if (p.level().dimension().location() == dimension) {
                val px = sx(p.x).toInt(); val pz = sz(p.z).toInt()
                graphics.fill(px - 1, pz - 4, px + 2, pz + 5, PLAYER)
                graphics.fill(px - 4, pz - 1, px + 5, pz + 2, PLAYER)
            }
        }
        graphics.disableScissor()
        graphics.renderOutline(x, y, width, height, 0xFF606060.toInt())
    }

    private fun updateHover(mouseX: Int, mouseY: Int, roads: List<MapRoad>, towns: List<MapTown>) {
        hoveredMarker = null; hoveredRoad = null; hoveredTown = null
        if (!isMouseOver(mouseX.toDouble(), mouseY.toDouble())) return
        hoveredMarker = markers.minByOrNull { distance(it.x, it.z, mouseX, mouseY) }
            ?.takeIf { distance(it.x, it.z, mouseX, mouseY) <= PICK_MARKER }
        if (hoveredMarker != null) return
        hoveredTown = towns.minByOrNull { distance(it.pos.x, it.pos.z, mouseX, mouseY) }?.takeIf { distance(it.pos.x, it.pos.z, mouseX, mouseY) <= PICK_MARKER }
        if (!roadsPickable) return
        var best = PICK_ROAD
        for (r in roads) {
            if (r.plannedId.isEmpty() || (highlighted.isNotEmpty() && r.plannedId !in highlighted)) continue
            for (i in 0 until r.xs.size - 1) {
                val d = segmentDistance(mouseX.toDouble(), mouseY.toDouble(),
                    sx(r.xs[i].toDouble()).toDouble(), sz(r.zs[i].toDouble()).toDouble(), sx(r.xs[i + 1].toDouble()).toDouble(), sz(r.zs[i + 1].toDouble()).toDouble())
                if (d < best) { best = d; hoveredRoad = r }
            }
        }
    }

    private fun distance(wx: Int, wz: Int, mouseX: Int, mouseY: Int): Double =
        hypot(sx(wx.toDouble()) - mouseX.toDouble(), sz(wz.toDouble()) - mouseY.toDouble())

    private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    override fun onClick(mouseX: Double, mouseY: Double) {
        dragged = false
    }

    override fun onRelease(mouseX: Double, mouseY: Double) {
        if (dragged) return
        hoveredMarker?.let { if (it.active) onMarkerClick(it); return }
        hoveredRoad?.let { onRoadClick(it) }
    }

    override fun onDrag(mouseX: Double, mouseY: Double, dragX: Double, dragY: Double) {
        if (abs(dragX) + abs(dragY) > 0.0) dragged = true
        centerX -= dragX / scale
        centerZ -= dragY / scale
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        // Keep the block under the cursor where it is.
        val wx = centerX + (mouseX - (x + width / 2.0)) / scale
        val wz = centerZ + (mouseY - (y + height / 2.0)) / scale
        scale = (scale * if (scrollY > 0) 1.25 else 0.8).coerceIn(MIN_SCALE, MAX_SCALE)
        centerX = wx - (mouseX - (x + width / 2.0)) / scale
        centerZ = wz - (mouseY - (y + height / 2.0)) / scale
        return true
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {}

    companion object {
        private const val MIN_SCALE = 0.005
        private const val MAX_SCALE = 2.0
        /** From this zoom on town names are drawn; below it they show on hover only. */
        private const val LABEL_SCALE = 0.04
        private const val PICK_MARKER = 7.0
        private const val PICK_ROAD = 5.0
        private val BACKGROUND = 0xFF1E2428.toInt()
        private val TOWN = 0xFFC8C8C8.toInt()
        private val PLAYER = 0xFFFF5050.toInt()
        private val HIGHLIGHT = 0xFFE0B040.toInt()
        private val SELECTED = 0xFFFFE070.toInt()
        private const val UNCHARTED_ALPHA = 0x50000000
        /** Dirt, gravel, paved: earth brown, stone grey, pale stone. */
        val TIER_COLORS = intArrayOf(0xFFA0703C.toInt(), 0xFF9AA0A4.toInt(), 0xFFF0E8D0.toInt())
    }
}
