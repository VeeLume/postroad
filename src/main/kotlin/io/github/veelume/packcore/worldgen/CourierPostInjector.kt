package io.github.veelume.packcore.worldgen

import com.mojang.datafixers.util.Pair
import io.github.veelume.packcore.Packcore
import io.github.veelume.packcore.PackcoreConfig
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent

/**
 * Appends the courier post to every configured village house pool once per server start.
 * Touches [StructureTemplatePool]'s two element lists through an access transformer; the
 * registry instances are rebuilt for every server start, so this never double-injects.
 */
object CourierPostInjector {
    private val STRUCTURE = Packcore.id("courier_post")
    private val EMPTY_PROCESSORS: ResourceKey<StructureProcessorList> =
        ResourceKey.create(Registries.PROCESSOR_LIST, ResourceLocation.withDefaultNamespace("empty"))

    fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        val access = event.server.registryAccess()
        val pools = access.registryOrThrow(Registries.TEMPLATE_POOL)
        val processors = access.registryOrThrow(Registries.PROCESSOR_LIST)
        val emptyProcessors = processors.getHolderOrThrow(EMPTY_PROCESSORS)

        val element = StructurePoolElement.legacy(STRUCTURE.toString(), emptyProcessors)
            .apply(StructureTemplatePool.Projection.RIGID)
        val weight = PackcoreConfig.courierPostWeight
        if (weight <= 0) return

        var injected = 0
        for (poolId in PackcoreConfig.targetPools) {
            val id = ResourceLocation.tryParse(poolId)
            if (id == null) {
                Packcore.LOGGER.warn("Invalid template pool id in config: {}", poolId)
                continue
            }
            val pool = pools.get(id)
            if (pool == null) {
                Packcore.LOGGER.debug("Template pool {} not present, skipping courier post", id)
                continue
            }
            val raw = ArrayList(pool.rawTemplates)
            raw.add(Pair.of(element, weight))
            pool.rawTemplates = raw
            repeat(weight) { pool.templates.add(element) }
            injected++
        }
        Packcore.LOGGER.info("Courier post injected into {} template pools (weight {})", injected, weight)
    }
}
