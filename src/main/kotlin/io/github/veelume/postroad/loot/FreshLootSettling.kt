package io.github.veelume.postroad.loot

import io.github.veelume.postroad.network.Network
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent

/**
 * Takes settled and unwanted stamps off items ([FreshLoot.settle]) wherever a player meets them:
 * their inventory, any container they open, item entities as they enter a level, and everything
 * [Network] holds. The same pass cleans stamps written before stackables were exempt, so old
 * worlds heal as they are played. Storage a player never opens (a hopper line, a storage
 * network's own records) keeps its stamps until the items come out.
 */
object FreshLootSettling {
    private const val PLAYER_INTERVAL = 40

    fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.tickCount % PLAYER_INTERVAL != 0) return
        val today = FreshLoot.dayOf(player.server.overworld())
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            if (FreshLoot.settle(inventory.getItem(slot), today)) inventory.setChanged()
        }
    }

    fun onContainerOpen(event: PlayerContainerEvent.Open) {
        val player = event.entity as? ServerPlayer ?: return
        val today = FreshLoot.dayOf(player.server.overworld())
        for (slot in event.container.slots) {
            if (FreshLoot.settle(slot.item, today)) slot.setChanged()
        }
    }

    fun onEntityJoin(event: EntityJoinLevelEvent) {
        val item = event.entity as? ItemEntity ?: return
        if (event.level.isClientSide) return
        val today = FreshLoot.dayOf(event.level.server?.overworld() ?: return)
        // Through setItem so the change reaches the entity's synced data.
        val stack = item.item.copy()
        if (FreshLoot.settle(stack, today)) item.item = stack
    }

    /** Settles everything the network holds; run at server start and on each new day, before delivery. */
    fun settleNetwork(server: MinecraftServer, today: Long) {
        val network = Network.get(server)
        var changed = 0
        for (stack in network.storedStacks()) {
            if (FreshLoot.settle(stack, today)) changed++
        }
        if (changed > 0) network.setDirty()
    }
}
