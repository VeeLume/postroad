package io.github.veelume.postroad.travel

import io.github.veelume.postroad.Postroad
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.registration.PayloadRegistrar

data class TravelEntry(val nodeId: String, val name: String, val length: Int, val tier: String, val fare: Long)

data class TravelState(val fromNodeId: String, val fromName: String, val entries: List<TravelEntry>, val wallet: Long, val fund: Long) {
    companion object {
        val EMPTY = TravelState("", "", emptyList(), 0L, 0L)
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TravelState> = StreamCodec.of(
            { buf, s ->
                buf.writeUtf(s.fromNodeId); buf.writeUtf(s.fromName)
                buf.writeCollection(s.entries) { b, e -> b.writeUtf(e.nodeId); b.writeUtf(e.name); b.writeVarInt(e.length); b.writeUtf(e.tier); b.writeVarLong(e.fare) }
                buf.writeVarLong(s.wallet); buf.writeVarLong(s.fund)
            },
            { buf ->
                TravelState(
                    buf.readUtf(), buf.readUtf(),
                    buf.readList { b -> TravelEntry(b.readUtf(), b.readUtf(), b.readVarInt(), b.readUtf(), b.readVarLong()) },
                    buf.readVarLong(), buf.readVarLong(),
                )
            },
        )
    }
}

/** Server → client: open the travel screen with these destinations. */
data class TravelStatePayload(val state: TravelState) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TravelStatePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TravelStatePayload> = CustomPacketPayload.Type(Postroad.id("travel_state"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TravelStatePayload> = TravelState.STREAM_CODEC.map(::TravelStatePayload, TravelStatePayload::state)
    }
}

/** Client → server: go. */
data class TravelActionPayload(val fromNodeId: String, val toNodeId: String, val express: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TravelActionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TravelActionPayload> = CustomPacketPayload.Type(Postroad.id("travel_action"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TravelActionPayload> = StreamCodec.of(
            { buf, p -> buf.writeUtf(p.fromNodeId); buf.writeUtf(p.toNodeId); buf.writeBoolean(p.express) },
            { buf -> TravelActionPayload(buf.readUtf(), buf.readUtf(), buf.readBoolean()) },
        )
    }
}

/** Client-side holder; the client setup plugs in the screen opener. No client imports here. */
object TravelClient {
    @Volatile
    var state: TravelState = TravelState.EMPTY
    var screenOpener: Runnable? = null
}

object TravelNetworking {
    fun register(registrar: PayloadRegistrar) {
        registrar.playToClient(TravelStatePayload.TYPE, TravelStatePayload.STREAM_CODEC) { payload, _ ->
            TravelClient.state = payload.state
            TravelClient.screenOpener?.run()
        }
        registrar.playToServer(TravelActionPayload.TYPE, TravelActionPayload.STREAM_CODEC) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@playToServer
            TravelService.depart(player, payload.fromNodeId, payload.toNodeId, payload.express)
        }
    }
}
