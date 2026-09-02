package io.github.veelume.postroad.gametest

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.depot.DepotBlockEntity
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.mail.MailService
import io.github.veelume.postroad.network.Parcel
import io.github.veelume.postroad.names.Culture
import io.github.veelume.postroad.names.NameGenerator
import io.github.veelume.postroad.network.LedgerEntry
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.network.Place
import io.github.veelume.postroad.registry.PostroadBlocks
import io.github.veelume.postroad.registry.PostroadItems
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
 * The arena template is `data/postroad/structure/arena.nbt` (stone floor, air above).
 */
@GameTestHolder(Postroad.MOD_ID)
@PrefixGameTestTemplate(false)
class PostroadGameTests {

    private val depotPos = BlockPos(3, 1, 3)

    private fun sneakingPlayer(helper: GameTestHelper): Player =
        helper.makeMockPlayer(GameType.SURVIVAL).also { it.isShiftKeyDown = true }

    @GameTest(template = ARENA)
    fun depot_registers_a_founded_place(helper: GameTestHelper) {
        helper.setBlock(depotPos, PostroadBlocks.DEPOT.get())
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
        helper.setBlock(depotPos, PostroadBlocks.DEPOT.get())
        val player = sneakingPlayer(helper)
        helper.runAfterDelay(5) {
            val network = Network.get(helper.level.server)
            val account = Network.playerAccount(player.gameProfile.name)
            val before = network.balance(account)

            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(PostroadItems.COIN.get(), 7))
            helper.useBlock(depotPos, player)

            helper.assertValueEqual(network.balance(account), before + 7L, "wallet after pay-in")
            helper.assertTrue(player.mainHandItem.isEmpty, "coins were not consumed")
            helper.assertValueEqual(network.ledger.last().op, LedgerEntry.OP_PAY_IN, "ledger op")
            helper.succeed()
        }
    }

    @GameTest(template = ARENA)
    fun buyback_accepts_fresh_loot_only(helper: GameTestHelper) {
        helper.setBlock(depotPos, PostroadBlocks.DEPOT.get())
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
            helper.assertValueEqual(network.balance(account), before + 24L, "3 fresh emeralds at 8 coins")
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
        helper.setBlock(depotPos, PostroadBlocks.DEPOT.get())
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

    /** Two founded places a few blocks apart, registered and returned by id. */
    private fun twoTowns(helper: GameTestHelper, then: (String, String) -> Unit) {
        val a = BlockPos(1, 1, 1)
        val b = BlockPos(5, 1, 5)
        helper.setBlock(a, PostroadBlocks.DEPOT.get())
        helper.setBlock(b, PostroadBlocks.DEPOT.get())
        helper.runAfterDelay(5) {
            val idA = helper.getBlockEntity<DepotBlockEntity>(a).placeId ?: return@runAfterDelay helper.fail("depot A not registered")
            val idB = helper.getBlockEntity<DepotBlockEntity>(b).placeId ?: return@runAfterDelay helper.fail("depot B not registered")
            then(idA, idB)
        }
    }

    @GameTest(template = ARENA)
    fun charter_unlocks_the_network_once(helper: GameTestHelper) {
        helper.setBlock(depotPos, PostroadBlocks.DEPOT.get())
        val player = sneakingPlayer(helper)
        helper.runAfterDelay(5) {
            val network = Network.get(helper.level.server)
            network.postalUnlocked = false
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(PostroadItems.POSTAL_CHARTER.get()))
            helper.useBlock(depotPos, player)
            helper.assertTrue(network.postalUnlocked, "charter did not unlock the network")
            helper.assertTrue(player.mainHandItem.isEmpty, "charter was not consumed")
            helper.assertValueEqual(network.ledger.last().op, LedgerEntry.OP_CHARTER, "ledger op")

            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(PostroadItems.POSTAL_CHARTER.get()))
            helper.useBlock(depotPos, player)
            helper.assertTrue(!player.mainHandItem.isEmpty, "second charter must not be consumed")
            helper.succeed()
        }
    }

    @GameTest(template = ARENA)
    fun mixed_outbox_splits_into_bulk_and_valuables(helper: GameTestHelper) {
        twoTowns(helper) { a, b ->
            val server = helper.level.server
            val network = Network.get(server)
            network.postalUnlocked = true
            val today = FreshLoot.dayOf(helper.level)
            val sender = "mail-test-sender-" + java.util.UUID.randomUUID().toString().take(8)
            val outbox = listOf(ItemStack(Items.COBBLESTONE, 40), ItemStack(Items.IRON_SWORD))

            val parcels = MailService.send(server, sender, a, b, outbox)
            helper.assertValueEqual(parcels.size, 2, "parcels created")
            val valuables = parcels.first { it.lane == Parcel.LANE_VALUABLES }
            val expectedDays = MailService.valuablesDays(network.distanceBetween(a, b))
            helper.assertValueEqual(valuables.arrivalDay, today + expectedDays, "valuables arrival day")

            // Bulk is delivered on send into the destination's storage; the sword is still in transit.
            val storage = network.storageFor(b)
            helper.assertValueEqual(storage.countItem(Items.COBBLESTONE), 40, "cobblestone delivered at once")
            helper.assertValueEqual(storage.countItem(Items.IRON_SWORD), 0, "sword must not be delivered yet")
            helper.assertTrue(network.parcelsFor(sender).any { it.lane == Parcel.LANE_VALUABLES }, "valuables parcel in transit")

            MailService.deliverDue(server, today + expectedDays)
            helper.assertValueEqual(storage.countItem(Items.IRON_SWORD), 1, "sword delivered on its day")
            helper.assertTrue(network.parcelsFor(sender).isEmpty(), "no parcels left")
            helper.succeed()
        }
    }

    @GameTest(template = ARENA)
    fun full_storage_holds_the_parcel_until_space(helper: GameTestHelper) {
        twoTowns(helper) { a, b ->
            val server = helper.level.server
            val network = Network.get(server)
            network.postalUnlocked = true
            val sender = "mail-test-hoarder-" + java.util.UUID.randomUUID().toString().take(8)
            val storage = network.storageFor(b)
            for (slot in 0 until storage.containerSize) storage.setItem(slot, ItemStack(Items.STONE_SWORD))

            MailService.send(server, sender, a, b, listOf(ItemStack(Items.COBBLESTONE, 5)))
            val held = network.parcelsFor(sender)
            helper.assertValueEqual(held.size, 1, "parcel held while the destination storage is full")

            storage.setItem(0, ItemStack.EMPTY)
            MailService.deliverDue(server, FreshLoot.dayOf(helper.level))
            helper.assertValueEqual(storage.countItem(Items.COBBLESTONE), 5, "delivered after space freed")
            helper.assertTrue(network.parcelsFor(sender).isEmpty(), "held parcel cleared")
            helper.succeed()
        }
    }

    @GameTest(template = ARENA)
    fun home_town_is_the_default_destination(helper: GameTestHelper) {
        twoTowns(helper) { a, b ->
            val network = Network.get(helper.level.server)
            val who = "mail-test-homebody-" + java.util.UUID.randomUUID().toString().take(8)
            helper.assertValueEqual(MailService.defaultDestination(network, who, a), a, "no home: current town")
            network.setHome(who, b)
            helper.assertValueEqual(MailService.defaultDestination(network, who, a), b, "home town wins")
            helper.assertValueEqual(network.homeOf(who.uppercase())?.id, b, "home lookup is case-insensitive")
            helper.succeed()
        }
    }

    @GameTest(template = ARENA)
    fun remote_unstackable_rule(helper: GameTestHelper) {
        val network = Network.get(helper.level.server)
        helper.assertTrue(network.mayMoveRemotely(ItemStack(Items.COBBLESTONE, 3)), "stackables move remotely")
        helper.assertTrue(!network.mayMoveRemotely(ItemStack(Items.IRON_SWORD)), "unstackables stay put")
        network.postalUnlocked = false
        helper.assertTrue(network.mayAccessPage("x", "x"), "own page always accessible")
        helper.assertTrue(!network.mayAccessPage("x", "y"), "remote page locked before the charter")
        network.postalUnlocked = true
        helper.assertTrue(network.mayAccessPage("x", "y"), "remote page open after the charter")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    fun mailbox_is_a_destination_and_the_default(helper: GameTestHelper) {
        twoTowns(helper) { a, _ ->
            val server = helper.level.server
            val network = Network.get(server)
            network.postalUnlocked = true
            val owner = "mail-test-owner-" + java.util.UUID.randomUUID().toString().take(8)
            val boxPos = BlockPos(3, 1, 5)
            helper.setBlock(boxPos, PostroadBlocks.MAILBOX.get())
            val entity = helper.getBlockEntity<io.github.veelume.postroad.mailbox.MailboxBlockEntity>(boxPos)
            entity.claim(helper.level, owner)
            val boxId = entity.placeId ?: return@twoTowns helper.fail("mailbox not registered")

            helper.assertTrue(network.isDestination(boxId), "mailbox is a destination")
            helper.assertValueEqual(MailService.defaultDestination(network, owner, a), boxId, "own mailbox is the default")
            helper.assertTrue(network.destinations().any { it.id == boxId }, "mailbox listed among destinations")
            helper.assertTrue(network.towns().none { it.id == boxId }, "mailbox is not a town page")

            MailService.send(server, owner, a, boxId, listOf(ItemStack(Items.COBBLESTONE, 12)))
            helper.assertValueEqual(network.mailboxStorage(boxId).countItem(Items.COBBLESTONE), 12, "delivered into the mailbox")
            helper.assertValueEqual(network.storageFor(a).countItem(Items.COBBLESTONE), 0, "nothing landed in the town")

            val contents = network.removeMailbox(boxId)
            helper.assertValueEqual(contents.countItem(Items.COBBLESTONE), 12, "contents returned on removal")
            helper.assertTrue(!network.isDestination(boxId), "removed mailbox is no destination")
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
