package io.github.veelume.postroad.network

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation

/**
 * A named place. [id] is stable across restarts: for structure-bound places it is
 * `<dimension>/<structure origin chunk x>/<z>`, for player-founded ones the depot position.
 */
data class Place(
    val id: String,
    val name: String,
    val culture: String,
    val type: String,
    val dimension: ResourceLocation,
    val pos: BlockPos,
    val discoveredDay: Long,
    /** Owning player (lower-case) for mailbox places; null otherwise. */
    val owner: String? = null,
) {
    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("Id", id)
        tag.putString("Name", name)
        tag.putString("Culture", culture)
        tag.putString("Type", type)
        tag.putString("Dimension", dimension.toString())
        tag.putLong("Pos", pos.asLong())
        tag.putLong("DiscoveredDay", discoveredDay)
        owner?.let { tag.putString("Owner", it) }
        return tag
    }

    companion object {
        const val TYPE_VILLAGE = "village"
        const val TYPE_TAVERN = "tavern"
        const val TYPE_FOUNDED = "founded"
        const val TYPE_MAILBOX = "mailbox"

        fun fromTag(tag: CompoundTag): Place? {
            val dimension = ResourceLocation.tryParse(tag.getString("Dimension")) ?: return null
            return Place(
                id = tag.getString("Id"),
                name = tag.getString("Name"),
                culture = tag.getString("Culture"),
                type = tag.getString("Type"),
                dimension = dimension,
                pos = BlockPos.of(tag.getLong("Pos")),
                discoveredDay = tag.getLong("DiscoveredDay"),
                owner = if (tag.contains("Owner")) tag.getString("Owner") else null,
            )
        }
    }
}

data class LedgerEntry(
    val day: Long,
    val actor: String,
    val op: String,
    val amount: Long,
    val account: String,
    val note: String,
) {
    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putLong("Day", day)
        tag.putString("Actor", actor)
        tag.putString("Op", op)
        tag.putLong("Amount", amount)
        tag.putString("Account", account)
        tag.putString("Note", note)
        return tag
    }

    companion object {
        const val OP_PAY_IN = "pay_in"
        const val OP_BUYBACK = "buyback"
        const val OP_GRANT = "grant"
        const val OP_CHARTER = "charter"
        const val OP_EXPRESS = "express"
        const val OP_PATH = "path"
        const val OP_FARE = "fare"

        fun fromTag(tag: CompoundTag): LedgerEntry = LedgerEntry(
            day = tag.getLong("Day"),
            actor = tag.getString("Actor"),
            op = tag.getString("Op"),
            amount = tag.getLong("Amount"),
            account = tag.getString("Account"),
            note = tag.getString("Note"),
        )
    }
}
