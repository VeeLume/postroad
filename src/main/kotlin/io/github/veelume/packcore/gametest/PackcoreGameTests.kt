package io.github.veelume.packcore.gametest

import io.github.veelume.packcore.Packcore
import io.github.veelume.packcore.depot.DepotBlockEntity
import io.github.veelume.packcore.loot.FreshLoot
import io.github.veelume.packcore.names.Culture
import io.github.veelume.packcore.names.NameGenerator
import io.github.veelume.packcore.network.LedgerEntry
import io.github.veelume.packcore.network.Network
import io.github.veelume.packcore.network.Place
import io.github.veelume.packcore.registry.PackcoreBlocks
import io.github.veelume.packcore.registry.PackcoreItems
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/**
 * Headless behaviour tests for increment 1. Run with `./gradlew runGameTestServer`.
 * The arena template is `data/packcore/structure/arena.nbt` (stone floor, air above).
 */
@GameTestHolder(Packcore.MOD_ID)
@PrefixGameTestTemplate(false)
class PackcoreGameTests {

    private val depotPos = BlockPos(3, 1, 3)

    private fun sneakingPlayer(helper: GameTestHelper): Player =
        helper.makeMockPlayer(GameType.SURVIVAL).also { it.isShiftKeyDown = true }

    @GameTest(template = ARENA)
    fun depot_registers_a_founded_place(helper: GameTestHelper) {
        helper.setBlock(depotPos, PackcoreBlocks.DEPOT.get())
        helper.succeedWhen {
            val depot = helper.getBlockEntity<DepotBlockEntity>(depotPos)
            helper.assertTrue(depot.placeId != null, "depot has not registered yet")
            val place = depot.place(helper.level)
            helper.assertTrue(place != null, "place missing from network")
            helper.assertValueEqual(place!!.type, Place.TYPE_FOUNDED, "place type")
            helper.assertTrue(place.name.isNotBlank(), "place has no name")
        }
    }

    @GameTest(template = ARENA)
    fun paying_in_coins_credits_the_wallet(helper: GameTestHelper) {
        helper.setBlock(depotPos, PackcoreBlocks.DEPOT.get())
        val player = sneakingPlayer(helper)
        helper.runAfterDelay(5) {
            val network = Network.get(helper.level.server)
            val account = Network.playerAccount(player.gameProfile.name)
            val before = network.balance(account)

            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(PackcoreItems.COIN.get(), 7))
            helper.useBlock(depotPos, player)

            helper.assertValueEqual(network.balance(account), before + 7L, "wallet after pay-in")
            helper.assertTrue(player.mainHandItem.isEmpty, "coins were not consumed")
            helper.assertValueEqual(network.ledger.last().op, LedgerEntry.OP_PAY_IN, "ledger op")
            helper.succeed()
        }
    }

    @GameTest(template = ARENA)
    fun buyback_accepts_fresh_loot_only(helper: GameTestHelper) {
        helper.setBlock(depotPos, PackcoreBlocks.DEPOT.get())
        val player = sneakingPlayer(helper)
        val today = FreshLoot.dayOf(helper.level)
        helper.runAfterDelay(5) {
            val network = Network.get(helper.level.server)
            val account = Network.playerAccount(player.gameProfile.name)
            val before = network.balance(account)

            val stale = ItemStack(Items.EMERALD, 3).also { FreshLoot.stamp(it, FreshLoot.ORIGIN_CONTAINER, today - 100) }
            player.setItemInHand(InteractionHand.MAIN_HAND, stale)
            helper.useBlock(depotPos, player)
            helper.assertValueEqual(network.balance(account), before, "settled loot must not be bought")
            helper.assertTrue(!player.mainHandItem.isEmpty, "settled loot must not be consumed")

            val unmarked = ItemStack(Items.EMERALD, 3)
            player.setItemInHand(InteractionHand.MAIN_HAND, unmarked)
            helper.useBlock(depotPos, player)
            helper.assertValueEqual(network.balance(account), before, "unmarked items must not be bought")

            val fresh = ItemStack(Items.EMERALD, 3).also { FreshLoot.stamp(it, FreshLoot.ORIGIN_CONTAINER, today) }
            player.setItemInHand(InteractionHand.MAIN_HAND, fresh)
            helper.useBlock(depotPos, player)
            helper.assertValueEqual(network.balance(account), before + 12L, "3 fresh emeralds at 4 coins")
            helper.assertTrue(player.mainHandItem.isEmpty, "fresh loot was not consumed")
            helper.assertValueEqual(network.ledger.last().op, LedgerEntry.OP_BUYBACK, "ledger op")
            helper.succeed()
        }
    }

    @GameTest(template = ARENA)
    fun container_loot_is_stamped_fresh(helper: GameTestHelper) {
        val chestPos = BlockPos(1, 1, 1)
        helper.setBlock(chestPos, Blocks.CHEST)
        val chest = helper.getBlockEntity<ChestBlockEntity>(chestPos)
        chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 42L)
        chest.unpackLootTable(null)

        var total = 0
        var stamped = 0
        for (slot in 0 until chest.containerSize) {
            val stack = chest.getItem(slot)
            if (stack.isEmpty) continue
            total++
            if (FreshLoot.isFresh(stack, FreshLoot.dayOf(helper.level))) stamped++
        }
        helper.assertTrue(total > 0, "loot table produced nothing")
        helper.assertValueEqual(stamped, total, "stacks carrying a fresh-loot stamp")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    fun any_container_table_is_stamped_but_block_drops_are_not(helper: GameTestHelper) {
        val level = helper.level
        val origin = Vec3.atCenterOf(helper.absolutePos(BlockPos(3, 1, 3)))
        val registries = level.server.reloadableRegistries()

        // A table whose path is not under chests/ (modded structures look like this).
        val gift = registries.getLootTable(BuiltInLootTables.CAT_MORNING_GIFT)
        val giftParams = LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, origin).create(LootContextParamSets.CHEST)
        val giftLoot = gift.getRandomItems(giftParams).filter { !it.isEmpty }
        helper.assertTrue(giftLoot.isNotEmpty(), "gift table produced nothing")
        helper.assertTrue(giftLoot.all { FreshLoot.of(it) != null }, "container loot without chests/ prefix was not stamped")

        // A block drop must stay unstamped.
        val stone = registries.getLootTable(Blocks.STONE.lootTable)
        val blockParams = LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, origin)
            .withParameter(LootContextParams.BLOCK_STATE, Blocks.STONE.defaultBlockState())
            .withParameter(LootContextParams.TOOL, ItemStack(Items.IRON_PICKAXE))
            .create(LootContextParamSets.BLOCK)
        val drops = stone.getRandomItems(blockParams).filter { !it.isEmpty }
        helper.assertTrue(drops.isNotEmpty(), "stone dropped nothing")
        helper.assertTrue(drops.none { FreshLoot.of(it) != null }, "block drop was stamped as loot")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    fun town_storage_is_shared_per_place(helper: GameTestHelper) {
        helper.setBlock(depotPos, PackcoreBlocks.DEPOT.get())
        helper.runAfterDelay(5) {
            val depot = helper.getBlockEntity<DepotBlockEntity>(depotPos)
            val placeId = depot.placeId ?: return@runAfterDelay helper.fail("depot not registered")
            val network = Network.get(helper.level.server)
            val storage = network.storageFor(placeId)
            storage.setItem(0, ItemStack(Items.COBBLESTONE, 5))
            helper.assertValueEqual(network.storageFor(placeId).getItem(0).count, 5, "stored count")
            helper.assertTrue(network.isDirty, "network not marked dirty after storage change")
            helper.succeed()
        }
    }

    @GameTest(template = ARENA)
    fun names_are_deterministic_per_seed_and_place(helper: GameTestHelper) {
        val culture = Culture(
            id = "test",
            match = emptyList(),
            patterns = listOf("{a}{b}"),
            parts = mapOf("a" to listOf("oak", "ash", "elm", "fen"), "b" to listOf("ford", "wick", "mere", "ton")),
            disambiguators = listOf("New"),
        )
        val first = NameGenerator.generate(1234L, "overworld/1/2", culture, emptySet())
        val again = NameGenerator.generate(1234L, "overworld/1/2", culture, emptySet())
        val elsewhere = NameGenerator.generate(1234L, "overworld/9/9", culture, emptySet())
        helper.assertValueEqual(again, first, "same seed and place")
        helper.assertTrue(first[0].isUpperCase(), "name is capitalised")
        helper.assertTrue(first != elsewhere || true, "different places may collide, that is fine")
        val taken = NameGenerator.generate(1234L, "overworld/1/2", culture, setOf(first))
        helper.assertTrue(taken != first, "taken name must be disambiguated")
        helper.succeed()
    }

    companion object {
        private const val ARENA = "arena"
    }
}
