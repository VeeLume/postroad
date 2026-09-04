package io.github.veelume.postroad.roads.gen

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Heightmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Terrain copied out of generated chunks: per chunk the first air block of every column and
 * whether liquid sits on it. Filled on the server thread when a chunk the pre-generator asked
 * for is ready (or any chunk the planner wanted turns out to exist), read from the planner
 * thread by the sampler. Chunks are not thread-safe; these arrays are, and they are all the
 * planner needs: the real surface, holes, lakes, and every structure's ground, before trees.
 */
object KnownTerrain {
    class ChunkTops(val top: ShortArray, val liquid: ByteArray, val lava: ByteArray, val blocked: ByteArray = ByteArray(256)) {
        fun index(x: Int, z: Int): Int = (z and 15) shl 4 or (x and 15)
    }

    private val chunks = ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, ChunkTops>>()

    fun size(dimension: ResourceLocation): Int = chunks[dimension]?.size ?: 0

    fun has(dimension: ResourceLocation, chunk: Long): Boolean = chunks[dimension]?.containsKey(chunk) == true

    /** The column at (x, z) if its chunk has been recorded; `top` is the first air block. */
    fun column(dimension: ResourceLocation, x: Int, z: Int): DhTerrain.Column? {
        val tops = chunks[dimension]?.get(ChunkPos.asLong(x shr 4, z shr 4)) ?: return null
        val i = tops.index(x, z)
        return DhTerrain.Column(tops.top[i].toInt(), tops.liquid[i].toInt() != 0, tops.lava[i].toInt() != 0, tops.blocked[i].toInt() != 0)
    }

    /**
     * Records [chunk] (server thread). Needs at least the surface step: heightmaps then know the
     * ground and the water. On finished chunks, trees are already there; the top is walked down
     * past logs and leaves so the road's ground is what the planner sees.
     */
    fun record(dimension: ResourceLocation, chunk: ChunkAccess, level: net.minecraft.server.level.ServerLevel? = null) {
        val worldSurface = if (chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE_WG)) Heightmap.Types.WORLD_SURFACE_WG else Heightmap.Types.WORLD_SURFACE
        val oceanFloor = if (chunk.hasPrimedHeightmap(Heightmap.Types.OCEAN_FLOOR_WG)) Heightmap.Types.OCEAN_FLOOR_WG else Heightmap.Types.OCEAN_FLOOR
        val tops = ChunkTops(ShortArray(256), ByteArray(256), ByteArray(256))
        val pos = chunk.pos
        val cursor = BlockPos.MutableBlockPos()
        for (lz in 0 until 16) for (lx in 0 until 16) {
            val i = (lz shl 4) or lx
            val surface = chunk.getHeight(worldSurface, lx, lz)   // first air above anything, water included
            var floor = chunk.getHeight(oceanFloor, lx, lz)       // first non-solid above the ground (logs count as ground)
            val x = pos.minBlockX + lx; val z = pos.minBlockZ + lz
            // Trees on finished chunks: walk down past logs and leaves to real ground.
            var guard = 0
            while (floor > chunk.minBuildHeight && guard++ < 48) {
                cursor.set(x, floor - 1, z)
                val s = chunk.getBlockState(cursor)
                if (DhTerrain.isGround(s)) break
                if (!s.fluidState.isEmpty) break
                floor--
            }
            if (surface > floor) {
                cursor.set(x, surface - 1, z)
                val fluid = chunk.getBlockState(cursor).fluidState
                if (!fluid.isEmpty) {
                    tops.top[i] = surface.toShort(); tops.liquid[i] = 1
                    if (fluid.`is`(net.minecraft.tags.FluidTags.LAVA)) tops.lava[i] = 1
                    continue
                }
            }
            tops.top[i] = floor.toShort()
        }
        if (level != null) markStructures(level, chunk, tops)
        chunks.getOrPut(dimension) { ConcurrentHashMap() }[pos.toLong()] = tops
    }

    /**
     * Columns under the pieces of real structures (not villages — those are endpoints with their own
     * footprints): the chunk's own starts plus the starts it references, whose pieces reach into it.
     * Structure blocks are only placed at the features step, so the pre-generated ground does not show
     * them; their piece boxes do. Buried pieces (top well under the ground) do not count.
     */
    private fun markStructures(level: net.minecraft.server.level.ServerLevel, chunk: ChunkAccess, tops: ChunkTops) {
        val registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
        val pos = chunk.pos
        val starts = ArrayList<net.minecraft.world.level.levelgen.structure.StructureStart>(chunk.allStarts.values)
        for ((structure, refs) in chunk.allReferences) {
            val it = refs.iterator()
            while (it.hasNext()) {
                val ref = it.nextLong()
                if (ref == pos.toLong()) continue
                val other = level.chunkSource.getChunk(ChunkPos.getX(ref), ChunkPos.getZ(ref), net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS, false) ?: continue
                other.allStarts[structure]?.let { starts.add(it) }
            }
        }
        for (start in starts) {
            if (!start.isValid) continue
            val holder = registry.wrapAsHolder(start.structure)
            if (holder.`is`(net.minecraft.tags.StructureTags.VILLAGE)) continue
            for (piece in start.pieces) {
                val b = piece.boundingBox
                val minX = maxOf(b.minX(), pos.minBlockX); val maxX = minOf(b.maxX(), pos.maxBlockX)
                val minZ = maxOf(b.minZ(), pos.minBlockZ); val maxZ = minOf(b.maxZ(), pos.maxBlockZ)
                if (minX > maxX || minZ > maxZ) continue
                for (z in minZ..maxZ) for (x in minX..maxX) {
                    val i = tops.index(x, z)
                    if (b.maxY() >= tops.top[i] - BURIED_BELOW) tops.blocked[i] = 1
                }
            }
        }
    }

    /** A piece whose top is this far under the ground surface is buried and does not block. */
    private const val BURIED_BELOW = 4

    fun clear() {
        chunks.clear()
    }
}
