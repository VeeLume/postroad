package io.github.veelume.postroad.roads

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.network.Network
import net.minecraft.ChatFormatting
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/**
 * The Charting Map. Right-click opens the charting screen (client); its buttons are
 * [ChartingActionPayload]s; the server answers with [ChartingStatePayload]. Using the map on a
 * sign is stage 3 (link to path).
 */
class ChartingMapItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        if (level.isClientSide) {
            ChartingClient.openScreen()
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide)
    }
}

/** What the charting screen shows. Held client-side in [ChartingClient.state]. */
data class ChartingState(
    val active: Boolean,
    val distance: Int,
    val roadShare: Int,
    val tier: String,
    /** Name/tier/length of the path nearest the player, or empty. */
    val nearPath: String,
    val pathCount: Int,
) {
    companion object {
        val EMPTY = ChartingState(false, 0, 0, "", "", 0)
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ChartingState> = StreamCodec.of(
            { buf, s -> buf.writeBoolean(s.active); buf.writeVarInt(s.distance); buf.writeVarInt(s.roadShare); buf.writeUtf(s.tier); buf.writeUtf(s.nearPath); buf.writeVarInt(s.pathCount) },
            { buf -> ChartingState(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readVarInt()) },
        )
    }
}

data class ChartingStatePayload(val state: ChartingState) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ChartingStatePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ChartingStatePayload> = CustomPacketPayload.Type(Postroad.id("charting_state"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ChartingStatePayload> =
            ChartingState.STREAM_CODEC.map(::ChartingStatePayload, ChartingStatePayload::state)
    }
}

data class ChartingActionPayload(val action: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ChartingActionPayload> = TYPE

    companion object {
        const val STATUS = 0
        const val START = 1
        const val FINISH = 2
        const val ABORT = 3
        const val REMOVE_PATH = 4
        const val SEVER = 5

        val TYPE: CustomPacketPayload.Type<ChartingActionPayload> = CustomPacketPayload.Type(Postroad.id("charting_action"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ChartingActionPayload> =
            ByteBufCodecs.VAR_INT.map(::ChartingActionPayload, ChartingActionPayload::action).cast()
    }
}

/** Client-side holder the screen reads; written by the payload handler. No client classes here. */
object ChartingClient {
    @Volatile
    var state: ChartingState = ChartingState.EMPTY

    /** Set by the client setup; opens the charting screen. Null on the server. */
    var screenOpener: Runnable? = null

    fun openScreen() {
        screenOpener?.run()
    }
}

object ChartingNetworking {
    fun register(registrar: PayloadRegistrar) {
        registrar.playToClient(ChartingStatePayload.TYPE, ChartingStatePayload.STREAM_CODEC) { payload, _ ->
            ChartingClient.state = payload.state
        }
        registrar.playToServer(ChartingActionPayload.TYPE, ChartingActionPayload.STREAM_CODEC) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@playToServer
            handle(player, payload.action)
        }
    }

    fun handle(player: ServerPlayer, action: Int) {
        val network = Network.get(player.server)
        when (action) {
            ChartingActionPayload.START -> if (!Charting.start(player)) {
                player.displayClientMessage(Component.translatable("message.postroad.chart.already").withStyle(ChatFormatting.YELLOW), false)
            }
            ChartingActionPayload.FINISH -> Charting.finish(player)
            ChartingActionPayload.ABORT -> Charting.abort(player, "message.postroad.chart.aborted")
            ChartingActionPayload.REMOVE_PATH -> {
                val near = network.nearestPathPoint(player.level().dimension().location(), player.blockPosition(), PostroadConfig.joinDistance * 2.0)
                if (near == null) {
                    player.displayClientMessage(Component.translatable("message.postroad.chart.no_path_near").withStyle(ChatFormatting.YELLOW), false)
                } else {
                    network.removePath(near.first.id)
                    player.displayClientMessage(Component.translatable("message.postroad.chart.removed", near.first.length.toInt()).withStyle(ChatFormatting.GOLD), false)
                    Postroad.LOGGER.info("{} removed path {}", player.gameProfile.name, near.first.id)
                }
            }
            ChartingActionPayload.SEVER -> {
                val near = network.nearestPathPoint(player.level().dimension().location(), player.blockPosition(), PostroadConfig.joinDistance * 2.0)
                if (near == null) {
                    player.displayClientMessage(Component.translatable("message.postroad.chart.no_path_near").withStyle(ChatFormatting.YELLOW), false)
                } else if (network.severPath(near.first.id, near.second)) {
                    player.displayClientMessage(Component.translatable("message.postroad.chart.severed").withStyle(ChatFormatting.GOLD), false)
                    Postroad.LOGGER.info("{} severed path {} at {}", player.gameProfile.name, near.first.id, near.second)
                } else {
                    player.displayClientMessage(Component.translatable("message.postroad.chart.cannot_sever").withStyle(ChatFormatting.YELLOW), false)
                }
            }
        }
        sendState(player)
    }

    fun sendState(player: ServerPlayer) {
        val network = Network.get(player.server)
        val session = Charting.session(player)
        val near = network.nearestPathPoint(player.level().dimension().location(), player.blockPosition(), PostroadConfig.joinDistance * 2.0)
        val nearText = near?.let { (path, _) ->
            Component.translatable("message.postroad.chart.near_path", path.length.toInt(),
                Component.translatable("tier.postroad.${path.tier?.key ?: "dirt"}"), network.knownPlayers[path.recordedBy] ?: path.recordedBy).string
        } ?: ""
        val tier = session?.let { RoadClassifier.evaluate(it.tiers).tier }
        val state = ChartingState(
            active = session != null,
            distance = session?.distance?.toInt() ?: 0,
            roadShare = session?.let { (it.roadShare * 100).toInt() } ?: 0,
            tier = tier?.key ?: "",
            nearPath = nearText,
            pathCount = network.paths.size,
        )
        PacketDistributor.sendToPlayer(player, ChartingStatePayload(state))
    }
}
