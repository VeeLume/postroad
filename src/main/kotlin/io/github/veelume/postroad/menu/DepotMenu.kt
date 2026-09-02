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
import net.minecraft.world.inventory.DataSlot
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The depot screen's menu. One fixed 6×9 grid whose backing container is swapped per tab:
 * a town's storage (paged through the network once chartered) or the send outbox. Ints (tab, page, …) sync through
 * data slots; names through [DepotStatePayload]. The server instance holds the network; the
 * client instance only mirrors.
 */
class DepotMenu private constructor(
    id: Int,
    private val playerInventory: Inventory,
    val pos: BlockPos,
    private val server: ServerSide?,
) : AbstractContainerMenu(PostroadMenus.DEPOT.get(), id) {

    class ServerSide(val level: ServerLevel, val network: Network, val placeId: String)

    enum class Tab(val activeSlots: Int) { STORAGE(GRID), SEND(9) }

    private val grid = DelegatingContainer(GRID)
    private val outbox = SimpleContainer(9)

    private val tabData: DataSlot = DataSlot.standalone()
    private val pageData: DataSlot = DataSlot.standalone()
    private val currentPageData: DataSlot = DataSlot.standalone()
    private val pageCountData: DataSlot = DataSlot.standalone()
    private val unlockedData: DataSlot = DataSlot.standalone()

    /** Name state; server builds it, client receives it. */
    var state: DepotState = DepotState.EMPTY

    // server-only bookkeeping
    private var pages: List<String> = emptyList()
    private var destinations: List<String> = emptyList()
    private var destinationIndex = 0

    val tab: Tab get() = Tab.entries[tabData.get().coerceIn(0, Tab.entries.size - 1)]
    val page: Int get() = pageData.get()
    val currentPage: Int get() = currentPageData.get()
    val pageCount: Int get() = pageCountData.get()
    val unlocked: Boolean get() = unlockedData.get() == 1
    val activeSlots: Int get() = tab.activeSlots
    val playerName: String get() = playerInventory.player.gameProfile.name

    init {
        for (row in 0 until 6) for (col in 0 until 9) {
            addSlot(DepotSlot(this, grid, row * 9 + col, 8 + col * 18, 18 + row * 18))
        }
        for (row in 0 until 3) for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 139 + row * 18))
        }
        for (col in 0 until 9) addSlot(Slot(playerInventory, col, 8 + col * 18, 197))

        addDataSlot(tabData)
        addDataSlot(pageData)
        addDataSlot(currentPageData)
        addDataSlot(pageCountData)
        addDataSlot(unlockedData)

        if (server != null) {
            server.network.seenPlayer(playerName)
            refreshPages()
            val current = pages.indexOf(server.placeId).coerceAtLeast(0)
            currentPageData.set(current)
            pageData.set(current)
            destinationIndex = destinations.indexOf(MailService.defaultDestination(server.network, playerName, server.placeId)).coerceAtLeast(0)
            applyTab(Tab.STORAGE)
        }
    }

    // ---- rules shared by both sides -------------------------------------------------------------

    /** The remote-unstackable rule, in a form both sides can evaluate. */
    fun isRemotePage(): Boolean = tab == Tab.STORAGE && page != currentPage

    fun mayMove(stack: ItemStack): Boolean = !isRemotePage() || stack.isEmpty || stack.isStackable

    // ---- server side ----------------------------------------------------------------------------

    private fun refreshPages() {
        val s = server ?: return
        pages = s.network.towns().map { it.id }
        destinations = s.network.destinations().map { it.id }
        pageCountData.set(pages.size)
        unlockedData.set(if (s.network.postalUnlocked) 1 else 0)
    }

    private fun applyTab(newTab: Tab) {
        val s = server ?: return
        val effective = if (newTab == Tab.SEND && !s.network.postalUnlocked) Tab.STORAGE else newTab
        tabData.set(effective.ordinal)
        if (!s.network.postalUnlocked) pageData.set(currentPage)
        grid.target = when (effective) {
            Tab.STORAGE -> s.network.storageFor(pages.getOrElse(page) { s.placeId })
            Tab.SEND -> outbox
        }
        sendState()
    }

    private fun sendState() {
        val s = server ?: return
        val today = FreshLoot.dayOf(s.level)
        val lines = s.network.parcelsFor(playerName).map { parcel ->
            val town = s.network.places[parcel.to]?.name ?: parcel.to
            val key = if (parcel.isDue(today)) "command.postroad.mail.held" else "command.postroad.mail.transit"
            Component.translatable(key, parcel.items.size, town, parcel.arrivalDay - today).string
        }
        state = DepotState(
            townName = s.network.places[s.placeId]?.name ?: "",
            pageNames = pages.map { s.network.places[it]?.name ?: it },
            destinationNames = destinations.map { s.network.places[it]?.name ?: it },
            destinationIndex = destinationIndex,
            homeName = s.network.homeOf(playerName)?.name ?: "",
            transitLines = lines,
        )
        (playerInventory.player as? ServerPlayer)?.let { PacketDistributor.sendToPlayer(it, DepotStatePayload(state)) }
    }

    /** Called from the serverbound payload handler; ignored on the client instance. */
    fun handleAction(action: Int, value: Int) {
        val s = server ?: return
        val player = playerInventory.player
        when (action) {
            ACTION_TAB -> applyTab(Tab.entries.getOrElse(value) { Tab.STORAGE })
            ACTION_PAGE -> if (tab == Tab.STORAGE && s.network.postalUnlocked && pages.isNotEmpty()) {
                pageData.set(Math.floorMod(page + value, pages.size))
                grid.target = s.network.storageFor(pages[page])
                sendState()
            }
            ACTION_DESTINATION -> if (destinations.isNotEmpty()) {
                destinationIndex = Math.floorMod(destinationIndex + value, destinations.size)
                sendState()
            }
            ACTION_SEND -> send(s, player)
            ACTION_SET_HOME -> {
                s.network.setHome(playerName, s.placeId)
                (player as? ServerPlayer)?.let { PostroadAdvancements.award(it, PostroadAdvancements.HOME_SET) }
                player.displayClientMessage(Component.translatable("command.postroad.home.set", state.townName), true)
                sendState()
            }
        }
    }

    private fun send(s: ServerSide, player: Player) {
        if (tab != Tab.SEND || !s.network.postalUnlocked) return
        val items = (0 until outbox.containerSize).map { outbox.getItem(it) }.filter { !it.isEmpty }
        if (items.isEmpty()) {
            player.displayClientMessage(Component.translatable("screen.postroad.depot.outbox_empty"), true)
            return
        }
        val destination = destinations.getOrNull(destinationIndex) ?: return
        if (destination == s.placeId) {
            player.displayClientMessage(Component.translatable("screen.postroad.depot.same_town"), true)
            return
        }
        val parcels = MailService.send(s.level.server, playerName, s.placeId, destination, items)
        outbox.clearContent()
        (player as? ServerPlayer)?.let { PostroadAdvancements.award(it, PostroadAdvancements.PARCEL_SENT) }
        val valuables = parcels.firstOrNull { it.lane == Parcel.LANE_VALUABLES }
        val town = s.network.places[destination]?.name ?: destination
        val message = if (valuables == null) {
            Component.translatable("screen.postroad.depot.sent_bulk", town)
        } else {
            Component.translatable("screen.postroad.depot.sent_valuables", town, valuables.arrivalDay - FreshLoot.dayOf(s.level))
        }
        player.displayClientMessage(message.withStyle(ChatFormatting.GOLD), false)
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
            if (!moveItemStackTo(stack, 0, activeSlots, false)) return ItemStack.EMPTY
        }
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return copy
    }

    override fun stillValid(player: Player): Boolean {
        val level = player.level()
        if (!level.getBlockState(pos).`is`(PostroadBlocks.DEPOT.get())) return false
        return player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= 64.0
    }

    override fun removed(player: Player) {
        super.removed(player)
        if (server != null) clearContainer(player, outbox)
    }

    companion object {
        const val GRID = 54

        const val ACTION_TAB = 0
        const val ACTION_PAGE = 1
        const val ACTION_DESTINATION = 3
        const val ACTION_SEND = 4
        const val ACTION_SET_HOME = 5

        fun client(id: Int, inventory: Inventory, buf: RegistryFriendlyByteBuf): DepotMenu =
            DepotMenu(id, inventory, buf.readBlockPos(), null)

        fun server(id: Int, inventory: Inventory, level: ServerLevel, pos: BlockPos, placeId: String): DepotMenu =
            DepotMenu(id, inventory, pos, ServerSide(level, Network.get(level.server), placeId))
    }
}

/** A grid slot that is only active for the current tab and obeys the remote-unstackable rule. */
class DepotSlot(private val menu: DepotMenu, container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
    override fun isActive(): Boolean = containerSlot < menu.activeSlots
    override fun mayPlace(stack: ItemStack): Boolean = isActive && menu.mayMove(stack)
    override fun mayPickup(player: Player): Boolean = isActive && menu.mayMove(item)
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
