package io.github.veelume.postroad.network

import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.roads.PathLink
import io.github.veelume.postroad.roads.RoadNode
import io.github.veelume.postroad.roads.RoadPath
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
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

    // ---- roads --------------------------------------------------------------------------------

    val paths: MutableMap<String, RoadPath> = LinkedHashMap()
    val links: MutableList<PathLink> = ArrayList()
    val nodes: MutableMap<String, RoadNode> = LinkedHashMap()

    /** Dimension → chunk → paths passing within [pathIndexReach] blocks of it; dropped whenever [paths] changes. */
    private var pathIndex: Map<ResourceLocation, Long2ObjectOpenHashMap<MutableList<RoadPath>>>? = null
    private var pathIndexReach = 0

    fun addPath(path: RoadPath) {
        paths[path.id] = path
        pathIndex = null
        setDirty()
    }

    /** Whether [pos] is within [reach] blocks of any path in [dimension], generated or charted. */
    fun nearRoad(dimension: ResourceLocation, pos: BlockPos, reach: Int): Boolean {
        val index = pathIndex.takeIf { pathIndexReach == reach } ?: buildPathIndex(reach)
        val candidates = index[dimension]?.get(ChunkPos.asLong(pos.x shr 4, pos.z shr 4)) ?: return false
        return candidates.any { it.passesNear(pos, reach.toDouble(), ROAD_RISE) }
    }

    private fun buildPathIndex(reach: Int): Map<ResourceLocation, Long2ObjectOpenHashMap<MutableList<RoadPath>>> {
        val index = HashMap<ResourceLocation, Long2ObjectOpenHashMap<MutableList<RoadPath>>>()
        for (path in paths.values) {
            val chunks = index.getOrPut(path.dimension) { Long2ObjectOpenHashMap() }
            for (i in path.points.indices) {
                val a = path.points[i]; val b = path.points[minOf(i + 1, path.points.size - 1)]
                for (cx in ((minOf(a.x, b.x) - reach) shr 4)..((maxOf(a.x, b.x) + reach) shr 4))
                    for (cz in ((minOf(a.z, b.z) - reach) shr 4)..((maxOf(a.z, b.z) + reach) shr 4)) {
                        val key = ChunkPos.asLong(cx, cz)
                        val list = chunks.get(key) ?: ArrayList<RoadPath>(2).also { chunks.put(key, it) }
                        if (list.lastOrNull() !== path && path !in list) list.add(path)
                    }
            }
        }
        pathIndex = index
        pathIndexReach = reach
        return index
    }

    fun addLink(link: PathLink) {
        links.add(link)
        setDirty()
    }

    /** Nearest point of any path in [dimension] within [maxDistance] blocks of [pos]. */
    fun nearestPathPoint(dimension: ResourceLocation, pos: BlockPos, maxDistance: Double, exclude: String? = null): Pair<RoadPath, Int>? {
        var best: Pair<RoadPath, Int>? = null
        var bestDist = maxDistance
        for (path in paths.values) {
            if (path.dimension != dimension || path.id == exclude) continue
            val (idx, d) = path.nearestIndex(pos) ?: continue
            if (d <= bestDist) {
                bestDist = d
                best = path to idx
            }
        }
        return best
    }

    /** Every town whose depot lies within [maxDistance] of the path gets (or keeps) a node on it; returns those that got one. */
    fun attachTowns(path: RoadPath, maxDistance: Double): List<Place> {
        val attached = ArrayList<Place>()
        for (place in towns()) {
            if (place.dimension != path.dimension) continue
            val depotPos = depotPos(place.id) ?: place.pos
            val (idx, d) = path.nearestIndex(depotPos) ?: continue
            if (d > maxDistance) continue
            val id = "town/${place.id}"
            if (nodes[id] == null) {
                nodes[id] = RoadNode(id, RoadNode.KIND_TOWN, path.dimension, depotPos, path.id, idx, place.name, place.id)
                attached.add(place)
            }
        }
        if (attached.isNotEmpty()) setDirty()
        return attached
    }

    // ---- travel -------------------------------------------------------------------------------

    /** Player → the overworld day time (ticks) at which their next free journey is ready. */
    private val freeJourneyAt: MutableMap<String, Long> = HashMap()

    fun freeJourneyAt(player: String): Long = freeJourneyAt[player.lowercase()] ?: 0L

    fun setFreeJourneyAt(player: String, dayTime: Long) {
        freeJourneyAt[player.lowercase()] = dayTime
        setDirty()
    }

    // ---- survey -------------------------------------------------------------------------------

    /**
     * Where the network has paid a surveyor: [SURVEY_CELL]-block cells per dimension, and town ids. Kept
     * when paths go, so a road removed (or replanned) and charted again is not paid twice.
     */
    private val surveyed: MutableMap<ResourceLocation, LongOpenHashSet> = HashMap()
    val surveyedTowns: MutableSet<String> = HashSet()

    /** Blocks of new road a player has charted beyond their last whole survey coin. */
    private val surveyCarry: MutableMap<String, Double> = HashMap()

    fun surveyCarry(player: String): Double = surveyCarry[player.lowercase()] ?: 0.0

    fun setSurveyCarry(player: String, blocks: Double) {
        surveyCarry[player.lowercase()] = blocks
        setDirty()
    }

    /** Whether [pos] lies in or next to a cell the network has already paid for. */
    fun isSurveyed(dimension: ResourceLocation, pos: BlockPos): Boolean {
        val cells = surveyed[dimension] ?: return false
        val cx = Math.floorDiv(pos.x, SURVEY_CELL); val cz = Math.floorDiv(pos.z, SURVEY_CELL)
        for (dx in -1..1) for (dz in -1..1) if (cells.contains(ChunkPos.asLong(cx + dx, cz + dz))) return true
        return false
    }

    fun markSurveyed(dimension: ResourceLocation, points: Collection<BlockPos>) {
        if (points.isEmpty()) return
        val cells = surveyed.getOrPut(dimension) { LongOpenHashSet() }
        for (p in points) cells.add(ChunkPos.asLong(Math.floorDiv(p.x, SURVEY_CELL), Math.floorDiv(p.z, SURVEY_CELL)))
        setDirty()
    }

    fun removePath(pathId: String) {
        paths.remove(pathId) ?: return
        pathIndex = null
        links.removeAll { it.pathA == pathId || it.pathB == pathId }
        nodes.values.removeAll { it.pathId == pathId }
        setDirty()
    }

    /** Splits a path at [index] into two; links and nodes keep their places. */
    fun severPath(pathId: String, index: Int): Boolean {
        val path = paths[pathId] ?: return false
        if (index <= 0 || index >= path.points.size - 1) return false
        val tail = RoadPath(path.id + "b", path.dimension, path.points.subList(index, path.points.size).toMutableList(),
            path.tiers.subList(index, path.tiers.size).toMutableList(), path.recordedBy, path.recordedDay)
        val head = RoadPath(path.id, path.dimension, path.points.subList(0, index + 1).toMutableList(),
            path.tiers.subList(0, index + 1).toMutableList(), path.recordedBy, path.recordedDay)
        paths[head.id] = head
        paths[tail.id] = tail
        pathIndex = null
        val relinked = links.map { l ->
            var a = l.pathA; var ia = l.indexA; var b = l.pathB; var ib = l.indexB
            if (a == pathId && ia > index) { a = tail.id; ia -= index }
            if (b == pathId && ib > index) { b = tail.id; ib -= index }
            PathLink(a, ia, b, ib)
        }
        links.clear(); links.addAll(relinked)
        val moved = nodes.values.filter { it.pathId == pathId && it.pointIndex > index }
        for (n in moved) nodes[n.id] = n.copy(pathId = tail.id, pointIndex = n.pointIndex - index)
        setDirty()
        return true
    }

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

    /** Position of the first depot bound to [placeId], if any. */
    fun depotPos(placeId: String): BlockPos? =
        depots.entries.firstOrNull { it.value == placeId }?.key?.substringAfter('|')?.toLongOrNull()?.let { BlockPos.of(it) }

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

    /** Every stack the network holds: town storage, mailboxes, parcels on the way. Mutate in place, then [setDirty]. */
    fun storedStacks(): Sequence<ItemStack> =
        (storage.values.asSequence() + mailboxes.values.asSequence()).flatMap { it.items.asSequence() } +
            parcels.asSequence().flatMap { it.items.asSequence() }

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

    /** Mailbox owners with mail they have not looked at yet; cleared when they open their mailbox. */
    private val unreadMail: MutableSet<String> = HashSet()

    fun hasUnreadMail(player: String): Boolean = player.lowercase() in unreadMail

    fun setUnreadMail(player: String, unread: Boolean) {
        val changed = if (unread) unreadMail.add(player.lowercase()) else unreadMail.remove(player.lowercase())
        if (changed) setDirty()
    }

    /** Players who switched the mail chime off at their mailbox; the chat line still comes. */
    private val mailChimeOff: MutableSet<String> = HashSet()

    fun mailChime(player: String): Boolean = player.lowercase() !in mailChimeOff

    fun setMailChime(player: String, on: Boolean) {
        val changed = if (on) mailChimeOff.remove(player.lowercase()) else mailChimeOff.add(player.lowercase())
        if (changed) setDirty()
    }

    // ---- parcels ------------------------------------------------------------------------------

    fun addParcel(parcel: Parcel) {
        parcels.add(parcel)
        setDirty()
    }

    /** Parcels on the way to, or held at, [placeId]. */
    fun parcelsTo(placeId: String): List<Parcel> = parcels.filter { it.to == placeId }

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

    /**
     * Takes [amount] coins for [player]: wallet first, the road fund for the rest. Nothing moves
     * unless the two together cover it. Debits are recorded with negative amounts.
     */
    fun charge(player: String, amount: Long, day: Long, op: String, note: String = ""): Boolean {
        if (amount <= 0) return true
        val wallet = playerAccount(player)
        val fromWallet = minOf(balance(wallet), amount)
        val fromFund = amount - fromWallet
        if (fromFund > balance(ROAD_FUND)) return false
        if (fromWallet > 0) {
            accounts[wallet] = balance(wallet) - fromWallet
            record(LedgerEntry(day, player, op, -fromWallet, wallet, note))
        }
        if (fromFund > 0) {
            accounts[ROAD_FUND] = balance(ROAD_FUND) - fromFund
            record(LedgerEntry(day, player, op, -fromFund, ROAD_FUND, note))
        }
        return true
    }

    fun canAfford(player: String, amount: Long): Boolean =
        amount <= 0 || balance(playerAccount(player)) + balance(ROAD_FUND) >= amount

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
        tag.put("Surveyed", CompoundTag().also { surveyed.forEach { (dim, cells) -> it.putLongArray(dim.toString(), cells.toLongArray()) } })
        tag.put("FreeJourneyAt", CompoundTag().also { freeJourneyAt.forEach { (p, t) -> it.putLong(p, t) } })
        tag.put("SurveyCarry", CompoundTag().also { surveyCarry.forEach { (p, b) -> it.putDouble(p, b) } })
        tag.put("SurveyedTowns", ListTag().also { list -> surveyedTowns.forEach { list.add(net.minecraft.nbt.StringTag.valueOf(it)) } })
        tag.put("UnreadMail", ListTag().also { list -> unreadMail.forEach { list.add(net.minecraft.nbt.StringTag.valueOf(it)) } })
        tag.put("MailChimeOff", ListTag().also { list -> mailChimeOff.forEach { list.add(net.minecraft.nbt.StringTag.valueOf(it)) } })
        tag.put("Parcels", ListTag().also { list -> parcels.forEach { if (it.items.isNotEmpty()) list.add(it.toTag(registries)) } })
        tag.put("Paths", ListTag().also { list -> paths.values.forEach { list.add(it.toTag()) } })
        tag.put("Links", ListTag().also { list -> links.forEach { list.add(it.toTag()) } })
        tag.put("Nodes", ListTag().also { list -> nodes.values.forEach { list.add(it.toTag()) } })
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
        val surveyedTag = tag.getCompound("Surveyed")
        for (key in surveyedTag.allKeys) {
            val dim = ResourceLocation.tryParse(key) ?: continue
            surveyed[dim] = LongOpenHashSet(surveyedTag.getLongArray(key))
        }
        tag.getList("SurveyedTowns", Tag.TAG_STRING.toInt()).forEach { surveyedTowns.add(it.asString) }
        tag.getList("UnreadMail", Tag.TAG_STRING.toInt()).forEach { unreadMail.add(it.asString) }
        tag.getList("MailChimeOff", Tag.TAG_STRING.toInt()).forEach { mailChimeOff.add(it.asString) }
        val journeyTag = tag.getCompound("FreeJourneyAt")
        journeyTag.allKeys.forEach { freeJourneyAt[it] = journeyTag.getLong(it) }
        val carryTag = tag.getCompound("SurveyCarry")
        carryTag.allKeys.forEach { surveyCarry[it] = carryTag.getDouble(it) }
        tag.getList("Parcels", Tag.TAG_COMPOUND.toInt()).forEach { t ->
            Parcel.fromTag(t as CompoundTag, registries)?.let { if (it.items.isNotEmpty()) parcels.add(it) }
        }
        tag.getList("Paths", Tag.TAG_COMPOUND.toInt()).forEach { t -> RoadPath.fromTag(t as CompoundTag)?.let { paths[it.id] = it } }
        tag.getList("Links", Tag.TAG_COMPOUND.toInt()).forEach { t -> links.add(PathLink.fromTag(t as CompoundTag)) }
        tag.getList("Nodes", Tag.TAG_COMPOUND.toInt()).forEach { t -> RoadNode.fromTag(t as CompoundTag)?.let { nodes[it.id] = it } }
        // A save from before survey pay: what is charted and every town on the network count as paid already.
        if (!tag.contains("Surveyed")) {
            for (path in paths.values) if (path.charted) markSurveyed(path.dimension, path.points)
            nodes.values.forEach { node -> if (node.kind == RoadNode.KIND_TOWN) node.placeId?.let { surveyedTowns.add(it) } }
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

        /** How far above or below a road's line the ground may be and still count as on it (bridges, cuts). */
        const val ROAD_RISE = 4
        const val ROAD_FUND = "fund:road"

        /** Cell size of the survey record, in blocks: the join distance a charted path covers to each side. */
        const val SURVEY_CELL = 8

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
        const val MAILBOX_SIZE = 54
    }
}

fun ResourceLocation.asDimensionKey(): ResourceKey<Level> = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, this)
