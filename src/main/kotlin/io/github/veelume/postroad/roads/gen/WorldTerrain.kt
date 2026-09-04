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

    /** Biomes that are water wherever the estimate says: oceans and rivers. Beaches and shores are land. */
    private val WATER = Regex("ocean|river")

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
class WorldTerrainSampler(private val level: ServerLevel, private val cacheDir: Path?, private val cellSize: Int) : TileSampler {
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
    /** Cells from our own generated chunks, from Distant Horizons, and cells that are still estimates. */
    @Volatile var chunkCells: Long = 0
        private set
    @Volatile var knownCells: Long = 0
        private set
    @Volatile var estimatedCells: Long = 0
        private set

    /**
     * Blocks the real surface sits above the density crossing, measured once against the generator's
     * own column scan at land points around the origin (Tectonic: about 4; vanilla: 0). Applied to every
     * estimate. Computed on first use, so on the planner thread.
     */
    val surfaceOffset: Int by lazy { calibrate() }

    private fun calibrate(): Int {
        if (noise == null) return 0
        val diffs = ArrayList<Int>()
        for (i in -3..3) for (j in -3..3) {
            val x = i * 700 + 37
            val z = j * 700 + 91
            val base = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState)
            if (base <= seaLevel) continue // water surfaces report sea level; only land calibrates
            diffs.add(base - rawSurface(x, z, null))
        }
        if (diffs.isEmpty()) return 0
        diffs.sort()
        val median = diffs[diffs.size / 2]
        Postroad.LOGGER.info("Terrain sampler ({} blocks/cell): surface offset {} from {} land points (spread {}..{})", cellSize, median, diffs.size, diffs.first(), diffs.last())
        return median
    }

    override fun sample(tx: Int, tz: Int): Tile {
        read(tx, tz)?.let { fromCache++; return it }
        val tile = Tile.empty()
        // Height per cell; biome once per BIOME_STEP × BIOME_STEP cells (the climate sampler is the
        // expensive call and biomes do not change at cell resolution).
        val biomeIds = HashMap<Int, String>()
        val biomeStep = maxOf(1, BIOME_BLOCKS / cellSize)
        var hint: Int? = null
        for (i in 0 until TiledTerrain.TILE) for (j in 0 until TiledTerrain.TILE) {
            val cx = tx * TiledTerrain.TILE + j
            val cz = tz * TiledTerrain.TILE + i
            val x = cx * cellSize + cellSize / 2
            val z = cz * cellSize + cellSize / 2
            // Generated terrain: our own pre-generated chunks first, then Distant Horizons; otherwise the estimate, marked as such.
            val own = KnownTerrain.column(dimension, x, z)
            val known = own ?: DhTerrain.column(level, x, z)
            if (own != null) chunkCells++
            val y: Int
            var flags = 0
            if (known != null) {
                y = known.top
                if (known.water) flags = flags or Terrain.WATER
                if (known.lava) flags = flags or Terrain.LAVA
                if (known.blocked) flags = flags or Terrain.BLOCKED
                knownCells++
            } else {
                // The cell to the left (or, at a row start, above) is the best guess for this one.
                if (j == 0 && i > 0) hint = tile.heights[(i - 1) * TiledTerrain.TILE].toInt()
                y = surface(x, z, hint)
                flags = flags or Terrain.ESTIMATED
                tile.provisional = true
                estimatedCells++
            }
            hint = y
            val bKey = (i / biomeStep) * TiledTerrain.TILE + (j / biomeStep)
            val id = biomeIds.getOrPut(bKey) {
                biomeSource.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), randomState.sampler())
                    .unwrapKey().map { it.location().toString() }.orElse("")
            }
            val k = i * TiledTerrain.TILE + j
            tile.heights[k] = y.toShort()
            tile.families[k] = Families.of(id).toByte()
            if (known == null && (y < seaLevel || Families.isWater(id))) flags = flags or Terrain.WATER
            tile.flags[k] = flags.toByte()
        }
        sampled++
        // Only tiles made of generated terrain are worth keeping on disk; estimates are re-asked later.
        if (!tile.provisional) write(tx, tz, tile)
        return tile
    }

    /**
     * The noise surface before decoration and jaggedness. `initial_density_without_jaggedness` is
     * monotonic in y (a depth gradient shaped by 2-D splines), so the crossing is found by bisection
     * in a handful of evaluations. Non-noise generators fall back to the generator's own column scan.
     */
    fun surface(x: Int, z: Int, hint: Int? = null): Int = rawSurface(x, z, hint?.let { it - surfaceOffset }) + surfaceOffset

    private fun rawSurface(x: Int, z: Int, hint: Int?): Int {
        val settings = noise ?: return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState)
        val ns = settings.noiseSettings()
        val density = randomState.router().initialDensityWithoutJaggedness()
        val step = ns.cellHeight
        val minY = ns.minY()
        val top = minY + ns.height()
        var lo = minY   // treated as solid
        var hi = top    // treated as air
        if (hint != null) {
            // Terrain is continuous: bracket around the neighbour's height, widening until it holds.
            val h = (minY + Math.floorDiv(hint - minY, step) * step).coerceIn(minY, top - step)
            var w = step
            var l = h
            while (l > minY && density.compute(DensityFunction.SinglePointContext(x, l, z)) <= SOLID) { l -= w; w *= 2 }
            w = step
            var u = maxOf(h + step, l + step)
            while (u < top && density.compute(DensityFunction.SinglePointContext(x, u, z)) > SOLID) { u += w; w *= 2 }
            lo = l.coerceAtLeast(minY)
            hi = u.coerceAtMost(top)
        }
        while (hi - lo > step) {
            val mid = lo + ((hi - lo) / 2 / step) * step
            if (density.compute(DensityFunction.SinglePointContext(x, mid, z)) > SOLID) lo = mid else hi = mid
        }
        // hi is the first air sample: the surface height the generator's heightmaps report (probe-verified: lo sat 8 below).
        return hi
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
        private const val SOLID = 0.390625
        private const val BIOME_BLOCKS = 16
    }
}
