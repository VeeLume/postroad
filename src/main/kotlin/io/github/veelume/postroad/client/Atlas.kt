package io.github.veelume.postroad.client

import com.mojang.blaze3d.platform.NativeImage
import io.github.veelume.postroad.Postroad
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.event.level.ChunkEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer

/**
 * The postroad atlas: a map of what this client has seen. Every chunk the client loads is sampled
 * every [STEP] blocks in vanilla map colours — the top block's map colour, brighter or darker by the
 * height step to the north, water darker with depth — into regions of [REGION] blocks, one image each,
 * transparent where nothing was seen. Saved as PNGs per server and dimension under the game folder,
 * so the road map shows the land a player has explored and nothing else.
 */
object Atlas {
    const val REGION = 512
    const val STEP = 2
    const val PIXELS = REGION / STEP

    class Region(val key: Long, val texture: DynamicTexture, val location: ResourceLocation) {
        val image: NativeImage get() = texture.pixels!!
        /** Heights of the samples, for the shading across chunk borders; not saved. */
        val heights = ShortArray(PIXELS * PIXELS) { Short.MIN_VALUE }
        var uploadDirty = false
        var saveDirty = false
    }

    private class World(val dimension: ResourceLocation, val dir: Path) {
        val regions = Long2ObjectOpenHashMap<Region>()
        /** Region files on disk, not loaded yet. */
        val onDisk = LongOpenHashSet()
    }

    private var server: String? = null
    private val worlds = HashMap<ResourceLocation, World>()
    private val queue = LongLinkedOpenHashSet()
    private var ticks = 0

    fun register() {
        FORGE_BUS.addListener(ChunkEvent.Load::class.java, Consumer(::onChunkLoad))
        FORGE_BUS.addListener(ClientTickEvent.Post::class.java, Consumer { onTick() })
        FORGE_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java, Consumer { close() })
    }

    /** Per server (address, or the world's name in single player) and dimension. */
    private fun world(dimension: ResourceLocation): World? {
        val mc = Minecraft.getInstance()
        val name = mc.singleplayerServer?.worldData?.levelName?.let { "local_$it" } ?: mc.currentServer?.ip ?: return null
        if (name != server) { close(); server = name }
        return worlds.getOrPut(dimension) {
            val dir = mc.gameDirectory.toPath().resolve("postroad").resolve("atlas").resolve(safe(name)).resolve(safe("${dimension.namespace}_${dimension.path}"))
            val w = World(dimension, dir)
            try {
                if (Files.isDirectory(dir)) Files.list(dir).use { files ->
                    for (f in files) parseName(f.fileName.toString())?.let { w.onDisk.add(it) }
                }
            } catch (e: Exception) { Postroad.LOGGER.warn("Could not read the atlas at {}: {}", dir, e.message) }
            w
        }
    }

    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun fileName(key: Long) = "r.${ChunkPos.getX(key)}.${ChunkPos.getZ(key)}.png"
    private fun parseName(n: String): Long? {
        val parts = n.removeSuffix(".png").split('.')
        if (parts.size != 3 || parts[0] != "r" || !n.endsWith(".png")) return null
        return ChunkPos.asLong(parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
    }

    /** The region at [rx], [rz]: from memory, else from disk, else new and empty when [create]. */
    fun region(dimension: ResourceLocation, rx: Int, rz: Int, create: Boolean): Region? {
        val w = world(dimension) ?: return null
        val key = ChunkPos.asLong(rx, rz)
        w.regions.get(key)?.let { return it }
        val image = if (w.onDisk.remove(key)) {
            try { Files.newInputStream(w.dir.resolve(fileName(key))).use { NativeImage.read(it) }.takeIf { it.width == PIXELS && it.height == PIXELS } }
            catch (e: Exception) { Postroad.LOGGER.warn("Atlas region {} unreadable: {}", fileName(key), e.message); null }
        } else null
        if (image == null && !create) return null
        val texture = DynamicTexture(image ?: NativeImage(PIXELS, PIXELS, true).also { it.fillRect(0, 0, PIXELS, PIXELS, 0) })
        val location = Postroad.id("atlas/${safe(dimension.path).lowercase()}/${rx}_$rz")
        Minecraft.getInstance().textureManager.register(location, texture)
        val region = Region(key, texture, location)
        w.regions.put(key, region)
        return region
    }

    /** Whether the region has a file or is loaded: the map asks before loading anything. */
    fun known(dimension: ResourceLocation, rx: Int, rz: Int): Boolean {
        val w = world(dimension) ?: return false
        val key = ChunkPos.asLong(rx, rz)
        return w.regions.containsKey(key) || w.onDisk.contains(key)
    }

    /** Whether the region is in memory (no disk read to draw it). */
    fun loaded(dimension: ResourceLocation, rx: Int, rz: Int): Boolean = world(dimension)?.regions?.containsKey(ChunkPos.asLong(rx, rz)) == true

    /** Whether this client has seen the block column at [x], [z] (only regions already loaded are asked). */
    fun seen(dimension: ResourceLocation, x: Int, z: Int): Boolean {
        val w = world(dimension) ?: return false
        val region = w.regions.get(ChunkPos.asLong(Math.floorDiv(x, REGION), Math.floorDiv(z, REGION))) ?: return false
        val px = Math.floorMod(x, REGION) / STEP; val pz = Math.floorMod(z, REGION) / STEP
        return (region.image.getPixelRGBA(px, pz) ushr 24) != 0
    }

    private fun onChunkLoad(event: ChunkEvent.Load) {
        if (!event.level.isClientSide) return
        queue.add(event.chunk.pos.toLong())
    }

    private fun onTick() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        ticks++
        // Chunks round the player again now and then: what players build shows up.
        if (ticks % 100 == 0) mc.player?.let { p ->
            val c = p.chunkPosition()
            for (dz in -1..1) for (dx in -1..1) queue.add(ChunkPos.asLong(c.x + dx, c.z + dz))
        }
        var budget = CHUNKS_PER_TICK
        while (budget-- > 0 && queue.isNotEmpty()) {
            val key = queue.removeFirstLong()
            val chunk = level.chunkSource.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), false) ?: continue
            record(level, chunk)
        }
        if (ticks % 20 == 0) for (w in worlds.values) for (r in w.regions.values) if (r.uploadDirty) { r.texture.upload(); r.uploadDirty = false }
        if (ticks % SAVE_TICKS == 0) save()
    }

    private fun record(level: Level, chunk: LevelChunk) {
        val dimension = level.dimension().location()
        val baseX = chunk.pos.minBlockX; val baseZ = chunk.pos.minBlockZ
        val region = region(dimension, Math.floorDiv(baseX, REGION), Math.floorDiv(baseZ, REGION), create = true) ?: return
        val pos = BlockPos.MutableBlockPos()
        var changed = false
        // Rows north to south, so each sample's northern neighbour is in place for the shading.
        for (lz in 0 until 16 step STEP) for (lx in 0 until 16 step STEP) {
            val x = baseX + lx; val z = baseZ + lz
            var y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz)
            if (y <= level.minBuildHeight) continue
            pos.set(x, y, z)
            var state = chunk.getBlockState(pos)
            while (y > level.minBuildHeight && state.getMapColor(level, pos) == MapColor.NONE) { y--; pos.setY(y); state = chunk.getBlockState(pos) }
            val color: Int
            val px = Math.floorMod(x, REGION) / STEP; val pz = Math.floorMod(z, REGION) / STEP
            if (!state.fluidState.isEmpty) {
                var depth = 0
                while (y - depth > level.minBuildHeight && depth < 16 && !chunk.getBlockState(pos.setY(y - depth - 1)).fluidState.isEmpty) depth++
                val shade = when { depth < 2 -> MapColor.Brightness.HIGH; depth < 6 -> MapColor.Brightness.NORMAL; else -> MapColor.Brightness.LOW }
                color = state.getMapColor(level, pos.setY(y)).calculateRGBColor(shade)
            } else {
                val north = if (pz > 0) region.heights[(pz - 1) * PIXELS + px].toInt() else Short.MIN_VALUE.toInt()
                val shade = when {
                    north == Short.MIN_VALUE.toInt() -> MapColor.Brightness.NORMAL
                    y > north -> MapColor.Brightness.HIGH
                    y < north -> MapColor.Brightness.LOW
                    else -> MapColor.Brightness.NORMAL
                }
                color = state.getMapColor(level, pos).calculateRGBColor(shade)
            }
            region.heights[pz * PIXELS + px] = y.toShort()
            // calculateRGBColor is already in NativeImage's ABGR order, alpha set.
            if (region.image.getPixelRGBA(px, pz) != color) { region.image.setPixelRGBA(px, pz, color); changed = true }
        }
        if (changed) { region.uploadDirty = true; region.saveDirty = true }
    }

    private fun save() {
        for (w in worlds.values) for (r in w.regions.values) {
            if (!r.saveDirty) continue
            try {
                Files.createDirectories(w.dir)
                r.image.writeToFile(w.dir.resolve(fileName(r.key)))
                r.saveDirty = false
            } catch (e: Exception) { Postroad.LOGGER.warn("Could not save atlas region {}: {}", fileName(r.key), e.message) }
        }
    }

    /** Saves and lets go of everything: on leaving a server, or switching to another. */
    private fun close() {
        save()
        val textures = Minecraft.getInstance().textureManager
        for (w in worlds.values) for (r in w.regions.values) textures.release(r.location)
        worlds.clear()
        queue.clear()
        server = null
    }

    private const val CHUNKS_PER_TICK = 24
    private const val SAVE_TICKS = 20 * 30
}
