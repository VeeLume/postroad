package io.github.veelume.postroad.roads

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.advancement.PostroadAdvancements
import io.github.veelume.postroad.loot.FreshLoot
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
 * it to existing paths it started or ended next to, and hooks up any depot it passed.
 */
object Charting {

    class Session(val playerId: UUID, val level: ServerLevel) {
        val points = ArrayList<BlockPos>()
        val tiers = ArrayList<Tier?>()
        var distance = 0.0
        var nextSpacing = spacing()
        var lastPos: BlockPos? = null
        var sinceReadout = 0

        val roadShare: Double get() = if (tiers.isEmpty()) 0.0 else tiers.count { it != null }.toDouble() / tiers.size

        private fun spacing(): Int {
            val r = RoadRules.current
            return r.sampleSpacingMin + (Math.random() * (r.sampleSpacingMax - r.sampleSpacingMin + 1)).toInt()
        }

        /** Adds a sample at [pos] if the walk has moved far enough; returns the tier when sampled. */
        fun step(pos: BlockPos, force: Boolean = false): Tier? {
            val last = lastPos
            if (last != null && !force) {
                val d = RoadPath.distance(last, pos)
                if (d < nextSpacing) return null
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
        val path = RoadPath(
            id = UUID.randomUUID().toString().take(8),
            dimension = level.dimension().location(),
            points = session.points,
            tiers = session.tiers,
            recordedBy = player.gameProfile.name.lowercase(),
            recordedDay = FreshLoot.dayOf(level),
        )
        val join = PostroadConfig.joinDistance
        val startLink = network.nearestPathPoint(path.dimension, path.points.first(), join, exclude = path.id)
        val endLink = network.nearestPathPoint(path.dimension, path.points.last(), join, exclude = path.id)
        network.addPath(path)
        startLink?.let { (other, idx) -> network.addLink(PathLink(path.id, 0, other.id, idx)) }
        endLink?.let { (other, idx) -> network.addLink(PathLink(path.id, path.points.size - 1, other.id, idx)) }
        val towns = network.attachTowns(path, join)

        network.record(LedgerEntry(path.recordedDay, player.gameProfile.name, LedgerEntry.OP_PATH, 0, "network",
            "${path.length.toInt()} blocks, ${path.tier?.key ?: "?"}, ${towns.size} town(s)"))
        PostroadAdvancements.award(player, PostroadAdvancements.PATH_CHARTED)
        val linked = listOfNotNull(startLink, endLink).size
        player.displayClientMessage(
            Component.translatable("message.postroad.chart.recorded", path.length.toInt(), percent(verdict.roadShare),
                Component.translatable("tier.postroad.${path.tier?.key ?: "dirt"}"), linked, towns.size).withStyle(ChatFormatting.GOLD),
            false,
        )
        Postroad.LOGGER.info("{} charted path {} ({} blocks, {}, {} links, towns {})", player.gameProfile.name, path.id, path.length.toInt(), path.tier, linked, towns)
        return path
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
