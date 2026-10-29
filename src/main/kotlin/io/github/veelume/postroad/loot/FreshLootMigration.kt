package io.github.veelume.postroad.loot

import io.github.veelume.postroad.network.Network
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent

/**
 * Replaces old fresh-loot stamps ([FreshLoot.migrate]) wherever a player meets them: their inventory, any
 * container they open, item entities as they enter a level, and everything [Network] holds. Storage a
 * player never opens (a hopper line, a storage network's own records) keeps its stamps until the items
 * come out.
 */
object FreshLootMigration {
    private const val PLAYER_INTERVAL = 40

    fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.tickCount % PLAYER_INTERVAL != 0) return
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            if (FreshLoot.migrate(inventory.getItem(slot))) inventory.setChanged()
        }
    }

    fun onContainerOpen(event: PlayerContainerEvent.Open) {
        if (event.entity !is ServerPlayer) return
        for (slot in event.container.slots) {
            if (FreshLoot.migrate(slot.item)) slot.setChanged()
        }
    }

    fun onEntityJoin(event: EntityJoinLevelEvent) {
        val item = event.entity as? ItemEntity ?: return
        if (event.level.isClientSide) return
        // Through setItem so the change reaches the entity's synced data.
        val stack = item.item.copy()
        if (FreshLoot.migrate(stack)) item.item = stack
    }

    /** Migrates everything the network holds; run at server start and on each new day. */
    fun migrateNetwork(server: MinecraftServer) {
        val network = Network.get(server)
        var changed = 0
        for (stack in network.storedStacks()) {
            if (FreshLoot.migrate(stack)) changed++
        }
        if (changed > 0) network.setDirty()
    }
}
