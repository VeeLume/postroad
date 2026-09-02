package io.github.veelume.packcore.depot

import io.github.veelume.packcore.Packcore
import io.github.veelume.packcore.network.Network
import io.github.veelume.packcore.network.Place
import io.github.veelume.packcore.network.PlaceResolver
import io.github.veelume.packcore.registry.PackcoreBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/** Holds nothing but the id of the place this depot belongs to; all state is in [Network]. */
class DepotBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(PackcoreBlockEntities.DEPOT.get(), pos, state) {

    var placeId: String? = null
        private set

    private var retryIn = 0

    fun serverTick(level: ServerLevel) {
        if (placeId != null) return
        if (retryIn > 0) {
            retryIn--
            return
        }
        try {
            placeId = PlaceResolver.register(level, blockPos)
            setChanged()
        } catch (e: Exception) {
            Packcore.LOGGER.warn("Depot at {} could not register its place, retrying: {}", blockPos.toShortString(), e.toString())
            retryIn = 100
        }
    }

    fun place(level: ServerLevel): Place? {
        val id = placeId ?: return null
        return Network.get(level.server).places[id]
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        placeId?.let { tag.putString("PlaceId", it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        placeId = if (tag.contains("PlaceId")) tag.getString("PlaceId") else null
    }
}
