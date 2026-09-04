package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.roads.RoadNode
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import java.util.UUID

/** A planned road as the client debug view sees it: its points, which chunks are built, whether it is charted. */
data class DebugRoad(val id: String, val points: List<BlockPos>, val builtChunks: LongArray, val charted: Boolean, val from: String, val to: String)

data class DebugJunction(val pos: BlockPos, val signPlaced: Boolean)

/** A predicted town: its box and the name of its place if the depot has registered. */
data class DebugTown(val id: String, val name: String, val box: IntArray)

data class DebugNode(val pos: BlockPos, val name: String, val kind: String)

/** A pair the planner dropped: the two town positions and the reason. */
data class DebugDropped(val a: BlockPos, val b: BlockPos, val reason: String)

data class RoadDebugState(val roads: List<DebugRoad>, val junctions: List<DebugJunction>, val towns: List<DebugTown>, val nodes: List<DebugNode>, val dropped: List<DebugDropped> = emptyList()) {
    companion object {
        val EMPTY = RoadDebugState(emptyList(), emptyList(), emptyList(), emptyList())
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RoadDebugState> = StreamCodec.of(
            { buf, s ->
                buf.writeCollection(s.roads) { b, r ->
                    b.writeUtf(r.id); b.writeCollection(r.points) { bb, p -> bb.writeLong(p.asLong()) }
                    b.writeLongArray(r.builtChunks); b.writeBoolean(r.charted); b.writeUtf(r.from); b.writeUtf(r.to)
                }
                buf.writeCollection(s.junctions) { b, j -> b.writeLong(j.pos.asLong()); b.writeBoolean(j.signPlaced) }
                buf.writeCollection(s.towns) { b, t -> b.writeUtf(t.id); b.writeUtf(t.name); b.writeVarIntArray(t.box) }
                buf.writeCollection(s.nodes) { b, n -> b.writeLong(n.pos.asLong()); b.writeUtf(n.name); b.writeUtf(n.kind) }
                buf.writeCollection(s.dropped) { b, d -> b.writeLong(d.a.asLong()); b.writeLong(d.b.asLong()); b.writeUtf(d.reason) }
            },
            { buf ->
                RoadDebugState(
                    buf.readList { b -> DebugRoad(b.readUtf(), b.readList { bb -> BlockPos.of(bb.readLong()) }, b.readLongArray(), b.readBoolean(), b.readUtf(), b.readUtf()) },
                    buf.readList { b -> DebugJunction(BlockPos.of(b.readLong()), b.readBoolean()) },
                    buf.readList { b -> DebugTown(b.readUtf(), b.readUtf(), b.readVarIntArray()) },
                    buf.readList { b -> DebugNode(BlockPos.of(b.readLong()), b.readUtf(), b.readUtf()) },
                    buf.readList { b -> DebugDropped(BlockPos.of(b.readLong()), BlockPos.of(b.readLong()), b.readUtf()) },
                )
            },
        )
    }
}

/** Server → client: what to draw. An empty state turns the view off. */
data class RoadDebugPayload(val state: RoadDebugState) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RoadDebugPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RoadDebugPayload> = CustomPacketPayload.Type(Postroad.id("road_debug"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RoadDebugPayload> = RoadDebugState.STREAM_CODEC.map(::RoadDebugPayload, RoadDebugPayload::state)
    }
}

/** Client-side holder; the client renderer reads it. No client imports here. */
object RoadDebugClient {
    @Volatile
    var state: RoadDebugState = RoadDebugState.EMPTY
}

/**
 * The server half of the debug view: per player a toggle, and every couple of seconds the roads,
 * junctions, towns and nodes within [RADIUS] blocks of them.
 */
object RoadDebug {
    private const val RADIUS = 512
    private const val INTERVAL_TICKS = 40

    private val enabled = HashSet<UUID>()

    fun isEnabled(player: ServerPlayer): Boolean = player.uuid in enabled

    /** Returns the new state. */
    fun toggle(player: ServerPlayer): Boolean {
        return if (enabled.remove(player.uuid)) {
            PacketDistributor.sendToPlayer(player, RoadDebugPayload(RoadDebugState.EMPTY))
            false
        } else {
            enabled.add(player.uuid)
            send(player)
            true
        }
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        if (enabled.isEmpty() || event.server.tickCount % INTERVAL_TICKS != 0) return
        for (player in event.server.playerList.players) {
            if (player.uuid in enabled) send(player)
        }
    }

    fun register(registrar: PayloadRegistrar) {
        registrar.playToClient(RoadDebugPayload.TYPE, RoadDebugPayload.STREAM_CODEC) { payload, _ ->
            RoadDebugClient.state = payload.state
        }
    }

    private fun send(player: ServerPlayer) {
        PacketDistributor.sendToPlayer(player, RoadDebugPayload(collect(player.server, player)))
    }

    fun collect(server: MinecraftServer, player: ServerPlayer): RoadDebugState {
        val storage = RoadPlanStorage.get(server)
        val network = Network.get(server)
        val dim = player.serverLevel().dimension().location()
        val centre = player.blockPosition()
        val r2 = RADIUS.toDouble() * RADIUS
        fun near(p: BlockPos): Boolean {
            val dx = (p.x - centre.x).toDouble(); val dz = (p.z - centre.z).toDouble()
            return dx * dx + dz * dz <= r2
        }
        val roads = storage.roadsIn(dim).filter { road -> road.points.any(::near) }.map { road ->
            DebugRoad(road.id, road.points, road.builtChunks.toLongArray(), network.paths[road.id]?.charted == true, road.from, road.to)
        }
        val junctions = storage.junctions.filter { it.dimension == dim && near(it.pos) }.map { DebugJunction(it.pos, it.signPlaced) }
        val towns = storage.townsIn(dim).filter { near(it.pos) }.map { t ->
            val b = t.box
            DebugTown(t.id, network.places[t.id]?.name ?: t.structure.path, intArrayOf(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()))
        }
        val obstacles = storage.obstaclesIn(dim).filter { near(it.box.center) }.map { o ->
            val b = o.box
            DebugTown("obstacle:${o.key}", o.structure.path, intArrayOf(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()))
        }
        val nodes = network.nodes.values.filter { it.dimension == dim && near(it.pos) }.map { DebugNode(it.pos, it.name, it.kind) }
        val dropped = storage.droppedDetails.values.mapNotNull { d ->
            val a = storage.towns[d.first] ?: return@mapNotNull null
            val b = storage.towns[d.second] ?: return@mapNotNull null
            if (a.dimension != dim || (!near(a.pos) && !near(b.pos))) null else DebugDropped(a.pos, b.pos, d.third)
        }
        return RoadDebugState(roads, junctions, towns + obstacles, nodes, dropped)
    }

    /** Chunk key of a road point, for the client's built/unbuilt colouring. */
    fun chunkOf(p: BlockPos): Long = ChunkPos.asLong(p.x shr 4, p.z shr 4)

    @Suppress("unused")
    private val kinds = listOf(RoadNode.KIND_TOWN, RoadNode.KIND_SIGN)
}
