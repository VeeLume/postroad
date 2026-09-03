package io.github.veelume.postroad.menu

import io.github.veelume.postroad.Postroad
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

/**
 * Everything the depot screen shows that is not a slot or a plain int: names and the current
 * selection. Built on the server, sent whenever it changes, read by the screen each frame.
 */
data class DepotState(
    val townName: String,
    val pageNames: List<String>,
    val destinationNames: List<String>,
    val destinationIndex: Int,
    val homeName: String,
    val transitLines: List<String>,
    /** Menu slot indices currently marked for sending. */
    val selected: List<Int>,
    val wallet: Long,
    /** Days the marked unstackables would take to the shown destination; 0 = at once. */
    val valuablesDays: Long,
    /** Coins express would cost for that; 0 = nothing to skip or express disabled. */
    val expressCost: Long,
    /** Marked stacks the depot buys, and what it would pay for them. */
    val sellCount: Int,
    val sellValue: Long,
) {
    companion object {
        val EMPTY = DepotState("", emptyList(), emptyList(), 0, "", emptyList(), emptyList(), 0L, 0L, 0L, 0, 0L)

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, DepotState> = StreamCodec.of(
            { buf, s ->
                buf.writeUtf(s.townName)
                buf.writeCollection(s.pageNames) { b, v -> b.writeUtf(v) }
                buf.writeCollection(s.destinationNames) { b, v -> b.writeUtf(v) }
                buf.writeVarInt(s.destinationIndex)
                buf.writeUtf(s.homeName)
                buf.writeCollection(s.transitLines) { b, v -> b.writeUtf(v) }
                buf.writeCollection(s.selected) { b, v -> b.writeVarInt(v) }
                buf.writeVarLong(s.wallet)
                buf.writeVarLong(s.valuablesDays)
                buf.writeVarLong(s.expressCost)
                buf.writeVarInt(s.sellCount)
                buf.writeVarLong(s.sellValue)
            },
            { buf ->
                DepotState(
                    townName = buf.readUtf(),
                    pageNames = buf.readList { it.readUtf() },
                    destinationNames = buf.readList { it.readUtf() },
                    destinationIndex = buf.readVarInt(),
                    homeName = buf.readUtf(),
                    transitLines = buf.readList { it.readUtf() },
                    selected = buf.readList { it.readVarInt() },
                    wallet = buf.readVarLong(),
                    valuablesDays = buf.readVarLong(),
                    expressCost = buf.readVarLong(),
                    sellCount = buf.readVarInt(),
                    sellValue = buf.readVarLong(),
                )
            },
        )
    }
}

/** Server → client: the depot menu's name state. Applied to whatever [DepotMenu] the player has open. */
data class DepotStatePayload(val state: DepotState) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<DepotStatePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DepotStatePayload> = CustomPacketPayload.Type(Postroad.id("depot_state"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, DepotStatePayload> =
            DepotState.STREAM_CODEC.map(::DepotStatePayload, DepotStatePayload::state)
    }
}

/** Client → server: a button press on the depot screen. Validated against the open menu. */
data class DepotActionPayload(val action: Int, val value: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<DepotActionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DepotActionPayload> = CustomPacketPayload.Type(Postroad.id("depot_action"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, DepotActionPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DepotActionPayload::action,
            ByteBufCodecs.VAR_INT, DepotActionPayload::value,
            ::DepotActionPayload,
        )
    }
}

object PostroadNetworking {
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        io.github.veelume.postroad.roads.ChartingNetworking.register(registrar)
        io.github.veelume.postroad.travel.TravelNetworking.register(registrar)
        registrar.playToClient(DepotStatePayload.TYPE, DepotStatePayload.STREAM_CODEC) { payload, context ->
            (context.player().containerMenu as? DepotMenu)?.state = payload.state
        }
        registrar.playToServer(DepotActionPayload.TYPE, DepotActionPayload.STREAM_CODEC) { payload, context ->
            (context.player().containerMenu as? DepotMenu)?.handleAction(payload.action, payload.value)
        }
    }
}
