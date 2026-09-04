package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.fml.ModList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Distant Horizons as a terrain source. DH's distant generator runs the real chunk generator far
 * ahead of the player and keeps what it saw; asked through its API, a column comes back with the
 * blocks the world will actually have — structures, carver holes and lakes included — where the
 * planner's density estimate can only guess. Absent DH, or where DH has not generated yet, the
 * answer is null and the sampler falls back to the estimate.
 *
 * DH's classes are only touched inside [DhTerrainAccess], which is never loaded when the mod is
 * not present.
 */
object DhTerrain {
    /** A column as DH knows it: the first air block above the ground, and what the ground is. */
    class Column(val top: Int, val water: Boolean, val lava: Boolean, val blocked: Boolean = false)

    val present: Boolean by lazy { ModList.get().isLoaded(MOD_ID) }

    val hits = AtomicInteger()
    val misses = AtomicInteger()

    @Volatile
    var failure: String? = null
        private set
    /** The last message DH gave for a column it did not return, for the status line. */
    @Volatile
    var lastMiss: String? = null

    /** DH's column at (x, z), or null when DH is absent, not ready, has no data here, or threw. */
    fun column(level: ServerLevel, x: Int, z: Int): Column? {
        if (!present || failure != null) return null
        return try {
            DhTerrainAccess.column(level, x, z).also { if (it == null) misses.incrementAndGet() else hits.incrementAndGet() }
        } catch (e: Throwable) {
            failure = e.toString()
            Postroad.LOGGER.warn("Distant Horizons terrain unavailable, using the estimate: {}", e.toString())
            null
        }
    }

    fun status(level: ServerLevel? = null): String = when {
        !present -> "Distant Horizons not installed"
        failure != null -> "Distant Horizons failed: $failure"
        else -> "Distant Horizons: ${hits.get()} column(s) known, ${misses.get()} not generated yet${lastMiss?.let { " (last miss: $it)" } ?: ""}" +
            (level?.let { try { "; " + DhTerrainAccess.diagnose(it) } catch (e: Throwable) { "; diagnose failed: $e" } } ?: "")
    }

    /** What counts as ground when reading a column top-down: not air, not a plant, not part of a tree. */
    fun isGround(state: BlockState): Boolean =
        !state.isAir && state.fluidState.isEmpty && state.block !is LeavesBlock && !state.`is`(BlockTags.LOGS) && state.blocksMotion()

    const val MOD_ID = "distanthorizons"
}

/** The part that references DH's API; loaded only when [DhTerrain.present]. */
private object DhTerrainAccess {
    private val levels = java.util.WeakHashMap<ServerLevel, com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper>()
    /** DH insists on a cache per repo call (soft references to its data sources); one per thread keeps it unshared. */
    private val caches = ThreadLocal<com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache>()

    private fun cache(repo: com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo): com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache =
        caches.get() ?: repo.createSoftCache().also { caches.set(it) }

    private fun wrapper(level: ServerLevel): com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper? {
        synchronized(levels) { levels[level]?.let { return it } }
        val proxy = com.seibel.distanthorizons.api.DhApi.Delayed.worldProxy ?: return null
        if (!proxy.worldLoaded()) return null
        for (w in proxy.allLoadedLevelWrappers) {
            if (w.wrappedMcObject === level) {
                synchronized(levels) { levels[level] = w }
                return w
            }
        }
        return null
    }

    /** Why a column might come back empty: what the API exposes right now. */
    fun diagnose(level: ServerLevel): String {
        val proxy = com.seibel.distanthorizons.api.DhApi.Delayed.worldProxy
        val repo = com.seibel.distanthorizons.api.DhApi.Delayed.terrainRepo
        if (proxy == null) return "world proxy missing"
        if (!proxy.worldLoaded()) return "DH world not loaded"
        val names = proxy.allLoadedLevelWrappers.map { "${it.dhIdentifier} (${it.wrappedMcObject?.javaClass?.simpleName}, match ${it.wrappedMcObject === level})" }
        return "repo ${if (repo == null) "missing" else "ok"}, levels: ${names.joinToString(", ").ifEmpty { "none" }}, api ${com.seibel.distanthorizons.api.DhApi.getApiMajorVersion()}.${com.seibel.distanthorizons.api.DhApi.getApiMinorVersion()}"
    }

    fun column(level: ServerLevel, x: Int, z: Int): DhTerrain.Column? {
        val repo = com.seibel.distanthorizons.api.DhApi.Delayed.terrainRepo ?: return null
        val wrapper = wrapper(level) ?: return null
        val result = repo.getColumnDataAtBlockPos(wrapper, x, z, cache(repo))
        if (!result.success) { DhTerrain.lastMiss = "not success: ${result.message}"; return null }
        val points = result.payload ?: run { DhTerrain.lastMiss = "null payload: ${result.message}"; return null }
        if (points.isEmpty()) { DhTerrain.lastMiss = "empty column: ${result.message}"; return null }
        // Top down: the first point that is ground decides; a liquid above it makes the column water/lava.
        var liquidTop = Int.MIN_VALUE
        var lava = false
        for (p in points.sortedByDescending { it.topYBlockPos }) {
            val wrapperState = p.blockStateWrapper ?: continue
            if (wrapperState.isAir) continue
            val state = wrapperState.wrappedMcObject as? BlockState
            if (state == null) {
                // No Minecraft state to inspect: trust DH's own flags.
                if (wrapperState.isLiquid) { if (liquidTop == Int.MIN_VALUE) liquidTop = p.topYBlockPos; continue }
                if (wrapperState.isSolid) return DhTerrain.Column(topOf(p, liquidTop), liquidTop != Int.MIN_VALUE, lava)
                continue
            }
            if (!state.fluidState.isEmpty) {
                if (liquidTop == Int.MIN_VALUE) { liquidTop = p.topYBlockPos; lava = state.fluidState.`is`(net.minecraft.tags.FluidTags.LAVA) }
                continue
            }
            if (!DhTerrain.isGround(state)) continue
            return DhTerrain.Column(topOf(p, liquidTop), liquidTop != Int.MIN_VALUE, lava)
        }
        DhTerrain.lastMiss = "no ground in ${points.size} point(s): " + points.take(4).joinToString { "${it.blockStateWrapper?.serialString}@${it.bottomYBlockPos}..${it.topYBlockPos}" }
        return null
    }

    /** The planner's height is the first air block: above the liquid surface when there is one, else above the ground. */
    private fun topOf(ground: com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint, liquidTop: Int): Int =
        if (liquidTop != Int.MIN_VALUE) liquidTop else ground.topYBlockPos
}
