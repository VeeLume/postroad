package io.github.veelume.postroad.registry

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.roads.gen.RoadPiece
import io.github.veelume.postroad.roads.gen.RoadPlacement
import io.github.veelume.postroad.roads.gen.RoadStructure
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * Generated roads as a structure: the type, its placement (from the plan snapshot) and its
 * piece. The structure itself is data (`worldgen/structure/road.json`) in the set
 * `worldgen/structure_set/roads.json`.
 */
object PostroadStructures {
    val TYPES: DeferredRegister<StructureType<*>> = DeferredRegister.create(Registries.STRUCTURE_TYPE, Postroad.MOD_ID)
    val PLACEMENTS: DeferredRegister<StructurePlacementType<*>> = DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, Postroad.MOD_ID)
    val PIECES: DeferredRegister<StructurePieceType> = DeferredRegister.create(Registries.STRUCTURE_PIECE, Postroad.MOD_ID)

    val ROAD_TYPE: DeferredHolder<StructureType<*>, StructureType<RoadStructure>> = TYPES.register("road") { -> StructureType { RoadStructure.CODEC } }
    val PLANNED_PLACEMENT: DeferredHolder<StructurePlacementType<*>, StructurePlacementType<RoadPlacement>> = PLACEMENTS.register("planned") { -> StructurePlacementType { RoadPlacement.CODEC } }
    val ROAD_PIECE: DeferredHolder<StructurePieceType, StructurePieceType> = PIECES.register("road") { -> StructurePieceType.ContextlessType { tag -> RoadPiece(tag) } }
}
