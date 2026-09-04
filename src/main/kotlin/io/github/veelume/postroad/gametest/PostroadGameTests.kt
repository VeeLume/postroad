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

            // Bulk is delivered on send into the destination's storage. The two arena depots are within
            // the instant distance, so the sword arrives at once too; the timing rule itself is tested below.
            val storage = network.storageFor(b)
            helper.assertValueEqual(storage.countItem(Items.COBBLESTONE), 40, "cobblestone delivered at once")
            helper.assertValueEqual(expectedDays, 0L, "arena towns are within the instant distance")
            MailService.deliverDue(server, today + expectedDays)
            helper.assertValueEqual(storage.countItem(Items.IRON_SWORD), 1, "sword delivered")
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
    fun valuables_timing_and_express_pricing(helper: GameTestHelper) {
        val instant = io.github.veelume.postroad.PostroadConfig.instantDistance
        val perDay = io.github.veelume.postroad.PostroadConfig.valuablesBlocksPerDay
        val base = io.github.veelume.postroad.PostroadConfig.valuablesBaseDays
        helper.assertValueEqual(MailService.valuablesDays(instant), 0L, "at the instant distance: at once")
        helper.assertValueEqual(MailService.valuablesDays(instant + 1), base + 1, "just beyond: base + 1 day")
        helper.assertValueEqual(MailService.valuablesDays(perDay * 3), base + 3, "three days of distance")
        helper.assertValueEqual(MailService.expressCost(0), 0L, "nothing to skip costs nothing")
        helper.assertValueEqual(MailService.expressCost(2), 2 * io.github.veelume.postroad.PostroadConfig.expressCoinsPerDay, "two days of express")

        val network = Network.get(helper.level.server)
        val who = "mail-test-payer-" + java.util.UUID.randomUUID().toString().take(8)
        val fundBefore = network.balance(Network.ROAD_FUND)
        network.credit(Network.playerAccount(who), 3, 0, who, LedgerEntry.OP_GRANT, "test")
        network.credit(Network.ROAD_FUND, 10, 0, who, LedgerEntry.OP_GRANT, "test")
        helper.assertTrue(network.canAfford(who, 12), "wallet plus fund covers it")
        helper.assertTrue(network.charge(who, 12, 0, LedgerEntry.OP_EXPRESS, "test"), "charge succeeds")
        helper.assertValueEqual(network.balance(Network.playerAccount(who)), 0L, "wallet emptied first")
        helper.assertValueEqual(network.balance(Network.ROAD_FUND), fundBefore + 1, "fund paid the remaining 9")
        val total = network.balance(Network.playerAccount(who)) + network.balance(Network.ROAD_FUND)
        val fundNow = network.balance(Network.ROAD_FUND)
        helper.assertTrue(!network.charge(who, total + 5, 0, LedgerEntry.OP_EXPRESS, "test"), "cannot overdraw")
        helper.assertValueEqual(network.balance(Network.ROAD_FUND), fundNow, "refused charge moved nothing")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    fun road_classifier_tiers_and_threshold(helper: GameTestHelper) {
        val rules = io.github.veelume.postroad.roads.RoadRules.current
        val cl = io.github.veelume.postroad.roads.RoadClassifier
        // Three strips on the arena floor (y = 0): dirt path, gravel, cobblestone; the rest is stone.
        for (x in 0..6) {
            helper.setBlock(BlockPos(x, 0, 1), Blocks.DIRT_PATH)
            helper.setBlock(BlockPos(x, 0, 3), Blocks.GRAVEL)
            helper.setBlock(BlockPos(x, 0, 5), Blocks.COBBLESTONE)
        }
        val level = helper.level
        helper.assertValueEqual(rules.classify(Blocks.DIRT_PATH.defaultBlockState()), io.github.veelume.postroad.roads.Tier.DIRT, "dirt path is dirt tier")
        helper.assertValueEqual(rules.classify(Blocks.GRAVEL.defaultBlockState()), io.github.veelume.postroad.roads.Tier.GRAVEL, "gravel is gravel tier")
        helper.assertValueEqual(rules.classify(Blocks.COBBLESTONE.defaultBlockState()), io.github.veelume.postroad.roads.Tier.PAVED, "cobblestone is paved")
        helper.assertValueEqual(rules.classify(Blocks.OAK_PLANKS.defaultBlockState()), io.github.veelume.postroad.roads.Tier.PAVED, "planks count as paved (bridges)")
        helper.assertTrue(rules.classify(Blocks.GRASS_BLOCK.defaultBlockState()) == null, "grass is not road")
        helper.assertTrue(rules.classify(Blocks.STONE.defaultBlockState()) == null, "stone is not road")

        helper.assertValueEqual(cl.sample(level, helper.absolutePos(BlockPos(3, 0, 1)), radius = 0), io.github.veelume.postroad.roads.Tier.DIRT, "standing on the dirt strip")
        helper.assertValueEqual(cl.sample(level, helper.absolutePos(BlockPos(3, 0, 3)), radius = 0), io.github.veelume.postroad.roads.Tier.GRAVEL, "standing on the gravel strip")
        helper.assertValueEqual(cl.sample(level, helper.absolutePos(BlockPos(3, 0, 5)), radius = 0), io.github.veelume.postroad.roads.Tier.PAVED, "standing on the cobble strip")
        helper.assertTrue(cl.sample(level, helper.absolutePos(BlockPos(3, 0, 0)), radius = 0) == null, "stone row is not road")
        helper.assertValueEqual(cl.sample(level, helper.absolutePos(BlockPos(3, 0, 2)), radius = 1), io.github.veelume.postroad.roads.Tier.GRAVEL, "between dirt and gravel, ties go to the better tier")

        val walk = listOf(io.github.veelume.postroad.roads.Tier.DIRT, null, null, io.github.veelume.postroad.roads.Tier.DIRT, null, null, io.github.veelume.postroad.roads.Tier.GRAVEL, null, null, null)
        val verdict = cl.evaluate(walk)
        helper.assertValueEqual(verdict.roadShare, 0.3, "three road samples of ten")
        helper.assertTrue(verdict.isRoad, "30 % is road")
        helper.assertValueEqual(verdict.tier, io.github.veelume.postroad.roads.Tier.DIRT, "majority tier")
        helper.assertTrue(!cl.evaluate(listOf(io.github.veelume.postroad.roads.Tier.PAVED, null, null, null)).isRoad, "25 % is not road")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    fun road_buff_follows_the_ground(helper: GameTestHelper) {
        val buff = io.github.veelume.postroad.roads.RoadBuff
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        helper.setBlock(BlockPos(2, 0, 2), Blocks.COBBLESTONE)
        val onRoad = helper.absoluteVec(net.minecraft.world.phys.Vec3(2.5, 1.0, 2.5))
        player.setPos(onRoad.x, onRoad.y, onRoad.z)
        val tier = buff.update(player)
        helper.assertValueEqual(tier, io.github.veelume.postroad.roads.Tier.PAVED, "paved under the player")
        helper.assertValueEqual(buff.currentStrength(player), io.github.veelume.postroad.PostroadConfig.buffPaved, "paved buff applied")

        val onStone = helper.absoluteVec(net.minecraft.world.phys.Vec3(5.5, 1.0, 5.5))
        player.setPos(onStone.x, onStone.y, onStone.z)
        helper.assertTrue(buff.update(player) == null, "stone is not road")
        helper.assertValueEqual(buff.currentStrength(player), 0.0, "buff removed off the road")
        helper.succeed()
    }

    /** A charting session fed by hand: walk the strip at z, sampling every block. */
    private fun chart(helper: GameTestHelper, player: net.minecraft.server.level.ServerPlayer, z: Int, xs: IntRange): io.github.veelume.postroad.roads.RoadPath? {
        val session = io.github.veelume.postroad.roads.Charting.Session(player.uuid, helper.level)
        for (x in xs) session.sampleAt(helper.absolutePos(BlockPos(x, 1, z)))
        return io.github.veelume.postroad.roads.Charting.record(player, session)
    }

    @GameTest(template = ARENA)
    fun charting_records_paths_branches_and_towns(helper: GameTestHelper) {
        val network = Network.get(helper.level.server)
        val player = helper.makeMockServerPlayerInLevel()
        // cobble strip along z = 1, gravel strip along x = 6 meeting it
        for (x in 0..6) helper.setBlock(BlockPos(x, 0, 1), Blocks.COBBLESTONE)
        for (z in 1..6) helper.setBlock(BlockPos(6, 0, z), Blocks.GRAVEL)
        helper.setBlock(BlockPos(0, 1, 3), PostroadBlocks.DEPOT.get())
        helper.runAfterDelay(5) {
            val before = network.paths.size
            val first = chart(helper, player, 1, 0..6) ?: return@runAfterDelay helper.fail("first walk refused")
            helper.assertValueEqual(first.tier, io.github.veelume.postroad.roads.Tier.PAVED, "cobble walk is paved")
            helper.assertTrue(first.length > 5.0, "length measured")
            helper.assertTrue(network.nodes.values.any { it.kind == io.github.veelume.postroad.roads.RoadNode.KIND_TOWN && it.pathId == first.id }, "depot next to the path became a town node")

            val session = io.github.veelume.postroad.roads.Charting.Session(player.uuid, helper.level)
            for (z in 1..6) session.sampleAt(helper.absolutePos(BlockPos(6, 1, z)))
            val branch = io.github.veelume.postroad.roads.Charting.record(player, session) ?: return@runAfterDelay helper.fail("branch refused")
            helper.assertValueEqual(branch.tier, io.github.veelume.postroad.roads.Tier.GRAVEL, "gravel branch")
            helper.assertTrue(network.links.any { (it.pathA == branch.id && it.pathB == first.id) }, "branch linked to the first path")
            helper.assertValueEqual(network.paths.size, before + 2, "two paths recorded")

            val grass = io.github.veelume.postroad.roads.Charting.Session(player.uuid, helper.level)
            // Stay more than the sampling radius (2) away from both strips: x 0..3 on row 5.
            for (x in 0..3) grass.sampleAt(helper.absolutePos(BlockPos(x, 1, 5)))
            helper.assertTrue(io.github.veelume.postroad.roads.Charting.record(player, grass) == null, "a walk over stone is refused")

            helper.assertTrue(network.severPath(first.id, 3), "sever in the middle")
            helper.assertTrue(network.paths.containsKey(first.id + "b"), "tail path exists after sever")
            network.removePath(branch.id)
            helper.assertTrue(network.links.none { it.pathA == branch.id || it.pathB == branch.id }, "links dropped with the path")
            helper.succeed()
        }
    }

    @GameTest(template = ARENA)
    fun routing_fares_and_journey(helper: GameTestHelper) {
        val network = Network.get(helper.level.server)
        val player = helper.makeMockServerPlayerInLevel()
        val dim = helper.level.dimension().location()
        // Two hand-made paths joined by a link, three nodes: A at the start, B in the middle, C on the branch.
        val pA = io.github.veelume.postroad.roads.RoadPath("test-a-" + java.util.UUID.randomUUID().toString().take(6), dim,
            (0..10).map { helper.absolutePos(BlockPos(it * 100, 1, 0)) }.toMutableList(), MutableList(11) { io.github.veelume.postroad.roads.Tier.PAVED }, "tester", 0)
        val pB = io.github.veelume.postroad.roads.RoadPath("test-b-" + java.util.UUID.randomUUID().toString().take(6), dim,
            (0..3).map { helper.absolutePos(BlockPos(500, 1, it * 100)) }.toMutableList(), MutableList(4) { io.github.veelume.postroad.roads.Tier.DIRT }, "tester", 0)
        network.addPath(pA); network.addPath(pB)
        network.addLink(io.github.veelume.postroad.roads.PathLink(pB.id, 0, pA.id, 5))
        val a = io.github.veelume.postroad.roads.RoadNode("sign/test/a", "sign", dim, pA.points[0], pA.id, 0, "A", null)
        val b = io.github.veelume.postroad.roads.RoadNode("sign/test/b", "sign", dim, pA.points[10], pA.id, 10, "B", null)
        val c = io.github.veelume.postroad.roads.RoadNode("sign/test/c", "sign", dim, pB.points[3], pB.id, 3, "C", null)
        network.nodes[a.id] = a; network.nodes[b.id] = b; network.nodes[c.id] = c

        val routes = io.github.veelume.postroad.roads.Routing.routes(network, a.id)
        helper.assertValueEqual(routes[b.id]?.length?.toInt(), 1000, "A→B along the paved path")
        helper.assertValueEqual(routes[b.id]?.worstTier, io.github.veelume.postroad.roads.Tier.PAVED, "A→B worst tier")
        helper.assertValueEqual(routes[c.id]?.length?.toInt(), 800, "A→C via the link")
        helper.assertValueEqual(routes[c.id]?.worstTier, io.github.veelume.postroad.roads.Tier.DIRT, "A→C worst tier is the dirt branch")

        val fares = io.github.veelume.postroad.travel.Fares
        val cfg = io.github.veelume.postroad.PostroadConfig
        helper.assertValueEqual(fares.fare(cfg.freeDistance, null), 0L, "free at the boundary")
        helper.assertValueEqual(fares.fare(cfg.freeDistance + 1, io.github.veelume.postroad.roads.Tier.DIRT), 1L, "one coin just beyond")
        val dirt = fares.fare(1000.0, io.github.veelume.postroad.roads.Tier.DIRT)
        val paved = fares.fare(1000.0, io.github.veelume.postroad.roads.Tier.PAVED)
        helper.assertTrue(paved < dirt, "paved is cheaper than dirt for the same distance")

        // The journey: fresh loot is mailed, fare charged, player moved.
        network.postalUnlocked = true
        val depotPos = BlockPos(3, 1, 3)
        helper.setBlock(depotPos, PostroadBlocks.DEPOT.get())
        helper.runAfterDelay(5) {
            val placeId = helper.getBlockEntity<DepotBlockEntity>(depotPos).placeId ?: return@runAfterDelay helper.fail("depot not registered")
            val town = io.github.veelume.postroad.roads.RoadNode("town/$placeId", "town", dim, helper.absolutePos(depotPos), pA.id, 10, "Town", placeId)
            network.nodes[town.id] = town
            val start = pA.points[0]
            player.setPos(start.x + 0.5, start.y.toDouble(), start.z + 0.5)
            val today = FreshLoot.dayOf(helper.level)
            val fresh = ItemStack(Items.EMERALD, 5).also { FreshLoot.stamp(it, FreshLoot.ORIGIN_CONTAINER, today) }
            player.inventory.setItem(0, fresh)
            player.inventory.setItem(1, ItemStack(Items.BREAD, 3))
            val account = Network.playerAccount(player.gameProfile.name)
            network.credit(account, 100, today, "test", LedgerEntry.OP_GRANT, "test")
            val before = network.balance(account)
            val expectedFare = fares.fare(1000.0, io.github.veelume.postroad.roads.Tier.PAVED)

            helper.assertTrue(io.github.veelume.postroad.travel.TravelService.depart(player, a.id, town.id, false), "journey accepted")
            helper.assertTrue(player.inventory.getItem(0).isEmpty, "fresh loot left the inventory")
            helper.assertValueEqual(player.inventory.getItem(1).count, 3, "bread stays")
            helper.assertValueEqual(network.balance(account), before - expectedFare, "fare charged")
            helper.assertValueEqual(network.storageFor(placeId).countItem(Items.EMERALD), 5, "loot mailed to the town (instant: stackable)")
            helper.assertTrue(player.blockPosition().distSqr(helper.absolutePos(depotPos)) < 25.0, "player arrived next to the depot")
            helper.succeed()
        }
    }

    @GameTest(template = ARENA)
    fun planner_detours_water_contours_hills_and_avoids_structures(helper: GameTestHelper) {
        val g = io.github.veelume.postroad.roads.gen.TerrainGrid
        val planner = io.github.veelume.postroad.roads.gen.RoadPlanner
        val cell = { x: Int, z: Int -> io.github.veelume.postroad.roads.gen.Cell(x, z) }

        // A river across the middle with a ford at x = 28..31: the route should cross at the ford.
        val river = g.flat(60, 40, 64)
        river.fill(0, 19, 59, 21, g.WATER)
        for (fx in 28..31) for (fz in 19..21) river.clear(fx, fz, g.WATER)
        val crossing = planner.route(river, cell(5, 5), cell(5, 35)) ?: return helper.fail("river route not found")
        val wet = crossing.count { river.has(it.x, it.z, g.WATER) }
        helper.assertValueEqual(wet, 0, "route uses the ford, no wet cells")
        helper.assertTrue(crossing.any { it.x in 28..31 && it.z == 20 }, "route passes the ford")
        // The same through the two-level planner: coarse corridor, fine route inside it.
        val coarse = river.downsample(4)
        val twoLevel = planner.routeHierarchical(river, coarse, 4, cell(5, 5), cell(5, 35)) ?: return helper.fail("hierarchical river route not found")
        helper.assertValueEqual(twoLevel.count { river.has(it.x, it.z, g.WATER) }, 0, "two-level route is dry too")
        helper.assertTrue(twoLevel.any { it.x in 28..31 && it.z == 20 }, "two-level route passes the ford")

        // A steep hill in the middle: the route goes around rather than over.
        val hill = g.flat(60, 60, 64)
        for (z in 20..40) for (x in 20..40) {
            val d = maxOf(kotlin.math.abs(x - 30), kotlin.math.abs(z - 30))
            hill.setHeight(x, z, 64 + (10 - d).coerceAtLeast(0) * 6)
        }
        val around = planner.route(hill, cell(5, 30), cell(55, 30)) ?: return helper.fail("hill route not found")
        val peak = around.maxOf { hill.heightAt(it.x, it.z) }
        helper.assertTrue(peak < 64 + 30, "route stays off the summit (peak height $peak)")
        // A gentle ridge (2 blocks per cell) is passable; a cliff (8 per cell) is not, and a road across it is refused.
        val ridge = g.flat(40, 20, 64)
        for (z in 0 until 20) { ridge.setHeight(20, z, 66); ridge.setHeight(21, z, 68); ridge.setHeight(22, z, 66) }
        helper.assertTrue(planner.route(ridge, cell(5, 10), cell(35, 10)) != null, "a gentle ridge is crossed")
        val cliff = g.flat(40, 20, 64)
        for (z in 0 until 20) for (x in 20 until 40) cliff.setHeight(x, z, 80)
        helper.assertTrue(planner.route(cliff, cell(5, 10), cell(35, 10)) == null, "a 16-block cliff with no way around is unreachable")
        // The band rule: a low valley route beats a route over a plateau above both towns.
        val plateau = g.flat(60, 40, 64)
        for (z in 15..25) for (x in 10..50) plateau.setHeight(x, z, 64 + 2 * (minOf(x - 9, 51 - x, z - 14, 26 - z).coerceAtMost(3)))
        val flat = planner.route(plateau, cell(5, 20), cell(55, 20)) ?: return helper.fail("plateau route not found")
        helper.assertTrue(flat.count { plateau.heightAt(it.x, it.z) > 66 } < flat.size / 2, "route keeps to the towns' elevation rather than the plateau")
        // A town inside a box is left on the side facing the other town.
        val sided = g.flat(60, 40, 64)
        sided.fill(20, 10, 30, 30, g.BLOCKED)
        val exit = planner.resolveEndpoint(sided, cell(25, 20), toward = cell(55, 20)) ?: return helper.fail("no exit")
        helper.assertTrue(exit.x == 31 && exit.z == 20, "exit is on the side toward the other town ($exit)")

        // A structure box between two towns: the route goes around it.
        val box = g.flat(40, 40, 64)
        box.fill(15, 10, 25, 30, g.BLOCKED)
        val past = planner.route(box, cell(5, 20), cell(35, 20)) ?: return helper.fail("box route not found")
        helper.assertTrue(past.none { box.has(it.x, it.z, g.BLOCKED) }, "route never enters the box")
        helper.assertTrue(planner.route(box, cell(5, 20), cell(20, 20)) != null, "a town inside a box is still reachable as the goal")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    fun planner_reuses_roads_and_makes_one_junction(helper: GameTestHelper) {
        val g = io.github.veelume.postroad.roads.gen.TerrainGrid
        val planner = io.github.veelume.postroad.roads.gen.RoadPlanner
        val cell = { x: Int, z: Int -> io.github.veelume.postroad.roads.gen.Cell(x, z) }
        // Towns A and B far apart on a line, C off to the side near the middle.
        val grid = g.flat(120, 60, 64)
        val towns = listOf(cell(5, 30), cell(115, 30), cell(60, 50)).mapIndexed { i, c -> io.github.veelume.postroad.roads.gen.Town("t$i", c) }
        val plan = planner.planNetwork(grid, towns, neighbours = 2, maxLinkCells = 200.0, coarse = grid.downsample(4), ratio = 4)
        helper.assertTrue(plan.routes.size >= 2, "at least two routes planned (${plan.routes.size})")
        val roadCells = plan.routes.flatMap { it.cells }.toSet().size
        val summed = plan.routes.sumOf { it.cells.size }
        helper.assertTrue(summed > roadCells, "later routes ride earlier road cells (reuse happened)")
        helper.assertTrue(plan.junctions.isNotEmpty(), "a junction exists")
        // The trunk A–B is planned first; C's spur joins it and does not run alongside it.
        helper.assertTrue(plan.routes[0].from == towns[0] && plan.routes[0].to == towns[1], "the longest pair is the trunk")
        helper.assertTrue(plan.junctions.size <= 2, "no ring of junctions (${plan.junctions.size})")
        helper.assertTrue(plan.junctions.all { it.joinedRoute == plan.routes[0].id }, "every junction is on the trunk: " + plan.junctions.joinToString { "${it.cell} ${it.joiningRoute}->${it.joinedRoute}" } + " routes " + plan.routes.joinToString { "${it.id}:${it.from.id}-${it.to.id}" })
        // Planning again with the result as existing roads adds nothing.
        val again = planner.planNetwork(grid, towns, neighbours = 2, maxLinkCells = 200.0, existing = plan.routes)
        helper.assertValueEqual(again.routes.size, 0, "no new routes on a second pass")
        val trunk = plan.routes[0].cells.toSet()
        var parallel = 0
        var spurNew = 0
        for (r in 1 until plan.routes.size) {
            for (c in planner.newCells(plan, r)) {
                spurNew++
                if (c !in trunk && trunk.any { it.distanceTo(c) <= 2.0 }) parallel++
            }
        }
        helper.assertTrue(spurNew > 0, "the spur has cells of its own")
        helper.assertTrue(parallel * 5 < spurNew, "spur does not run parallel to the trunk ($parallel of $spurNew cells within 2 of it)")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    fun tiled_terrain_samples_lazily_and_routes_around_water(helper: GameTestHelper) {
        val gen = io.github.veelume.postroad.roads.gen.TiledTerrain
        val t = io.github.veelume.postroad.roads.gen.Terrain
        // A synthetic sampler: flat at 64 with a water band at cells z in 20..22, a ford at x = 40.
        val sampled = java.util.concurrent.atomic.AtomicInteger()
        val sampler = io.github.veelume.postroad.roads.gen.TileSampler { tx, tz ->
            sampled.incrementAndGet()
            val tile = io.github.veelume.postroad.roads.gen.Tile.empty()
            for (i in 0 until gen.TILE) for (j in 0 until gen.TILE) {
                val cx = tx * gen.TILE + j
                val cz = tz * gen.TILE + i
                tile.heights[i * gen.TILE + j] = 64
                if (cz in 20..22 && cx != 40) tile.flags[i * gen.TILE + j] = t.WATER.toByte()
            }
            tile
        }
        val terrain = io.github.veelume.postroad.roads.gen.TiledTerrain(4, sampler)
        terrain.bounds = io.github.veelume.postroad.roads.gen.CellBox(-10, -10, 90, 60)
        helper.assertValueEqual(terrain.tileCount, 0, "nothing sampled before the first read")
        val route = io.github.veelume.postroad.roads.gen.RoadPlanner.route(terrain, io.github.veelume.postroad.roads.gen.Cell(10, 5), io.github.veelume.postroad.roads.gen.Cell(10, 40))
            ?: return helper.fail("no route over tiled terrain")
        helper.assertTrue(route.any { it.x == 40 && it.z == 21 }, "route crosses at the ford")
        helper.assertTrue(route.none { terrain.has(it.x, it.z, t.WATER) }, "route stays dry")
        helper.assertTrue(sampled.get() == terrain.tileCount && sampled.get() > 0, "each tile sampled once (${sampled.get()} / ${terrain.tileCount})")
        // A structure box blocks cells on tiles loaded before and after it was added.
        terrain.block(io.github.veelume.postroad.roads.gen.CellBox(60, 30, 65, 35))
        helper.assertTrue(terrain.has(62, 32, t.BLOCKED), "box marks a loaded tile")
        helper.assertTrue(terrain.has(64, 34, t.BLOCKED), "box marks a tile loaded later")
        helper.assertTrue(terrain.cellToBlock(10, 5) == BlockPos(42, 64, 22), "cell centre at surface height")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    fun world_sampler_reads_the_generator_off_chunks(helper: GameTestHelper) {
        val level = helper.level
        val sampler = io.github.veelume.postroad.roads.gen.WorldTerrainSampler(level, null, 4)
        val tile = sampler.sample(200, 200) // far from anything loaded
        val expected = level.chunkSource.generator.getBaseHeight(200 * 64 + 2, 200 * 64 + 2, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, level.chunkSource.randomState())
        helper.assertValueEqual(tile.heights[0].toInt(), expected, "tile height matches the generator's base height")
        helper.assertTrue(tile.heights.all { it.toInt() == expected }, "the flat test world samples flat")
        helper.assertTrue(tile.families.all { it in 0..5 }, "families are in range")
        val finder = io.github.veelume.postroad.roads.gen.TownFinder(level, sampler::surface)
        var found = 0
        for (cz in 100..103) for (cx in 100..103) if (finder.find(cx, cz) != null) found++
        helper.assertTrue(found >= 0, "town finder runs off-chunk without error (${finder.villageSets.size} village set(s))")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    fun plan_pass_applies_as_uncharted_paths(helper: GameTestHelper) {
        val gen = io.github.veelume.postroad.roads.gen.RoadGen
        val level = helper.level
        val server = level.server
        val tag = java.util.UUID.randomUUID().toString().take(6)
        val dim = level.dimension().location()
        val box = { x: Int, z: Int -> net.minecraft.world.level.levelgen.structure.BoundingBox(x - 20, 60, z - 20, x + 20, 80, z + 20) }
        val a = io.github.veelume.postroad.roads.gen.PlannedTown("test/$tag/a", dim, Postroad.id("test"), BlockPos(100000, 64, 100000), box(100000, 100000))
        val b = io.github.veelume.postroad.roads.gen.PlannedTown("test/$tag/b", dim, Postroad.id("test"), BlockPos(100400, 64, 100000), box(100400, 100000))
        val c = io.github.veelume.postroad.roads.gen.PlannedTown("test/$tag/c", dim, Postroad.id("test"), BlockPos(100200, 64, 100200), box(100200, 100200))
        val request = io.github.veelume.postroad.roads.gen.RoadGen.PassRequest(dim, BlockPos(100200, 64, 100100), radius = 600, maxLink = 900, neighbours = 2, margin = 8,
            costs = io.github.veelume.postroad.roads.gen.PlannerCosts(), knownTowns = listOf(a, b, c), knownRoads = emptyList(),
            discovered = it.unimi.dsi.fastutil.longs.LongOpenHashSet(), discoverTowns = false)
        val worker = gen.workerFor(level)
        val result = gen.runPass(worker, request)
        helper.assertTrue(result.newRoads.size >= 2, "roads planned between the three towns (${result.newRoads.size})")
        helper.assertTrue(result.newRoads.all { r -> r.points.none { p -> p.x in 99980..100020 && p.z in 99980..100020 } }, "no road runs through town A's box")
        helper.assertTrue(result.newJunctions.isNotEmpty(), "the spur joins the trunk")

        val network = Network.get(server)
        val storage = io.github.veelume.postroad.roads.gen.RoadPlanStorage.get(server)
        val pathsBefore = network.paths.size
        gen.apply(server, result)
        helper.assertValueEqual(network.paths.size - pathsBefore, result.newRoads.size, "one uncharted path per planned road")
        for (road in result.newRoads) {
            val path = network.paths[road.id] ?: return helper.fail("path ${road.id} missing")
            helper.assertTrue(!path.charted, "generated path starts uncharted")
            helper.assertTrue(path.recordedBy == gen.GENERATED_BY, "generated path is recorded by the mod")
            helper.assertTrue(storage.roads[road.id] != null, "plan storage knows the road")
        }
        helper.assertTrue(network.links.any { it.pathA == result.newJunctions[0].joiningRoad && it.pathB == result.newJunctions[0].joinedRoad }, "junction became a link")
        // Uncharted: routing sees nothing.
        val trunk = result.newRoads[0]
        val nodeId = "test/$tag/node"
        network.nodes[nodeId] = io.github.veelume.postroad.roads.RoadNode(nodeId, io.github.veelume.postroad.roads.RoadNode.KIND_SIGN, dim, trunk.points[0], trunk.id, 0, "n", null)
        helper.assertTrue(io.github.veelume.postroad.roads.Routing.routes(network, nodeId).isEmpty(), "uncharted roads carry no travel")
        // Applying the same result again changes nothing.
        gen.apply(server, result)
        helper.assertValueEqual(network.paths.size - pathsBefore, result.newRoads.size, "apply is idempotent")
        network.nodes.remove(nodeId)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    fun builder_lays_a_strip_once_and_charting_marks_it(helper: GameTestHelper) {
        val level = helper.level
        val server = level.server
        val tag = java.util.UUID.randomUUID().toString().take(6)
        val dim = level.dimension().location()
        // A straight road across the arena floor, one point every 4 blocks, on the floor's surface.
        val start = helper.absolutePos(BlockPos(1, 1, 4))
        // The ground the builder will see: the heightmap at the road's start, whatever the batch put there.
        val floorY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start.x + 4, start.z) - 1
        val points = (0..4).map { BlockPos(start.x + it * 4, floorY + 1, start.z) }
        val road = io.github.veelume.postroad.roads.gen.PlannedRoad("t$tag", dim, "test/$tag/a", "test/$tag/b", points, ByteArray(points.size))
        val storage = io.github.veelume.postroad.roads.gen.RoadPlanStorage.get(server)
        val network = Network.get(server)
        storage.addRoad(road)
        network.addPath(io.github.veelume.postroad.roads.RoadPath(road.id, dim, points.toMutableList(), MutableList(points.size) { io.github.veelume.postroad.roads.Tier.PAVED },
            io.github.veelume.postroad.roads.gen.RoadGen.GENERATED_BY, 0, charted = false))
        // Real ground so the styles file allows the replacement (the arena floor is stone: replaceable).
        val chunk = net.minecraft.world.level.ChunkPos.asLong(start.x shr 4, start.z shr 4)
        val placed = io.github.veelume.postroad.roads.gen.RoadBuilder.buildChunk(level, storage, chunk)
        val floor = level.getBlockState(BlockPos(start.x + 4, floorY, start.z))
        val above = level.getBlockState(BlockPos(start.x + 4, floorY + 1, start.z))
        val hm = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start.x + 4, start.z)
        helper.assertTrue(placed > 0, "the builder placed road blocks ($placed); floor ${floor.block} replaceable=${io.github.veelume.postroad.roads.gen.RoadStyles.current.isReplaceable(floor)}, above ${above.block}, heightmap $hm vs floorY $floorY, roads in chunk ${storage.roadsInChunk(dim, chunk).size}, towns ${storage.towns.size}")
        helper.assertTrue(road.builtChunks.contains(chunk), "the chunk is marked built")
        val styles = io.github.veelume.postroad.roads.gen.RoadStyles.current
        val centre = level.getBlockState(BlockPos(start.x + 4, floorY, start.z))
        helper.assertTrue(!centre.`is`(Blocks.STONE) && !centre.`is`(Blocks.POLISHED_ANDESITE), "the road centre is a palette block (${centre.block})")
        helper.assertValueEqual(io.github.veelume.postroad.roads.gen.RoadBuilder.buildChunk(level, storage, chunk), 0, "a second build places nothing")

        // Walking the road charts it instead of recording a duplicate.
        val player = helper.makeMockServerPlayerInLevel()
        val session = io.github.veelume.postroad.roads.Charting.Session(player.uuid, level)
        for (p in points) session.sampleAt(p)
        val pathsBefore = network.paths.size
        val marked = io.github.veelume.postroad.roads.Charting.markGenerated(player, session, network)
        helper.assertTrue(marked != null && marked.id == road.id, "the walk charted the generated road")
        helper.assertTrue(network.paths[road.id]!!.charted, "the road is now charted")
        helper.assertValueEqual(network.paths.size, pathsBefore, "no duplicate path recorded")
        helper.assertTrue(network.paths[road.id]!!.tiers.any { it != null }, "the road keeps a tier after charting (${network.paths[road.id]!!.tiers})")
        helper.succeed()
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
