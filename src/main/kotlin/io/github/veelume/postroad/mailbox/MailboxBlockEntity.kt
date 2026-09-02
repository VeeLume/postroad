package io.github.veelume.postroad.mailbox

import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.network.Place
import io.github.veelume.postroad.network.PlaceResolver
import io.github.veelume.postroad.registry.PostroadBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/** Holds only the id of the mailbox place; contents and ownership live in the [Network]. */
class MailboxBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(PostroadBlockEntities.MAILBOX.get(), pos, state) {

    var placeId: String? = null
        private set

    fun claim(level: ServerLevel, owner: String) {
        if (placeId != null) return
        placeId = PlaceResolver.registerMailbox(level, blockPos, owner)
        setChanged()
    }

    fun place(level: ServerLevel): Place? = placeId?.let { Network.get(level.server).places[it] }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        placeId?.let { tag.putString("PlaceId", it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        placeId = if (tag.contains("PlaceId")) tag.getString("PlaceId") else null
    }
}
