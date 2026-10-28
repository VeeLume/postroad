package io.github.veelume.postroad.compat.xaero

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.map.MapOverlayClient
import io.github.veelume.postroad.map.MapOverlayState
import io.github.veelume.postroad.map.MapRoad
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import xaero.map.WorldMapSession
import xaero.map.highlight.AbstractHighlighter
import xaero.map.highlight.HighlighterRegistry
import java.util.Collections

/**
 * Roads on Xaero's World Map (and the minimap where it draws from world map tiles), painted block by block
 * into the map tiles the way Open Parties and Claims paints claims.
 *
 * The world map fills its highlighter list once per session (claims only) and then freezes it, so the
 * road highlighter is put into the list by reflection when a session appears. Tiles cache their highlights
 * under a per-region hash; a new overlay clears those hashes so changed regions repaint.
 */
object XaeroRoadHighlights {
    private var installedIn: HighlighterRegistry? = null
    private var highlighter: RoadHighlighter? = null
    private var appliedVersion = -1

    private val highlightersField by lazy {
        HighlighterRegistry::class.java.getDeclaredField("highlighters").apply { isAccessible = true }
    }

    fun tick() {
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return
        val registry = processor.highlighterRegistry ?: return
        val version = MapOverlayClient.version
        if (registry === installedIn && version == appliedVersion) return

        val state = MapOverlayClient.state
        val raster = RoadRaster(state)
        val current = highlighter
        if (registry !== installedIn || current == null || current.beyondExplored != state.roadsBeyondExplored) {
            val replacement = RoadHighlighter(state.roadsBeyondExplored, raster)
            @Suppress("UNCHECKED_CAST")
            val list = (highlightersField.get(registry) as List<AbstractHighlighter>).filterNot { it is RoadHighlighter } + replacement
            highlightersField.set(registry, Collections.unmodifiableList(list))
            highlighter = replacement
            installedIn = registry
        } else {
            current.raster = raster
        }
        processor.mapWorld?.clearAllCachedHighlightHashes()
        appliedVersion = version
        Postroad.LOGGER.debug("Xaero's World Map: {} roads", state.roads.size)
    }
}

/** The overlay's roads indexed by chunk: which segments come close enough to paint, and a hash per map region. */
internal class RoadRaster(state: MapOverlayState) {
    class Segment(val road: MapRoad, val ax: Int, val az: Int, val bx: Int, val bz: Int)

    private val chunks = HashMap<ResourceLocation, Long2ObjectOpenHashMap<MutableList<Segment>>>()
    private val regionHashes = HashMap<ResourceLocation, Long2IntOpenHashMap>()

    init {
        val reach = HALF_WIDTH.toInt() + 1
        for (road in state.roads) {
            val byChunk = chunks.getOrPut(road.dimension) { Long2ObjectOpenHashMap() }
            val byRegion = regionHashes.getOrPut(road.dimension) { Long2IntOpenHashMap() }
            for (i in 0 until road.xs.size - 1) {
                val s = Segment(road, road.xs[i], road.zs[i], road.xs[i + 1], road.zs[i + 1])
                val hash = 31 * (31 * (31 * (31 * s.ax + s.az) + s.bx) + s.bz) + road.charted.hashCode()
                for (cx in ((minOf(s.ax, s.bx) - reach) shr 4)..((maxOf(s.ax, s.bx) + reach) shr 4))
                    for (cz in ((minOf(s.az, s.bz) - reach) shr 4)..((maxOf(s.az, s.bz) + reach) shr 4)) {
                        val key = ChunkPos.asLong(cx, cz)
                        val list = byChunk.get(key) ?: ArrayList<Segment>(2).also { byChunk.put(key, it) }
                        list.add(s)
                        val region = ChunkPos.asLong(cx shr 5, cz shr 5)
                        byRegion.put(region, 31 * byRegion.get(region) + hash)
                    }
            }
        }
    }

    fun regionHash(dimension: ResourceLocation, regionX: Int, regionZ: Int): Int =
        regionHashes[dimension]?.get(ChunkPos.asLong(regionX, regionZ)) ?: 0

    fun hasRegion(dimension: ResourceLocation, regionX: Int, regionZ: Int): Boolean =
        regionHashes[dimension]?.containsKey(ChunkPos.asLong(regionX, regionZ)) == true

    fun hasChunk(dimension: ResourceLocation, chunkX: Int, chunkZ: Int): Boolean =
        chunks[dimension]?.containsKey(ChunkPos.asLong(chunkX, chunkZ)) == true

    /** Per-block colours of a chunk (`z * 16 + x`, Xaero's BGRA layout), or null when no road comes close. */
    fun colours(dimension: ResourceLocation, chunkX: Int, chunkZ: Int): IntArray? {
        val segments = chunks[dimension]?.get(ChunkPos.asLong(chunkX, chunkZ)) ?: return null
        val out = IntArray(256)
        var any = false
        for (z in 0 until 16) for (x in 0 until 16) {
            val road = roadAt(segments, (chunkX shl 4) + x, (chunkZ shl 4) + z) ?: continue
            out[(z shl 4) or x] = if (road.charted) CHARTED else UNCHARTED
            any = true
        }
        return if (any) out else null
    }

    fun roadAt(dimension: ResourceLocation, x: Int, z: Int): MapRoad? =
        chunks[dimension]?.get(ChunkPos.asLong(x shr 4, z shr 4))?.let { roadAt(it, x, z) }

    /** The road painted at a block: a charted one wins where two overlap. */
    private fun roadAt(segments: List<Segment>, x: Int, z: Int): MapRoad? {
        val px = x + 0.5; val pz = z + 0.5
        var found: MapRoad? = null
        for (s in segments) {
            if (distance2(s, px, pz) > HALF_WIDTH * HALF_WIDTH) continue
            if (s.road.charted) return s.road
            found = s.road
        }
        return found
    }

    /** Squared distance from a block centre to a segment between block centres. */
    private fun distance2(s: Segment, px: Double, pz: Double): Double {
        val ax = s.ax + 0.5; val az = s.az + 0.5
        val dx = (s.bx - s.ax).toDouble(); val dz = (s.bz - s.az).toDouble()
        val len2 = dx * dx + dz * dz
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (pz - az) * dz) / len2).coerceIn(0.0, 1.0)
        val ex = px - (ax + t * dx); val ez = pz - (az + t * dz)
        return ex * ex + ez * ez
    }

    companion object {
        /** Blocks either side of the centre line: the 3-wide road and a block of margin, so it reads zoomed out. */
        const val HALF_WIDTH = 2.0

        /** Xaero's highlight colour: blue, green, red, alpha from the high byte down. */
        private fun colour(rgb: Int, alpha: Int): Int =
            ((rgb and 0xFF) shl 24) or (((rgb shr 8) and 0xFF) shl 16) or (((rgb shr 16) and 0xFF) shl 8) or alpha

        /** Amber: travel runs along it. */
        val CHARTED = colour(0xF0A830, 200)

        /** Pale slate: built, not charted yet. */
        val UNCHARTED = colour(0xB4C3D2, 150)
    }
}

/** Paints [raster]; swapped as a whole when the overlay changes (Xaero reads it from its map thread). */
internal class RoadHighlighter(val beyondExplored: Boolean, @Volatile var raster: RoadRaster) : AbstractHighlighter(beyondExplored) {
    override fun calculateRegionHash(dimension: ResourceKey<Level>, regionX: Int, regionZ: Int): Int =
        raster.regionHash(dimension.location(), regionX, regionZ)

    override fun regionHasHighlights(dimension: ResourceKey<Level>, regionX: Int, regionZ: Int): Boolean =
        raster.hasRegion(dimension.location(), regionX, regionZ)

    override fun chunkIsHighlit(dimension: ResourceKey<Level>, chunkX: Int, chunkZ: Int): Boolean =
        raster.hasChunk(dimension.location(), chunkX, chunkZ)

    override fun getChunkHighlitColor(dimension: ResourceKey<Level>, chunkX: Int, chunkZ: Int): IntArray? =
        raster.colours(dimension.location(), chunkX, chunkZ)

    override fun getBlockHighlightSubtleTooltip(dimension: ResourceKey<Level>, x: Int, z: Int): Component? =
        raster.roadAt(dimension.location(), x, z)?.let(::name)

    override fun getBlockHighlightBluntTooltip(dimension: ResourceKey<Level>, x: Int, z: Int): Component? =
        raster.roadAt(dimension.location(), x, z)?.let { road ->
            Component.translatable("map.postroad.road.status", name(road),
                Component.translatable(if (road.charted) "map.postroad.road.charted" else "map.postroad.road.uncharted"))
        }

    override fun addMinimapBlockHighlightTooltips(list: MutableList<Component>, dimension: ResourceKey<Level>, x: Int, z: Int, width: Int) {}

    private fun name(road: MapRoad): Component = when {
        road.from.isNotEmpty() && road.to.isNotEmpty() -> Component.translatable("map.postroad.road.between", road.from, road.to)
        road.from.isNotEmpty() || road.to.isNotEmpty() -> Component.translatable("map.postroad.road.to", road.from.ifEmpty { road.to })
        else -> Component.translatable("map.postroad.road.walk")
    }
}
