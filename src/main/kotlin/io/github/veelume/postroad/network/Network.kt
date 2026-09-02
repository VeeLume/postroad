package io.github.veelume.postroad.network

import io.github.veelume.postroad.PostroadConfig
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.ContainerHelper
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import java.util.function.BiFunction
import java.util.function.Supplier
import kotlin.math.sqrt

/**
 * The one server-side state object of the civilization layer: places, depots, town storage,
 * accounts, ledger, and — since increment 2 — the postal network: homes and parcels.
 * Stored on the overworld as `postroad_network`.
 */
class Network : SavedData() {
    val places: MutableMap<String, Place> = LinkedHashMap()

    /** Depot key ([depotKey]) → place id. */
    val depots: MutableMap<String, String> = HashMap()

    /** Town storage per place; after the postal unlock these are the pages of the network storage. */
    private val storage: MutableMap<String, TownContainer> = HashMap()

    /** Account id ("player:<name>", "fund:road") → balance in coins. */
    val accounts: MutableMap<String, Long> = HashMap()

    val ledger: ArrayDeque<LedgerEntry> = ArrayDeque()

    // ---- postal network -----------------------------------------------------------------------

    var postalUnlocked: Boolean = false
        set(value) {
            field = value
            setDirty()
        }

    /** player (lower-case) → home place id. */
    val homes: MutableMap<String, String> = HashMap()

    /** player (lower-case) → last seen cased name; everyone who ever used a depot. */
    val knownPlayers: MutableMap<String, String> = LinkedHashMap()

    val parcels: MutableList<Parcel> = ArrayList()

    /** Mailbox place id → contents. Lives here so parcels arrive while the chunk is unloaded. */
    private val mailboxes: MutableMap<String, TownContainer> = HashMap()

    var lastDeliveryDay: Long = -1L
        set(value) {
            field = value
            setDirty()
        }

    // ---- places -------------------------------------------------------------------------------

    fun addPlace(place: Place) {
        places[place.id] = place
        setDirty()
    }

    fun placeOfDepot(dimension: ResourceKey<Level>, pos: BlockPos): Place? =
        depots[depotKey(dimension, pos)]?.let { places[it] }

    fun bindDepot(dimension: ResourceKey<Level>, pos: BlockPos, placeId: String) {
        depots[depotKey(dimension, pos)] = placeId
        setDirty()
    }

    fun hasDepot(placeId: String): Boolean = depots.values.contains(placeId)

    /** Places that have a depot, in discovery order. */
    fun towns(): List<Place> = places.values.filter { hasDepot(it.id) }

    /** Everything a parcel can be sent to: towns, then player mailboxes. */
    fun destinations(): List<Place> = towns() + places.values.filter { it.type == Place.TYPE_MAILBOX }

    fun isDestination(placeId: String): Boolean = hasDepot(placeId) || places[placeId]?.type == Place.TYPE_MAILBOX

    /** The container a parcel to [placeId] lands in. */
    fun deliveryTarget(placeId: String): SimpleContainer =
        if (places[placeId]?.type == Place.TYPE_MAILBOX) mailboxStorage(placeId) else storageFor(placeId)

    fun mailboxOf(player: String): Place? =
        places.values.firstOrNull { it.type == Place.TYPE_MAILBOX && it.owner == player.lowercase() }

    fun placeByName(name: String): Place? = places.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Horizontal distance between two places' anchors, in blocks. */
    fun distanceBetween(a: String, b: String): Double {
        val pa = places[a]?.pos ?: return 0.0
        val pb = places[b]?.pos ?: return 0.0
        val dx = (pa.x - pb.x).toDouble()
        val dz = (pa.z - pb.z).toDouble()
        return sqrt(dx * dx + dz * dz)
    }

    // ---- storage ------------------------------------------------------------------------------

    fun storageFor(placeId: String): SimpleContainer =
        storage.getOrPut(placeId) { TownContainer(this, TownContainer.TOWN_SIZE) }

    /**
     * The remote-unstackable rule: an unstackable may only be taken from, or put into, the page
     * of the town the player is standing in. Stackables move freely once the network is unlocked.
     */
    fun mayMoveRemotely(stack: ItemStack): Boolean = stack.isEmpty || stack.isStackable

    fun mayAccessPage(currentPlaceId: String, pageId: String): Boolean =
        currentPlaceId == pageId || postalUnlocked

    // ---- homes and players --------------------------------------------------------------------

    fun seenPlayer(name: String) {
        val key = name.lowercase()
        if (knownPlayers[key] != name) {
            knownPlayers[key] = name
            setDirty()
        }
    }

    fun setHome(player: String, placeId: String) {
        homes[player.lowercase()] = placeId
        setDirty()
    }

    fun homeOf(player: String): Place? = homes[player.lowercase()]?.let { places[it] }

    // ---- mailboxes ----------------------------------------------------------------------------

    fun mailboxStorage(placeId: String): SimpleContainer =
        mailboxes.getOrPut(placeId) { TownContainer(this, TownContainer.MAILBOX_SIZE) }

    /** Removes a mailbox place; returns its contents. Parcels on the way are redirected. */
    fun removeMailbox(placeId: String): SimpleContainer {
        val place = places.remove(placeId)
        val contents = mailboxes.remove(placeId) ?: TownContainer(this, TownContainer.MAILBOX_SIZE)
        val fallbackHome = place?.owner?.let { homes[it] }
        for (parcel in parcels) {
            if (parcel.to == placeId) {
                parcel.to = fallbackHome?.takeIf { hasDepot(it) } ?: parcel.from
            }
        }
        setDirty()
        return contents
    }

    // ---- parcels ------------------------------------------------------------------------------

    fun addParcel(parcel: Parcel) {
        parcels.add(parcel)
        setDirty()
    }

    /** Parcels [player] sent that are still on the way or held. */
    fun parcelsFor(player: String): List<Parcel> {
        val key = player.lowercase()
        return parcels.filter { it.sender == key }
    }

    // ---- accounts and ledger ------------------------------------------------------------------

    fun balance(account: String): Long = accounts[normalizeAccount(account)] ?: 0L

    /** Credit [amount] coins to [account] and record it. Amounts are always positive here. */
    fun credit(account: String, amount: Long, day: Long, actor: String, op: String, note: String = "") {
        require(amount > 0) { "credit amount must be positive" }
        val id = normalizeAccount(account)
        accounts[id] = balance(id) + amount
        record(LedgerEntry(day, actor, op, amount, id, note))
    }

    fun record(entry: LedgerEntry) {
        ledger.addLast(entry)
        val max = PostroadConfig.ledgerMaxEntries
        while (ledger.size > max) ledger.removeFirst()
        setDirty()
    }

    // ---- persistence --------------------------------------------------------------------------

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        tag.put("Places", ListTag().also { list -> places.values.forEach { list.add(it.toTag()) } })

        tag.put("Depots", ListTag().also { list ->
            depots.forEach { (key, placeId) ->
                list.add(CompoundTag().apply { putString("Key", key); putString("Place", placeId) })
            }
        })

        tag.put("Storage", ListTag().also { list ->
            storage.forEach { (placeId, container) ->
                if (!container.isEmpty) {
                    val entry = CompoundTag()
                    entry.putString("Place", placeId)
                    ContainerHelper.saveAllItems(entry, container.items, registries)
                    list.add(entry)
                }
            }
        })

        tag.put("Accounts", CompoundTag().also { accounts.forEach { (id, bal) -> it.putLong(id, bal) } })
        tag.put("Ledger", ListTag().also { list -> ledger.forEach { list.add(it.toTag()) } })

        tag.putBoolean("PostalUnlocked", postalUnlocked)
        tag.putLong("LastDeliveryDay", lastDeliveryDay)
        tag.put("Homes", CompoundTag().also { homes.forEach { (p, place) -> it.putString(p, place) } })
        tag.put("KnownPlayers", CompoundTag().also { knownPlayers.forEach { (k, v) -> it.putString(k, v) } })
        tag.put("Parcels", ListTag().also { list -> parcels.forEach { if (it.items.isNotEmpty()) list.add(it.toTag(registries)) } })
        tag.put("Mailboxes", ListTag().also { list ->
            mailboxes.forEach { (placeId, container) ->
                if (!container.isEmpty) {
                    val entry = CompoundTag()
                    entry.putString("Place", placeId)
                    ContainerHelper.saveAllItems(entry, container.items, registries)
                    list.add(entry)
                }
            }
        })
        return tag
    }

    private fun load(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.getList("Places", Tag.TAG_COMPOUND.toInt()).forEach { t ->
            Place.fromTag(t as CompoundTag)?.let { places[it.id] = it }
        }
        tag.getList("Depots", Tag.TAG_COMPOUND.toInt()).forEach { t ->
            val c = t as CompoundTag
            depots[c.getString("Key")] = c.getString("Place")
        }
        tag.getList("Storage", Tag.TAG_COMPOUND.toInt()).forEach { t ->
            val c = t as CompoundTag
            val container = TownContainer(this, TownContainer.TOWN_SIZE)
            ContainerHelper.loadAllItems(c, container.items, registries)
            storage[c.getString("Place")] = container
        }
        val accountsTag = tag.getCompound("Accounts")
        accountsTag.allKeys.forEach { key ->
            // Account ids are case-insensitive; fold older mixed-case player ids together.
            val id = normalizeAccount(key)
            accounts[id] = (accounts[id] ?: 0L) + accountsTag.getLong(key)
        }
        tag.getList("Ledger", Tag.TAG_COMPOUND.toInt()).forEach { t ->
            ledger.addLast(LedgerEntry.fromTag(t as CompoundTag))
        }

        postalUnlocked = tag.getBoolean("PostalUnlocked")
        lastDeliveryDay = if (tag.contains("LastDeliveryDay")) tag.getLong("LastDeliveryDay") else -1L
        val homesTag = tag.getCompound("Homes")
        homesTag.allKeys.forEach { homes[it] = homesTag.getString(it) }
        val knownTag = tag.getCompound("KnownPlayers")
        knownTag.allKeys.forEach { knownPlayers[it] = knownTag.getString(it) }
        tag.getList("Parcels", Tag.TAG_COMPOUND.toInt()).forEach { t ->
            Parcel.fromTag(t as CompoundTag, registries)?.let { if (it.items.isNotEmpty()) parcels.add(it) }
        }
        tag.getList("Mailboxes", Tag.TAG_COMPOUND.toInt()).forEach { t ->
            val c = t as CompoundTag
            val container = TownContainer(this, TownContainer.MAILBOX_SIZE)
            ContainerHelper.loadAllItems(c, container.items, registries)
            mailboxes[c.getString("Place")] = container
        }
    }

    companion object {
        const val NAME = "postroad_network"
        const val ROAD_FUND = "fund:road"

        /** Player names are matched case-insensitively (`player:veelume`). */
        fun playerAccount(name: String): String = "player:${name.lowercase()}"

        fun normalizeAccount(id: String): String = id.lowercase()

        fun depotKey(dimension: ResourceKey<Level>, pos: BlockPos): String =
            "${dimension.location()}|${pos.asLong()}"

        val FACTORY: Factory<Network> = Factory(
            Supplier { Network() },
            BiFunction { tag, registries -> Network().also { it.load(tag, registries) } },
            null,
        )

        fun get(server: MinecraftServer): Network =
            server.overworld().dataStorage.computeIfAbsent(FACTORY, NAME)
    }
}

/** A container owned by the network; marks it dirty on every change so it is saved with the world. */
class TownContainer(private val network: Network, size: Int) : SimpleContainer(size) {
    override fun setChanged() {
        super.setChanged()
        network.setDirty()
    }

    companion object {
        const val TOWN_SIZE = 54
        const val MAILBOX_SIZE = 27
    }
}

fun ResourceLocation.asDimensionKey(): ResourceKey<Level> = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, this)
