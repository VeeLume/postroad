package io.github.veelume.postroad.roads.gen

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.saveddata.SavedData
import java.util.function.BiFunction
import java.util.function.Supplier

/** A village the finder predicted; its id is the one the depot's place will get (see `PlaceResolver`). */
class PlannedTown(val id: String, val dimension: ResourceLocation, val structure: ResourceLocation, val pos: BlockPos, val box: BoundingBox) {
    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("Id", id)
        tag.putString("Dimension", dimension.toString())
        tag.putString("Structure", structure.toString())
        tag.putLong("Pos", pos.asLong())
        tag.putIntArray("Box", intArrayOf(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()))
        return tag
    }

    companion object {
        fun fromTag(tag: CompoundTag): PlannedTown? {
            val dimension = ResourceLocation.tryParse(tag.getString("Dimension")) ?: return null
            val structure = ResourceLocation.tryParse(tag.getString("Structure")) ?: return null
            val b = tag.getIntArray("Box")
            if (b.size != 6) return null
            return PlannedTown(tag.getString("Id"), dimension, structure, BlockPos.of(tag.getLong("Pos")), BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]))
        }
    }
}

/**
 * A planned road: its points (one per cell, at the planner's surface estimate) and the family
 * per point for the builder's palette. Chunks are marked built as the builder places them.
 */
class PlannedRoad(
    val id: String,
    val dimension: ResourceLocation,
    val from: String,
    val to: String,
    val points: List<BlockPos>,
    val families: ByteArray,
) {
    val builtChunks = LongOpenHashSet()

    /** Chunks this road passes through. */
    fun chunks(): LongOpenHashSet {
        val set = LongOpenHashSet()
        for (p in points) set.add(ChunkPos.asLong(p.x shr 4, p.z shr 4))
        return set
    }

    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("Id", id)
        tag.putString("Dimension", dimension.toString())
        tag.putString("From", from)
        tag.putString("To", to)
        tag.putLongArray("Points", points.map { it.asLong() }.toLongArray())
        tag.putByteArray("Families", families)
        tag.putLongArray("Built", builtChunks.toLongArray())
        return tag
    }

    companion object {
        fun fromTag(tag: CompoundTag): PlannedRoad? {
            val dimension = ResourceLocation.tryParse(tag.getString("Dimension")) ?: return null
            val points = tag.getLongArray("Points").map { BlockPos.of(it) }
            if (points.isEmpty()) return null
            val families = tag.getByteArray("Families").takeIf { it.size == points.size } ?: ByteArray(points.size)
            val road = PlannedRoad(tag.getString("Id"), dimension, tag.getString("From"), tag.getString("To"), points, families)
            for (c in tag.getLongArray("Built")) road.builtChunks.add(c)
            return road
        }
    }
}

/** Where a planned road joined another: the builder puts a way sign here. */
class PlannedJunction(val dimension: ResourceLocation, val pos: BlockPos, val roadA: String, val roadB: String) {
    var signPlaced: Boolean = false

    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("Dimension", dimension.toString())
        tag.putLong("Pos", pos.asLong())
        tag.putString("RoadA", roadA)
        tag.putString("RoadB", roadB)
        tag.putBoolean("Sign", signPlaced)
        return tag
    }

    companion object {
        fun fromTag(tag: CompoundTag): PlannedJunction? {
            val dimension = ResourceLocation.tryParse(tag.getString("Dimension")) ?: return null
            return PlannedJunction(dimension, BlockPos.of(tag.getLong("Pos")), tag.getString("RoadA"), tag.getString("RoadB")).also { it.signPlaced = tag.getBoolean("Sign") }
        }
    }
}

/**
 * The road plan, separate from the travel network so the network file stays small: predicted
 * towns, planned roads with their build state, junctions, and which discovery squares have
 * been searched for towns. Stored on the overworld as `postroad_roadplan`.
 */
class RoadPlanStorage : SavedData() {
    val towns: MutableMap<String, PlannedTown> = LinkedHashMap()
    val roads: MutableMap<String, PlannedRoad> = LinkedHashMap()
    val junctions: MutableList<PlannedJunction> = ArrayList()

    /** Dimension → [DISCOVERY_SQUARE]-block squares (packed as chunk-style longs) already searched for towns. */
    val discovered: MutableMap<ResourceLocation, LongOpenHashSet> = HashMap()

    private var chunkIndex: Map<Long, List<PlannedRoad>>? = null

    fun addTown(town: PlannedTown) {
        towns[town.id] = town
        setDirty()
    }

    fun addRoad(road: PlannedRoad) {
        roads[road.id] = road
        chunkIndex = null
        setDirty()
    }

    fun addJunction(junction: PlannedJunction) {
        junctions.add(junction)
        setDirty()
    }

    fun markDiscovered(dimension: ResourceLocation, squares: Collection<Long>) {
        if (squares.isEmpty()) return
        discovered.getOrPut(dimension) { LongOpenHashSet() }.addAll(squares)
        setDirty()
    }

    fun isDiscovered(dimension: ResourceLocation, square: Long): Boolean = discovered[dimension]?.contains(square) == true

    fun townsIn(dimension: ResourceLocation): List<PlannedTown> = towns.values.filter { it.dimension == dimension }
    fun roadsIn(dimension: ResourceLocation): List<PlannedRoad> = roads.values.filter { it.dimension == dimension }

    /** Roads with points in [chunk] of [dimension]; the index is rebuilt after a change. */
    fun roadsInChunk(dimension: ResourceLocation, chunk: Long): List<PlannedRoad> {
        val index = chunkIndex ?: buildIndex().also { chunkIndex = it }
        return index[chunk]?.filter { it.dimension == dimension } ?: emptyList()
    }

    private fun buildIndex(): Map<Long, List<PlannedRoad>> {
        val map = HashMap<Long, MutableList<PlannedRoad>>()
        for (road in roads.values) for (c in road.chunks()) map.getOrPut(c) { ArrayList() }.add(road)
        return map
    }

    fun clear() {
        towns.clear(); roads.clear(); junctions.clear(); discovered.clear()
        chunkIndex = null
        setDirty()
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        tag.put("Towns", ListTag().also { list -> towns.values.forEach { list.add(it.toTag()) } })
        tag.put("Roads", ListTag().also { list -> roads.values.forEach { list.add(it.toTag()) } })
        tag.put("Junctions", ListTag().also { list -> junctions.forEach { list.add(it.toTag()) } })
        tag.put("Discovered", CompoundTag().also { d -> discovered.forEach { (dim, set) -> d.putLongArray(dim.toString(), set.toLongArray()) } })
        return tag
    }

    private fun load(tag: CompoundTag) {
        tag.getList("Towns", Tag.TAG_COMPOUND.toInt()).forEach { t -> PlannedTown.fromTag(t as CompoundTag)?.let { towns[it.id] = it } }
        tag.getList("Roads", Tag.TAG_COMPOUND.toInt()).forEach { t -> PlannedRoad.fromTag(t as CompoundTag)?.let { roads[it.id] = it } }
        tag.getList("Junctions", Tag.TAG_COMPOUND.toInt()).forEach { t -> PlannedJunction.fromTag(t as CompoundTag)?.let { junctions.add(it) } }
        val d = tag.getCompound("Discovered")
        for (key in d.allKeys) {
            val dim = ResourceLocation.tryParse(key) ?: continue
            discovered[dim] = LongOpenHashSet(d.getLongArray(key))
        }
    }

    companion object {
        const val NAME = "postroad_roadplan"

        /** Town discovery is bookkept per square of this many blocks. */
        const val DISCOVERY_SQUARE = 256

        fun squareKey(x: Int, z: Int): Long = ChunkPos.asLong(Math.floorDiv(x, DISCOVERY_SQUARE), Math.floorDiv(z, DISCOVERY_SQUARE))

        val FACTORY: Factory<RoadPlanStorage> = Factory(
            Supplier { RoadPlanStorage() },
            BiFunction { tag, _ -> RoadPlanStorage().also { it.load(tag) } },
            null,
        )

        fun get(server: MinecraftServer): RoadPlanStorage =
            server.overworld().dataStorage.computeIfAbsent(FACTORY, NAME)
    }
}
