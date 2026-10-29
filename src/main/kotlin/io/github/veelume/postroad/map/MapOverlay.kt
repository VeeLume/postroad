package io.github.veelume.postroad.map

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.roads.gen.RoadPlanStorage
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import kotlin.math.sqrt

/** A town with a depot, marked at the depot. */
data class MapTown(val id: String, val name: String, val dimension: ResourceLocation, val pos: BlockPos)

/**
 * A road of the network as a simplified polyline on the ground plane. [from]/[to] are the end towns'
 * names for a generated road, both empty for a walk somebody charted by hand. [plannedId] is the
 * generated road's plan id (what road works upgrade), empty for a walk; [tier] the road's tier ordinal:
 * the plan's for a generated road, the ground's majority for a walk.
 */
class MapRoad(val dimension: ResourceLocation, val charted: Boolean, val from: String, val to: String, val xs: IntArray, val zs: IntArray,
              val plannedId: String = "", val tier: Int = 0)

/** What the client's maps show: the network's towns and roads, the same for every player. */
class MapOverlayState(val towns: List<MapTown>, val roads: List<MapRoad>, val roadsBeyondExplored: Boolean) {
    companion object {
        val EMPTY = MapOverlayState(emptyList(), emptyList(), false)

        private fun zigzag(v: Int): Int = (v shl 1) xor (v shr 31)
        private fun unzigzag(v: Int): Int = (v ushr 1) xor -(v and 1)

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MapOverlayState> = StreamCodec.of(
            { buf, s ->
                buf.writeCollection(s.towns) { b, t -> b.writeUtf(t.id); b.writeUtf(t.name); b.writeResourceLocation(t.dimension); b.writeLong(t.pos.asLong()) }
                buf.writeCollection(s.roads) { b, r ->
                    b.writeResourceLocation(r.dimension); b.writeBoolean(r.charted); b.writeUtf(r.from); b.writeUtf(r.to)
                    b.writeUtf(r.plannedId); b.writeVarInt(r.tier)
                    // First point absolute, the rest as deltas: most steps along a simplified road fit a byte or two.
                    b.writeVarInt(r.xs.size)
                    for (i in r.xs.indices) {
                        b.writeVarInt(zigzag(if (i == 0) r.xs[0] else r.xs[i] - r.xs[i - 1]))
                        b.writeVarInt(zigzag(if (i == 0) r.zs[0] else r.zs[i] - r.zs[i - 1]))
                    }
                }
                buf.writeBoolean(s.roadsBeyondExplored)
            },
            { buf ->
                val towns = buf.readList { b -> MapTown(b.readUtf(), b.readUtf(), b.readResourceLocation(), BlockPos.of(b.readLong())) }
                val roads = buf.readList { b ->
                    val dim = b.readResourceLocation(); val charted = b.readBoolean(); val from = b.readUtf(); val to = b.readUtf()
                    val plannedId = b.readUtf(); val tier = b.readVarInt()
                    val n = b.readVarInt()
                    val xs = IntArray(n); val zs = IntArray(n)
                    for (i in 0 until n) {
                        xs[i] = unzigzag(b.readVarInt()) + (if (i == 0) 0 else xs[i - 1])
                        zs[i] = unzigzag(b.readVarInt()) + (if (i == 0) 0 else zs[i - 1])
                    }
                    MapRoad(dim, charted, from, to, xs, zs, plannedId, tier)
                }
                MapOverlayState(towns, roads, buf.readBoolean())
            },
        )
    }
}

/** Server → client: the whole map overlay; replaces the previous one. */
data class MapOverlayPayload(val state: MapOverlayState) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MapOverlayPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MapOverlayPayload> = CustomPacketPayload.Type(Postroad.id("map_overlay"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MapOverlayPayload> = MapOverlayState.STREAM_CODEC.map(::MapOverlayPayload, MapOverlayPayload::state)
    }
}

/** Client-side holder of the last overlay; no client imports. Map integrations poll [version]. */
object MapOverlayClient {
    @Volatile
    var state: MapOverlayState = MapOverlayState.EMPTY
        private set

    @Volatile
    var version: Int = 0
        private set

    fun update(state: MapOverlayState) {
        this.state = state
        version++
    }
}

/**
 * Sends every player the network's towns and roads for their map mod: on login, and to
 * everyone whenever a cheap fingerprint of that data changes (checked every [INTERVAL_TICKS]).
 */
object MapOverlay {
    private const val INTERVAL_TICKS = 100

    /** Blocks a simplified polyline may stray from the recorded points; well inside the 3-wide road. */
    private const val SIMPLIFY_TOLERANCE = 1.0

    private var sentFingerprint = 0
    private var current: MapOverlayState? = null

    /** Optional: a client still on an older postroad joins all the same, it just has no overlay. */
    fun register(registrar: PayloadRegistrar) {
        registrar.optional().playToClient(MapOverlayPayload.TYPE, MapOverlayPayload.STREAM_CODEC) { payload, _ ->
            MapOverlayClient.update(payload.state)
        }
    }

    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        send(player, MapOverlayPayload(upToDate(player.server).first))
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        if (server.tickCount % INTERVAL_TICKS != 0 || server.playerList.playerCount == 0) return
        val (state, changed) = upToDate(server)
        if (!changed) return
        val payload = MapOverlayPayload(state)
        for (player in server.playerList.players) send(player, payload)
    }

    /** Only to connections that negotiated the channel; game-test mock players have none. */
    private fun send(player: ServerPlayer, payload: MapOverlayPayload) {
        if (player.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(player, payload)
    }

    /** The current overlay and whether it was rebuilt since the last call. */
    private fun upToDate(server: MinecraftServer): Pair<MapOverlayState, Boolean> {
        val fingerprint = fingerprint(server)
        current?.let { if (fingerprint == sentFingerprint) return it to false }
        val state = collect(server)
        current = state
        sentFingerprint = fingerprint
        return state to true
    }

    private fun fingerprint(server: MinecraftServer): Int {
        val network = Network.get(server)
        val storage = RoadPlanStorage.get(server)
        var h = PostroadConfig.mapRoadsBeyondExplored.hashCode()
        for (place in network.towns()) h = 31 * (31 * h + place.id.hashCode()) + place.name.hashCode()
        h = 31 * h + network.depots.hashCode()
        for (path in network.paths.values) {
            h = 31 * (31 * h + path.id.hashCode()) + path.charted.hashCode()
            h = 31 * h + path.points.hashCode()
        }
        // End-town names of generated roads come from the plan.
        for (road in storage.roads.values) h = 31 * (31 * (31 * h + road.from.hashCode()) + road.to.hashCode()) + road.tier
        return h
    }

    fun collect(server: MinecraftServer): MapOverlayState {
        val network = Network.get(server)
        val storage = RoadPlanStorage.get(server)
        val towns = network.towns().map { MapTown(it.id, it.name, it.dimension, network.depotPos(it.id) ?: it.pos) }
        fun nameOf(townId: String): String = network.places[townId]?.name ?: ""
        val roads = network.paths.values.filter { it.points.size >= 2 }.map { path ->
            // A severed path keeps its id with a "b" per cut on the tail; its plan entry is under the original id.
            val planned = generateSequence(path.id) { if (it.endsWith('b')) it.dropLast(1) else null }.firstNotNullOfOrNull { storage.roads[it] }
            val points = simplify(path.points, SIMPLIFY_TOLERANCE)
            MapRoad(path.dimension, path.charted, planned?.let { nameOf(it.from) } ?: "", planned?.let { nameOf(it.to) } ?: "",
                IntArray(points.size) { points[it].x }, IntArray(points.size) { points[it].z },
                planned?.id ?: "", planned?.tier ?: (path.tier?.ordinal ?: 0))
        }
        return MapOverlayState(towns, roads, PostroadConfig.mapRoadsBeyondExplored)
    }

    /** Ramer–Douglas–Peucker on the ground plane. */
    fun simplify(points: List<BlockPos>, tolerance: Double): List<BlockPos> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1
            var worstDist = tolerance
            for (i in a + 1 until b) {
                val d = distanceToSegment(points[i], points[a], points[b])
                if (d > worstDist) { worstDist = d; worst = i }
            }
            if (worst < 0) continue
            keep[worst] = true
            stack.addLast(a to worst)
            stack.addLast(worst to b)
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** To the segment, not its line: a road that doubles back must keep its turning point. */
    private fun distanceToSegment(p: BlockPos, a: BlockPos, b: BlockPos): Double {
        val dx = (b.x - a.x).toDouble(); val dz = (b.z - a.z).toDouble()
        val len2 = dx * dx + dz * dz
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.z - a.z) * dz) / len2).coerceIn(0.0, 1.0)
        val ex = p.x - (a.x + t * dx); val ez = p.z - (a.z + t * dz)
        return sqrt(ex * ex + ez * ez)
    }
}
