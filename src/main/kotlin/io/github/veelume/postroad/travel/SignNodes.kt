package io.github.veelume.postroad.travel

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.advancement.PostroadAdvancements
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.registry.PostroadBlocks
import io.github.veelume.postroad.registry.PostroadItems
import io.github.veelume.postroad.roads.RoadNode
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.function.Consumer

/**
 * Signs as nodes. Using the Charting Map on a sign links it to the nearest path (or unlinks
 * it); left-clicking a linked sign opens the travel list. Which blocks count as signs is the
 * block tag `#postroad:sign_nodes`; the plain right-click stays the sign's own editor.
 */
object SignNodes {
    val SIGN_NODES: TagKey<net.minecraft.world.level.block.Block> = TagKey.create(Registries.BLOCK, Postroad.id("sign_nodes"))

    fun register() {
        FORGE_BUS.addListener(PlayerInteractEvent.RightClickBlock::class.java, Consumer(::onRightClick))
        FORGE_BUS.addListener(PlayerInteractEvent.LeftClickBlock::class.java, Consumer(::onLeftClick))
    }

    fun nodeIdAt(dimension: ResourceLocation, pos: BlockPos): String = "sign/$dimension/${pos.x}/${pos.y}/${pos.z}"

    private fun onRightClick(event: PlayerInteractEvent.RightClickBlock) {
        if (event.hand != InteractionHand.MAIN_HAND || !event.itemStack.`is`(PostroadItems.CHARTING_MAP.get())) return
        val level = event.level
        if (!level.getBlockState(event.pos).`is`(SIGN_NODES)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.sidedSuccess(level.isClientSide)
        if (level !is ServerLevel) return
        val player = event.entity as? ServerPlayer ?: return
        toggleLink(level, player, event.pos)
    }

    private fun onLeftClick(event: PlayerInteractEvent.LeftClickBlock) {
        val level = event.level
        val dimension = level.dimension().location()
        val state = level.getBlockState(event.pos)
        val nodeId = when {
            state.`is`(SIGN_NODES) -> nodeIdAt(dimension, event.pos)
            state.`is`(PostroadBlocks.DEPOT.get()) -> null // the depot has its own Travel button
            else -> return
        } ?: return
        if (level is ServerLevel) {
            val network = Network.get(level.server)
            if (network.nodes[nodeId] == null) return
            event.isCanceled = true
            (event.entity as? ServerPlayer)?.let { TravelService.open(it, nodeId) }
        } else {
            // Client: cancel too so the sign is not attacked while the screen opens.
            event.isCanceled = true
        }
    }

    fun toggleLink(level: ServerLevel, player: ServerPlayer, pos: BlockPos) {
        val network = Network.get(level.server)
        val dimension = level.dimension().location()
        val id = nodeIdAt(dimension, pos)
        val existing = network.nodes[id]
        if (existing != null) {
            network.nodes.remove(id)
            network.setDirty()
            player.displayClientMessage(Component.translatable("message.postroad.sign.unlinked", existing.name).withStyle(ChatFormatting.YELLOW), false)
            return
        }
        val near = network.nearestPathPoint(dimension, pos, PostroadConfig.joinDistance)
        if (near == null) {
            player.displayClientMessage(Component.translatable("message.postroad.sign.no_path", PostroadConfig.joinDistance.toInt()).withStyle(ChatFormatting.YELLOW), false)
            return
        }
        val (path, index) = near
        val ownText = signName(level, pos)?.takeUnless { it.startsWith(Component.translatable("sign.postroad.to", "").string.trim()) }
        val town = nearestTownName(network, dimension, pos)
        val name = ownText ?: defaultNodeName(network, path, index) ?: town?.let { Component.translatable("message.postroad.sign.default_name", it).string } ?: "Signpost"
        val node = RoadNode(id, RoadNode.KIND_SIGN, dimension, pos, path.id, index, name, null)
        network.nodes[id] = node
        network.setDirty()
        if (PostroadConfig.autoNameSigns) {
            level.getBlockEntity(pos)?.let { entity ->
                if (entity is SignBlockEntity) {
                    // A plain sign with no text of its own shows the place it stands by.
                    if (ownText == null && town != null) SignWriter.labelSign(level, entity, Component.translatable("sign.postroad.town").string, town)
                } else {
                    val arms = SignWriter.pointWaySign(level, entity, network, node, player.blockPosition())
                    if (arms > 0) player.displayClientMessage(Component.translatable("message.postroad.sign.arms_set", arms), true)
                }
            }
        }
        PostroadAdvancements.award(player, PostroadAdvancements.SIGN_LINKED)
        player.displayClientMessage(Component.translatable("message.postroad.sign.linked", name, path.length.toInt()).withStyle(ChatFormatting.GOLD), false)
        Postroad.LOGGER.info("{} linked sign '{}' at {} to path {}", player.gameProfile.name, name, pos.toShortString(), path.id)
    }

    /**
     * Registers a generated signpost as a node on [pathId] at [index] (the builder's junction signs).
     * Named after the road's towns when known, else a plain "Junction". Returns the node, or null if the
     * path is unknown or the position already carries a node.
     */
    fun linkGenerated(level: ServerLevel, pos: BlockPos, pathId: String, index: Int): RoadNode? {
        val network = Network.get(level.server)
        val dimension = level.dimension().location()
        val path = network.paths[pathId] ?: return null
        val id = nodeIdAt(dimension, pos)
        if (network.nodes[id] != null) return null
        val storage = io.github.veelume.postroad.roads.gen.RoadPlanStorage.get(level.server)
        val road = storage.roads[pathId]
        val from = road?.let { network.places[it.from]?.name }
        val to = road?.let { network.places[it.to]?.name }
        val name = when {
            from != null && to != null -> Component.translatable("message.postroad.sign.junction_name", from, to).string
            from != null || to != null -> Component.translatable("message.postroad.sign.road_name", from ?: to, minOf(path.lengthBetween(0, index), path.lengthBetween(index, path.points.size - 1)).toInt()).string
            else -> Component.translatable("message.postroad.sign.junction").string
        }
        val node = RoadNode(id, RoadNode.KIND_SIGN, dimension, pos, pathId, index.coerceIn(0, path.points.size - 1), name, null)
        network.nodes[id] = node
        network.setDirty()
        return node
    }

    /** "<Town> road, N blocks": the nearest town along this path, by road distance. */
    private fun defaultNodeName(network: Network, path: io.github.veelume.postroad.roads.RoadPath, index: Int): String? {
        val towns = network.nodes.values.filter { it.pathId == path.id && it.kind == RoadNode.KIND_TOWN }
        val nearest = towns.minByOrNull { path.lengthBetween(index, it.pointIndex) } ?: return null
        val blocks = path.lengthBetween(index, nearest.pointIndex).toInt()
        return Component.translatable("message.postroad.sign.road_name", nearest.name, blocks).string
    }

    /** Renames a sign node (never a town) and rewrites a plain sign's text to match. */
    fun rename(level: ServerLevel, player: ServerPlayer, nodeId: String, newName: String): Boolean {
        val network = Network.get(level.server)
        val node = network.nodes[nodeId] ?: return false
        if (node.kind != RoadNode.KIND_SIGN) return false
        val name = newName.trim().take(32)
        if (name.isEmpty()) return false
        network.nodes[nodeId] = node.copy(name = name)
        network.setDirty()
        (level.getBlockEntity(node.pos) as? SignBlockEntity)?.let { SignWriter.labelSign(level, it, name, "") }
        player.displayClientMessage(Component.translatable("message.postroad.sign.renamed", name).withStyle(ChatFormatting.GOLD), false)
        return true
    }

    /** First non-empty line of a vanilla sign, or the first text found on a sign-post tile. */
    fun signName(level: ServerLevel, pos: BlockPos): String? {
        val entity = level.getBlockEntity(pos) ?: return null
        if (entity is SignBlockEntity) {
            for (i in 0 until 4) {
                val line = entity.frontText.getMessage(i, false).string.trim()
                if (line.isNotEmpty()) return line
            }
            return null
        }
        return try {
            val tag = entity.saveWithoutMetadata(level.registryAccess())
            findText(tag, level)
        } catch (e: Exception) {
            null
        }
    }

    /** Supplementaries way signs: `SignUp`/`SignDown` → `TextHolder.message` (a list of strings), active arms first. */
    private fun findText(tag: CompoundTag, level: ServerLevel): String? {
        val arms = listOf("SignUp", "SignDown").map { tag.getCompound(it) }.sortedByDescending { it.getBoolean("Active") }
        for (arm in arms) {
            if (arm.isEmpty) continue
            val holder = arm.getCompound("TextHolder")
            val messages = holder.getList("message", net.minecraft.nbt.Tag.TAG_STRING.toInt())
            for (i in 0 until messages.size) {
                val line = messages.getString(i).trim()
                if (line.isNotEmpty()) return line
            }
        }
        return null
    }

    private fun nearestTownName(network: Network, dimension: ResourceLocation, pos: BlockPos): String? =
        network.towns().filter { it.dimension == dimension }.minByOrNull { it.pos.distSqr(pos) }?.name
}
