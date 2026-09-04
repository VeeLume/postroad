package io.github.veelume.postroad.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.veelume.postroad.roads.gen.RoadDebug
import io.github.veelume.postroad.roads.gen.RoadDebugClient
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
    private const val LABEL_RANGE = 96.0

    fun register() {
        FORGE_BUS.addListener(RenderLevelStageEvent::class.java, Consumer(::onRenderStage))
    }

    private fun onRenderStage(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val state = RoadDebugClient.state
        if (state.roads.isEmpty() && state.towns.isEmpty() && state.nodes.isEmpty()) return
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
        for (j in state.junctions) {
            val y = surfaceY(j.pos)
            post(pose, lines, j.pos, y, 4.0, if (j.signPlaced) Triple(255, 230, 0) else Triple(200, 120, 0))
        }
        for (n in state.nodes) {
            val y = surfaceY(n.pos)
            post(pose, lines, n.pos, y, 3.0, if (n.kind == "town") Triple(255, 80, 255) else Triple(80, 220, 255))
        }
        for (t in state.towns) {
            val b = t.box
            LevelRenderer.renderLineBox(pose, lines, b[0].toDouble(), b[1].toDouble(), b[2].toDouble(), b[3] + 1.0, b[4] + 1.0, b[5] + 1.0, 1.0f, 0.3f, 1.0f, 0.8f)
        }
        buffers.endBatch(RenderType.lines())

        // Labels near the camera.
        val font = mc.font
        for (t in state.towns) {
            val b = t.box
            val cx = (b[0] + b[3]) / 2.0; val cz = (b[2] + b[5]) / 2.0
            label(pose, buffers, font, camera, cx, b[4] + 2.0, cz, t.name, cam.distanceToSqr(cx, b[4].toDouble(), cz), 0xFFFF80FF.toInt())
        }
        for (n in state.nodes) {
            val y = surfaceY(n.pos)
            label(pose, buffers, font, camera, n.pos.x + 0.5, y + 3.3, n.pos.z + 0.5, n.name, cam.distanceToSqr(n.pos.x + 0.5, y, n.pos.z + 0.5), 0xFF80E0FF.toInt())
        }
        for (road in state.roads) {
            val mid = road.points[road.points.size / 2]
            val y = surfaceY(mid)
            label(pose, buffers, font, camera, mid.x + 0.5, y + 1.5, mid.z + 0.5, road.id, cam.distanceToSqr(mid.x + 0.5, y, mid.z + 0.5), 0xFFFFC060.toInt())
        }
        buffers.endBatch()
        pose.popPose()
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
