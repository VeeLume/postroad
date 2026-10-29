package io.github.veelume.postroad.travel

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.advancement.PostroadAdvancements
import io.github.veelume.postroad.network.GameDay
import io.github.veelume.postroad.network.LedgerEntry
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.roads.Charting
import io.github.veelume.postroad.roads.RoadNode
import io.github.veelume.postroad.roads.RoadPath
import io.github.veelume.postroad.roads.Routing
import io.github.veelume.postroad.roads.gen.RoadPlanStorage
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.levelgen.structure.BoundingBox

/** Opening the travel list at a node, and the journey itself: fare and teleport. */
object TravelService {

    /** Sends the travel screen for [fromNodeId] to the player. */
    fun open(player: ServerPlayer, fromNodeId: String) {
        val network = Network.get(player.server)
        val from = network.nodes[fromNodeId] ?: return
        val routes = Routing.routes(network, fromNodeId, townAnchors(player.server))
        val readyIn = if (PostroadConfig.cooldownBlocksPerDay <= 0) -1L
        else (network.freeJourneyAt(player.gameProfile.name) - now(player.server)).coerceAtLeast(0L)
        val entries = destinations(network, from, routes, ready = readyIn == 0L)
        val state = TravelState(
            fromNodeId = fromNodeId,
            fromName = from.name,
            entries = entries,
            wallet = network.balance(Network.playerAccount(player.gameProfile.name)),
            fund = network.balance(Network.ROAD_FUND),
            renamable = from.kind == RoadNode.KIND_SIGN,
            freeJourneyIn = readyIn,
            fromX = from.pos.x,
            fromZ = from.pos.z,
        )
        // A client without the mod (or a game test's mock player) has no screen to open.
        if (player.connection.hasChannel(TravelStatePayload.TYPE)) PacketDistributor.sendToPlayer(player, TravelStatePayload(state))
    }

    /**
     * What the travel list offers, nearest first: towns, and signs players named, except one named like
     * a town on the list (a sign put up at a town, labelled with its name). Nothing within joinDistance of
     * where you stand. The builder's junction and road-end signs are places to leave from, not to go to.
     * Each is priced as of now: free while the player's free journey is [ready], else its fare.
     */
    fun destinations(network: Network, from: RoadNode, routes: Map<String, Routing.Route>, ready: Boolean = false): List<TravelEntry> {
        val near = PostroadConfig.joinDistance * PostroadConfig.joinDistance
        val reachable = routes.mapNotNull { (id, route) ->
            network.nodes[id]?.takeIf { it.pos.distSqr(from.pos) > near && (it.kind == RoadNode.KIND_TOWN || it.named) }?.let { it to route }
        }
        val towns = reachable.filter { it.first.kind == RoadNode.KIND_TOWN }.map { it.first.name.lowercase() }.toSet()
        return reachable
            .filter { (node, _) -> node.kind == RoadNode.KIND_TOWN || node.name.lowercase() !in towns }
            .map { (node, route) ->
                val free = Fares.usesFreeJourney(route.length, ready)
                TravelEntry(node.id, node.name, route.length.toInt(), route.worstTier?.key ?: "dirt",
                    Fares.price(route.length, route.worstTier, ready), if (free) Fares.cooldown(route.length) else 0L, node.pos.x, node.pos.z)
            }
            .sortedBy { it.length }
    }

    /** The journey. Validates everything again server-side; the client only chose. */
    fun depart(player: ServerPlayer, fromNodeId: String, toNodeId: String): Boolean {
        val level = player.serverLevel()
        val network = Network.get(player.server)
        val from = network.nodes[fromNodeId] ?: return false
        val to = network.nodes[toNodeId] ?: return false
        if (to.dimension != level.dimension().location() || from.dimension != to.dimension) return false
        if (player.distanceToSqr(from.pos.x + 0.5, from.pos.y + 0.5, from.pos.z + 0.5) > 64.0) {
            player.displayClientMessage(Component.translatable("message.postroad.travel.too_far").withStyle(ChatFormatting.YELLOW), false)
            return false
        }
        val ends = townAnchors(player.server)
        val route = Routing.routes(network, fromNodeId, ends)[toNodeId] ?: run {
            player.displayClientMessage(Component.translatable("message.postroad.travel.unreachable").withStyle(ChatFormatting.YELLOW), false)
            return false
        }
        val name = player.gameProfile.name
        val today = GameDay.of(level)
        val now = now(player.server)
        val free = Fares.usesFreeJourney(route.length, network.freeJourneyAt(name) <= now)
        val fare = if (free) 0L else Fares.fare(route.length, route.worstTier)
        if (!network.canAfford(name, fare)) {
            val short = fare - network.balance(Network.playerAccount(name)) - network.balance(Network.ROAD_FUND)
            player.displayClientMessage(Component.translatable("message.postroad.travel.unaffordable", fare, short).withStyle(ChatFormatting.RED), false)
            return false
        }

        if (fare > 0) {
            network.charge(name, fare, today, LedgerEntry.OP_FARE, "${from.name} → ${to.name}, ${route.length.toInt()} blocks")
        }
        val cooldown = if (free) Fares.cooldown(route.length) else 0L
        if (free) network.setFreeJourneyAt(name, now + cooldown)
        Charting.abort(player, "message.postroad.chart.aborted_teleport")
        val preferred = network.paths[to.pathId]?.points?.getOrNull(to.pointIndex) ?: to.pos
        val spot = safeSpot(level, to.pos, preferred)
        player.teleportTo(level, spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, player.yRot, player.xRot)
        PostroadAdvancements.award(player, PostroadAdvancements.FIRST_JOURNEY)
        if (route.length > PostroadConfig.freeDistance) PostroadAdvancements.award(player, PostroadAdvancements.LONG_JOURNEY)
        player.displayClientMessage(
            Component.translatable("message.postroad.travel.arrived", to.name, route.length.toInt(), fare).withStyle(ChatFormatting.GOLD),
            false,
        )
        if (free) {
            player.displayClientMessage(
                Component.translatable("message.postroad.travel.free_used", minutes(cooldown)).withStyle(ChatFormatting.GRAY),
                false,
            )
        }
        Postroad.LOGGER.info("{} travelled {} → {} ({} blocks, fare {}{})", name, from.name, to.name, route.length.toInt(), fare, if (free) ", free journey" else "")
        return true
    }

    /**
     * Every generated road's ends by the town they lead into (a road runs from its `from` town at point 0
     * to its `to` town at the last point). A road usually ends at a street exit, not at the depot, and
     * only one of a town's roads carries its node; these ends are what joins the others to it.
     */
    fun townEnds(server: MinecraftServer): Map<String, List<Routing.Anchor>> {
        val network = Network.get(server)
        val ends = HashMap<String, MutableList<Routing.Anchor>>()
        for (road in RoadPlanStorage.get(server).roads.values) {
            val path = network.paths[road.id] ?: continue
            ends.getOrPut(road.from) { ArrayList() }.add(Routing.Anchor(road.id, 0))
            ends.getOrPut(road.to) { ArrayList() }.add(Routing.Anchor(road.id, path.points.size - 1))
        }
        return ends
    }

    /** Where a town's roads count as charted: its village box and [TOWN_MARGIN] around it, else [TOWN_RADIUS] around the depot. */
    class TownArea(val placeId: String, val dimension: ResourceLocation, val box: BoundingBox?, val depot: BlockPos) {
        fun contains(p: BlockPos): Boolean {
            if (box != null && p.x in box.minX() - TOWN_MARGIN..box.maxX() + TOWN_MARGIN && p.z in box.minZ() - TOWN_MARGIN..box.maxZ() + TOWN_MARGIN) return true
            val dx = (p.x - depot.x).toDouble(); val dz = (p.z - depot.z).toDouble()
            return dx * dx + dz * dz <= TOWN_RADIUS.toDouble() * TOWN_RADIUS
        }
    }

    /** The clock free journeys run on: the overworld's day time, so sleeping shortens a cooldown as it does mail days. */
    fun now(server: MinecraftServer): Long = server.overworld().dayTime

    /** Real minutes a span of [ticks] takes at 20 ticks a second, rounded up. */
    fun minutes(ticks: Long): Long = (ticks + 1199) / 1200

    /** Blocks around a predicted village's box: street exits, road ends and the pit stop beside them. */
    const val TOWN_MARGIN = 16

    /** Blocks around the depot of a town the planner did not predict. */
    const val TOWN_RADIUS = 48

    /** [townEnds] plus everything inside each town with a depot (see the other overload). */
    fun townAnchors(server: MinecraftServer): Map<String, List<Routing.Anchor>> {
        val network = Network.get(server)
        val planned = RoadPlanStorage.get(server).towns
        val areas = network.towns().mapNotNull { place ->
            TownArea(place.id, place.dimension, planned[place.id]?.box, network.depotPos(place.id) ?: return@mapNotNull null)
        }
        return townAnchors(network, townEnds(server), areas)
    }

    /**
     * Inside a town every road counts as charted: the town's hub joins, besides its generated road ends,
     * every node and link end in its [TownArea], and the point of each charted path nearest the depot
     * within it. A walk or sign that lands on an uncharted road at a street exit still reaches the town.
     */
    fun townAnchors(network: Network, ends: Map<String, List<Routing.Anchor>>, areas: List<TownArea>): Map<String, List<Routing.Anchor>> {
        val out = HashMap<String, MutableList<Routing.Anchor>>()
        for ((place, list) in ends) out[place] = list.toMutableList()
        val candidates = ArrayList<Pair<Routing.Anchor, RoadPath>>()
        for (node in network.nodes.values) network.paths[node.pathId]?.let { candidates.add(Routing.Anchor(node.pathId, node.pointIndex) to it) }
        for (link in network.links) {
            network.paths[link.pathA]?.let { candidates.add(Routing.Anchor(link.pathA, link.indexA) to it) }
            network.paths[link.pathB]?.let { candidates.add(Routing.Anchor(link.pathB, link.indexB) to it) }
        }
        for (area in areas) {
            val list = out.getOrPut(area.placeId) { ArrayList() }
            for ((anchor, path) in candidates) {
                if (path.dimension != area.dimension) continue
                val point = path.points.getOrNull(anchor.index) ?: continue
                if (area.contains(point)) list.add(anchor)
            }
            for (path in network.paths.values) {
                if (!path.charted || path.dimension != area.dimension) continue
                var best = -1
                var bestDistance = Double.MAX_VALUE
                for ((i, p) in path.points.withIndex()) {
                    if (!area.contains(p)) continue
                    val d = RoadPath.distance(p, area.depot)
                    if (d < bestDistance) { bestDistance = d; best = i }
                }
                if (best >= 0) list.add(Routing.Anchor(path.id, best))
            }
        }
        return out
    }

    /**
     * The travel node of [placeId]'s depot, made on demand when the town has none yet: a depot that
     * stands away from every road (a village's own courier post) is placed on the nearest end of a
     * road into its town. Null when no road leads there.
     */
    fun townNode(server: MinecraftServer, placeId: String, depotPos: BlockPos): RoadNode? {
        val network = Network.get(server)
        val id = "town/$placeId"
        network.nodes[id]?.let { return it }
        val place = network.places[placeId] ?: return null
        val end = townEnds(server)[placeId]
            ?.mapNotNull { end -> network.paths[end.pathId]?.let { end to RoadPath.distance(depotPos, it.points[end.index]) } }
            ?.minByOrNull { it.second }?.first ?: return null
        val node = RoadNode(id, RoadNode.KIND_TOWN, place.dimension, depotPos, end.pathId, end.index, place.name, place.id)
        network.nodes[id] = node
        network.setDirty()
        return node
    }

    /**
     * A standable spot *next to* [pos] (never in its column — that is the sign or the depot):
     * feet and head without collision, something with collision below. Nearest first.
     */
    fun safeSpot(level: ServerLevel, pos: BlockPos, preferTowards: BlockPos? = null): BlockPos {
        var offsets = listOf(
            BlockPos(1, 0, 0), BlockPos(-1, 0, 0), BlockPos(0, 0, 1), BlockPos(0, 0, -1),
            BlockPos(1, 0, 1), BlockPos(-1, 0, -1), BlockPos(1, 0, -1), BlockPos(-1, 0, 1),
            BlockPos(2, 0, 0), BlockPos(-2, 0, 0), BlockPos(0, 0, 2), BlockPos(0, 0, -2),
        )
        // In front of the sign means on its road: try the spots nearest the linked path point first.
        if (preferTowards != null) offsets = offsets.sortedBy { pos.offset(it).distSqr(preferTowards) }
        fun passable(p: BlockPos) = level.getBlockState(p).getCollisionShape(level, p).isEmpty
        fun solid(p: BlockPos) = !level.getBlockState(p).getCollisionShape(level, p).isEmpty
        for (dy in listOf(0, -1, 1, -2, 2)) {
            for (o in offsets) {
                val p = pos.offset(o.x, dy, o.z)
                if (passable(p) && passable(p.above()) && solid(p.below())) return p
            }
        }
        return pos.above(2)
    }
}
