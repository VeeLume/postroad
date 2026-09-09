package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Roads planned on ground the world actually has, in the order that makes that possible.
 *
 *   1. find the towns  — candidate chunks generated to structure starts and read ([TownScan])
 *   2. make the ground — a diamond corridor between each pair the planner may link, generated
 *      to the carvers step so the heights along it are real
 *   3. plan, once      — the ordinary pass, with discovery off, over terrain that is now known
 *
 * The old order was the reverse: plan on a density estimate, discover the estimate was wrong,
 * generate the corridor, plan again. That loop ran 143 passes on one world and still left about a
 * third of the roads on estimated heights, because a pair whose replan never happened was kept as
 * it was. Planning after the ground exists removes the loop rather than adding a fourth correction
 * to it — no provisional roads, no replans, nothing to re-fit.
 *
 * The cost is roughly what the old way already paid. Measured here: about 18 chunks a second at
 * carvers, and the diamonds for a spawn network come to some twelve thousand chunks.
 */
object RoadPipeline {

    /**
     * Half the corridor's width at its middle, in blocks. Wide enough for a route to get round a
     * ridge or a lake, narrow enough that the chunks are not wasted: the diamond tapers to
     * [Corridor.diamond]'s end width at the towns, where the route is pinned to a street stub
     * anyway. This is the lever to raise when a pair comes back unreachable inside its corridor.
     */
    const val CORRIDOR_HALF = 96

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var stage: String = "idle"
        private set

    /** Starts the pipeline around [center]; false if one is already running. */
    fun run(level: ServerLevel, center: BlockPos, radius: Int, onDone: (String) -> Unit): Boolean {
        if (running) return false
        running = true
        stage = "finding towns"
        val startedAt = System.nanoTime()
        val scanned = TownScan.scan(level, center, radius) { scan ->
            stage = "making the ground"
            val corridors = corridorsFor(level, center, radius)
            if (corridors.isEmpty()) {
                finish(level, center, startedAt, scan, 0, onDone)
                return@scan
            }
            Postroad.LOGGER.info("Pipeline: {} corridor chunk(s) for the towns in reach", corridors.size)
            ChunkPregen.request(level.dimension().location(), corridors, ChunkStatus.CARVERS) { ok, _ ->
                finish(level, center, startedAt, scan, ok, onDone)
            }
        }
        if (scanned < 0) { running = false; return false }
        return true
    }

    /** The chunks under every pair the planner may link, as diamonds. */
    private fun corridorsFor(level: ServerLevel, center: BlockPos, radius: Int): LongOpenHashSet {
        val storage = RoadPlanStorage.get(level.server)
        val dimension = level.dimension().location()
        val towns = storage.townsIn(dimension).filter { it.pos.distSqr(center) <= radius.toDouble() * radius }
        val maxLink = PostroadConfig.planMaxLink.toDouble()
        val neighbours = PostroadConfig.planNeighbours
        val out = LongOpenHashSet()
        val done = HashSet<String>()
        for (town in towns) {
            // The same shape the planner links by: the nearest few towns, within the longest link.
            val near = towns.asSequence()
                .filter { it.id != town.id && it.pos.distSqr(town.pos) <= maxLink * maxLink }
                .sortedBy { it.pos.distSqr(town.pos) }
                .take(neighbours)
            for (other in near) {
                val key = if (town.id < other.id) "${town.id}|${other.id}" else "${other.id}|${town.id}"
                if (!done.add(key)) continue
                if (storage.roads.values.any { pairKey(it.from, it.to) == key }) continue
                out.addAll(Corridor.diamond(town.pos, other.pos, CORRIDOR_HALF))
            }
            // The town itself, so its streets and stubs stand on real ground too.
            out.addAll(Corridor.square(town.pos, 64))
        }
        return out
    }

    private fun pairKey(a: String, b: String): String = if (a < b) "$a|$b" else "$b|$a"

    private fun finish(level: ServerLevel, center: BlockPos, startedAt: Long, scan: TownScan.Result, corridorChunks: Int, onDone: (String) -> Unit) {
        stage = "planning"
        // Discovery is done and the corridors are real ground, so the pass may not leave them: an
        // estimated cell is impassable. Without this a route simply walks out of its corridor and
        // guesses again — the first run of this pipeline planned 81 roads and 40 came back
        // provisional for exactly that reason. Now a pair that cannot be routed inside the ground it
        // was given comes back unreachable, which says "widen the corridor", not "lay a guess".
        RoadPlanner.realTerrainOnly = true
        RoadGen.schedule(level, center, discoverTowns = false)
        running = false
        stage = "idle"
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val message = "Pipeline: %d town(s) and %d obstacle(s) found in %.0f s, %d corridor chunk(s) generated, planning pass queued (%.0f s total)"
            .format(scan.towns, scan.obstacles, scan.seconds, corridorChunks, seconds)
        Postroad.LOGGER.info(message)
        onDone(message)
    }
}
