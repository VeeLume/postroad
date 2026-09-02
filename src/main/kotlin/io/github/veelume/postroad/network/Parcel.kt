package io.github.veelume.postroad.network

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * Items on their way from one town to another. [items] shrinks as delivery succeeds; a
 * parcel with an empty list is finished and dropped. `arrivalDay <= today` and non-empty
 * items means "held": arrived, but the destination storage had no room.
 */
data class Parcel(
    val id: UUID,
    val sender: String,
    val from: String,
    val to: String,
    val items: MutableList<ItemStack>,
    val sentDay: Long,
    val arrivalDay: Long,
    val lane: String,
) {
    fun isDue(today: Long): Boolean = arrivalDay <= today

    fun toTag(registries: HolderLookup.Provider): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("Id", id)
        tag.putString("Sender", sender)
        tag.putString("From", from)
        tag.putString("To", to)
        tag.putLong("SentDay", sentDay)
        tag.putLong("ArrivalDay", arrivalDay)
        tag.putString("Lane", lane)
        val ops = registries.createSerializationContext(NbtOps.INSTANCE)
        tag.put("Items", ItemStack.OPTIONAL_CODEC.listOf().encodeStart(ops, items).getOrThrow())
        return tag
    }

    companion object {
        const val LANE_BULK = "bulk"
        const val LANE_VALUABLES = "valuables"

        fun fromTag(tag: CompoundTag, registries: HolderLookup.Provider): Parcel? {
            if (!tag.hasUUID("Id")) return null
            val ops = registries.createSerializationContext(NbtOps.INSTANCE)
            val itemsTag: Tag = tag.get("Items") ?: return null
            val items = ItemStack.OPTIONAL_CODEC.listOf().parse(ops, itemsTag).result().orElse(emptyList())
                .filter { !it.isEmpty }
                .toMutableList()
            return Parcel(
                id = tag.getUUID("Id"),
                sender = tag.getString("Sender"),
                from = tag.getString("From"),
                to = tag.getString("To"),
                items = items,
                sentDay = tag.getLong("SentDay"),
                arrivalDay = tag.getLong("ArrivalDay"),
                lane = tag.getString("Lane"),
            )
        }
    }
}
