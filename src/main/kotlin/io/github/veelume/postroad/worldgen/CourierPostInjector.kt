package io.github.veelume.postroad.worldgen

import com.mojang.datafixers.util.Pair
import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.names.CultureRegistry
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent

/**
 * Appends a courier post to every configured village house pool once per server start.
 * The palette style comes from the culture whose keywords match the pool id (the same
 * lookup that picks a town's name style), so a Swiss meadow village gets a spruce post and
 * a desert village a sandstone one. Touches [StructureTemplatePool]'s two element lists
 * through an access transformer; the registry instances are rebuilt for every server
 * start, so this never double-injects.
 */
object CourierPostInjector {
    private val EMPTY_PROCESSORS: ResourceKey<StructureProcessorList> =
        ResourceKey.create(Registries.PROCESSOR_LIST, ResourceLocation.withDefaultNamespace("empty"))

    fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        val weight = PostroadConfig.courierPostWeight
        if (weight <= 0) return

        val access = event.server.registryAccess()
        val pools = access.registryOrThrow(Registries.TEMPLATE_POOL)
        val processors = access.registryOrThrow(Registries.PROCESSOR_LIST)
        val emptyProcessors = processors.getHolderOrThrow(EMPTY_PROCESSORS)
        val elements = HashMap<String, StructurePoolElement>()

        var injected = 0
        val styles = HashMap<String, Int>()
        for (poolId in PostroadConfig.targetPools) {
            val id = ResourceLocation.tryParse(poolId)
            if (id == null) {
                Postroad.LOGGER.warn("Invalid template pool id in config: {}", poolId)
                continue
            }
            val pool = pools.get(id)
            if (pool == null) {
                Postroad.LOGGER.debug("Template pool {} not present, skipping courier post", id)
                continue
            }
            val style = CultureRegistry.get(CultureRegistry.resolve(id)).post
            val element = elements.getOrPut(style) { element(style, emptyProcessors) }

            val raw = ArrayList(pool.rawTemplates)
            raw.add(Pair.of(element, weight))
            pool.rawTemplates = raw
            repeat(weight) { pool.templates.add(element) }
            injected++
            styles.merge(style, 1, Int::plus)
        }
        Postroad.LOGGER.info("Courier post injected into {} template pools (weight {}, styles {})", injected, weight, styles)
    }

    private fun element(style: String, processors: Holder<StructureProcessorList>): StructurePoolElement =
        StructurePoolElement.legacy(Postroad.id("courier_post_$style").toString(), processors)
            .apply(StructureTemplatePool.Projection.RIGID)
}
