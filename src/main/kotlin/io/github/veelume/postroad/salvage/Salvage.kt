package io.github.veelume.postroad.salvage

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.loot.Looted
import io.github.veelume.postroad.network.LedgerEntry
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.registry.PostroadDataMaps
import io.github.veelume.postroad.registry.PostroadItems
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TieredItem

/**
 * What the satchel (and the depot) pays for an item, in one place. Loot, never resources:
 *
 * - an item with a [SalvageRate] in `postroad:salvage` is bought at that rate, marked or not unless the
 *   rate says `looted` — treasure nobody can craft or farm, such as saddles or music discs;
 * - any other item with the [Looted] mark is bought by what it is made of: the best `postroad:salvage_material`
 *   rate among its repair ingredients, else `salvage.lootedDefault`;
 * - looted enchanted gear adds `salvage.enchantedBonus`.
 *
 * A rate of 0 means never bought. Runs on both sides: the data maps and the mark are synced, so the
 * tooltip and the satchel screen quote what the server will pay.
 */
object Salvage {

    /** Coins for the whole [stack], or null if it is not bought. Coins themselves are not salvage. */
    fun quote(stack: ItemStack): Long? {
        if (stack.isEmpty || stack.`is`(PostroadItems.COIN.get())) return null
        val looted = Looted.isLooted(stack)
        val rate = stack.itemHolder.getData(PostroadDataMaps.SALVAGE)
        var each = when {
            rate != null -> if (rate.coins <= 0 || (rate.looted && !looted)) return null else rate.coins.toLong()
            looted -> materialRate(stack) ?: PostroadConfig.salvageLootedDefault
            else -> return null
        }
        if (each <= 0) return null
        if (looted && stack.isEnchanted) each += PostroadConfig.salvageEnchantedBonus
        return each * stack.count
    }

    /** The best material rate among the repair ingredients of armour and tools; null when none is listed. */
    fun materialRate(stack: ItemStack): Long? {
        val ingredient = when (val item = stack.item) {
            is ArmorItem -> item.material.value().repairIngredient().get()
            is TieredItem -> item.tier.repairIngredient
            else -> return null
        }
        return ingredient.items.mapNotNull { it.itemHolder.getData(PostroadDataMaps.SALVAGE_MATERIAL) }.maxOrNull()?.toLong()
    }

    /**
     * Sells every stack of [stacks] that has a quote and pays coins in, in one ledger entry each;
     * returns what was sold (salvage coins, coins paid in). The caller empties the stacks that were
     * taken: those with a quote, and coins.
     */
    fun sell(network: Network, actor: String, where: String, stacks: List<ItemStack>, day: Long): Sale {
        var items = 0
        var coins = 0L
        var paidIn = 0L
        for (stack in stacks) {
            if (stack.`is`(PostroadItems.COIN.get())) { paidIn += stack.count; continue }
            val amount = quote(stack) ?: continue
            items += stack.count
            coins += amount
        }
        val account = Network.playerAccount(actor)
        if (coins > 0) network.credit(account, coins, day, actor, LedgerEntry.OP_SALVAGE, "$items item(s) @ $where")
        if (paidIn > 0) network.credit(account, paidIn, day, actor, LedgerEntry.OP_PAY_IN, where)
        return Sale(items, coins, paidIn)
    }

    /** Whether [sell] takes [stack]. */
    fun takes(stack: ItemStack): Boolean = stack.`is`(PostroadItems.COIN.get()) || quote(stack) != null

    data class Sale(val items: Int, val coins: Long, val paidIn: Long)
}

/** A `postroad:salvage` entry: coins per unit, and whether only looted ones are bought. A bare number is `{coins}`. */
data class SalvageRate(val coins: Int, val looted: Boolean = false) {
    companion object {
        private val FULL: Codec<SalvageRate> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.intRange(0, Int.MAX_VALUE).fieldOf("coins").forGetter(SalvageRate::coins),
                Codec.BOOL.optionalFieldOf("looted", false).forGetter(SalvageRate::looted),
            ).apply(instance, ::SalvageRate)
        }

        val CODEC: Codec<SalvageRate> = Codec.either(Codec.intRange(0, Int.MAX_VALUE), FULL).xmap(
            { either -> either.map({ SalvageRate(it) }, { it }) },
            { rate -> if (rate.looted) Either.right(rate) else Either.left(rate.coins) },
        )
    }
}
