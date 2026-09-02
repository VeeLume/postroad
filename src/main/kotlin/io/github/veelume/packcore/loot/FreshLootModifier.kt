package io.github.veelume.packcore.loot

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.veelume.packcore.Packcore
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.neoforged.neoforge.common.loot.IGlobalLootModifier
import net.neoforged.neoforge.common.loot.LootModifier

/**
 * Global loot modifier that stamps [FreshLoot] on everything a loot table produces.
 *
 * Classification comes from the loot context, not the table id, so modded structure tables
 * with arbitrary paths (`kaisyn:village/…`, `betterdungeons:zombie_dungeon/…`) count too:
 * a block state means a block drop (skipped), a damage source means a mob drop (`entity`),
 * anything else is container loot (`container`). Data rules by table-path prefix override
 * that default; origin `none` excludes a table family.
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
        val tableId = context.queriedLootTableId
        val origin = classify(context, tableId?.path) ?: return generatedLoot
        val day = FreshLoot.dayOf(context.level)
        var stamped = 0
        for (stack in generatedLoot) {
            if (!stack.isEmpty && FreshLoot.of(stack) == null) {
                FreshLoot.stamp(stack, origin, day)
                stamped++
            }
        }
        if (stamped > 0) {
            Packcore.LOGGER.debug("Stamped {} stack(s) from {} as {} loot", stamped, tableId, origin)
        }
        return generatedLoot
    }

    private fun classify(context: LootContext, path: String?): String? {
        if (path != null) {
            rules.firstOrNull { path.startsWith(it.prefix) }?.let { rule ->
                return rule.origin.takeUnless { it == ORIGIN_NONE }
            }
        }
        return when {
            context.hasParam(LootContextParams.BLOCK_STATE) -> null
            context.hasParam(LootContextParams.DAMAGE_SOURCE) -> FreshLoot.ORIGIN_ENTITY
            else -> FreshLoot.ORIGIN_CONTAINER
        }
    }

    override fun codec(): MapCodec<out IGlobalLootModifier> = CODEC

    companion object {
        const val ORIGIN_NONE = "none"

        val CODEC: MapCodec<FreshLootModifier> = RecordCodecBuilder.mapCodec { instance ->
            codecStart(instance)
                .and(Codec.list(OriginRule.CODEC).optionalFieldOf("rules", emptyList()).forGetter(FreshLootModifier::rules))
                .apply(instance, ::FreshLootModifier)
        }
    }
}
