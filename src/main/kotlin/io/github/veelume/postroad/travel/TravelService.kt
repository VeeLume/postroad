package io.github.veelume.postroad.travel

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.advancement.PostroadAdvancements
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.mail.MailService
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
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

/** Opening the travel list at a node, and the journey itself: fare, fresh loot, teleport. */
object TravelService {

    /** Sends the travel screen for [fromNodeId] to the player. */
    fun open(player: ServerPlayer, fromNodeId: String) {
        val network = Network.get(player.server)
        val from = network.nodes[fromNodeId] ?: return
        val routes = Routing.routes(network, fromNodeId, townEnds(player.server))
        val near = PostroadConfig.joinDistance * PostroadConfig.joinDistance
        val entries = routes.entries
            .mapNotNull { (id, route) -> network.nodes[id]?.takeIf { it.pos.distSqr(from.pos) > near }?.let { node -> TravelEntry(id, node.name, route.length.toInt(), route.worstTier?.key ?: "dirt", Fares.fare(route.length, route.worstTier)) } }
            .sortedBy { it.length }
        val state = TravelState(
            fromNodeId = fromNodeId,
            fromName = from.name,
            entries = entries,
            wallet = network.balance(Network.playerAccount(player.gameProfile.name)),
            fund = network.balance(Network.ROAD_FUND),
            renamable = from.kind == RoadNode.KIND_SIGN,
        )
        // A client without the mod (or a game test's mock player) has no screen to open.
        if (player.connection.hasChannel(TravelStatePayload.TYPE)) PacketDistributor.sendToPlayer(player, TravelStatePayload(state))
    }

    /** The journey. Validates everything again server-side; the client only chose. */
    fun depart(player: ServerPlayer, fromNodeId: String, toNodeId: String, express: Boolean): Boolean {
        val level = player.serverLevel()
        val network = Network.get(player.server)
        val from = network.nodes[fromNodeId] ?: return false
        val to = network.nodes[toNodeId] ?: return false
        if (to.dimension != level.dimension().location() || from.dimension != to.dimension) return false
        if (player.distanceToSqr(from.pos.x + 0.5, from.pos.y + 0.5, from.pos.z + 0.5) > 64.0) {
            player.displayClientMessage(Component.translatable("message.postroad.travel.too_far").withStyle(ChatFormatting.YELLOW), false)
            return false
        }
        val ends = townEnds(player.server)
        val route = Routing.routes(network, fromNodeId, ends)[toNodeId] ?: run {
            player.displayClientMessage(Component.translatable("message.postroad.travel.unreachable").withStyle(ChatFormatting.YELLOW), false)
            return false
        }
        val name = player.gameProfile.name
        val today = FreshLoot.dayOf(level)
        val fare = Fares.fare(route.length, route.worstTier)
        if (!network.canAfford(name, fare)) {
            val short = fare - network.balance(Network.playerAccount(name)) - network.balance(Network.ROAD_FUND)
            player.displayClientMessage(Component.translatable("message.postroad.travel.unaffordable", fare, short).withStyle(ChatFormatting.RED), false)
            return false
        }

        // Fresh loot never teleports: it is mailed to the destination's town.
        val loot = (0 until player.inventory.containerSize).filter { FreshLoot.isFresh(player.inventory.getItem(it), today) }
        if (loot.isNotEmpty()) {
            val townId = destinationTown(network, to, ends)
            if (townId != null && network.postalUnlocked) {
                val items = loot.map { player.inventory.getItem(it).copy() }
                val fromPlace = from.placeId ?: destinationTown(network, from, ends) ?: townId
                val parcels = MailService.send(player.server, name, fromPlace, townId, items, express)
                loot.forEach { player.inventory.setItem(it, ItemStack.EMPTY) }
                val valuables = parcels.firstOrNull { it.lane == io.github.veelume.postroad.network.Parcel.LANE_VALUABLES }
                val days = valuables?.let { it.arrivalDay - today } ?: 0L
                player.displayClientMessage(
                    Component.translatable("message.postroad.travel.loot_mailed", items.sumOf { it.count }, network.places[townId]?.name ?: townId, days)
                        .withStyle(ChatFormatting.GOLD),
                    false,
                )
            } else {
                player.displayClientMessage(Component.translatable("message.postroad.travel.loot_kept").withStyle(ChatFormatting.YELLOW), false)
            }
        }

        if (fare > 0) {
            network.charge(name, fare, today, LedgerEntry.OP_FARE, "${from.name} → ${to.name}, ${route.length.toInt()} blocks")
        }
        Charting.abort(player, "message.postroad.chart.aborted_teleport")
        val preferred = network.paths[to.pathId]?.points?.getOrNull(to.pointIndex) ?: to.pos
        val spot = safeSpot(level, to.pos, preferred)
        player.teleportTo(level, spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, player.yRot, player.xRot)
        PostroadAdvancements.award(player, PostroadAdvancements.FIRST_JOURNEY)
        if (fare > 0) PostroadAdvancements.award(player, PostroadAdvancements.LONG_JOURNEY)
        player.displayClientMessage(
            Component.translatable("message.postroad.travel.arrived", to.name, route.length.toInt(), fare).withStyle(ChatFormatting.GOLD),
            false,
        )
        Postroad.LOGGER.info("{} travelled {} → {} ({} blocks, fare {})", name, from.name, to.name, route.length.toInt(), fare)
        return true
    }

    /** The town a node belongs to: its own place, else the nearest town node along the paths. */
    fun destinationTown(network: Network, node: RoadNode, townEnds: Map<String, List<Routing.Anchor>> = emptyMap()): String? {
        node.placeId?.let { return it }
        val routes = Routing.routes(network, node.id, townEnds)
        return routes.entries
            .mapNotNull { (id, route) -> network.nodes[id]?.placeId?.let { it to route.length } }
            .minByOrNull { it.second }?.first
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
