package io.github.veelume.postroad.map

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.network.asDimensionKey
import io.github.veelume.postroad.roads.gen.RoadGen
import io.github.veelume.postroad.roads.gen.TerrainShading
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The road map's background: the network's area as a coarse terrain picture, [blocksPerPixel]
 * blocks a pixel from the block ([x0], [z0]) at its north-west corner. [rgb] is row by row.
 */
class MapTerrainImage(val dimension: ResourceLocation, val x0: Int, val z0: Int, val blocksPerPixel: Int, val width: Int, val height: Int, val rgb: IntArray) {
    val x1: Int get() = x0 + width * blocksPerPixel
    val z1: Int get() = z0 + height * blocksPerPixel

    /** The pixels deflated, made once: the same picture goes to every player who opens a map. */
    val packed: ByteArray by lazy {
        val raw = ByteArray(rgb.size * 3)
        for ((i, c) in rgb.withIndex()) { raw[i * 3] = (c shr 16).toByte(); raw[i * 3 + 1] = (c shr 8).toByte(); raw[i * 3 + 2] = c.toByte() }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(raw); deflater.finish()
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16384)
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
            out.toByteArray()
        } finally { deflater.end() }
    }

    companion object {
        /** Pixels as 3 bytes each, deflated: terrain compresses well, and a packet has a size limit. */
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MapTerrainImage> = StreamCodec.of(
            { buf, m ->
                buf.writeResourceLocation(m.dimension); buf.writeVarInt(m.x0); buf.writeVarInt(m.z0); buf.writeVarInt(m.blocksPerPixel)
                buf.writeVarInt(m.width); buf.writeVarInt(m.height)
                buf.writeByteArray(m.packed)
            },
            { buf ->
                val dim = buf.readResourceLocation(); val x0 = buf.readVarInt(); val z0 = buf.readVarInt(); val bpp = buf.readVarInt()
                val w = buf.readVarInt(); val h = buf.readVarInt()
                require(w in 1..MapTerrain.MAX_PIXELS && h in 1..MapTerrain.MAX_PIXELS) { "map terrain of $w x $h" }
                val packed = buf.readByteArray(4 * 1024 * 1024)
                val raw = ByteArray(w * h * 3)
                val inflater = Inflater()
                try {
                    inflater.setInput(packed)
                    var n = 0
                    while (n < raw.size && !inflater.finished()) {
                        val got = inflater.inflate(raw, n, raw.size - n)
                        // Truncated or not deflate at all: an error, never a loop that waits for input that will not come.
                        if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IllegalArgumentException("map terrain data ends early")
                        n += got
                    }
                    require(n == raw.size) { "map terrain data is $n bytes, not ${raw.size}" }
                } finally { inflater.end() }
                val rgb = IntArray(w * h) { i -> ((raw[i * 3].toInt() and 0xFF) shl 16) or ((raw[i * 3 + 1].toInt() and 0xFF) shl 8) or (raw[i * 3 + 2].toInt() and 0xFF) }
                MapTerrainImage(dim, x0, z0, bpp, w, h, rgb)
            },
        )
    }
}

data class MapTerrainPayload(val image: MapTerrainImage) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MapTerrainPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MapTerrainPayload> = CustomPacketPayload.Type(Postroad.id("map_terrain"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MapTerrainPayload> = MapTerrainImage.STREAM_CODEC.map(::MapTerrainPayload, MapTerrainPayload::image)
    }
}

/** Client → server: a road map opened and wants the terrain under it. */
class MapTerrainRequestPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MapTerrainRequestPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MapTerrainRequestPayload> = CustomPacketPayload.Type(Postroad.id("map_terrain_request"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MapTerrainRequestPayload> = StreamCodec.unit(MapTerrainRequestPayload())
    }
}

/** Client-side holder of the last terrain picture; no client imports. The map widget polls [version]. */
object MapTerrainClient {
    @Volatile var image: MapTerrainImage? = null; private set
    @Volatile var version = 0; private set

    fun update(image: MapTerrainImage) { this.image = image; version++ }
}

/**
 * Makes the terrain picture of the network's area on the planner thread (its coarse map: height and
 * water, no chunks) and hands it to whoever asked. Kept per dimension until the network outgrows it
 * or it is [MAX_AGE_MS] old: estimates turn into generated terrain as the world fills in.
 */
object MapTerrain {
    const val MAX_PIXELS = 320
    /** Blocks of land around the outermost town or road. */
    private const val MARGIN = 320
    private const val MAX_AGE_MS = 60 * 60 * 1000L

    private class Cached(val image: MapTerrainImage, val made: Long)

    private val cache = HashMap<ResourceLocation, Cached>()
    private val computing = HashSet<ResourceLocation>()
    private val waiting = HashMap<ResourceLocation, MutableSet<UUID>>()
    /** When each player last asked: a map opens a few times a minute at most, the rest is spam. */
    private val lastAsked = HashMap<UUID, Long>()
    private const val REQUEST_COOLDOWN_MS = 5000L

    fun register(registrar: PayloadRegistrar) {
        registrar.playToClient(MapTerrainPayload.TYPE, MapTerrainPayload.STREAM_CODEC) { payload, _ -> MapTerrainClient.update(payload.image) }
        registrar.playToServer(MapTerrainRequestPayload.TYPE, MapTerrainRequestPayload.STREAM_CODEC) { _, context ->
            (context.player() as? ServerPlayer)?.let { request(it) }
        }
    }

    /** The block rectangle the network covers in [dimension], with the margin; null when it has nothing there. */
    private fun extent(player: ServerPlayer, dimension: ResourceLocation): IntArray? {
        val network = Network.get(player.server)
        var minX = Int.MAX_VALUE; var minZ = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        fun add(x: Int, z: Int) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (z < minZ) minZ = z; if (z > maxZ) maxZ = z }
        for (town in network.towns()) if (town.dimension == dimension) (network.depotPos(town.id) ?: town.pos).let { add(it.x, it.z) }
        for (path in network.paths.values) if (path.dimension == dimension) for (p in path.points) add(p.x, p.z)
        if (minX > maxX) return null
        return intArrayOf(minX - MARGIN, minZ - MARGIN, maxX + MARGIN, maxZ + MARGIN)
    }

    fun request(player: ServerPlayer) {
        val now = System.currentTimeMillis()
        if (now - (lastAsked[player.uuid] ?: 0L) < REQUEST_COOLDOWN_MS) return
        lastAsked[player.uuid] = now
        val dimension = player.level().dimension().location()
        val area = extent(player, dimension) ?: return
        val cached = cache[dimension]
        val covers = cached != null && cached.image.x0 <= area[0] && cached.image.z0 <= area[1] && cached.image.x1 >= area[2] && cached.image.z1 >= area[3]
        if (cached != null) send(player, cached.image)
        if (covers && System.currentTimeMillis() - cached!!.made < MAX_AGE_MS) return
        waiting.getOrPut(dimension) { HashSet() }.add(player.uuid)
        if (!computing.add(dimension)) return
        val level = player.server.getLevel(dimension.asDimensionKey()) ?: run { computing.remove(dimension); return }
        val server = player.server
        val started = RoadGen.onPlannerThread(level) { worker ->
            val image = draw(worker, dimension, area)
            server.execute {
                computing.remove(dimension)
                cache[dimension] = Cached(image, System.currentTimeMillis())
                waiting.remove(dimension)?.forEach { id -> server.playerList.getPlayer(id)?.let { send(it, image) } }
                Postroad.LOGGER.info("Road map terrain for {}: {}x{} pixels, {} blocks each", dimension, image.width, image.height, image.blocksPerPixel)
            }
        }
        // The planner off: no terrain to draw from, the map stays plain.
        if (!started) { computing.remove(dimension); waiting.remove(dimension) }
    }

    /** Planner thread: samples the coarse map over [area] and shades it. */
    private fun draw(worker: RoadGen.Worker, dimension: ResourceLocation, area: IntArray): MapTerrainImage {
        val coarse = worker.coarse
        val cell = coarse.cellSize
        val span = maxOf(area[2] - area[0], area[3] - area[1])
        val bpp = (((span + MAX_PIXELS - 1) / MAX_PIXELS + cell - 1) / cell * cell).coerceAtLeast(cell)
        val w = ((area[2] - area[0]) / bpp + 1).coerceIn(1, MAX_PIXELS)
        val h = ((area[3] - area[1]) / bpp + 1).coerceIn(1, MAX_PIXELS)
        val heights = IntArray(w * h)
        val flags = IntArray(w * h)
        for (pz in 0 until h) for (px in 0 until w) {
            val cx = Math.floorDiv(area[0] + px * bpp + bpp / 2, cell)
            val cz = Math.floorDiv(area[1] + pz * bpp + bpp / 2, cell)
            heights[pz * w + px] = coarse.heightAt(cx, cz)
            flags[pz * w + px] = coarse.flagsAt(cx, cz)
        }
        return MapTerrainImage(dimension, area[0], area[1], bpp, w, h, TerrainShading.colors(heights, flags, w, h, showBlocked = false))
    }

    private fun send(player: ServerPlayer, image: MapTerrainImage) {
        if (player.connection.hasChannel(MapTerrainPayload.TYPE)) PacketDistributor.sendToPlayer(player, MapTerrainPayload(image))
    }
}
