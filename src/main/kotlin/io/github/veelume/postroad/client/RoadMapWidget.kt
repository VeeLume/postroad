package io.github.veelume.postroad.client

import com.mojang.blaze3d.systems.RenderSystem
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
 * The road map: the land this player has explored ([Atlas]), the charted roads (coloured by tier),
 * the towns the player has seen, the player, and [markers] a screen wants to offer. Drag pans,
 * the wheel zooms about the cursor; [hoveredMarker] / [hoveredRoad] say what is under the mouse,
 * and a click hands it to [onMarkerClick] or [onRoadClick]. Shared by the travel screen and road works.
 */
class RoadMapWidget(x: Int, y: Int, width: Int, height: Int, private val dimension: ResourceLocation) :
    AbstractWidget(x, y, width, height, Component.empty()) {

    /** Something to pick on the map: a destination, say. [id] is the screen's own; an inactive one shows but is not clicked. [label] is drawn beside it. */
    data class Marker(val id: String, val x: Int, val z: Int, val color: Int, val active: Boolean = true, val label: String = "")

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
        val roads = state.roads.filter { it.dimension == dimension && (it.charted || it.plannedId in highlighted) }
        // A town is on the map where this player has been, or where a screen offers it.
        val towns = state.towns.filter { it.dimension == dimension && Atlas.seen(dimension, it.pos.x, it.pos.z) }
        updateHover(mouseX, mouseY, roads, towns)
        val font = Minecraft.getInstance().font

        graphics.fill(x, y, x + width, y + height, BACKGROUND)
        graphics.enableScissor(x, y, x + width, y + height)
        drawAtlas(graphics)
        val pose = graphics.pose().last().pose()
        val buffer = graphics.bufferSource().getBuffer(RenderType.gui())
        fun line(r: MapRoad, widthPx: Float, color: Int) {
            for (i in 0 until r.xs.size - 1) {
                val ax = sx(r.xs[i].toDouble()); val az = sz(r.zs[i].toDouble())
                val bx = sx(r.xs[i + 1].toDouble()); val bz = sz(r.zs[i + 1].toDouble())
                val len = hypot((bx - ax).toDouble(), (bz - az).toDouble()).toFloat()
                if (len < 0.01f) continue
                val nx = -(bz - az) / len * widthPx / 2; val nz = (bx - ax) / len * widthPx / 2
                // The winding vanilla's fill uses; the other way round the quad is culled as a back face.
                buffer.addVertex(pose, ax - nx, az - nz, 0f).setColor(color)
                buffer.addVertex(pose, ax + nx, az + nz, 0f).setColor(color)
                buffer.addVertex(pose, bx + nx, bz + nz, 0f).setColor(color)
                buffer.addVertex(pose, bx - nx, bz - nz, 0f).setColor(color)
            }
        }
        val lineWidth = (2.5 * scale / 0.05).coerceIn(2.0, 5.0).toFloat()
        val ordered = roads.sortedBy { (if (it.plannedId in highlighted) 1 else 0) + (if (it.plannedId == selectedRoad || it === hoveredRoad) 2 else 0) }
        // A dark casing under every road first, so a road reads on any ground; then the roads.
        for (r in ordered) {
            val emphasis = r.plannedId.isNotEmpty() && (r.plannedId == selectedRoad || r === hoveredRoad)
            line(r, lineWidth + if (emphasis) 6f else 2f, if (emphasis) SELECTED else CASING)
        }
        for (r in ordered) {
            val base = TIER_COLORS.getOrElse(r.tier) { TIER_COLORS[0] }
            line(r, lineWidth, if (r.charted) base else (base and 0x00FFFFFF) or UNCHARTED_ALPHA)
        }
        graphics.flush()

        for (t in towns) {
            val tx = sx(t.pos.x.toDouble()).toInt(); val tz = sz(t.pos.z.toDouble()).toInt()
            graphics.fill(tx - 2, tz - 2, tx + 3, tz + 3, 0xFF000000.toInt())
            graphics.fill(tx - 1, tz - 1, tx + 2, tz + 2, if (t === hoveredTown) 0xFFFFFFFF.toInt() else TOWN)
        }
        // Names only for markers, emphasised first, and none over another: a full map of labels is unreadable.
        val labelled = ArrayList<IntArray>()
        val byPriority = markers.sortedBy { if (it === hoveredMarker || it.id == emphasised) 0 else if (!it.active) 2 else 1 }
        for (m in byPriority) {
            val mx = sx(m.x.toDouble()).toInt(); val mz = sz(m.z.toDouble()).toInt()
            val big = m === hoveredMarker || m.id == emphasised
            val r = if (big) 5 else 3
            graphics.fill(mx - r - 1, mz - r - 1, mx + r + 2, mz + r + 2, 0xFF000000.toInt())
            graphics.fill(mx - r, mz - r, mx + r + 1, mz + r + 1, m.color)
            if (m.label.isEmpty()) continue
            val lw = font.width(m.label)
            val box = intArrayOf(mx + r + 3, mz - 5, mx + r + 3 + lw + 4, mz + 6)
            if (!big && labelled.any { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) continue
            labelled.add(box)
            graphics.fill(box[0], box[1], box[2], box[3], LABEL_BACK)
            graphics.drawString(font, m.label, box[0] + 2, box[1] + 2, if (m.active) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt(), false)
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

    /**
     * The atlas regions in view, each one image scaled into place by the pose (so neighbours meet
     * without seams), transparent where the player has not been. A few regions come off disk per frame.
     */
    private fun drawAtlas(graphics: GuiGraphics) {
        // Fills are batched and blits are not: flush the background first or it lands on top.
        graphics.flush()
        val r = Atlas.REGION
        val minRx = Math.floorDiv((centerX - width / 2.0 / scale).toInt(), r); val maxRx = Math.floorDiv((centerX + width / 2.0 / scale).toInt(), r)
        val minRz = Math.floorDiv((centerZ - height / 2.0 / scale).toInt(), r); val maxRz = Math.floorDiv((centerZ + height / 2.0 / scale).toInt(), r)
        var loads = LOADS_PER_FRAME
        RenderSystem.enableBlend()
        for (rz in minRz..maxRz) for (rx in minRx..maxRx) {
            if (!Atlas.known(dimension, rx, rz)) continue
            val loaded = Atlas.loaded(dimension, rx, rz)
            if (!loaded && loads-- <= 0) continue
            val region = Atlas.region(dimension, rx, rz, create = false) ?: continue
            graphics.pose().pushPose()
            graphics.pose().translate(sx((rx * r).toDouble()), sz((rz * r).toDouble()), 0f)
            val s = (scale * Atlas.STEP).toFloat()
            graphics.pose().scale(s, s, 1f)
            graphics.blit(region.location, 0, 0, 0f, 0f, Atlas.PIXELS, Atlas.PIXELS, Atlas.PIXELS, Atlas.PIXELS)
            graphics.pose().popPose()
        }
        RenderSystem.disableBlend()
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
        /** Atlas regions read from disk per frame at most; the rest follow over the next frames. */
        private const val LOADS_PER_FRAME = 4
        private val CASING = 0xE0201810.toInt()
        private val LABEL_BACK = 0xB0101010.toInt()
        private const val MIN_SCALE = 0.005
        private const val MAX_SCALE = 2.0
        private const val PICK_MARKER = 7.0
        private const val PICK_ROAD = 5.0
        private val BACKGROUND = 0xFF1E2428.toInt()
        private val TOWN = 0xFFC8C8C8.toInt()
        private val PLAYER = 0xFFFF5050.toInt()
        private val SELECTED = 0xFFFFE070.toInt()
        private const val UNCHARTED_ALPHA = 0x50000000
        /** Dirt, gravel, paved: earth brown, stone grey, pale stone. */
        val TIER_COLORS = intArrayOf(0xFFA0703C.toInt(), 0xFF9AA0A4.toInt(), 0xFFF0E8D0.toInt())
    }
}
