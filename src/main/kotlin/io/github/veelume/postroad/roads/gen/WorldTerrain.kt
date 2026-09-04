package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import net.minecraft.core.QuartPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.RandomState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Biome families for road palettes, by biome-id keyword. Ported from the modpack's road-style
 * generator so the two agree; first match wins, temperate is the rest.
 */
object Families {
    const val TEMPERATE = 0
    const val BADLANDS = 1
    const val ARID = 2
    const val WET = 3
    const val COLD = 4
    const val MOUNTAIN = 5

    val NAMES = listOf("temperate", "badlands", "arid", "wet", "cold", "mountain")

    private val RULES: List<Pair<Int, Regex>> = listOf(
        BADLANDS to Regex("badland|mesa|red_rock|canyon|red_desert|outback|eroded"),
        ARID to Regex("desert|dune|sand|arid|mojave|atacama|savanna|scrub|chaparral|steppe|prairie_dry"),
        WET to Regex("jungle|rainforest|swamp|marsh|bog|bayou|mangrove|wetland|tropic|fen|lush"),
        COLD to Regex("snow|frost|frozen|ice|glacier|tundra|alpine|siberian|wintry|permafrost|arctic|cold"),
        MOUNTAIN to Regex("peak|mountain|highland|cliff|ridge|crag|shield|plateau|summit|slope"),
    )

    private val WATER = Regex("ocean|river|beach|shore")

    fun of(biomeId: String): Int {
        val path = biomeId.substringAfter(':')
        for ((family, rx) in RULES) if (rx.containsMatchIn(path)) return family
        return TEMPERATE
    }

    fun isWater(biomeId: String): Boolean = WATER.containsMatchIn(biomeId.substringAfter(':'))

    fun name(family: Int): String = NAMES.getOrElse(family) { "temperate" }
}

/**
 * Samples a level's terrain from its chunk generator without touching chunks: the noise
 * router's preliminary surface (cheap, 2-D) for height and the biome source for the family.
 * Everything it calls is a pure function of position and the world's random state, so it runs
 * on the planner thread. Tiles are cached on disk under [cacheDir] (null = no cache).
 */
class WorldTerrainSampler(level: ServerLevel, private val cacheDir: Path?, private val cellSize: Int) : TileSampler {
    private val generator: ChunkGenerator = level.chunkSource.generator
    private val randomState: RandomState = level.chunkSource.randomState()
    private val biomeSource: BiomeSource = generator.biomeSource
    private val heightAccessor: LevelHeightAccessor = level
    private val seaLevel: Int = generator.seaLevel
    private val noise: NoiseGeneratorSettings? = (generator as? NoiseBasedChunkGenerator)?.generatorSettings()?.value()
    private val dimension: ResourceLocation = level.dimension().location()

    @Volatile var sampled: Int = 0
        private set
    @Volatile var fromCache: Int = 0
        private set

    override fun sample(tx: Int, tz: Int): Tile {
        read(tx, tz)?.let { fromCache++; return it }
        val tile = Tile.empty()
        for (i in 0 until TiledTerrain.TILE) for (j in 0 until TiledTerrain.TILE) {
            val cx = tx * TiledTerrain.TILE + j
            val cz = tz * TiledTerrain.TILE + i
            val x = cx * cellSize + cellSize / 2
            val z = cz * cellSize + cellSize / 2
            val y = surface(x, z)
            val biome = biomeSource.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), randomState.sampler())
            val id = biome.unwrapKey().map { it.location().toString() }.orElse("")
            val k = i * TiledTerrain.TILE + j
            tile.heights[k] = y.toShort()
            tile.families[k] = Families.of(id).toByte()
            if (y < seaLevel || Families.isWater(id)) tile.flags[k] = Terrain.WATER.toByte()
        }
        sampled++
        write(tx, tz, tile)
        return tile
    }

    /** The noise surface before decoration and jaggedness; falls back to the generator's own column scan. */
    fun surface(x: Int, z: Int): Int {
        val settings = noise ?: return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState)
        val ns = settings.noiseSettings()
        val density = randomState.router().initialDensityWithoutJaggedness()
        var y = ns.minY() + ns.height()
        while (y >= ns.minY()) {
            if (density.compute(DensityFunction.SinglePointContext(x, y, z)) > 0.390625) return y
            y -= ns.cellHeight
        }
        return ns.minY()
    }

    private fun file(tx: Int, tz: Int): Path? {
        val dir = cacheDir ?: return null
        return dir.resolve("${dimension.namespace}_${dimension.path.replace('/', '_')}").resolve("${tx}_$tz.bin")
    }

    private fun read(tx: Int, tz: Int): Tile? {
        val f = file(tx, tz) ?: return null
        if (!Files.isRegularFile(f)) return null
        return try {
            DataInputStream(Files.newInputStream(f).buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != cellSize) return null
                val tile = Tile.empty()
                for (k in 0 until TiledTerrain.CELLS) tile.heights[k] = input.readShort()
                input.readFully(tile.flags)
                input.readFully(tile.families)
                tile
            }
        } catch (e: Exception) {
            Postroad.LOGGER.warn("Unreadable terrain tile {}: {}", f, e.message)
            null
        }
    }

    private fun write(tx: Int, tz: Int, tile: Tile) {
        val f = file(tx, tz) ?: return
        try {
            Files.createDirectories(f.parent)
            DataOutputStream(Files.newOutputStream(f).buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(cellSize)
                for (h in tile.heights) out.writeShort(h.toInt())
                out.write(tile.flags)
                out.write(tile.families)
            }
        } catch (e: Exception) {
            Postroad.LOGGER.warn("Could not cache terrain tile {}: {}", f, e.message)
        }
    }

    companion object {
        private const val MAGIC = 0x50524431 // "PRD1"
    }
}
