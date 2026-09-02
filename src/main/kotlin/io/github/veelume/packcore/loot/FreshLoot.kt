package io.github.veelume.packcore.loot

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.veelume.packcore.PackcoreConfig
import io.github.veelume.packcore.registry.PackcoreComponents
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * Marker for items that came out of a loot container or a mob. Stamped by [FreshLootModifier];
 * never removed. An item is *fresh* while fewer than `settleDays` in-game days have passed since
 * [day]. Days rather than ticks so loot from the same day stacks.
 */
data class FreshLoot(val origin: String, val day: Long) {
    companion object {
        const val ORIGIN_CONTAINER = "container"
        const val ORIGIN_ENTITY = "entity"

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

        /** In-game day index. Uses day time, so sleeping through a night advances it. */
        fun dayOf(level: Level): Long = level.dayTime / 24000L

        fun of(stack: ItemStack): FreshLoot? = stack.get(PackcoreComponents.FRESH_LOOT.get())

        /** Days until the stamp stops counting; `<= 0` means settled. */
        fun daysLeft(mark: FreshLoot, currentDay: Long): Long =
            PackcoreConfig.settleDays - (currentDay - mark.day)

        fun isFresh(stack: ItemStack, currentDay: Long): Boolean {
            val mark = of(stack) ?: return false
            return daysLeft(mark, currentDay) > 0
        }

        fun stamp(stack: ItemStack, origin: String, day: Long) {
            stack.set(PackcoreComponents.FRESH_LOOT.get(), FreshLoot(origin, day))
        }
    }
}
