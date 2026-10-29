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
    data class Marker(val id: String, val x: Int, val z: Int, val color: Int, val active: Boolean = true, val label: String = "", val kind: Kind = Kind.TOWN)

    /** How a marker is drawn: a town (village icon on a coloured disc), a sign (banner), or where the player leaves from (a pin). */
    enum class Kind { TOWN, SIGN, HERE }

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
        drawRoads(graphics, roads)
        RenderSystem.enableBlend()
        for (t in towns) {
            if (markers.any { it.x == t.pos.x && it.z == t.pos.z }) continue
            icon(graphics, VILLAGE, sx(t.pos.x.toDouble()), sz(t.pos.z.toDouble()), if (t === hoveredTown) 10f else 8f)
        }
        // Names only for markers, emphasised first, and none over another: a full map of labels is unreadable.
        val labelled = ArrayList<IntArray>()
        val byPriority = markers.sortedBy { if (it === hoveredMarker || it.id == emphasised) 2 else if (!it.active) 0 else 1 }
        // Drawn back to front, labels decided front to back.
        for (m in byPriority) drawMarker(graphics, m, m === hoveredMarker || m.id == emphasised)
        for (m in byPriority.reversed()) {
            if (m.label.isEmpty()) continue
            val big = m === hoveredMarker || m.id == emphasised
            val mx = sx(m.x.toDouble()).toInt(); val mz = sz(m.z.toDouble()).toInt()
            val lw = font.width(m.label)
            val box = intArrayOf(mx + 8, mz - 5, mx + 8 + lw + 4, mz + 6)
            if (!big && labelled.any { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) continue
            labelled.add(box)
            graphics.fill(box[0], box[1], box[2], box[3], LABEL_BACK)
            graphics.drawString(font, m.label, box[0] + 2, box[1] + 2, if (m.active) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt(), false)
        }
        Minecraft.getInstance().player?.let { p ->
            // Vanilla's map arrow points north; a yaw of 0 faces south.
            if (p.level().dimension().location() == dimension) icon(graphics, PLAYER_ICON, sx(p.x), sz(p.z), 10f, p.yRot + 180f)
        }
        RenderSystem.disableBlend()
        graphics.disableScissor()
        graphics.renderOutline(x, y, width, height, 0xFF606060.toInt())
    }

    /**
     * Roads as continuous strokes: the polyline thinned in screen space (points closer than a couple of
     * pixels merged, so a zoomed-out road is not a bristle of tiny segments), each segment a quad and
     * each bend a square of the stroke's width, over a dark casing drawn the same way.
     */
    private fun drawRoads(graphics: GuiGraphics, roads: List<MapRoad>) {
        val pose = graphics.pose().last().pose()
        val buffer = graphics.bufferSource().getBuffer(RenderType.gui())
        fun quad(ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float, dx: Float, dz: Float, color: Int) {
            // The winding vanilla's fill uses; the other way round the quad is culled as a back face.
            buffer.addVertex(pose, ax, az, 0f).setColor(color)
            buffer.addVertex(pose, bx, bz, 0f).setColor(color)
            buffer.addVertex(pose, cx, cz, 0f).setColor(color)
            buffer.addVertex(pose, dx, dz, 0f).setColor(color)
        }
        fun stroke(pts: FloatArray, w: Float, color: Int) {
            val h = w / 2
            for (i in 0 until pts.size / 2 - 1) {
                val ax = pts[i * 2]; val az = pts[i * 2 + 1]; val bx = pts[i * 2 + 2]; val bz = pts[i * 2 + 3]
                val len = hypot((bx - ax).toDouble(), (bz - az).toDouble()).toFloat()
                if (len < 0.01f) continue
                val nx = -(bz - az) / len * h; val nz = (bx - ax) / len * h
                quad(ax - nx, az - nz, ax + nx, az + nz, bx + nx, bz + nz, bx - nx, bz - nz, color)
            }
            for (i in 0 until pts.size / 2) {
                val px = pts[i * 2]; val pz = pts[i * 2 + 1]
                quad(px - h, pz - h, px - h, pz + h, px + h, pz + h, px + h, pz - h, color)
            }
        }
        // GUI units, which are several screen pixels each at the usual GUI scales: keep it thin.
        val w = (1.0 * scale / 0.05).coerceIn(1.0, 2.5).toFloat()
        val strokes = roads.map { r -> r to screenPoints(r) }
            .sortedBy { (r, _) -> (if (r.plannedId in highlighted) 1 else 0) + (if (r.plannedId == selectedRoad || r === hoveredRoad) 2 else 0) }
        for ((r, pts) in strokes) {
            val emphasis = r.plannedId.isNotEmpty() && (r.plannedId == selectedRoad || r === hoveredRoad)
            stroke(pts, w + if (emphasis) 3f else 1f, if (emphasis) SELECTED else CASING)
        }
        for ((r, pts) in strokes) {
            val base = TIER_COLORS.getOrElse(r.tier) { TIER_COLORS[0] }
            // In road works the other roads step back behind the town's own.
            val faded = !r.charted || (highlighted.isNotEmpty() && r.plannedId !in highlighted)
            stroke(pts, w, if (faded) (base and 0x00FFFFFF) or FADED_ALPHA else base)
        }
        graphics.flush()
    }

    private fun screenPoints(r: MapRoad): FloatArray {
        val out = ArrayList<Float>(r.xs.size * 2)
        var lastX = Float.NaN; var lastZ = Float.NaN
        for (i in r.xs.indices) {
            val px = sx(r.xs[i].toDouble()); val pz = sz(r.zs[i].toDouble())
            val keep = i == 0 || i == r.xs.size - 1 || hypot((px - lastX).toDouble(), (pz - lastZ).toDouble()) >= MIN_SEGMENT_PX
            if (!keep) continue
            out.add(px); out.add(pz); lastX = px; lastZ = pz
        }
        return out.toFloatArray()
    }

    /** One of vanilla's 8-pixel map icons, centred on (cx, cz), [size] pixels across, turned [degrees] clockwise. */
    private fun icon(graphics: GuiGraphics, texture: ResourceLocation, cx: Float, cz: Float, size: Float, degrees: Float = 0f) {
        graphics.pose().pushPose()
        graphics.pose().translate(cx, cz, 0f)
        if (degrees != 0f) graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(degrees))
        graphics.pose().scale(size / 8f, size / 8f, 1f)
        graphics.blit(texture, -4, -4, 0f, 0f, 8, 8, 8, 8)
        graphics.pose().popPose()
    }

    private fun drawMarker(graphics: GuiGraphics, m: Marker, big: Boolean) {
        val mx = sx(m.x.toDouble()); val mz = sz(m.z.toDouble())
        when (m.kind) {
            // A pin whose tip is the spot.
            Kind.HERE -> icon(graphics, PIN, mx, mz - 4f, if (big) 12f else 10f)
            else -> {
                val r = if (big) 7 else 6
                disc(graphics, mx.toInt(), mz.toInt(), r + 1, 0xFF000000.toInt())
                disc(graphics, mx.toInt(), mz.toInt(), r, m.color)
                graphics.flush()
                icon(graphics, if (m.kind == Kind.SIGN) BANNER else VILLAGE, mx, mz, if (big) 10f else 8f)
            }
        }
    }

    /** A filled circle out of one fill per row. */
    private fun disc(graphics: GuiGraphics, cx: Int, cz: Int, r: Int, color: Int) {
        for (dz in -r..r) {
            val half = kotlin.math.sqrt((r * r - dz * dz).toDouble() + 0.5).toInt()
            graphics.fill(cx - half, cz + dz, cx + half + 1, cz + dz + 1, color)
        }
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
        private val CASING = 0xD0281A0E.toInt()
        /** Screen pixels a road's thinned polyline keeps between its points. */
        private const val MIN_SEGMENT_PX = 2.5
        private const val FADED_ALPHA = 0x90000000.toInt()
        private val VILLAGE = ResourceLocation.withDefaultNamespace("textures/map/decorations/plains_village.png")
        private val BANNER = ResourceLocation.withDefaultNamespace("textures/map/decorations/white_banner.png")
        private val PIN = ResourceLocation.withDefaultNamespace("textures/map/decorations/target_point.png")
        private val PLAYER_ICON = ResourceLocation.withDefaultNamespace("textures/map/decorations/player.png")
        private val LABEL_BACK = 0xB0101010.toInt()
        private const val MIN_SCALE = 0.005
        private const val MAX_SCALE = 2.0
        private const val PICK_MARKER = 7.0
        private const val PICK_ROAD = 5.0
        private val BACKGROUND = 0xFF1E2428.toInt()
        private val SELECTED = 0xFFFFE070.toInt()
        /** Dirt, gravel, paved: light enough to read on grass and forest, told apart by warmth. */
        val TIER_COLORS = intArrayOf(0xFFE2B26E.toInt(), 0xFFC4CACF.toInt(), 0xFFFFF6E2.toInt())
    }
}
