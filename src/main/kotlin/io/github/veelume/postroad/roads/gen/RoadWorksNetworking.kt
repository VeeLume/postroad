package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.network.Network
import net.minecraft.ChatFormatting
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/** One road of a town, as the road works screen shows it. [material] is an item id; counts are items. */
data class RoadWorksEntry(
    val roadId: String, val fromName: String, val toName: String, val length: Int, val tier: Int,
    val coinsNeeded: Long, val coinsPaid: Long, val material: String, val materialNeeded: Int, val materialDelivered: Int,
    /** Of the material, what the player carries plus what the town's depot holds. */
    val materialAvailable: Int,
    /** Chunks still being re-laid at [tier] after an upgrade. */
    val pending: Int,
)

data class RoadWorksState(val placeId: String, val townName: String, val wallet: Long, val fund: Long, val entries: List<RoadWorksEntry>) {
    companion object {
        val EMPTY = RoadWorksState("", "", 0L, 0L, emptyList())
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RoadWorksState> = StreamCodec.of(
            { buf, s ->
                buf.writeUtf(s.placeId); buf.writeUtf(s.townName); buf.writeVarLong(s.wallet); buf.writeVarLong(s.fund)
                buf.writeCollection(s.entries) { b, e ->
                    b.writeUtf(e.roadId); b.writeUtf(e.fromName); b.writeUtf(e.toName); b.writeVarInt(e.length); b.writeVarInt(e.tier)
                    b.writeVarLong(e.coinsNeeded); b.writeVarLong(e.coinsPaid); b.writeUtf(e.material); b.writeVarInt(e.materialNeeded)
                    b.writeVarInt(e.materialDelivered); b.writeVarInt(e.materialAvailable); b.writeVarInt(e.pending)
                }
            },
            { buf ->
                RoadWorksState(buf.readUtf(), buf.readUtf(), buf.readVarLong(), buf.readVarLong(), buf.readList { b ->
                    RoadWorksEntry(b.readUtf(), b.readUtf(), b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarLong(), b.readVarLong(),
                        b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt())
                })
            },
        )
    }
}

/** Server → client: open (or refresh) the road works screen. */
data class RoadWorksStatePayload(val state: RoadWorksState) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RoadWorksStatePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RoadWorksStatePayload> = CustomPacketPayload.Type(Postroad.id("road_works_state"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RoadWorksStatePayload> = RoadWorksState.STREAM_CODEC.map(::RoadWorksStatePayload, RoadWorksStatePayload::state)
    }
}

/** Client → server: pay [amount] coins toward, or deliver material to, [roadId] at [placeId]'s depot. */
data class RoadWorksActionPayload(val placeId: String, val roadId: String, val action: Int, val amount: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RoadWorksActionPayload> = TYPE

    companion object {
        const val FUND = 0
        const val DELIVER = 1
        val TYPE: CustomPacketPayload.Type<RoadWorksActionPayload> = CustomPacketPayload.Type(Postroad.id("road_works_action"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RoadWorksActionPayload> = StreamCodec.of(
            { buf, p -> buf.writeUtf(p.placeId); buf.writeUtf(p.roadId); buf.writeVarInt(p.action); buf.writeVarLong(p.amount) },
            { buf -> RoadWorksActionPayload(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarLong()) },
        )
    }
}

/** Client-side holder; the client setup plugs in the screen opener. No client imports here. */
object RoadWorksClient {
    @Volatile
    var state: RoadWorksState = RoadWorksState.EMPTY
    /** Opens the screen, or refreshes it when it is already open. */
    var screenOpener: Runnable? = null
}

object RoadWorksNetworking {
    fun register(registrar: PayloadRegistrar) {
        registrar.playToClient(RoadWorksStatePayload.TYPE, RoadWorksStatePayload.STREAM_CODEC) { payload, _ ->
            RoadWorksClient.state = payload.state
            RoadWorksClient.screenOpener?.run()
        }
        registrar.playToServer(RoadWorksActionPayload.TYPE, RoadWorksActionPayload.STREAM_CODEC) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@playToServer
            act(player, payload)
        }
    }

    /** Sends the works screen for the town at [placeId]'s depot. */
    fun open(player: ServerPlayer, placeId: String) {
        val network = Network.get(player.server)
        val place = network.places[placeId] ?: return
        val entries = RoadWorks.roadsOf(player.server, placeId).map { road ->
            val price = RoadWorks.price(road)
            val material = price?.material
            fun count(item: Item?): Int {
                if (item == null) return 0
                val storage = network.storageFor(placeId)
                return (0 until player.inventory.containerSize).sumOf { val s = player.inventory.getItem(it); if (s.`is`(item) && s.componentsPatch.isEmpty) s.count else 0 } +
                    (0 until storage.containerSize).sumOf { val s = storage.getItem(it); if (s.`is`(item) && s.componentsPatch.isEmpty) s.count else 0 }
            }
            fun name(id: String) = network.places[id]?.name ?: id
            RoadWorksEntry(road.id, name(road.from), name(road.to), RoadWorks.length(road).toInt(), road.tier,
                price?.coins ?: 0L, road.worksCoins, material?.let { BuiltInRegistries.ITEM.getKey(it).toString() } ?: "",
                price?.materialCount ?: 0, road.worksMaterial, count(material), road.upgradeChunks.size)
        }
        val state = RoadWorksState(placeId, place.name, network.balance(Network.playerAccount(player.gameProfile.name)), network.balance(Network.ROAD_FUND), entries)
        if (player.connection.hasChannel(RoadWorksStatePayload.TYPE)) PacketDistributor.sendToPlayer(player, RoadWorksStatePayload(state))
    }

    private fun act(player: ServerPlayer, payload: RoadWorksActionPayload) {
        val network = Network.get(player.server)
        // At the depot of the town the road belongs to, like every other depot action.
        val place = network.places[payload.placeId] ?: return
        val depot = network.depotPos(payload.placeId) ?: return
        // Same dimension and a depot really standing there: the position alone exists in every dimension.
        if (player.level().dimension().location() != place.dimension) return
        if (!player.level().getBlockState(depot).`is`(io.github.veelume.postroad.registry.PostroadBlocks.DEPOT.get())) return
        if (player.distanceToSqr(depot.x + 0.5, depot.y + 0.5, depot.z + 0.5) > 64.0) return
        val road = RoadWorks.roadsOf(player.server, payload.placeId).firstOrNull { it.id == payload.roadId } ?: return
        val tierBefore = road.tier
        when (payload.action) {
            RoadWorksActionPayload.FUND -> {
                val paid = RoadWorks.fund(player, road, payload.amount.coerceAtLeast(0L))
                if (paid == 0L && RoadWorks.price(road) != null) {
                    player.displayClientMessage(Component.translatable("message.postroad.works.unaffordable").withStyle(ChatFormatting.YELLOW), true)
                }
            }
            RoadWorksActionPayload.DELIVER -> {
                val n = RoadWorks.deliver(player, road, payload.placeId)
                if (n == 0) player.displayClientMessage(Component.translatable("message.postroad.works.no_material").withStyle(ChatFormatting.YELLOW), true)
            }
        }
        if (road.tier > tierBefore) {
            player.server.playerList.broadcastSystemMessage(
                Component.translatable("message.postroad.works.upgraded", network.places[road.from]?.name ?: road.from, network.places[road.to]?.name ?: road.to,
                    Component.translatable("tier.postroad.${io.github.veelume.postroad.roads.Tier.entries[road.tier].key}")).withStyle(ChatFormatting.GOLD),
                false,
            )
        }
        open(player, payload.placeId)
    }
}
