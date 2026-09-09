package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.registries.Registries
import net.minecraft.tags.StructureTags
import net.minecraft.tags.TagKey
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.StructureSet
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate

/**
 * Predicts where structures will generate, before their chunks exist, by running the same
 * decision the chunk generator runs: structure-set placement says whether a structure may start
 * in a chunk, and the structure's own generation (biome check, layout) says whether it does.
 * Everything here is a pure function of the seed, so it runs on the planner thread.
 *
 * A structure tagged `#minecraft:village` is a **town** (a road endpoint, with its pieces and
 * street exits). Any other structure that stands on the surface — towers, temples, mansions —
 * is an **obstacle**: its start box is kept so the planner routes around it. Structures tagged
 * `#postroad:passable` are neither: landscape structures (a plateau or arch whose box spans
 * hundreds of blocks and the whole world height) and underground ones whose box reaches the
 * surface (mineshafts) — a road crosses them like any other ground.
 * Buried ones (mineshafts, strongholds, dungeons) are ignored; a road above them is fine.
 */
class TownFinder(level: ServerLevel, private val surface: (Int, Int) -> Int) {

    /**
     * A predicted village: its start box, the boxes of its non-street pieces (what a road must not cross)
     * and the centres of its street pieces (where a road may join the village's own roads).
     */
    data class Candidate(val id: String, val structure: ResourceLocation, val chunk: ChunkPos, val box: BoundingBox,
                         val pieces: List<BoundingBox>, val streets: List<net.minecraft.core.BlockPos>)

    /** A predicted surface structure that is not a town: something to route around. */
    data class Obstacle(val structure: ResourceLocation, val chunk: ChunkPos, val box: BoundingBox)

    /** What starts in one chunk: at most one town (sets are exclusive per chunk in vanilla's draw), any number of obstacles. */
    class Found(val town: Candidate?, val obstacles: List<Obstacle>) {
        val isEmpty: Boolean get() = town == null && obstacles.isEmpty()
    }

    private val state: ChunkGeneratorStructureState = level.chunkSource.getGeneratorState()
    private val generator: ChunkGenerator = level.chunkSource.generator
    private val randomState: RandomState = level.chunkSource.randomState()
    private val registryAccess: RegistryAccess = level.registryAccess()
    private val templates: StructureTemplateManager = level.server.structureManager
    private val heightAccessor = level
    private val seed: Long = state.levelSeed
    private val biomeSource = generator.biomeSource

    /** Structures rejected by the biome pre-check; layouts never built for them. */
    @Volatile var prefiltered: Int = 0
        private set
    @Volatile var generated: Int = 0
        private set
    @Volatile var obstaclesFound: Int = 0
    /** Layouts of `#postroad:passable` structures: predicted, then ignored. */
    @Volatile var passableFound: Int = 0
        private set
    /** Per structure: layouts built, nanoseconds spent, and how many came out buried — the cost picture in `status`. */
    val timing = ConcurrentHashMap<String, LongArray>()
    /** Structures whose first [LEARN_BURIED] layouts were all buried: never built again, they cannot be obstacles or towns. */
    val skipped: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val warned = HashSet<String>()
    private val dimension: ResourceLocation = level.dimension().location()

    /** Every structure set the generator may place here. */
    val sets: List<Holder<StructureSet>> = state.possibleStructureSets().toList()

    /** Structure sets that can produce a village. */
    val villageSets: List<Holder<StructureSet>> = sets.filter { set ->
        set.value().structures().any { it.structure().`is`(StructureTags.VILLAGE) }
    }

    /** Everything that starts in this chunk. */
    fun find(chunkX: Int, chunkZ: Int): Found {
        val chunkPos = ChunkPos(chunkX, chunkZ)
        var town: Candidate? = null
        var obstacles: MutableList<Obstacle>? = null
        fun take(outcome: Outcome) {
            outcome.candidate?.let { if (town == null) town = it }
            outcome.obstacle?.let { (obstacles ?: ArrayList<Obstacle>().also { l -> obstacles = l }).add(it) }
        }
        for (set in sets) {
            val placement = set.value().placement()
            // The cheap spread test first; the full test also walks exclusion zones and costs ~2 ms.
            if (placement is net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement) {
                val potential = placement.getPotentialStructureChunk(seed, chunkX, chunkZ)
                if (potential.x != chunkX || potential.z != chunkZ) continue
            }
            if (!placement.isStructureChunk(state, chunkX, chunkZ)) continue
            val list = set.value().structures()
            if (list.size == 1) {
                take(tryOne(list[0], chunkPos))
                continue
            }
            // The generator's weighted draw, reproduced: same random, same order, same removals.
            val remaining = ArrayList(list)
            val random = WorldgenRandom(LegacyRandomSource(0L))
            random.setLargeFeatureSeed(seed, chunkX, chunkZ)
            var total = remaining.sumOf { it.weight() }
            while (remaining.isNotEmpty()) {
                var pick = random.nextInt(total)
                var k = 0
                for (entry in remaining) {
                    pick -= entry.weight()
                    if (pick < 0) break
                    k++
                }
                val entry = remaining[k]
                val outcome = tryOne(entry, chunkPos)
                if (outcome.generated) { take(outcome); break }
                remaining.removeAt(k)
                total -= entry.weight()
            }
        }
        return Found(town, obstacles ?: emptyList())
    }

    private class Outcome(val generated: Boolean, val candidate: Candidate? = null, val obstacle: Obstacle? = null)

    private fun tryOne(entry: StructureSet.StructureSelectionEntry, chunkPos: ChunkPos): Outcome {
        val holder = entry.structure()
        val structure = holder.value()
        val biomes = structure.biomes()
        val structureId = holder.unwrapKey().map { it.location() }.orElse(ResourceLocation.withDefaultNamespace("structure"))
        // Vanilla builds the whole layout and only then tests the biome at its start; the start
        // sits at the chunk's min corner on the surface, so test that first and skip the layout when it fails.
        val x = chunkPos.minBlockX
        val z = chunkPos.minBlockZ
        val y = surface(x, z)
        val biome = biomeSource.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(y), net.minecraft.core.QuartPos.fromBlock(z), randomState.sampler())
        if (!biomes.contains(biome)) { prefiltered++; return Outcome(false) }
        val sid = structureId.toString()
        if (sid in skipped) return Outcome(false)
        generated++
        val t0 = System.nanoTime()
        val start = try {
            structure.generate(
                registryAccess, generator, generator.biomeSource, randomState, templates, seed, chunkPos, 0, heightAccessor,
                Predicate<Holder<Biome>> { biomes.contains(it) },
            )
        } catch (e: Exception) {
            // A modded structure that will not generate off its usual thread: treat as absent, say so once.
            if (warned.add(structureId.toString())) Postroad.LOGGER.warn("Structure {} could not be predicted at {}: {}", structureId, chunkPos, e.toString())
            return Outcome(false)
        } finally {
            val t = timing.getOrPut(sid) { LongArray(3) }
            t[0]++; t[1] += System.nanoTime() - t0
        }
        if (!start.isValid) return Outcome(false)
        val box = start.boundingBox
        if (holder.`is`(PASSABLE)) { passableFound++; return Outcome(true) }
        if (!holder.`is`(StructureTags.VILLAGE)) {
            // On the surface, or buried? The box's mid-height against the estimated surface at its centre.
            val centre = box.center
            val ground = surface(centre.x, centre.z)
            if ((box.minY() + box.maxY()) / 2 < ground - BURIED_BELOW) {
                // Buried. A structure that is always buried (dungeons, chambers) is not worth another layout.
                val t = timing[sid]
                if (t != null && ++t[2] >= LEARN_BURIED && t[2] == t[0]) skipped.add(sid)
                return Outcome(true)
            }
            obstaclesFound++
            return Outcome(true, obstacle = Obstacle(structureId, chunkPos, box))
        }
        val id = "$dimension/${chunkPos.x}/${chunkPos.z}"
        val pieces = ArrayList<BoundingBox>()
        val streets = ArrayList<net.minecraft.core.BlockPos>()
        for (piece in start.pieces) {
            val name = (piece as? net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece)?.element?.toString() ?: ""
            if (name.contains("street", ignoreCase = true)) streets.add(piece.boundingBox.center) else pieces.add(piece.boundingBox)
        }
        return Outcome(true, candidate = Candidate(id, structureId, chunkPos, box, pieces, streets))
    }

    companion object {
        /** Structures a road may cross: landscape features and underground structures. Neither town nor obstacle. */
        val PASSABLE: TagKey<Structure> = TagKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(Postroad.MOD_ID, "passable"))
        /** A structure whose box mid-height is this far under the surface is buried and ignored. */
        const val BURIED_BELOW = 4
        /** After this many layouts that all came out buried, a structure is skipped for good. */
        const val LEARN_BURIED = 4
    }
}
