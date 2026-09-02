package io.github.veelume.packcore.network

import io.github.veelume.packcore.Packcore
import io.github.veelume.packcore.loot.FreshLoot
import io.github.veelume.packcore.names.CultureRegistry
import io.github.veelume.packcore.names.NameGenerator
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.structure.StructureStart

/** Turns a depot position into a registered [Place], creating the place on first sight. */
object PlaceResolver {

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
            id = "$dimension/${chunk.x}/${chunk.z}"
            type = if (structureId.path.contains("tavern")) Place.TYPE_TAVERN else Place.TYPE_VILLAGE
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

        if (network.places[id] == null) {
            val taken = network.places.values.mapTo(HashSet()) { it.name }
            val name = NameGenerator.generate(level.seed, id, CultureRegistry.get(culture), taken)
            network.addPlace(Place(id, name, culture, type, dimension, anchor, FreshLoot.dayOf(level)))
            Packcore.LOGGER.info("New place '{}' ({}, {}) at {}", name, type, culture, anchor.toShortString())
        }
        network.bindDepot(level.dimension(), pos, id)
        return Result.Bound(id)
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
