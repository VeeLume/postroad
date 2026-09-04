package io.github.veelume.postroad.worldgen

import com.mojang.serialization.Codec
import io.github.veelume.postroad.roads.gen.RoadLayer
import io.github.veelume.postroad.roads.gen.RoadPlanSnapshot
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration

/**
 * Planned roads laid while a chunk generates: a placed feature at the `surface_structures` step
 * (after the surface, before vegetation) in every overworld biome, placed once per chunk at the
 * chunk's origin. It asks the plan snapshot for the runs through this chunk and lays them; a chunk
 * without runs is untouched. Unlike a structure start this needs the plan only when the chunk
 * reaches its features step, so chunks pre-generated to the carvers step still get their road.
 */
class RoadFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {
    override fun place(ctx: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val chunk = ChunkPos(ctx.origin())
        val runs = RoadPlanSnapshot.segmentsAt(chunk.x, chunk.z)
        if (runs.isEmpty()) return false
        RoadPlanSnapshot.featureRuns.incrementAndGet()
        var placed = 0
        for (run in runs) placed += RoadLayer.lay(ctx.level(), run, chunk)
        RoadPlanSnapshot.piecesPlaced.addAndGet(runs.size)
        RoadPlanSnapshot.laid.add(chunk.toLong())
        return placed > 0
    }
}
