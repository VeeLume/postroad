package io.github.veelume.postroad.loot

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.veelume.postroad.registry.PostroadComponents
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack

/**
 * The fresh-loot stamp up to 0.2.6 (origin and day). Nothing writes it any more; the component type stays
 * registered because items in existing worlds carry it, and an item with an unknown component fails to load.
 * [migrate] turns a container stamp on an unstackable into the [Looted] mark and drops every other stamp.
 */
data class FreshLoot(val origin: String, val day: Long) {
    companion object {
        const val ORIGIN_CONTAINER = "container"

        val CODEC: Codec<FreshLoot> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("origin").forGetter(FreshLoot::origin),
                Codec.LONG.fieldOf("day").forGetter(FreshLoot::day),
            ).apply(instance, ::FreshLoot)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, FreshLoot> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, FreshLoot::origin,
            ByteBufCodecs.VAR_LONG, FreshLoot::day,
            ::FreshLoot,
        )

        /** Replaces an old stamp on [stack]; true if the stack changed. */
        fun migrate(stack: ItemStack): Boolean {
            val old = stack.remove(PostroadComponents.FRESH_LOOT.get()) ?: return false
            if (old.origin == ORIGIN_CONTAINER && Looted.wanted(stack)) Looted.mark(stack)
            return true
        }
    }
}
