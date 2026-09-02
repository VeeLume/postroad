package io.github.veelume.packcore.loot

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.neoforged.neoforge.common.loot.IGlobalLootModifier
import net.neoforged.neoforge.common.loot.LootModifier

/**
 * Global loot modifier that stamps [FreshLoot] on everything a matching loot table produces.
 * Which tables count is data: each rule maps a loot-table path prefix (any namespace) to an
 * origin. Tables that match no rule — block drops, shearing — are left alone.
 *
 * Data: `data/packcore/loot_modifiers/fresh_loot.json`.
 */
class FreshLootModifier(conditions: Array<LootItemCondition>, val rules: List<OriginRule>) : LootModifier(conditions) {

    data class OriginRule(val prefix: String, val origin: String) {
        companion object {
            val CODEC: Codec<OriginRule> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.STRING.fieldOf("prefix").forGetter(OriginRule::prefix),
                    Codec.STRING.fieldOf("origin").forGetter(OriginRule::origin),
                ).apply(instance, ::OriginRule)
            }
        }
    }

    override fun doApply(generatedLoot: ObjectArrayList<ItemStack>, context: LootContext): ObjectArrayList<ItemStack> {
        val tableId = context.queriedLootTableId ?: return generatedLoot
        val path = tableId.path
        val origin = rules.firstOrNull { path.startsWith(it.prefix) }?.origin ?: return generatedLoot
        val day = FreshLoot.dayOf(context.level)
        for (stack in generatedLoot) {
            if (!stack.isEmpty && FreshLoot.of(stack) == null) {
                FreshLoot.stamp(stack, origin, day)
            }
        }
        return generatedLoot
    }

    override fun codec(): MapCodec<out IGlobalLootModifier> = CODEC

    companion object {
        val CODEC: MapCodec<FreshLootModifier> = RecordCodecBuilder.mapCodec { instance ->
            codecStart(instance)
                .and(Codec.list(OriginRule.CODEC).fieldOf("rules").forGetter(FreshLootModifier::rules))
                .apply(instance, ::FreshLootModifier)
        }
    }
}
