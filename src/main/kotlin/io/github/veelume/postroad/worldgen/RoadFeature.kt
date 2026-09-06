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
 * chunk's origin. It asks the plan snapshot for the segments whose pieces reach into this chunk
 * and lays each piece's blocks inside it; a chunk without segments is untouched.
 */
class RoadFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {
    override fun place(ctx: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val chunk = ChunkPos(ctx.origin())
        val segments = RoadPlanSnapshot.segmentsAt(chunk.x, chunk.z)
        if (segments.isEmpty()) return false
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
        var placed = 0
        for (seg in segments) {
            val p = catalog.placement(seg.a, seg.b, seg.index) ?: run { RoadPlanSnapshot.noPiece.incrementAndGet(); null } ?: continue
            // The neighbouring pieces' footprints keep this piece's decoration off them.
            val around = listOfNotNull(seg.prev?.let { catalog.placement(it, seg.a, seg.index - 1) }, p, seg.next?.let { catalog.placement(seg.b, it, seg.index + 1) })
            val footprint = RoadPieceLayer.footprintOf(around)
            placed += RoadPieceLayer.lay(level, p, styles.style(seg.family), styles, ::ground, ::inChunk) { x, z -> footprint.contains(RoadPieceLayer.key(x, z)) }
        }
        // Anchor squares after the pieces: ends, turns and dips (see RoadPieceLayer.needsSquare).
        for (seg in segments) {
            val style = styles.style(seg.family)
            if (RoadPieceLayer.needsSquare(seg.prev, seg.a, seg.b)) placed += RoadPieceLayer.layCorner(level, seg.a.below(), if (seg.prev == null) catalog.end else catalog.corner, style, styles, ::ground, ::inChunk)
            if (seg.next == null) placed += RoadPieceLayer.layCorner(level, seg.b.below(), catalog.end, style, styles, ::ground, ::inChunk)
        }
        RoadPlanSnapshot.piecesPlaced.addAndGet(segments.size)
        RoadPlanSnapshot.markLaid(chunk.toLong(), segments.mapTo(HashSet()) { it.roadId })
        return placed > 0
    }
}
