package io.github.veelume.postroad.mail

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.network.Parcel
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.ceil

/**
 * Parcels: splitting an outbox into lanes, computing arrival days, and delivering what is due
 * into the destination town's storage. Delivery runs when the in-game day changes and once at
 * server start; bulk parcels are delivered immediately on send.
 */
object MailService {

    /** Days a valuables parcel takes between two places. */
    fun valuablesDays(distance: Double): Long =
        PostroadConfig.valuablesBaseDays + ceil(distance / PostroadConfig.valuablesBlocksPerDay).toLong()

    /** The town a parcel from [sender] goes to unless they pick another. */
    fun defaultDestination(network: Network, sender: String, currentPlaceId: String): String =
        network.homeOf(sender)?.id ?: currentPlaceId

    /**
     * Sends the non-empty stacks of [outbox] from [fromPlace] to [toPlace]. Returns the parcels
     * created (one per lane actually used). The stacks are copied; the caller clears the outbox.
     */
    fun send(server: MinecraftServer, sender: String, fromPlace: String, toPlace: String, outbox: List<ItemStack>): List<Parcel> {
        val network = Network.get(server)
        require(network.postalUnlocked) { "postal network not unlocked" }
        require(network.hasDepot(toPlace)) { "destination has no depot" }
        val today = FreshLoot.dayOf(server.overworld())
        val bulk = outbox.filter { !it.isEmpty && it.isStackable }.map { it.copy() }
        val valuables = outbox.filter { !it.isEmpty && !it.isStackable }.map { it.copy() }
        val created = ArrayList<Parcel>(2)
        if (bulk.isNotEmpty()) {
            created += Parcel(UUID.randomUUID(), sender.lowercase(), fromPlace, toPlace, bulk.toMutableList(), today, today, Parcel.LANE_BULK)
        }
        if (valuables.isNotEmpty()) {
            val days = valuablesDays(network.distanceBetween(fromPlace, toPlace))
            created += Parcel(UUID.randomUUID(), sender.lowercase(), fromPlace, toPlace, valuables.toMutableList(), today, today + days, Parcel.LANE_VALUABLES)
        }
        created.forEach { network.addParcel(it) }
        Postroad.LOGGER.info(
            "{} sent {} parcel(s) from {} to {} ({} bulk, {} valuables)",
            sender, created.size, network.places[fromPlace]?.name ?: fromPlace,
            network.places[toPlace]?.name ?: toPlace, bulk.size, valuables.size,
        )
        deliverDue(server, today)
        return created
    }

    /** Delivers every parcel whose arrival day has come; parcels that do not fit stay held. */
    fun deliverDue(server: MinecraftServer, today: Long) {
        val network = Network.get(server)
        if (network.parcels.isEmpty()) return
        val iterator = network.parcels.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val parcel = iterator.next()
            if (!parcel.isDue(today)) continue
            val storage = network.storageFor(parcel.to)
            val before = parcel.items.size
            deliverInto(storage, parcel.items)
            if (parcel.items.isEmpty()) {
                iterator.remove()
                changed = true
                notify(server, network, parcel)
            } else if (parcel.items.size != before) {
                changed = true
            }
        }
        if (changed) network.setDirty()
    }

    /** Moves as much of [items] as fits into [container]; leftovers stay in the list. */
    private fun deliverInto(container: SimpleContainer, items: MutableList<ItemStack>) {
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val stack = iterator.next()
            val remainder = container.addItem(stack)
            if (remainder.isEmpty) iterator.remove() else stack.count = remainder.count
        }
    }

    private fun notify(server: MinecraftServer, network: Network, parcel: Parcel) {
        if (parcel.lane == Parcel.LANE_BULK) return
        val player = server.playerList.players.firstOrNull { it.gameProfile.name.equals(parcel.sender, ignoreCase = true) }
            ?: return
        val town = network.places[parcel.to]?.name ?: parcel.to
        player.sendSystemMessage(
            Component.translatable("message.postroad.mail.delivered", parcel.items.size.coerceAtLeast(1), town).withStyle(ChatFormatting.GOLD),
        )
    }

    // ---- scheduling -----------------------------------------------------------------------------

    fun onServerStarted(event: ServerStartedEvent) {
        val server = event.server
        val today = FreshLoot.dayOf(server.overworld())
        deliverDue(server, today)
        Network.get(server).lastDeliveryDay = today
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        if (server.tickCount % 20 != 0) return
        val today = FreshLoot.dayOf(server.overworld())
        val network = Network.get(server)
        if (network.lastDeliveryDay == today) return
        deliverDue(server, today)
        network.lastDeliveryDay = today
    }
}
