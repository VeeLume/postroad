package io.github.veelume.postroad.roads.gen

import net.minecraft.core.BlockPos

/**
 * A dense, fixed-size [Terrain]: one cell per [cellSize] blocks with a surface height, flags,
 * and a biome family. Filled by hand in tests; the world uses [TiledTerrain] instead.
 */
class TerrainGrid(
    /** Block coordinates of cell (0, 0)'s corner. */
    val originX: Int,
    val originZ: Int,
    override val cellSize: Int,
    val width: Int,
    val height: Int,
) : Terrain {
    val heights = ShortArray(width * height)
    val flags = ByteArray(width * height)
    val families = ByteArray(width * height)

    override fun inBounds(cx: Int, cz: Int): Boolean = cx in 0 until width && cz in 0 until height
    fun index(cx: Int, cz: Int): Int = cz * width + cx

    override fun heightAt(cx: Int, cz: Int): Int = heights[index(cx, cz)].toInt()
    override fun setHeight(cx: Int, cz: Int, y: Int) { heights[index(cx, cz)] = y.toShort() }

    override fun has(cx: Int, cz: Int, flag: Int): Boolean = (flags[index(cx, cz)].toInt() and flag) != 0
    override fun set(cx: Int, cz: Int, flag: Int) { flags[index(cx, cz)] = (flags[index(cx, cz)].toInt() or flag).toByte() }
    fun clear(cx: Int, cz: Int, flag: Int) { flags[index(cx, cz)] = (flags[index(cx, cz)].toInt() and flag.inv()).toByte() }

    override fun family(cx: Int, cz: Int): Int = families[index(cx, cz)].toInt()
    fun setFamily(cx: Int, cz: Int, family: Int) { families[index(cx, cz)] = family.toByte() }

    override fun cellToBlock(cx: Int, cz: Int): BlockPos =
        BlockPos(originX + cx * cellSize + cellSize / 2, heightAt(cx, cz), originZ + cz * cellSize + cellSize / 2)

    override fun blockToCell(x: Int, z: Int): Cell = Cell(Math.floorDiv(x - originX, cellSize), Math.floorDiv(z - originZ, cellSize))

    /** A coarse copy: mean height per [ratio]×[ratio] block, water where at least half the cells are, blocked where any is. */
    fun downsample(ratio: Int): TerrainGrid {
        val g = TerrainGrid(originX, originZ, cellSize * ratio, Math.ceilDiv(width, ratio), Math.ceilDiv(height, ratio))
        for (cz in 0 until g.height) for (cx in 0 until g.width) {
            var sum = 0; var n = 0; var water = 0; var flags = 0
            for (dz in 0 until ratio) for (dx in 0 until ratio) {
                val x = cx * ratio + dx; val z = cz * ratio + dz
                if (!inBounds(x, z)) continue
                sum += heightAt(x, z); n++
                if (has(x, z, WATER)) water++
                if (has(x, z, BLOCKED)) flags = flags or BLOCKED
                if (has(x, z, LAVA)) flags = flags or LAVA
            }
            if (n == 0) continue
            g.setHeight(cx, cz, sum / n)
            if (water * 2 >= n) flags = flags or WATER
            g.flags[g.index(cx, cz)] = flags.toByte()
        }
        return g
    }

    /** Fills a rectangle of cells (inclusive) with a flag; used for structure boxes and tests. */
    fun fill(fromX: Int, fromZ: Int, toX: Int, toZ: Int, flag: Int) {
        for (cz in maxOf(0, fromZ)..minOf(height - 1, toZ)) for (cx in maxOf(0, fromX)..minOf(width - 1, toX)) set(cx, cz, flag)
    }

    companion object {
        const val WATER = Terrain.WATER
        const val LAVA = Terrain.LAVA
        const val BLOCKED = Terrain.BLOCKED
        const val ROAD = Terrain.ROAD

        /** A flat grid at [y], every cell passable; the tests start from this. */
        fun flat(width: Int, height: Int, y: Int, cellSize: Int = 4, originX: Int = 0, originZ: Int = 0): TerrainGrid {
            val grid = TerrainGrid(originX, originZ, cellSize, width, height)
            grid.heights.fill(y.toShort())
            return grid
        }
    }
}

data class Cell(val x: Int, val z: Int) {
    fun distanceTo(other: Cell): Double {
        val dx = (x - other.x).toDouble()
        val dz = (z - other.z).toDouble()
        return Math.sqrt(dx * dx + dz * dz)
    }

    val key: Long get() = Terrain.key(x, z)

    companion object {
        fun of(key: Long): Cell = Cell(Terrain.keyX(key), Terrain.keyZ(key))
    }
}
