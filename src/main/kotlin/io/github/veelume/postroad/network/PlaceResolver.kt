package io.github.veelume.postroad.network

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.loot.FreshLoot
import io.github.veelume.postroad.names.CultureRegistry
import io.github.veelume.postroad.names.NameGenerator
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.structure.StructureStart

/** Turns a depot position into a registered [Place], creating the place on first sight. */
object PlaceResolver {

    /**
     * The id of the place a structure start stands for. The planner predicts towns long before a
     * depot loads, so both derive the id here rather than each formatting their own — a mismatch
     * would give the same town two places and two names.
     */
    fun placeId(dimension: ResourceLocation, chunkX: Int, chunkZ: Int): String = "$dimension/$chunkX/$chunkZ"

    /** Tavern or village, from the structure id. */
    fun typeOf(structureId: ResourceLocation): String =
        if (structureId.path.contains("tavern")) Place.TYPE_TAVERN else Place.TYPE_VILLAGE

    /** Creates the place if it is not known yet, with the deterministic name for its culture. */
    private fun ensure(level: ServerLevel, network: Network, id: String, type: String, culture: String, anchor: BlockPos): Place {
        network.places[id]?.let { return it }
        val taken = network.places.values.mapTo(HashSet()) { it.name }
        val name = NameGenerator.generate(level.seed, id, CultureRegistry.get(culture), taken)
        val place = Place(id, name, culture, type, level.dimension().location(), anchor, FreshLoot.dayOf(level))
        network.addPlace(place)
        Postroad.LOGGER.info("New place '{}' ({}, {}) at {}", name, type, culture, anchor.toShortString())
        return place
    }

    /**
     * Names a town the planner predicted, before any player has been there, so the junction signs
     * on a road leading to it can carry its name instead of "a village".
     *
     * This registers no depot, and that is what keeps it honest: [Network.towns] and
     * [Network.destinations] both key on a *bound depot*, so a predicted place is a name and
     * nothing else until its depot actually loads. It cannot be mailed to or travelled to early.
     * When the depot does load, [register] finds this place and binds to it, so the sign and the
     * town always agree.
     */
    fun predict(level: ServerLevel, structureId: ResourceLocation, id: String, anchor: BlockPos): Place =
        ensure(level, Network.get(level.server), id, typeOf(structureId), CultureRegistry.resolve(structureId), anchor)

    sealed interface Result {
        /** The depot is bound to this place. */
        data class Bound(val placeId: String) : Result

        /** The town already has a depot elsewhere; this worldgen copy should stand down. */
        data class Redundant(val placeId: String) : Result
    }

    /**
     * Registers the depot at [pos]. A worldgen depot ([playerPlaced] = false) in a town that
     * already has a depot is reported as [Result.Redundant] and not bound.
     */
    fun register(level: ServerLevel, pos: BlockPos, playerPlaced: Boolean): Result {
        val network = Network.get(level.server)
        network.placeOfDepot(level.dimension(), pos)?.let { return Result.Bound(it.id) }

        val dimension = level.dimension().location()
        val found = findStructure(level, pos)

        val id: String
        val type: String
        val culture: String
        val anchor: BlockPos
        if (found != null) {
            val (structureId, start) = found
            val chunk = start.chunkPos
            id = placeId(dimension, chunk.x, chunk.z)
            type = typeOf(structureId)
            culture = CultureRegistry.resolve(structureId)
            anchor = BlockPos(chunk.middleBlockX, pos.y, chunk.middleBlockZ)
        } else {
            id = "founded/$dimension/${pos.x}/${pos.y}/${pos.z}"
            type = Place.TYPE_FOUNDED
            culture = CultureRegistry.DEFAULT
            anchor = pos
        }

        if (!playerPlaced && network.hasDepot(id)) {
            return Result.Redundant(id)
        }

        val known = network.places[id]
        if (known == null) {
            ensure(level, network, id, type, culture, anchor)
        } else if (known.pos != anchor && known.type != Place.TYPE_FOUNDED) {
            // The place was predicted from the structure's placement, so its anchor carries the
            // planner's estimated height. The depot is the authority on where the town actually is.
            network.addPlace(known.copy(pos = anchor))
        }
        network.bindDepot(level.dimension(), pos, id)
        return Result.Bound(id)
    }

    /** Registers a player's mailbox at [pos] as a destination named after them. */
    fun registerMailbox(level: ServerLevel, pos: BlockPos, owner: String): String {
        val network = Network.get(level.server)
        val dimension = level.dimension().location()
        val id = "mailbox/$dimension/${pos.x}/${pos.y}/${pos.z}"
        if (network.places[id] == null) {
            network.addPlace(
                Place(id, "$owner's mailbox", CultureRegistry.DEFAULT, Place.TYPE_MAILBOX, dimension, pos, FreshLoot.dayOf(level), owner.lowercase()),
            )
            Postroad.LOGGER.info("New mailbox for {} at {}", owner, pos.toShortString())
        }
        return id
    }

    private fun findStructure(level: ServerLevel, pos: BlockPos): Pair<ResourceLocation, StructureStart>? {
        val manager = level.structureManager()
        val registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
        for (structure in manager.getAllStructuresAt(pos).keys) {
            val start = manager.getStructureWithPieceAt(pos, structure)
            if (!start.isValid) continue
            val key = registry.getKey(structure) ?: continue
            return key to start
        }
        return null
    }
}
