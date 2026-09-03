package io.github.veelume.postroad.depot

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.network.Place
import io.github.veelume.postroad.network.PlaceResolver
import io.github.veelume.postroad.registry.PostroadBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/** Holds nothing but the id of the place this depot belongs to; all state is in [Network]. */
class DepotBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(PostroadBlockEntities.DEPOT.get(), pos, state) {

    var placeId: String? = null
        private set

    /** Set by [DepotBlock.setPlacedBy]; player-placed depots are never demoted. */
    var playerPlaced: Boolean = false

    private var retryIn = 0

    fun serverTick(level: ServerLevel) {
        if (placeId != null) return
        if (retryIn > 0) {
            retryIn--
            return
        }
        try {
            when (val result = PlaceResolver.register(level, blockPos, playerPlaced)) {
                is PlaceResolver.Result.Bound -> {
                    placeId = result.placeId
                    setChanged()
                    labelSigns(level)
                }
                is PlaceResolver.Result.Redundant -> demote(level, result.placeId)
            }
        } catch (e: Exception) {
            Postroad.LOGGER.warn("Depot at {} could not register its place, retrying: {}", blockPos.toShortString(), e.toString())
            retryIn = 100
        }
    }

    /** Writes the town name onto every hanging sign of the courier post around this depot. */
    private fun labelSigns(level: ServerLevel) {
        val name = place(level)?.name ?: return
        val from = blockPos.offset(-5, -1, -5)
        val to = blockPos.offset(5, 4, 5)
        for (pos in BlockPos.betweenClosed(from, to)) {
            val entity = level.getBlockEntity(pos) as? net.minecraft.world.level.block.entity.SignBlockEntity ?: continue
            if (level.getBlockState(pos).block !is net.minecraft.world.level.block.CeilingHangingSignBlock &&
                level.getBlockState(pos).block !is net.minecraft.world.level.block.WallHangingSignBlock) continue
            io.github.veelume.postroad.travel.SignWriter.labelTownSign(level, entity, name)
        }
    }

    /** A second worldgen courier post in the same town becomes an ordinary storage barrel. */
    private fun demote(level: ServerLevel, placeId: String) {
        val facing = blockState.getValue(HorizontalDirectionalBlock.FACING)
        val barrel = Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, facing)
        level.setBlock(blockPos, barrel, Block.UPDATE_ALL)
        Postroad.LOGGER.info("Redundant courier post at {} in {} demoted to a barrel", blockPos.toShortString(), placeId)
    }

    fun place(level: ServerLevel): Place? {
        val id = placeId ?: return null
        return Network.get(level.server).places[id]
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        placeId?.let { tag.putString("PlaceId", it) }
        if (playerPlaced) tag.putBoolean("PlayerPlaced", true)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        placeId = if (tag.contains("PlaceId")) tag.getString("PlaceId") else null
        playerPlaced = tag.getBoolean("PlayerPlaced")
    }
}
