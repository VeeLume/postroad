package io.github.veelume.postroad.worldgen

import com.mojang.serialization.Codec
import io.github.veelume.postroad.roads.gen.RoadPieceLayer
import io.github.veelume.postroad.roads.gen.RoadPieces
import io.github.veelume.postroad.roads.gen.RoadPlanSnapshot
import io.github.veelume.postroad.roads.gen.RoadStyles
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration

/**
 * Planned roads laid while a chunk generates: a placed feature at the `surface_structures` step
 * (after the surface, before vegetation) in every overworld biome, placed once per chunk at the
 * chunk's origin. It asks the plan snapshot for the piece placements that reach into this chunk
 * and lays each one's blocks inside it; a chunk without placements is untouched.
 */
class RoadFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {
    override fun place(ctx: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val chunk = ChunkPos(ctx.origin())
        val placements = RoadPlanSnapshot.placementsAt(chunk.x, chunk.z)
        if (placements.isEmpty()) return false
        val level = ctx.level()
        val catalog = RoadPieces.current
        val styles = RoadStyles.current
        RoadPlanSnapshot.featureRuns.incrementAndGet()
        fun ground(x: Int, z: Int): Int {
            if (!level.hasChunk(x shr 4, z shr 4)) return Int.MIN_VALUE
            val y = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1
            if (y <= level.minBuildHeight) return Int.MIN_VALUE
            if (!level.getFluidState(BlockPos(x, y + 1, z)).isEmpty) return Int.MIN_VALUE
            return y
        }
        fun inChunk(x: Int, z: Int): Boolean = (x shr 4) == chunk.x && (z shr 4) == chunk.z
        // Every placement reaching into the chunk is in the snapshot, so the footprint of all of them keeps decoration off any road block here.
        val footprint = RoadPieceLayer.footprintOf(placements.map { it.placement })
        var placed = 0
        for (p in placements) {
            placed += RoadPieceLayer.lay(level, p.placement, styles.style(p.family), styles, ::ground, ::inChunk, { x, z -> footprint.contains(RoadPieceLayer.key(x, z)) }, catalog)
        }
        RoadPlanSnapshot.piecesPlaced.addAndGet(placements.size)
        RoadPlanSnapshot.markLaid(chunk.toLong(), placements.mapTo(HashSet()) { it.placement.roadId })
        return placed > 0
    }
}
