package io.github.veelume.postroad.roads.gen

import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.StructureTags
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
import java.util.function.Predicate

/**
 * Predicts where villages will generate, before their chunks exist, by running the same
 * decision the chunk generator runs: structure-set placement says whether a village may start
 * in a chunk, and the structure's own generation (biome check, jigsaw layout) says whether it
 * does. Everything here is a pure function of the seed, so it runs on the planner thread.
 *
 * A structure counts as a town when it is tagged `#minecraft:village`.
 */
class TownFinder(level: ServerLevel, private val surface: (Int, Int) -> Int) {

    data class Candidate(val id: String, val structure: ResourceLocation, val chunk: ChunkPos, val box: BoundingBox)

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
    private val dimension: ResourceLocation = level.dimension().location()

    /** Structure sets that can produce a village; the others never matter here. */
    val villageSets: List<Holder<StructureSet>> = state.possibleStructureSets().filter { set ->
        set.value().structures().any { it.structure().`is`(StructureTags.VILLAGE) }
    }

    /** The village that starts in this chunk, if one does. */
    fun find(chunkX: Int, chunkZ: Int): Candidate? {
        val chunkPos = ChunkPos(chunkX, chunkZ)
        for (set in villageSets) {
            val placement = set.value().placement()
            // The cheap spread test first; the full test also walks exclusion zones and costs ~2 ms.
            if (placement is net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement) {
                val potential = placement.getPotentialStructureChunk(seed, chunkX, chunkZ)
                if (potential.x != chunkX || potential.z != chunkZ) continue
            }
            if (!placement.isStructureChunk(state, chunkX, chunkZ)) continue
            val list = set.value().structures()
            if (list.size == 1) {
                val outcome = tryOne(list[0], chunkPos)
                if (outcome.generated) return outcome.candidate
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
                if (outcome.generated) return outcome.candidate
                remaining.removeAt(k)
                total -= entry.weight()
            }
        }
        return null
    }

    private class Outcome(val generated: Boolean, val candidate: Candidate?)

    private fun tryOne(entry: StructureSet.StructureSelectionEntry, chunkPos: ChunkPos): Outcome {
        val holder = entry.structure()
        val structure = holder.value()
        val biomes = structure.biomes()
        // Vanilla builds the whole jigsaw layout and only then tests the biome at its start; the start
        // sits at the chunk's min corner on the surface, so test that first and skip the layout when it fails.
        val x = chunkPos.minBlockX
        val z = chunkPos.minBlockZ
        val y = surface(x, z)
        val biome = biomeSource.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(y), net.minecraft.core.QuartPos.fromBlock(z), randomState.sampler())
        if (!biomes.contains(biome)) { prefiltered++; return Outcome(false, null) }
        generated++
        val start = structure.generate(
            registryAccess, generator, generator.biomeSource, randomState, templates, seed, chunkPos, 0, heightAccessor,
            Predicate<Holder<Biome>> { biomes.contains(it) },
        )
        if (!start.isValid) return Outcome(false, null)
        if (!holder.`is`(StructureTags.VILLAGE)) return Outcome(true, null)
        val id = "$dimension/${chunkPos.x}/${chunkPos.z}"
        val structureId = holder.unwrapKey().map { it.location() }.orElse(ResourceLocation.withDefaultNamespace("village"))
        return Outcome(true, Candidate(id, structureId, chunkPos, start.boundingBox))
    }
}
