package io.github.veelume.postroad.travel

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.advancement.PostroadAdvancements
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.mail.MailService
import io.github.veelume.postroad.network.LedgerEntry
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.roads.Charting
import io.github.veelume.postroad.roads.RoadNode
import io.github.veelume.postroad.roads.Routing
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
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
        val routes = Routing.routes(network, fromNodeId)
        val entries = routes.entries
            .mapNotNull { (id, route) -> network.nodes[id]?.let { node -> TravelEntry(id, node.name, route.length.toInt(), route.worstTier?.key ?: "dirt", Fares.fare(route.length, route.worstTier)) } }
            .sortedBy { it.length }
        val state = TravelState(
            fromNodeId = fromNodeId,
            fromName = from.name,
            entries = entries,
            wallet = network.balance(Network.playerAccount(player.gameProfile.name)),
            fund = network.balance(Network.ROAD_FUND),
            renamable = from.kind == RoadNode.KIND_SIGN,
        )
        PacketDistributor.sendToPlayer(player, TravelStatePayload(state))
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
        val route = Routing.routes(network, fromNodeId)[toNodeId] ?: run {
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
            val townId = destinationTown(network, to)
            if (townId != null && network.postalUnlocked) {
                val items = loot.map { player.inventory.getItem(it).copy() }
                val fromPlace = from.placeId ?: destinationTown(network, from) ?: townId
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
    fun destinationTown(network: Network, node: RoadNode): String? {
        node.placeId?.let { return it }
        val routes = Routing.routes(network, node.id)
        return routes.entries
            .mapNotNull { (id, route) -> network.nodes[id]?.placeId?.let { it to route.length } }
            .minByOrNull { it.second }?.first
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
