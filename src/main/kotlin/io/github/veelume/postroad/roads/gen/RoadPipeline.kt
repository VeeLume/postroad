package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Roads planned on ground the world actually has.
 *
 *   1. find the towns   — candidate chunks generated to structure starts and read ([TownScan])
 *   2. plan the shape   — one pass over the estimate, for the *line* a route wants, not its heights
 *   3. make the ground  — each route's own corridor generated to the carvers step
 *   4. plan for real    — the replan, with estimated cells impassable
 *
 * Step 2 is the part that took two tries to get right. Generating a diamond between each pair and
 * planning inside it looked cleaner, but a straight line is not where a route goes: it curves round
 * a ridge or a lake, leaves the diamond, and either plans on estimates again (81 roads, 40 of them
 * provisional) or, with estimates forbidden, is dropped (61 pairs dropped, a fragmented network).
 * No plausible width fixes that, because covering every curve a route might take is the whole-square
 * cost the pipeline exists to avoid.
 *
 * So the estimate earns its keep as a *shape hint* and nothing else. Its roads are provisional, so
 * nothing ever lays them; their corridors follow the line the planner actually chose; and the replan
 * over that real ground either produces a road standing on real heights or produces nothing. An
 * estimate is a shape, never geometry.
 */
object RoadPipeline {

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
            finish(level, center, startedAt, scan, onDone)
        }
        if (scanned < 0) { running = false; return false }
        return true
    }

    private fun finish(level: ServerLevel, center: BlockPos, startedAt: Long, scan: TownScan.Result, onDone: (String) -> Unit) {
        stage = "planning the shape"
        // The towns are real. Now a pass over the estimate, purely for the shape of each route: its
        // roads are provisional, so nothing lays them, and each one's corridor is generated along the
        // line it actually wants rather than the straight line between its towns. The replan then runs
        // on that ground with estimates impassable, and produces either a road that stands on real
        // heights or nothing at all.
        RoadGen.schedule(level, center, discoverTowns = false, realTerrainOnly = false)
        running = false
        stage = "idle"
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val message = "Pipeline: %d town(s) and %d obstacle(s) found in %.0f s (%.0f s total); shape pass queued, its corridors and replans follow"
            .format(scan.towns, scan.obstacles, scan.seconds, seconds)
        Postroad.LOGGER.info(message)
        onDone(message)
    }
}
