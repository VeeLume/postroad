package io.github.veelume.postroad.compat

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.mailbox.MailboxBlock
import io.github.veelume.postroad.mailbox.MailboxBlockEntity
import io.github.veelume.postroad.network.Network
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import snownee.jade.api.BlockAccessor
import snownee.jade.api.IBlockComponentProvider
import snownee.jade.api.IServerDataProvider
import snownee.jade.api.ITooltip
import snownee.jade.api.IWailaClientRegistration
import snownee.jade.api.IWailaCommonRegistration
import snownee.jade.api.IWailaPlugin
import snownee.jade.api.WailaPlugin
import snownee.jade.api.config.IPluginConfig

/**
 * Jade integration: looking at a mailbox shows what is stored and what is on the way. Loaded
 * only by Jade through the annotation; nothing else references this class.
 */
@WailaPlugin
class PostroadJadePlugin : IWailaPlugin {
    override fun register(registration: IWailaCommonRegistration) {
        registration.registerBlockDataProvider(MailboxProvider, MailboxBlockEntity::class.java)
    }

    override fun registerClient(registration: IWailaClientRegistration) {
        registration.registerBlockComponent(MailboxProvider, MailboxBlock::class.java)
    }
}

object MailboxProvider : IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    private val UID: ResourceLocation = Postroad.id("mailbox")

    override fun getUid(): ResourceLocation = UID

    override fun appendServerData(data: CompoundTag, accessor: BlockAccessor) {
        val entity = accessor.blockEntity as? MailboxBlockEntity ?: return
        val level = accessor.level as? ServerLevel ?: return
        val placeId = entity.placeId ?: return
        val network = Network.get(level.server)
        val today = FreshLoot.dayOf(level)
        val storage = network.mailboxStorage(placeId)
        data.putInt("Stored", (0 until storage.containerSize).sumOf { storage.getItem(it).count })
        val lines = ListTag()
        for (parcel in network.parcelsTo(placeId)) {
            val from = network.places[parcel.from]?.name ?: parcel.from
            val key = if (parcel.isDue(today)) "jade.postroad.mailbox.held" else "jade.postroad.mailbox.transit"
            lines.add(StringTag.valueOf(Component.translatable(key, parcel.items.sumOf { it.count }, from, parcel.arrivalDay - today).string))
        }
        data.put("Parcels", lines)
    }

    override fun appendTooltip(tooltip: ITooltip, accessor: BlockAccessor, config: IPluginConfig) {
        val data = accessor.serverData
        if (!data.contains("Stored")) return
        tooltip.add(Component.translatable("jade.postroad.mailbox.stored", data.getInt("Stored")))
        val lines = data.getList("Parcels", Tag.TAG_STRING.toInt())
        if (lines.isEmpty()) {
            tooltip.add(Component.translatable("jade.postroad.mailbox.nothing").withStyle(ChatFormatting.GRAY))
        } else {
            for (i in 0 until lines.size) tooltip.add(Component.literal(lines.getString(i)).withStyle(ChatFormatting.GOLD))
        }
    }
}
