package io.github.veelume.postroad.registry

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.worldgen.RoadFeature
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * Generated roads as a placed feature. The feature type is code; where and when it runs is data:
 * `worldgen/configured_feature/road.json`, `worldgen/placed_feature/road.json` and the biome
 * modifier `neoforge/biome_modifier/road.json` (every overworld biome, step `surface_structures`).
 */
object PostroadFeatures {
    val REGISTER: DeferredRegister<Feature<*>> = DeferredRegister.create(Registries.FEATURE, Postroad.MOD_ID)

    val ROAD: DeferredHolder<Feature<*>, RoadFeature> = REGISTER.register("road") { -> RoadFeature(NoneFeatureConfiguration.CODEC) }
}
