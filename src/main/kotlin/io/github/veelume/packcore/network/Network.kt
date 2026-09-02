package io.github.veelume.packcore.network

import io.github.veelume.packcore.PackcoreConfig
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
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import java.util.function.BiFunction
import java.util.function.Supplier

/**
 * The one server-side state object of the civilization layer: places, depots, town storage,
 * accounts and the ledger. Stored on the overworld as `packcore_network`.
 */
class Network : SavedData() {
    val places: MutableMap<String, Place> = LinkedHashMap()

    /** Depot key ([depotKey]) → place id. */
    val depots: MutableMap<String, String> = HashMap()

    private val storage: MutableMap<String, TownContainer> = HashMap()

    /** Account id ("player:<name>", "fund:road") → balance in coins. */
    val accounts: MutableMap<String, Long> = HashMap()

    val ledger: ArrayDeque<LedgerEntry> = ArrayDeque()

    // ---- places -----------------------------------------------------------------------------

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

    // ---- storage ----------------------------------------------------------------------------

    fun storageFor(placeId: String): SimpleContainer =
        storage.getOrPut(placeId) { TownContainer(this) }

    // ---- accounts and ledger ----------------------------------------------------------------

    fun balance(account: String): Long = accounts[account] ?: 0L

    /** Credit [amount] coins to [account] and record it. Amounts are always positive here. */
    fun credit(account: String, amount: Long, day: Long, actor: String, op: String, note: String = "") {
        require(amount > 0) { "credit amount must be positive" }
        accounts[account] = balance(account) + amount
        record(LedgerEntry(day, actor, op, amount, account, note))
    }

    fun record(entry: LedgerEntry) {
        ledger.addLast(entry)
        val max = PackcoreConfig.ledgerMaxEntries
        while (ledger.size > max) ledger.removeFirst()
        setDirty()
    }

    // ---- persistence ------------------------------------------------------------------------

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
            val container = TownContainer(this)
            ContainerHelper.loadAllItems(c, container.items, registries)
            storage[c.getString("Place")] = container
        }
        val accountsTag = tag.getCompound("Accounts")
        accountsTag.allKeys.forEach { accounts[it] = accountsTag.getLong(it) }
        tag.getList("Ledger", Tag.TAG_COMPOUND.toInt()).forEach { t ->
            ledger.addLast(LedgerEntry.fromTag(t as CompoundTag))
        }
    }

    companion object {
        const val NAME = "packcore_network"
        const val ROAD_FUND = "fund:road"

        fun playerAccount(name: String): String = "player:$name"

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

/** Town storage; marks the network dirty on every change so it is saved with the world. */
class TownContainer(private val network: Network) : SimpleContainer(SIZE) {
    override fun setChanged() {
        super.setChanged()
        network.setDirty()
    }

    companion object {
        const val SIZE = 54
    }
}

fun ResourceLocation.asDimensionKey(): ResourceKey<Level> = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, this)
