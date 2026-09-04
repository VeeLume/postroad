package io.github.veelume.postroad.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.veelume.postroad.roads.gen.RoadDebug
import io.github.veelume.postroad.roads.gen.RoadDebugClient
import io.github.veelume.postroad.roads.gen.Terrain
import io.github.veelume.postroad.roads.gen.TerrainDebugClient
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.function.Consumer
import kotlin.math.max

/**
 * Draws the road plan in the world: planned roads as lines lifted above the actual surface,
 * orange where nothing is built, white where the chunk is built, green once charted; junctions as
 * yellow posts; town boxes as magenta wireframes with the town's name; nodes as cyan posts with
 * their names. Toggled per player with `/postroad roads debug`; data arrives every two seconds.
 */
object RoadDebugRenderer {
    private const val LIFT = 1.2
    private const val LABEL_RANGE = 160.0

    fun register() {
        FORGE_BUS.addListener(RenderLevelStageEvent::class.java, Consumer(::onRenderStage))
    }

    private fun onRenderStage(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val state = RoadDebugClient.state
        val terrain = TerrainDebugClient.state
        if (state.roads.isEmpty() && state.towns.isEmpty() && state.nodes.isEmpty() && terrain.grids.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val camera = event.camera
        val cam = camera.position
        val pose = event.poseStack
        val buffers = mc.renderBuffers().bufferSource()

        pose.pushPose()
        pose.translate(-cam.x, -cam.y, -cam.z)

        fun surfaceY(p: BlockPos): Double {
            val y = if (level.hasChunk(p.x shr 4, p.z shr 4)) level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.x, p.z) else p.y
            return y + LIFT
        }

        // Roads: one line strip each, coloured per segment by build state.
        for (road in state.roads) {
            val built = road.builtChunks.toHashSet()
            val strip = buffers.getBuffer(RenderType.debugLineStrip(3.0))
            for (p in road.points) {
                val (r, g, b) = when {
                    road.charted -> Triple(60, 230, 60)
                    RoadDebug.chunkOf(p) in built -> Triple(240, 240, 240)
                    else -> Triple(255, 150, 30)
                }
                strip.addVertex(pose.last().pose(), (p.x + 0.5).toFloat(), surfaceY(p).toFloat(), (p.z + 0.5).toFloat()).setColor(r, g, b, 255)
            }
            buffers.endBatch(RenderType.debugLineStrip(3.0))
        }

        // Junctions and nodes as short vertical posts; towns as boxes.
        val lines = buffers.getBuffer(RenderType.lines())
        for (g in terrain.grids) drawGrid(pose, lines, level, g)
        for (j in state.junctions) {
            val y = surfaceY(j.pos)
            post(pose, lines, j.pos, y, 4.0, if (j.signPlaced) Triple(255, 230, 0) else Triple(200, 120, 0))
        }
        for (n in state.nodes) {
            val y = surfaceY(n.pos)
            post(pose, lines, n.pos, y, 3.0, if (n.kind == "town") Triple(255, 80, 255) else Triple(80, 220, 255))
        }
        for (d in state.dropped) {
            // A dropped pair: a straight red line between the two towns, well above the ground.
            val m = pose.last()
            val ya = surfaceY(d.a) + 12; val yb = surfaceY(d.b) + 12
            lines.addVertex(m.pose(), (d.a.x + 0.5).toFloat(), ya.toFloat(), (d.a.z + 0.5).toFloat()).setColor(255, 40, 40, 255).setNormal(m, 0f, 1f, 0f)
            lines.addVertex(m.pose(), (d.b.x + 0.5).toFloat(), yb.toFloat(), (d.b.z + 0.5).toFloat()).setColor(255, 40, 40, 255).setNormal(m, 0f, 1f, 0f)
        }
        for (t in state.towns) {
            val b = t.box
            // Towns magenta, predicted obstacles (other surface structures) red.
            val obstacle = t.id.startsWith("obstacle:")
            LevelRenderer.renderLineBox(pose, lines, b[0].toDouble(), b[1].toDouble(), b[2].toDouble(), b[3] + 1.0, b[4] + 1.0, b[5] + 1.0, 1.0f, if (obstacle) 0.25f else 0.3f, if (obstacle) 0.25f else 1.0f, 0.8f)
        }
        buffers.endBatch(RenderType.lines())

        // Labels near the camera.
        val font = mc.font
        for (t in state.towns) {
            val b = t.box
            val cx = (b[0] + b[3]) / 2.0; val cz = (b[2] + b[5]) / 2.0
            label(pose, buffers, font, camera, cx, b[4] + 2.0, cz, t.name, cam.distanceToSqr(cx, b[4].toDouble(), cz), if (t.id.startsWith("obstacle:")) 0xFFFF6060.toInt() else 0xFFFF80FF.toInt())
        }
        for (n in state.nodes) {
            val y = surfaceY(n.pos)
            label(pose, buffers, font, camera, n.pos.x + 0.5, y + 3.3, n.pos.z + 0.5, n.name, cam.distanceToSqr(n.pos.x + 0.5, y, n.pos.z + 0.5), 0xFF80E0FF.toInt())
        }
        for (d in state.dropped) {
            val mx = (d.a.x + d.b.x) / 2.0; val mz = (d.a.z + d.b.z) / 2.0
            val my = (surfaceY(d.a) + surfaceY(d.b)) / 2.0 + 12
            label(pose, buffers, font, camera, mx, my, mz, "dropped: ${d.reason}", cam.distanceToSqr(mx, my, mz), 0xFFFF6060.toInt())
        }
        for (road in state.roads) {
            val mid = road.points[road.points.size / 2]
            val y = surfaceY(mid)
            label(pose, buffers, font, camera, mid.x + 0.5, y + 1.5, mid.z + 0.5, road.id, cam.distanceToSqr(mid.x + 0.5, y, mid.z + 0.5), 0xFFFFC060.toInt())
        }
        buffers.endBatch()
        pose.popPose()
    }

    /**
     * The planner's cells: a cross at each estimated surface, coloured by the step class to the
     * cell's east and south neighbours (what the planner would pay to walk there) or by its flags,
     * and a tick from the estimate to the real surface where the two disagree by more than a block.
     */
    private fun drawGrid(pose: PoseStack, vc: VertexConsumer, level: net.minecraft.client.multiplayer.ClientLevel, g: io.github.veelume.postroad.roads.gen.DebugGrid) {
        val m = pose.last()
        val arm = (g.cellSize * 0.35).toFloat()
        val fullAlpha = if (g.cellSize <= 4) 255 else 120
        var alpha = fullAlpha
        fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, r: Int, gr: Int, b: Int, a: Int = alpha) {
            vc.addVertex(m.pose(), x1, y1, z1).setColor(r, gr, b, a).setNormal(m, 0f, 1f, 0f)
            vc.addVertex(m.pose(), x2, y2, z2).setColor(r, gr, b, a).setNormal(m, 0f, 1f, 0f)
        }
        for (dz in 0 until g.h) for (dx in 0 until g.w) {
            val h = g.heights[dz * g.w + dx]
            if (h == Int.MIN_VALUE) continue
            val flags = g.flags[dz * g.w + dx].toInt()
            // Estimates are drawn faint; generated terrain (Distant Horizons) at full strength.
            alpha = if (flags and Terrain.ESTIMATED != 0) fullAlpha / 3 else fullAlpha
            var step = 0
            if (dx + 1 < g.w) g.heights[dz * g.w + dx + 1].let { if (it != Int.MIN_VALUE) step = max(step, kotlin.math.abs(it - h)) }
            if (dz + 1 < g.h) g.heights[(dz + 1) * g.w + dx].let { if (it != Int.MIN_VALUE) step = max(step, kotlin.math.abs(it - h)) }
            val (r, gr, b) = when {
                flags and Terrain.ROAD != 0 -> Triple(255, 255, 255)
                flags and Terrain.BLOCKED != 0 -> Triple(255, 0, 255)
                flags and Terrain.LAVA != 0 -> Triple(255, 90, 0)
                flags and Terrain.WATER != 0 -> Triple(60, 120, 255)
                step == 0 -> Triple(80, 220, 80)
                step <= 2 -> Triple(230, 230, 60)
                step <= 4 -> Triple(255, 150, 30)
                else -> Triple(150, 0, 170)
            }
            val cx = ((g.originCx + dx) * g.cellSize + g.cellSize / 2 + 0.5).toFloat()
            val cz = ((g.originCz + dz) * g.cellSize + g.cellSize / 2 + 0.5).toFloat()
            val y = (h + 0.15).toFloat()
            line(cx - arm, y, cz, cx + arm, y, cz, r, gr, b)
            line(cx, y, cz - arm, cx, y, cz + arm, r, gr, b)
            // Estimate against the world, fine cells only: red tick floating above the ground, blue tick buried.
            if (g.cellSize <= 4 && level.hasChunk(cx.toInt() shr 4, cz.toInt() shr 4)) {
                val real = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx.toInt(), cz.toInt())
                if (h > real + 1) line(cx, real.toFloat(), cz, cx, y, cz, 255, 50, 50, 255)
                else if (h < real - 1) line(cx, y, cz, cx, real.toFloat(), cz, 50, 90, 255, 255)
            }
        }
    }

    private fun post(pose: PoseStack, vc: VertexConsumer, p: BlockPos, y: Double, height: Double, rgb: Triple<Int, Int, Int>) {
        val x = (p.x + 0.5).toFloat(); val z = (p.z + 0.5).toFloat()
        val m = pose.last()
        vc.addVertex(m.pose(), x, y.toFloat(), z).setColor(rgb.first, rgb.second, rgb.third, 255).setNormal(m, 0f, 1f, 0f)
        vc.addVertex(m.pose(), x, (y + height).toFloat(), z).setColor(rgb.first, rgb.second, rgb.third, 255).setNormal(m, 0f, 1f, 0f)
    }

    private fun label(pose: PoseStack, buffers: MultiBufferSource.BufferSource, font: Font, camera: net.minecraft.client.Camera, x: Double, y: Double, z: Double, text: String, distSqr: Double, color: Int) {
        if (distSqr > LABEL_RANGE * LABEL_RANGE) return
        pose.pushPose()
        pose.translate(x, y, z)
        pose.mulPose(camera.rotation())
        val scale = max(0.02f, (Math.sqrt(distSqr) / 400.0).toFloat())
        pose.scale(scale, -scale, scale)
        val w = font.width(text)
        font.drawInBatch(text, -w / 2f, 0f, color, false, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0x40000000, 0xF000F0)
        pose.popPose()
    }
}
