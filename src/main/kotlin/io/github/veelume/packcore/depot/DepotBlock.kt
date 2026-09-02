package io.github.veelume.packcore.depot

import com.mojang.serialization.MapCodec
import io.github.veelume.packcore.registry.PackcoreBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult

/**
 * The town's courier depot. Use: open town storage. Sneak-use: pay coins in, sell fresh loot,
 * or (empty hand) show the balance sheet. All logic lives in [DepotInteraction]; this class is
 * only the block plumbing.
 */
class DepotBlock(properties: Properties) : HorizontalDirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun codec(): MapCodec<out HorizontalDirectionalBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = DepotBlockEntity(pos, state)

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (placer is Player) {
            (level.getBlockEntity(pos) as? DepotBlockEntity)?.let {
                it.playerPlaced = true
                it.setChanged()
            }
        }
    }

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide || type != PackcoreBlockEntities.DEPOT.get()) return null
        @Suppress("UNCHECKED_CAST")
        return BlockEntityTicker<DepotBlockEntity> { lvl, _, _, entity -> entity.serverTick(lvl as ServerLevel) } as BlockEntityTicker<T>
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? DepotBlockEntity ?: return InteractionResult.PASS
        return if (player.isShiftKeyDown) {
            DepotInteraction.showSummary(level as ServerLevel, entity, player)
        } else {
            DepotInteraction.openStorage(level as ServerLevel, entity, player)
        }
    }

    override fun useItemOn(
        stack: ItemStack, state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): ItemInteractionResult {
        if (!player.isShiftKeyDown) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        if (level.isClientSide) return ItemInteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? DepotBlockEntity
            ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        return DepotInteraction.useItem(level as ServerLevel, entity, player, stack)
    }

    companion object {
        val CODEC: MapCodec<DepotBlock> = simpleCodec(::DepotBlock)
    }
}
