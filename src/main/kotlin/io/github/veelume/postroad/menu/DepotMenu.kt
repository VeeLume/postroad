package io.github.veelume.postroad.menu

import io.github.veelume.postroad.advancement.PostroadAdvancements
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.mail.MailService
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.network.Parcel
import io.github.veelume.postroad.registry.PostroadBlocks
import io.github.veelume.postroad.registry.PostroadMenus
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.DataSlot
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The depot screen's menu: one view, no tabs. The grid is a town's storage (paged through the
 * network once chartered); below it the player's inventory. In *select mode* a click on any
 * stack — inventory or grid — marks it instead of picking it up; *Send* ships the marked stacks
 * to the chosen destination. Dump/Take remain for plain chest use. Ints sync through data
 * slots, names and the selection through [DepotStatePayload]. The server instance holds the
 * network; the client instance only mirrors.
 */
class DepotMenu private constructor(
    id: Int,
    private val playerInventory: Inventory,
    val pos: BlockPos,
    private val server: ServerSide?,
) : AbstractContainerMenu(PostroadMenus.DEPOT.get(), id) {

    class ServerSide(val level: ServerLevel, val network: Network, val placeId: String)

    private val grid = DelegatingContainer(GRID)

    private val pageData: DataSlot = DataSlot.standalone()
    private val currentPageData: DataSlot = DataSlot.standalone()
    private val pageCountData: DataSlot = DataSlot.standalone()
    private val unlockedData: DataSlot = DataSlot.standalone()
    private val selectModeData: DataSlot = DataSlot.standalone()

    /** Name state and selection; server builds it, client receives it. */
    var state: DepotState = DepotState.EMPTY

    // server-only bookkeeping
    private var pages: List<String> = emptyList()
    private var destinations: List<String> = emptyList()
    private var destinationIndex = 0
    private val selected = LinkedHashSet<Int>()

    val page: Int get() = pageData.get()
    val currentPage: Int get() = currentPageData.get()
    val pageCount: Int get() = pageCountData.get()
    val unlocked: Boolean get() = unlockedData.get() == 1
    val selectMode: Boolean get() = selectModeData.get() == 1
    val playerName: String get() = playerInventory.player.gameProfile.name

    init {
        for (row in 0 until 6) for (col in 0 until 9) {
            addSlot(DepotSlot(this, grid, row * 9 + col, 8 + col * 18, 18 + row * 18))
        }
        for (row in 0 until 3) for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, INVENTORY_Y + row * 18))
        }
        for (col in 0 until 9) addSlot(Slot(playerInventory, col, 8 + col * 18, HOTBAR_Y))

        addDataSlot(pageData)
        addDataSlot(currentPageData)
        addDataSlot(pageCountData)
        addDataSlot(unlockedData)
        addDataSlot(selectModeData)

        if (server != null) {
            server.network.seenPlayer(playerName)
            refreshPages()
            val current = pages.indexOf(server.placeId).coerceAtLeast(0)
            currentPageData.set(current)
            pageData.set(current)
            destinationIndex = destinations.indexOf(defaultDestination()).coerceAtLeast(0)
            grid.target = server.network.storageFor(server.placeId)
        }
    }

    // ---- rules shared by both sides -------------------------------------------------------------

    /** The remote-unstackable rule, in a form both sides can evaluate. */
    fun isRemotePage(): Boolean = page != currentPage

    fun mayMove(stack: ItemStack): Boolean = !isRemotePage() || stack.isEmpty || stack.isStackable

    /** In select mode, clicks mark stacks instead of moving them. Runs on both sides. */
    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        if (selectMode && slotId in 0 until slots.size && clickType != ClickType.QUICK_CRAFT) {
            val s = server ?: return
            val slot = slots[slotId]
            if (!slot.hasItem()) return
            if (slotId < GRID && !slot.mayPickup(player)) return
            if (!selected.remove(slotId)) selected.add(slotId)
            sendState()
            return
        }
        super.clicked(slotId, button, clickType, player)
    }

    // ---- server side ----------------------------------------------------------------------------

    private fun defaultDestination(): String {
        val s = server ?: return ""
        return MailService.defaultDestination(s.network, playerName, s.placeId)
    }

    private fun refreshPages() {
        val s = server ?: return
        val here = s.placeId
        val byDistance = compareBy<String> { s.network.distanceBetween(here, it) }
        pages = s.network.towns().map { it.id }.sortedWith(compareBy<String> { it != here }.then(byDistance))
        val mailbox = s.network.mailboxOf(playerName)?.id
        val home = s.network.homeOf(playerName)?.id
        destinations = s.network.destinations().map { it.id }
            .filter { it != here }
            .sortedWith(compareBy<String> { it != mailbox }.thenBy { it != home }.then(byDistance))
        pageCountData.set(pages.size)
        unlockedData.set(if (s.network.postalUnlocked) 1 else 0)
    }

    private fun sendState() {
        val s = server ?: return
        val today = FreshLoot.dayOf(s.level)
        val lines = s.network.parcelsFor(playerName).map { parcel ->
            val town = s.network.places[parcel.to]?.name ?: parcel.to
            val key = if (parcel.isDue(today)) "command.postroad.mail.held" else "command.postroad.mail.transit"
            Component.translatable(key, parcel.items.sumOf { it.count }, town, parcel.arrivalDay - today).string
        }
        state = DepotState(
            townName = s.network.places[s.placeId]?.name ?: "",
            pageNames = pages.map { s.network.places[it]?.name ?: it },
            destinationNames = destinations.map { s.network.places[it]?.name ?: it },
            destinationIndex = destinationIndex,
            homeName = s.network.homeOf(playerName)?.name ?: "",
            transitLines = lines,
            selected = selected.toList(),
        )
        (playerInventory.player as? ServerPlayer)?.let { PacketDistributor.sendToPlayer(it, DepotStatePayload(state)) }
    }

    /** Vanilla calls this once the client has the screen open; anything sent from init is too early. */
    override fun sendAllDataToRemote() {
        super.sendAllDataToRemote()
        sendState()
    }

    /** Called from the serverbound payload handler; ignored on the client instance. */
    fun handleAction(action: Int, value: Int) {
        val s = server ?: return
        val player = playerInventory.player
        when (action) {
            ACTION_PAGE -> if (s.network.postalUnlocked && pages.isNotEmpty()) {
                pageData.set(Math.floorMod(page + value, pages.size))
                grid.target = s.network.storageFor(pages[page])
                selected.removeAll { it < GRID }
                sendState()
            }
            ACTION_DESTINATION -> if (destinations.isNotEmpty()) {
                destinationIndex = Math.floorMod(destinationIndex + value, destinations.size)
                sendState()
            }
            ACTION_SELECT_MODE -> {
                val on = !selectMode
                selectModeData.set(if (on) 1 else 0)
                if (!on) selected.clear()
                sendState()
            }
            ACTION_SELECT_LOOT -> {
                selectModeData.set(1)
                val today = FreshLoot.dayOf(s.level)
                for (index in 0 until slots.size) {
                    val slot = slots[index]
                    if (slot.hasItem() && FreshLoot.isFresh(slot.item, today) && (index >= GRID || slot.mayPickup(player))) selected.add(index)
                }
                sendState()
            }
            ACTION_SEND -> send(s, player)
            ACTION_DUMP -> dump(player)
            ACTION_TAKE_ALL -> takeAll(player)
            ACTION_SET_HOME -> {
                s.network.setHome(playerName, s.placeId)
                (player as? ServerPlayer)?.let { PostroadAdvancements.award(it, PostroadAdvancements.HOME_SET) }
                player.displayClientMessage(Component.translatable("command.postroad.home.set", state.townName), true)
                refreshPages()
                destinationIndex = destinations.indexOf(defaultDestination()).coerceAtLeast(0)
                sendState()
            }
        }
    }

    /** Ships the marked stacks to the chosen destination and clears the selection. */
    private fun send(s: ServerSide, player: Player) {
        if (!s.network.postalUnlocked) return
        val destination = destinations.getOrNull(destinationIndex) ?: return
        val indices = selected.filter { slots[it].hasItem() && (it >= GRID || slots[it].mayPickup(player)) }
        if (indices.isEmpty()) {
            player.displayClientMessage(Component.translatable("screen.postroad.depot.nothing_selected"), true)
            return
        }
        val items = indices.map { slots[it].item.copy() }
        val parcels = MailService.send(s.level.server, playerName, s.placeId, destination, items)
        indices.forEach { slots[it].set(ItemStack.EMPTY) }
        selected.clear()
        (player as? ServerPlayer)?.let { PostroadAdvancements.award(it, PostroadAdvancements.PARCEL_SENT) }
        val valuables = parcels.firstOrNull { it.lane == Parcel.LANE_VALUABLES }
        val town = s.network.places[destination]?.name ?: destination
        val count = items.sumOf { it.count }
        val message = if (valuables == null) {
            Component.translatable("screen.postroad.depot.sent_bulk", count, town)
        } else {
            Component.translatable("screen.postroad.depot.sent_valuables", count, town, valuables.arrivalDay - FreshLoot.dayOf(s.level))
        }
        player.displayClientMessage(message.withStyle(ChatFormatting.GOLD), false)
        broadcastChanges()
        sendState()
    }

    /** Moves the main inventory into the shown page, as far as it fits. */
    private fun dump(player: Player) {
        val s = server ?: return
        var moved = 0
        for (index in INVENTORY_START until INVENTORY_END) {
            val slot = slots[index]
            if (!slot.hasItem()) continue
            val before = slot.item.count
            if (moveItemStackTo(slot.item, 0, GRID, false)) {
                moved += before - slot.item.count
                if (slot.item.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
            }
        }
        selected.removeAll { it in INVENTORY_START until INVENTORY_END && !slots[it].hasItem() }
        val name = state.pageNames.getOrElse(page) { s.network.places[s.placeId]?.name ?: "" }
        player.displayClientMessage(Component.translatable("screen.postroad.depot.dumped", moved, name), true)
        broadcastChanges()
        sendState()
    }

    /** Moves the shown page into the player's inventory, as far as it fits and the rules allow. */
    private fun takeAll(player: Player) {
        var moved = 0
        for (index in 0 until GRID) {
            val slot = slots[index]
            if (!slot.hasItem() || !slot.mayPickup(player)) continue
            val before = slot.item.count
            if (moveItemStackTo(slot.item, GRID, slots.size, true)) {
                moved += before - slot.item.count
                if (slot.item.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
            }
        }
        selected.removeAll { it < GRID && !slots[it].hasItem() }
        player.displayClientMessage(Component.translatable("screen.postroad.depot.took", moved), true)
        broadcastChanges()
        sendState()
    }

    // ---- vanilla plumbing -----------------------------------------------------------------------

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val copy = stack.copy()
        if (index < GRID) {
            if (!moveItemStackTo(stack, GRID, slots.size, true)) return ItemStack.EMPTY
        } else {
            if (!moveItemStackTo(stack, 0, GRID, false)) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return copy
    }

    override fun stillValid(player: Player): Boolean {
        val level = player.level()
        if (!level.getBlockState(pos).`is`(PostroadBlocks.DEPOT.get())) return false
        return player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= 64.0
    }

    companion object {
        const val GRID = 54
        const val INVENTORY_START = GRID
        const val INVENTORY_END = GRID + 27
        const val INVENTORY_Y = 183
        const val HOTBAR_Y = 241

        const val ACTION_PAGE = 1
        const val ACTION_DESTINATION = 3
        const val ACTION_SEND = 4
        const val ACTION_SET_HOME = 5
        const val ACTION_DUMP = 6
        const val ACTION_TAKE_ALL = 8
        const val ACTION_SELECT_MODE = 10
        const val ACTION_SELECT_LOOT = 11

        fun client(id: Int, inventory: Inventory, buf: RegistryFriendlyByteBuf): DepotMenu =
            DepotMenu(id, inventory, buf.readBlockPos(), null)

        fun server(id: Int, inventory: Inventory, level: ServerLevel, pos: BlockPos, placeId: String): DepotMenu =
            DepotMenu(id, inventory, pos, ServerSide(level, Network.get(level.server), placeId))
    }
}

/** A grid slot that obeys the remote-unstackable rule. */
class DepotSlot(private val menu: DepotMenu, container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
    override fun mayPlace(stack: ItemStack): Boolean = menu.mayMove(stack)
    override fun mayPickup(player: Player): Boolean = menu.mayMove(item)
}

/** Fixed-size container whose backing store can be swapped; slots beyond the target's size read empty. */
class DelegatingContainer(private val size: Int) : Container {
    var target: Container = SimpleContainer(size)

    private fun inRange(slot: Int) = slot in 0 until target.containerSize

    override fun getContainerSize(): Int = size
    override fun isEmpty(): Boolean = target.isEmpty
    override fun getItem(slot: Int): ItemStack = if (inRange(slot)) target.getItem(slot) else ItemStack.EMPTY
    override fun removeItem(slot: Int, amount: Int): ItemStack = if (inRange(slot)) target.removeItem(slot, amount) else ItemStack.EMPTY
    override fun removeItemNoUpdate(slot: Int): ItemStack = if (inRange(slot)) target.removeItemNoUpdate(slot) else ItemStack.EMPTY
    override fun setItem(slot: Int, stack: ItemStack) {
        if (inRange(slot)) target.setItem(slot, stack)
    }
    override fun setChanged() = target.setChanged()
    override fun stillValid(player: Player): Boolean = true
    override fun clearContent() = target.clearContent()
}
