package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.network.PlaceResolver
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Finds towns by generating structure starts and reading them, instead of predicting layouts.
 *
 * Two steps, and the first is what makes the second affordable. [TownFinder.mayStart] is a pure
 * seed function that says which chunks a structure set *could* begin in — it touches nothing and
 * rejects the overwhelming majority. Only those chunks are then generated, to `STRUCTURE_STARTS`
 * alone, and [TownFinder.fromChunk] reads what really started there.
 *
 * Measured on this machine: structure starts generate at about 118 chunks a second against 18 for
 * terrain, and leave the tick alone. Discovery over a wide area is therefore cheap, which is the
 * opposite of the old arrangement, where predicting layouts was the single most expensive thing the
 * planner did — and it was work the world would do again anyway when the chunk caught up.
 */
object TownScan {

    class Result(val candidates: Int, val towns: Int, val obstacles: Int, val seconds: Double)

    @Volatile
    var running: Boolean = false
        private set

    /**
     * Scans the square of [radius] blocks around [center]. Returns how many candidate chunks it will
     * generate, or -1 if a scan is already going. [onDone] runs on the server thread.
     */
    fun scan(level: ServerLevel, center: BlockPos, radius: Int, onDone: (Result) -> Unit): Int {
        if (running) return -1
        val finder = RoadGen.workerFor(level).finder
        val storage = RoadPlanStorage.get(level.server)
        val dimension = level.dimension().location()

        // The cheap pass: which chunks could hold a start at all.
        val candidates = LongOpenHashSet()
        for (cz in ((center.z - radius) shr 4)..((center.z + radius) shr 4)) {
            for (cx in ((center.x - radius) shr 4)..((center.x + radius) shr 4)) {
                if (finder.mayStart(cx, cz)) candidates.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz))
            }
        }
        if (candidates.isEmpty()) {
            onDone(Result(0, 0, 0, 0.0))
            return 0
        }

        running = true
        val startedAt = System.nanoTime()
        var towns = 0
        var obstacles = 0
        ChunkPregen.request(dimension, candidates, ChunkStatus.STRUCTURE_STARTS, onChunk = { chunk ->
            val found = finder.fromChunk(chunk)
            found.town?.let { c ->
                if (!storage.towns.containsKey(c.id)) {
                    // The town's own height is not known yet — terrain comes later, with the corridors.
                    // The box centre is what the planner needs; y is filled in when the ground is real.
                    val centre = c.box.center
                    storage.addTown(
                        PlannedTown(
                            c.id, dimension, c.structure, BlockPos(centre.x, centre.y, centre.z), c.box,
                            c.pieces, c.streets, c.exits.map { it.first }, c.exits.map { e -> e.second.get2DDataValue() },
                        )
                    )
                    PlaceResolver.predict(level, c.structure, c.id, BlockPos(centre.x, centre.y, centre.z))
                    towns++
                }
            }
            for (o in found.obstacles) {
                val obstacle = PlannedObstacle(dimension, o.structure, o.chunk.toLong(), o.box)
                if (!storage.obstacles.containsKey(obstacle.key)) { storage.addObstacle(obstacle); obstacles++ }
            }
        }) { ok, failed ->
            running = false
            storage.setDirty()
            val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
            Postroad.LOGGER.info(
                "Town scan: {} candidate chunk(s) generated to structure starts in {} s ({} failed) — {} town(s), {} obstacle(s)",
                ok, "%.1f".format(seconds), failed, towns, obstacles,
            )
            onDone(Result(candidates.size, towns, obstacles, seconds))
        }
        return candidates.size
    }
}
