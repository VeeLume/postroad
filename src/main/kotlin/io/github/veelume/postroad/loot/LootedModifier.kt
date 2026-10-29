package io.github.veelume.postroad.loot

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.veelume.postroad.Postroad
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.neoforged.neoforge.common.loot.IGlobalLootModifier
import net.neoforged.neoforge.common.loot.LootModifier

/**
 * Global loot modifier that puts the [Looted] mark on the unstackables a loot container produces.
 *
 * Classification comes from the loot context, not the table id, so modded structure tables
 * with arbitrary paths (`kaisyn:village/…`, `betterdungeons:zombie_dungeon/…`) count too:
 * a block state means a block drop, a damage source a mob drop (neither is marked: both can be
 * farmed), anything else is container loot. Data rules by table-path prefix override that
 * default, either way.
 *
 * Data: `data/postroad/loot_modifiers/looted.json`.
 */
class LootedModifier(conditions: Array<LootItemCondition>, val rules: List<Rule>) : LootModifier(conditions) {

    data class Rule(val prefix: String, val looted: Boolean) {
        companion object {
            val CODEC: Codec<Rule> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.STRING.fieldOf("prefix").forGetter(Rule::prefix),
                    Codec.BOOL.fieldOf("looted").forGetter(Rule::looted),
                ).apply(instance, ::Rule)
            }
        }
    }

    override fun doApply(generatedLoot: ObjectArrayList<ItemStack>, context: LootContext): ObjectArrayList<ItemStack> {
        val tableId = context.queriedLootTableId
        if (!isContainerLoot(context, tableId?.path)) return generatedLoot
        var marked = 0
        for (stack in generatedLoot) {
            if (Looted.wanted(stack) && !Looted.isLooted(stack)) {
                Looted.mark(stack)
                marked++
            }
        }
        if (marked > 0) {
            Postroad.LOGGER.debug("Marked {} stack(s) from {} as looted", marked, tableId)
        }
        return generatedLoot
    }

    private fun isContainerLoot(context: LootContext, path: String?): Boolean {
        if (path != null) {
            rules.firstOrNull { path.startsWith(it.prefix) }?.let { return it.looted }
        }
        return !context.hasParam(LootContextParams.BLOCK_STATE) && !context.hasParam(LootContextParams.DAMAGE_SOURCE)
    }

    override fun codec(): MapCodec<out IGlobalLootModifier> = CODEC

    companion object {
        val CODEC: MapCodec<LootedModifier> = RecordCodecBuilder.mapCodec { instance ->
            codecStart(instance)
                .and(Codec.list(Rule.CODEC).optionalFieldOf("rules", emptyList()).forGetter(LootedModifier::rules))
                .apply(instance, ::LootedModifier)
        }
    }
}
