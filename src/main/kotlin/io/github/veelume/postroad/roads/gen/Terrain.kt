package io.github.veelume.postroad.roads.gen

import net.minecraft.core.BlockPos

/**
 * What the planner routes over: cells of [cellSize] blocks with a surface height, flags and a
 * biome family. [TerrainGrid] is the dense in-memory form (tests); [TiledTerrain] fills
 * itself lazily from a [TileSampler] and is what the world uses.
 */
interface Terrain {
    val cellSize: Int

    fun inBounds(cx: Int, cz: Int): Boolean
    fun heightAt(cx: Int, cz: Int): Int
    /** Overrides a cell's height: an existing road's stored level, which is the ground there now. */
    fun setHeight(cx: Int, cz: Int, y: Int)
    fun has(cx: Int, cz: Int, flag: Int): Boolean
    fun set(cx: Int, cz: Int, flag: Int)
    fun family(cx: Int, cz: Int): Int

    /** Block position at the centre of a cell, at its surface height. */
    fun cellToBlock(cx: Int, cz: Int): BlockPos
    fun blockToCell(x: Int, z: Int): Cell

    companion object {
        const val WATER = 1
        const val LAVA = 2
        const val BLOCKED = 4
        const val ROAD = 8
        /** The cell's height is the planner's estimate, not generated terrain; a later sample may replace it. */
        const val ESTIMATED = 16

        fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
        fun keyX(key: Long): Int = (key shr 32).toInt()
        fun keyZ(key: Long): Int = key.toInt()
    }
}

/** One sampled tile: [TiledTerrain.TILE] × [TiledTerrain.TILE] cells, row-major by z. */
class Tile(val heights: ShortArray, val flags: ByteArray, val families: ByteArray) {
    /** True when any cell is an estimate: kept in memory only and re-sampled after [TiledTerrain.REFRESH_MS]. */
    var provisional: Boolean = false
    var sampledAt: Long = System.currentTimeMillis()

    companion object {
        fun empty(): Tile = Tile(ShortArray(TiledTerrain.CELLS), ByteArray(TiledTerrain.CELLS), ByteArray(TiledTerrain.CELLS))
    }
}

/** Produces the terrain of a tile; the world sampler and the tests implement this. */
fun interface TileSampler {
    fun sample(tx: Int, tz: Int): Tile
}

/** A rectangle of cells, inclusive, that roads may not cross: a structure's footprint. */
data class CellBox(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int)

/**
 * World-sized terrain filled tile by tile as the planner asks. Sampled data (height, water,
 * family) comes from the sampler; BLOCKED and ROAD live in an overlay so the sampled tiles
 * stay pure terrain and can be cached. Owned by one thread at a time.
 */
class TiledTerrain(override val cellSize: Int, private val sampler: TileSampler) : Terrain {
    // Concurrent maps: the planner thread fills them, the server thread reads loaded cells for the debug view.
    private val tiles = java.util.concurrent.ConcurrentHashMap<Long, Tile>()
    private val overlays = java.util.concurrent.ConcurrentHashMap<Long, ByteArray>()
    private val boxes = LinkedHashSet<CellBox>()

    /** Cells outside this rectangle are out of bounds for the search; null = unbounded. */
    var bounds: CellBox? = null

    val tileCount: Int get() = tiles.size

    private fun tileOf(cx: Int, cz: Int): Tile {
        val tx = Math.floorDiv(cx, TILE)
        val tz = Math.floorDiv(cz, TILE)
        val key = Terrain.key(tx, tz)
        tiles[key]?.let { return it }
        val tile = sampler.sample(tx, tz)
        tiles[key] = tile
        for (box in boxes) markBox(tx, tz, box)
        return tile
    }

    private fun overlayOf(cx: Int, cz: Int, create: Boolean): ByteArray? {
        val key = Terrain.key(Math.floorDiv(cx, TILE), Math.floorDiv(cz, TILE))
        return if (create) overlays.getOrPut(key) { ByteArray(CELLS) } else overlays[key]
    }

    private fun idx(cx: Int, cz: Int): Int = Math.floorMod(cz, TILE) * TILE + Math.floorMod(cx, TILE)

    override fun inBounds(cx: Int, cz: Int): Boolean {
        val b = bounds ?: return true
        return cx in b.minX..b.maxX && cz in b.minZ..b.maxZ
    }

    /** Heights set by [setHeight] (road cells), over the sampled ones. */
    private val heightOverrides = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    override fun heightAt(cx: Int, cz: Int): Int = heightOverrides[Terrain.key(cx, cz)] ?: tileOf(cx, cz).heights[idx(cx, cz)].toInt()
    override fun setHeight(cx: Int, cz: Int, y: Int) { heightOverrides[Terrain.key(cx, cz)] = y }

    /**
     * Forgets provisional tiles older than [maxAgeMs]. Called between passes, never inside one: a pass
     * must see one set of heights from its first expansion to its stored points, or a road can carry
     * a step the search never judged.
     */
    fun dropStaleProvisional(maxAgeMs: Long = REFRESH_MS) {
        val now = System.currentTimeMillis()
        tiles.entries.removeIf { it.value.provisional && now - it.value.sampledAt > maxAgeMs }
    }

    /** Forgets provisional tiles covering any of [chunks], so the next look samples them afresh (their corridor was just generated). */
    fun dropProvisionalTouching(chunks: it.unimi.dsi.fastutil.longs.LongOpenHashSet) {
        val tileBlocks = TILE * cellSize
        val it = chunks.iterator()
        while (it.hasNext()) {
            val c = it.nextLong()
            val minX = net.minecraft.world.level.ChunkPos.getX(c) shl 4; val minZ = net.minecraft.world.level.ChunkPos.getZ(c) shl 4
            for (bz in intArrayOf(minZ, minZ + 15)) for (bx in intArrayOf(minX, minX + 15)) {
                val key = Terrain.key(Math.floorDiv(bx, tileBlocks), Math.floorDiv(bz, tileBlocks))
                tiles[key]?.let { t -> if (t.provisional) tiles.remove(key) }
            }
        }
    }

    /** Height if the tile is already in memory, else null — never samples (used for drawing). */
    fun loadedHeightAt(cx: Int, cz: Int): Int? = tiles[Terrain.key(Math.floorDiv(cx, TILE), Math.floorDiv(cz, TILE))]?.heights?.get(idx(cx, cz))?.toInt()

    /** Flags if the tile is already in memory (sampled and overlay), else null. */
    fun loadedFlagsAt(cx: Int, cz: Int): Int? {
        val key = Terrain.key(Math.floorDiv(cx, TILE), Math.floorDiv(cz, TILE))
        val tile = tiles[key] ?: return null
        val i = idx(cx, cz)
        return tile.flags[i].toInt() or (overlays[key]?.get(i)?.toInt() ?: 0)
    }

    override fun has(cx: Int, cz: Int, flag: Int): Boolean {
        val i = idx(cx, cz)
        val sampled = tileOf(cx, cz).flags[i].toInt()
        val overlay = overlayOf(cx, cz, create = false)?.get(i)?.toInt() ?: 0
        return ((sampled or overlay) and flag) != 0
    }

    override fun set(cx: Int, cz: Int, flag: Int) {
        val o = overlayOf(cx, cz, create = true)!!
        val i = idx(cx, cz)
        o[i] = (o[i].toInt() or flag).toByte()
    }

    override fun family(cx: Int, cz: Int): Int = tileOf(cx, cz).families[idx(cx, cz)].toInt()

    override fun cellToBlock(cx: Int, cz: Int): BlockPos =
        BlockPos(cx * cellSize + cellSize / 2, heightAt(cx, cz), cz * cellSize + cellSize / 2)

    override fun blockToCell(x: Int, z: Int): Cell = Cell(Math.floorDiv(x, cellSize), Math.floorDiv(z, cellSize))

    /** Marks a structure footprint impassable, on tiles already loaded and on every tile loaded later. */
    fun block(box: CellBox) {
        if (!boxes.add(box)) return
        for (key in tiles.keys) markBox(Terrain.keyX(key), Terrain.keyZ(key), box)
    }

    private fun markBox(tx: Int, tz: Int, box: CellBox) {
        val fromX = maxOf(box.minX, tx * TILE)
        val toX = minOf(box.maxX, tx * TILE + TILE - 1)
        val fromZ = maxOf(box.minZ, tz * TILE)
        val toZ = minOf(box.maxZ, tz * TILE + TILE - 1)
        if (fromX > toX || fromZ > toZ) return
        for (cz in fromZ..toZ) for (cx in fromX..toX) set(cx, cz, Terrain.BLOCKED)
    }

    companion object {
        const val TILE = 16
        /** How long a provisional tile (estimates in it) is trusted before the sampler is asked again. */
        const val REFRESH_MS = 60_000L
        const val CELLS = TILE * TILE
    }
}

/**
 * A fine terrain restricted to a corridor: only cells whose coarse cell (fine ÷ [ratio]) is in
 * [allowed] are in bounds. The hierarchical planner searches the fine map through this.
 */
class CorridorTerrain(private val fine: Terrain, private val ratio: Int, private val allowed: it.unimi.dsi.fastutil.longs.LongOpenHashSet) : Terrain by fine {
    override fun inBounds(cx: Int, cz: Int): Boolean =
        fine.inBounds(cx, cz) && allowed.contains(Terrain.key(Math.floorDiv(cx, ratio), Math.floorDiv(cz, ratio)))
}
