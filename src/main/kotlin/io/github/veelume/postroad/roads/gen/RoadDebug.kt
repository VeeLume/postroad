package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.roads.RoadNode
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.resources.ResourceLocation
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

/**
 * One grid of the planner's sampled cells around the player: cell size in blocks, the cell
 * coordinates of the grid's corner, its size, and per cell the estimated surface height
 * (`Int.MIN_VALUE` where the planner has not sampled) and the terrain flags.
 */
class DebugGrid(val cellSize: Int, val originCx: Int, val originCz: Int, val w: Int, val h: Int, val heights: IntArray, val flags: ByteArray)

class TerrainDebugState(val grids: List<DebugGrid>) {
    companion object {
        val EMPTY = TerrainDebugState(emptyList())
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerrainDebugState> = StreamCodec.of(
            { buf, s ->
                buf.writeCollection(s.grids) { b, g ->
                    b.writeVarInt(g.cellSize); b.writeInt(g.originCx); b.writeInt(g.originCz); b.writeVarInt(g.w); b.writeVarInt(g.h)
                    for (v in g.heights) b.writeInt(v)
                    b.writeByteArray(g.flags)
                }
            },
            { buf ->
                TerrainDebugState(buf.readList { b ->
                    val cell = b.readVarInt(); val ox = b.readInt(); val oz = b.readInt(); val w = b.readVarInt(); val h = b.readVarInt()
                    val heights = IntArray(w * h) { b.readInt() }
                    DebugGrid(cell, ox, oz, w, h, heights, b.readByteArray())
                })
            },
        )
    }
}

data class TerrainDebugPayload(val state: TerrainDebugState) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TerrainDebugPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TerrainDebugPayload> = CustomPacketPayload.Type(Postroad.id("terrain_debug"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerrainDebugPayload> = TerrainDebugState.STREAM_CODEC.map(::TerrainDebugPayload, TerrainDebugPayload::state)
    }
}

/** One placed piece as the client debug view draws it: its box, anchors, facing and kind. */
class DebugPiece(val piece: String, val shape: String, val reversed: Boolean, val entry: BlockPos, val exit: BlockPos, val box: IntArray, val roadId: String)

class PieceDebugState(val pieces: List<DebugPiece>) {
    companion object {
        val EMPTY = PieceDebugState(emptyList())
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, PieceDebugState> = StreamCodec.of(
            { buf, s -> buf.writeCollection(s.pieces) { b, p -> b.writeUtf(p.piece); b.writeUtf(p.shape); b.writeBoolean(p.reversed); b.writeLong(p.entry.asLong()); b.writeLong(p.exit.asLong()); b.writeVarIntArray(p.box); b.writeUtf(p.roadId) } },
            { buf -> PieceDebugState(buf.readList { b -> DebugPiece(b.readUtf(), b.readUtf(), b.readBoolean(), BlockPos.of(b.readLong()), BlockPos.of(b.readLong()), b.readVarIntArray(), b.readUtf()) }) },
        )
    }
}

data class PieceDebugPayload(val state: PieceDebugState) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PieceDebugPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PieceDebugPayload> = CustomPacketPayload.Type(Postroad.id("piece_debug"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, PieceDebugPayload> = PieceDebugState.STREAM_CODEC.map(::PieceDebugPayload, PieceDebugPayload::state)
    }
}

object PieceDebugClient {
    @Volatile
    var state: PieceDebugState = PieceDebugState.EMPTY
}

object TerrainDebugClient {
    @Volatile
    var state: TerrainDebugState = TerrainDebugState.EMPTY
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
    private val terrainEnabled = HashSet<UUID>()
    private val piecesEnabled = HashSet<UUID>()

    /** Pieces this far around the player are outlined; they are dense, so a smaller radius than the roads. */
    private const val PIECE_RADIUS = 96

    /** Placements laid by `/postroad roads showcase`, drawn in the piece layer with their names. */
    val showcase = ArrayList<Pair<ResourceLocation, PiecePlacement>>()

    /** The piece layer: every placement the plan is laid from, outlined with its anchors. Returns the new state. */
    fun togglePieces(player: ServerPlayer): Boolean {
        return if (piecesEnabled.remove(player.uuid)) {
            PacketDistributor.sendToPlayer(player, PieceDebugPayload(PieceDebugState.EMPTY))
            false
        } else {
            piecesEnabled.add(player.uuid)
            sendPieces(player)
            true
        }
    }

    private fun sendPieces(player: ServerPlayer) {
        PacketDistributor.sendToPlayer(player, PieceDebugPayload(collectPieces(player)))
    }

    /** The placements of every road near the player, plus the showcase, as the data says they are laid. */
    fun collectPieces(player: ServerPlayer): PieceDebugState {
        val storage = RoadPlanStorage.get(player.server)
        val dim = player.serverLevel().dimension().location()
        val centre = player.blockPosition()
        val r2 = PIECE_RADIUS.toDouble() * PIECE_RADIUS
        fun near(p: BlockPos): Boolean { val dx = (p.x - centre.x).toDouble(); val dz = (p.z - centre.z).toDouble(); return dx * dx + dz * dz <= r2 }
        val catalog = RoadPieces.current
        val out = ArrayList<DebugPiece>()
        fun add(p: PiecePlacement, roadId: String) {
            val anchor = p.anchor
            if (!near(anchor)) return
            val cs = p.connectors()
            val entry = cs.firstOrNull()?.first ?: anchor
            val exit = cs.getOrNull(1)?.first ?: entry
            val shape = p.piece.id.path.substringBefore('_')
            out.add(DebugPiece(p.piece.id.path, shape, false, entry, exit, RoadPieceLayer.boundsOf(p), roadId))
        }
        for (road in storage.roadsIn(dim)) {
            if (road.points.none(::near)) continue
            val placements = catalog.assemble(road.points, road.id) ?: continue
            for (p in placements) add(p, road.id + (if (road.provisional) " (provisional)" else ""))
        }
        for ((d, p) in showcase) if (d == dim) add(p, "showcase")
        return PieceDebugState(out)
    }

    /** Fine cells (4 blocks) this far around the player, coarse cells (16 blocks) four times as far. */
    private const val TERRAIN_RADIUS = 64

    fun isEnabled(player: ServerPlayer): Boolean = player.uuid in enabled

    /** The terrain layer: the planner's sampled cells with their flags. Returns the new state. */
    fun toggleTerrain(player: ServerPlayer): Boolean {
        return if (terrainEnabled.remove(player.uuid)) {
            PacketDistributor.sendToPlayer(player, TerrainDebugPayload(TerrainDebugState.EMPTY))
            false
        } else {
            terrainEnabled.add(player.uuid)
            sendTerrain(player)
            true
        }
    }

    private fun sendTerrain(player: ServerPlayer) {
        PacketDistributor.sendToPlayer(player, TerrainDebugPayload(collectTerrain(player)))
    }

    /** The loaded cells of the planner's fine and coarse terrain around the player; never samples. */
    fun collectTerrain(player: ServerPlayer): TerrainDebugState {
        val level = player.serverLevel()
        if (!RoadGen.isOverworld(level)) return TerrainDebugState.EMPTY
        val worker = RoadGen.workerFor(level)
        val centre = player.blockPosition()
        fun grid(terrain: TiledTerrain, radius: Int): DebugGrid {
            val min = terrain.blockToCell(centre.x - radius, centre.z - radius)
            val max = terrain.blockToCell(centre.x + radius, centre.z + radius)
            val w = max.x - min.x + 1; val h = max.z - min.z + 1
            val heights = IntArray(w * h); val flags = ByteArray(w * h)
            val missing = LinkedHashMap<Long, Cell>()
            for (dz in 0 until h) for (dx in 0 until w) {
                val cx = min.x + dx; val cz = min.z + dz
                val height = terrain.loadedHeightAt(cx, cz)
                if (height == null) missing.putIfAbsent(Terrain.key(Math.floorDiv(cx, TiledTerrain.TILE), Math.floorDiv(cz, TiledTerrain.TILE)), Cell(cx, cz))
                heights[dz * w + dx] = height ?: Int.MIN_VALUE
                flags[dz * w + dx] = (terrain.loadedFlagsAt(cx, cz) ?: 0).toByte()
            }
            // Tiles the passes never loaded: sample them on the planner thread; the next refresh shows them.
            RoadGen.prefetch(level, terrain, missing.values)
            return DebugGrid(terrain.cellSize, min.x, min.z, w, h, heights, flags)
        }
        return TerrainDebugState(listOf(grid(worker.terrain, TERRAIN_RADIUS), grid(worker.coarse, TERRAIN_RADIUS * (RoadGen.COARSE_CELL / RoadGen.CELL_SIZE))))
    }

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
        if ((enabled.isEmpty() && terrainEnabled.isEmpty() && piecesEnabled.isEmpty()) || event.server.tickCount % INTERVAL_TICKS != 0) return
        for (player in event.server.playerList.players) {
            if (player.uuid in enabled) send(player)
            if (player.uuid in terrainEnabled) sendTerrain(player)
            if (player.uuid in piecesEnabled) sendPieces(player)
        }
    }

    fun register(registrar: PayloadRegistrar) {
        registrar.playToClient(RoadDebugPayload.TYPE, RoadDebugPayload.STREAM_CODEC) { payload, _ ->
            RoadDebugClient.state = payload.state
        }
        registrar.playToClient(TerrainDebugPayload.TYPE, TerrainDebugPayload.STREAM_CODEC) { payload, _ ->
            TerrainDebugClient.state = payload.state
        }
        registrar.playToClient(PieceDebugPayload.TYPE, PieceDebugPayload.STREAM_CODEC) { payload, _ ->
            PieceDebugClient.state = payload.state
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
            val a = storage.towns[d.from] ?: return@mapNotNull null
            val b = storage.towns[d.to] ?: return@mapNotNull null
            if (a.dimension != dim || (!near(a.pos) && !near(b.pos))) null else DebugDropped(a.pos, b.pos, d.reason + (if (d.provisional) " (provisional)" else ""))
        }
        return RoadDebugState(roads, junctions, towns + obstacles, nodes, dropped)
    }

    /** Chunk key of a road point, for the client's built/unbuilt colouring. */
    fun chunkOf(p: BlockPos): Long = ChunkPos.asLong(p.x shr 4, p.z shr 4)

    @Suppress("unused")
    private val kinds = listOf(RoadNode.KIND_TOWN, RoadNode.KIND_SIGN)
}
