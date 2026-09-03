package io.github.veelume.postroad.mailbox

import com.mojang.serialization.MapCodec
import io.github.veelume.postroad.advancement.PostroadAdvancements
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.network.PlaceResolver
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult

/**
 * A player's mailbox: an earned block that makes their base a parcel destination. Receive-only —
 * sending still happens at a depot. Contents live in the [Network], so parcels arrive even
 * while the chunk is unloaded; the block is just the door to them.
 */
class MailboxBlock(properties: Properties) : HorizontalDirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun codec(): MapCodec<out HorizontalDirectionalBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = MailboxBlockEntity(pos, state)

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level !is ServerLevel || placer !is Player) return
        val entity = level.getBlockEntity(pos) as? MailboxBlockEntity ?: return
        entity.claim(level, placer.gameProfile.name)
        (placer as? ServerPlayer)?.let { PostroadAdvancements.award(it, PostroadAdvancements.MAILBOX_PLACED) }
        placer.displayClientMessage(
            Component.translatable("message.postroad.mailbox.placed", entity.place(level)?.name ?: ""),
            true,
        )
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block) && level is ServerLevel) {
            val entity = level.getBlockEntity(pos) as? MailboxBlockEntity
            val placeId = entity?.placeId
            if (placeId != null) {
                val network = Network.get(level.server)
                val contents = network.removeMailbox(placeId)
                Containers.dropContents(level, pos, contents)
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? MailboxBlockEntity ?: return InteractionResult.PASS
        val serverLevel = level as ServerLevel
        val place = entity.place(serverLevel)
        if (place == null) {
            // Placed by worldgen or a non-player; claim for whoever opens it first.
            entity.claim(serverLevel, player.gameProfile.name)
        }
        val placeId = entity.placeId ?: return InteractionResult.CONSUME
        val network = Network.get(serverLevel.server)
        val container = network.mailboxStorage(placeId)
        val title = Component.literal(network.places[placeId]?.name ?: "Mailbox")
        player.openMenu(SimpleMenuProvider({ id, inventory, _ -> ChestMenu.sixRows(id, inventory, container) }, title))
        return InteractionResult.CONSUME
    }

    companion object {
        val CODEC: MapCodec<MailboxBlock> = simpleCodec(::MailboxBlock)
    }
}
