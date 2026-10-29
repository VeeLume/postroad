package io.github.veelume.postroad.loot

import io.github.veelume.postroad.registry.PostroadComponents
import net.minecraft.world.item.ItemStack

/**
 * The looted mark: an unstackable item that came out of a loot container. Stamped by [LootedModifier],
 * never dated and never removed. It is what lets the satchel buy looted gear without buying crafted or
 * farmed gear. Only unstackables carry it, so it never keeps two stacks from merging.
 */
object Looted {
    fun isLooted(stack: ItemStack): Boolean = stack.has(PostroadComponents.LOOTED.get())

    fun mark(stack: ItemStack) {
        stack.set(PostroadComponents.LOOTED.get(), net.minecraft.util.Unit.INSTANCE)
    }

    /** Whether [stack] may carry the mark at all. */
    fun wanted(stack: ItemStack): Boolean = !stack.isEmpty && !stack.isStackable
}
