package io.github.veelume.postroad.roads

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.advancement.PostroadAdvancements
import io.github.veelume.postroad.network.GameDay
import io.github.veelume.postroad.network.LedgerEntry
import io.github.veelume.postroad.network.Network
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import java.util.UUID

/**
 * Free-form charting, Via Romana's way: start anywhere, walk, finish anywhere. The server
 * samples the walk every few blocks and shows each sample as a particle column (green road,
 * grey not). Finishing records a [RoadPath] per network if the walk is road enough, attaches
 * it to existing paths it started or ended next to, and hooks up any depot it passed. Road nobody
 * had charted before, and towns it brings onto the network, are paid for ([paySurvey]).
 */
object Charting {

    class Session(val playerId: UUID, val level: ServerLevel) {
        val points = ArrayList<BlockPos>()
        val tiers = ArrayList<Tier?>()
        var distance = 0.0
        var nextSpacing = spacing()
        var lastPos: BlockPos? = null
        var sinceReadout = 0
        /** Set when the player moved further than [MAX_STEP] between two samples: a teleport, not a walk. */
        var jumped = false

        val roadShare: Double get() = if (tiers.isEmpty()) 0.0 else tiers.count { it != null }.toDouble() / tiers.size

        private fun spacing(): Int {
            val r = RoadRules.current
            return r.sampleSpacingMin + (Math.random() * (r.sampleSpacingMax - r.sampleSpacingMin + 1)).toInt()
        }

        /** Adds a sample at [pos] if the walk has moved far enough; returns the tier when sampled. */
        fun step(pos: BlockPos, force: Boolean = false): Tier? {
            val last = lastPos
            if (last != null) {
                val d = RoadPath.distance(last, pos)
                if (d > MAX_STEP) { jumped = true; return null }
                if (d < nextSpacing && !force) return null
                distance += d
            }
            return sampleAt(pos)
        }

        fun sampleAt(pos: BlockPos): Tier? {
            val tier = RoadClassifier.sample(level, pos)
            points.add(pos.immutable())
            tiers.add(tier)
            lastPos = pos.immutable()
            nextSpacing = spacing()
            showSample(level, pos, tier)
            return tier
        }
    }

    private val sessions = HashMap<UUID, Session>()

    fun session(player: ServerPlayer): Session? = sessions[player.uuid]
    fun isCharting(player: ServerPlayer): Boolean = sessions.containsKey(player.uuid)

    fun start(player: ServerPlayer): Boolean {
        if (sessions.containsKey(player.uuid)) return false
        val session = Session(player.uuid, player.serverLevel())
        sessions[player.uuid] = session
        session.step(player.onPos.above(), force = true)
        player.displayClientMessage(Component.translatable("message.postroad.chart.started").withStyle(ChatFormatting.GOLD), false)
        return true
    }

    fun abort(player: ServerPlayer, reasonKey: String) {
        if (sessions.remove(player.uuid) == null) return
        player.displayClientMessage(Component.translatable(reasonKey).withStyle(ChatFormatting.YELLOW), false)
    }

    /** Finishes the walk; returns the recorded path, or null if refused. */
    fun finish(player: ServerPlayer): RoadPath? {
        val session = sessions.remove(player.uuid) ?: return null
        session.step(player.onPos.above(), force = true)
        if (session.jumped) {
            player.displayClientMessage(Component.translatable("message.postroad.chart.aborted_teleport").withStyle(ChatFormatting.YELLOW), false)
            return null
        }
        return record(player, session)
    }

    fun record(player: ServerPlayer, session: Session): RoadPath? {
        val rules = RoadRules.current
        val verdict = RoadClassifier.evaluate(session.tiers, rules)
        if (session.points.size < 2) {
            player.displayClientMessage(Component.translatable("message.postroad.chart.too_short").withStyle(ChatFormatting.YELLOW), false)
            return null
        }
        if (!verdict.isRoad) {
            player.displayClientMessage(
                Component.translatable("message.postroad.chart.refused", percent(verdict.roadShare), percent(rules.minRoadShare), describeGaps(session))
                    .withStyle(ChatFormatting.YELLOW),
                false,
            )
            return null
        }
        val level = session.level
        val network = Network.get(level.server)
        markGenerated(player, session, network)?.let { return it }
        val path = RoadPath(
            id = UUID.randomUUID().toString().take(8),
            dimension = level.dimension().location(),
            points = session.points,
            tiers = session.tiers,
            recordedBy = player.gameProfile.name.lowercase(),
            recordedDay = GameDay.of(level),
        )
        val join = PostroadConfig.joinDistance
        val surveyed = newRoad(network, path.dimension, path.points, 0, path.points.size - 1) { path.tiers[it] != null }
        val startLink = network.nearestPathPoint(path.dimension, path.points.first(), join, exclude = path.id)
        val endLink = network.nearestPathPoint(path.dimension, path.points.last(), join, exclude = path.id)
        network.addPath(path)
        startLink?.let { (other, idx) -> network.addLink(PathLink(path.id, 0, other.id, idx)) }
        endLink?.let { (other, idx) -> network.addLink(PathLink(path.id, path.points.size - 1, other.id, idx)) }
        val towns = network.attachTowns(path, join)

        network.record(LedgerEntry(path.recordedDay, player.gameProfile.name, LedgerEntry.OP_PATH, 0, "network",
            "${path.length.toInt()} blocks, ${path.tier?.key ?: "?"}, ${towns.size} town(s)"))
        PostroadAdvancements.award(player, PostroadAdvancements.PATH_CHARTED)
        paySurvey(player, network, path.recordedDay, surveyed, towns)
        val linked = listOfNotNull(startLink, endLink).size
        player.displayClientMessage(
            Component.translatable("message.postroad.chart.recorded", path.length.toInt(), percent(verdict.roadShare),
                Component.translatable("tier.postroad.${path.tier?.key ?: "dirt"}"), linked, towns.size).withStyle(ChatFormatting.GOLD),
            false,
        )
        Postroad.LOGGER.info("{} charted path {} ({} blocks, {}, {} links, towns {})", player.gameProfile.name, path.id, path.length.toInt(), path.tier, linked, towns.map { it.name })
        return path
    }

    /**
     * A walk that mostly follows one uncharted generated road charts that road instead of recording
     * a duplicate: the path is marked charted, its tiers take the sampled values where the walk
     * covered them, and towns along it attach. Null if no generated road fits.
     */
    fun markGenerated(player: ServerPlayer, session: Session, network: Network): RoadPath? {
        val dimension = session.level.dimension().location()
        val join = PostroadConfig.joinDistance
        val candidates = network.paths.values.filter { it.dimension == dimension && !it.charted && it.recordedBy == io.github.veelume.postroad.roads.gen.RoadGen.GENERATED_BY }
        if (candidates.isEmpty()) return null
        var best: RoadPath? = null
        var bestHits: List<Int?> = emptyList()
        for (path in candidates) {
            val hits = session.points.map { p -> path.nearestIndex(p)?.takeIf { it.second <= join }?.first }
            val share = hits.count { it != null }.toDouble() / hits.size
            if (share >= PostroadConfig.chartMarkShare && hits.count { it != null } > bestHits.count { it != null }) { best = path; bestHits = hits }
        }
        val path = best ?: return null
        val covered = bestHits.filterNotNull()
        val surveyed = newRoad(network, dimension, path.points, covered.min(), covered.max()) { true }
        for ((k, idx) in bestHits.withIndex()) {
            if (idx == null) continue
            session.tiers[k]?.let { path.tiers[idx] = it }
        }
        path.charted = true
        val towns = network.attachTowns(path, join)
        network.setDirty()
        val day = GameDay.of(session.level)
        network.record(LedgerEntry(day, player.gameProfile.name, LedgerEntry.OP_PATH, 0, "network",
            "${path.length.toInt()} blocks, generated road ${path.id} charted, ${towns.size} town(s)"))
        PostroadAdvancements.award(player, PostroadAdvancements.PATH_CHARTED)
        paySurvey(player, network, day, surveyed, towns)
        player.displayClientMessage(Component.translatable("message.postroad.chart.marked", path.length.toInt(), towns.size).withStyle(ChatFormatting.GOLD), false)
        Postroad.LOGGER.info("{} charted generated road {} ({} blocks, towns {})", player.gameProfile.name, path.id, path.length.toInt(), towns.map { it.name })
        return path
    }

    /** Road a walk charted for the first time: its length, and points along it at most a survey cell apart (to record as paid). */
    data class Survey(val dimension: net.minecraft.resources.ResourceLocation, val blocks: Double, val points: List<BlockPos>)

    /**
     * What of [points] between [from] and [to] nobody has charted yet: segments between two consecutive
     * points that are both road ([isRoad]), both farther than joinDistance from every charted path, and
     * both outside what the network has already paid for (a road removed and charted again stays paid).
     * A segment next to one this walk counted more than [REVISIT] blocks earlier is the walk coming back
     * along its own road, and is not counted twice. Called before the walk's own path is charted.
     */
    fun newRoad(network: Network, dimension: net.minecraft.resources.ResourceLocation, points: List<BlockPos>, from: Int, to: Int, isRoad: (Int) -> Boolean): Survey {
        val reach = PostroadConfig.joinDistance
        val cell = Network.SURVEY_CELL
        val charted = network.paths.values.filter { it.charted && it.dimension == dimension }
        val fresh = BooleanArray(points.size) { i ->
            i in from..to && isRoad(i) && !network.isSurveyed(dimension, points[i]) &&
                charted.none { other -> other.nearestIndex(points[i])?.let { it.second <= reach } == true }
        }
        // Cell of the walk → how far along the walk it was first counted.
        val countedAt = it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap()
        fun key(x: Double, z: Double) = net.minecraft.world.level.ChunkPos.asLong(Math.floorDiv(x.toInt(), cell), Math.floorDiv(z.toInt(), cell))
        var total = 0.0
        var along = 0.0
        val record = ArrayList<BlockPos>()
        for (i in from until to) {
            val a = points[i]; val b = points[i + 1]
            val length = RoadPath.distance(a, b)
            if (fresh[i] && fresh[i + 1] && length <= MAX_STEP) {
                val mx = (a.x + b.x) / 2.0; val mz = (a.z + b.z) / 2.0
                val cx = Math.floorDiv(mx.toInt(), cell); val cz = Math.floorDiv(mz.toInt(), cell)
                var revisit = false
                for (dx in -1..1) for (dz in -1..1) {
                    val k = net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz)
                    if (countedAt.containsKey(k) && countedAt.get(k) < along - REVISIT) revisit = true
                }
                if (!revisit) {
                    total += length
                    val steps = (length / cell).toInt() + 1
                    for (s in 0..steps) {
                        val t = s.toDouble() / steps
                        val x = a.x + (b.x - a.x) * t; val z = a.z + (b.z - a.z) * t
                        val k = key(x, z)
                        if (!countedAt.containsKey(k)) countedAt.put(k, along + length * t)
                        record.add(BlockPos.containing(x, a.y + (b.y - a.y) * t, z))
                    }
                }
            }
            along += length
        }
        return Survey(dimension, total, record)
    }

    /** Further than this between two samples is a teleport: the walk is abandoned, and no such segment is paid. */
    const val MAX_STEP = 64.0

    /** Blocks of a walk after which coming back next to its own counted road is a revisit, not progress. */
    private const val REVISIT = 4.0 * Network.SURVEY_CELL

    /**
     * The network pays the surveyor: a coin per `surveyBlocksPerCoin` blocks of new road (what falls short
     * of a coin carries over to the player's next walk), plus a bonus per town brought on for the first
     * time. Both are recorded as paid.
     */
    fun paySurvey(player: ServerPlayer, network: Network, day: Long, survey: Survey, towns: List<io.github.veelume.postroad.network.Place>) {
        val name = player.gameProfile.name
        network.markSurveyed(survey.dimension, survey.points)
        val newTowns = towns.filter { network.surveyedTowns.add(it.id) }
        val per = PostroadConfig.surveyBlocksPerCoin
        val owed = network.surveyCarry(name) + survey.blocks
        val fromRoad = if (per > 0) (owed / per).toLong() else 0L
        network.setSurveyCarry(name, if (per > 0) owed - fromRoad * per else 0.0)
        val coins = fromRoad + newTowns.size * PostroadConfig.surveyTownBonus
        if (coins <= 0) return
        network.credit(Network.playerAccount(name), coins, day, name, LedgerEntry.OP_SURVEY, "${survey.blocks.toInt()} new blocks, ${newTowns.size} town(s)")
        player.displayClientMessage(
            Component.translatable("message.postroad.chart.paid", coins, survey.blocks.toInt(), newTowns.size, network.balance(Network.playerAccount(name)))
                .withStyle(ChatFormatting.GOLD),
            false,
        )
    }

    /** Where the road was missing: the longest run of non-road samples, in blocks from the start. */
    private fun describeGaps(session: Session): String {
        var bestStart = -1
        var bestLen = 0
        var runStart = -1
        for ((i, t) in session.tiers.withIndex()) {
            if (t == null) {
                if (runStart < 0) runStart = i
                if (i - runStart + 1 > bestLen) { bestLen = i - runStart + 1; bestStart = runStart }
            } else runStart = -1
        }
        if (bestStart < 0) return ""
        val path = RoadPath("tmp", session.level.dimension().location(), session.points, session.tiers, "", 0)
        val from = path.lengthBetween(0, bestStart).toInt()
        val to = path.lengthBetween(0, (bestStart + bestLen - 1).coerceAtMost(session.points.size - 1)).toInt()
        return Component.translatable("message.postroad.chart.gap", from, to).string
    }

    private fun percent(share: Double): Int = (share * 100).toInt()

    // ---- ticking and lifecycle ------------------------------------------------------------------

    fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity as? ServerPlayer ?: return
        val session = sessions[player.uuid] ?: return
        if (player.serverLevel() != session.level) {
            abort(player, "message.postroad.chart.aborted_dimension")
            return
        }
        session.step(player.onPos.above())
        if (session.jumped) {
            abort(player, "message.postroad.chart.aborted_teleport")
            return
        }
        if (session.distance > PostroadConfig.chartMaxLength) {
            abort(player, "message.postroad.chart.aborted_length")
            return
        }
        if (++session.sinceReadout >= 20) {
            session.sinceReadout = 0
            val tier = RoadClassifier.evaluate(session.tiers).tier
            player.displayClientMessage(
                Component.translatable("message.postroad.chart.readout", session.distance.toInt(), percent(session.roadShare),
                    Component.translatable("tier.postroad.${tier?.key ?: "none"}")),
                true,
            )
            // keep the recent part of the walk visible
            val from = (session.points.size - 40).coerceAtLeast(0)
            for (i in from until session.points.size) showSample(session.level, session.points[i], session.tiers[i], faint = true)
        }
    }

    fun onDeath(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        abort(player, "message.postroad.chart.aborted_death")
    }

    fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        sessions.remove(event.entity.uuid)
    }

    fun onChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        (event.entity as? ServerPlayer)?.let { abort(it, "message.postroad.chart.aborted_dimension") }
    }

    private fun showSample(level: ServerLevel, pos: BlockPos, tier: Tier?, faint: Boolean = false) {
        val particle = if (tier != null) ParticleTypes.HAPPY_VILLAGER else ParticleTypes.SMOKE
        val count = if (faint) 1 else 3
        for (i in 0 until 4) {
            level.sendParticles(particle, pos.x + 0.5, pos.y + 0.2 + i * 0.5, pos.z + 0.5, count, 0.05, 0.05, 0.05, 0.0)
        }
    }
}
