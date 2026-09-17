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
        val share = PostroadConfig.courierPostShare
        if (share <= 0) return

        val access = event.server.registryAccess()
        val pools = access.registryOrThrow(Registries.TEMPLATE_POOL)
        val processors = access.registryOrThrow(Registries.PROCESSOR_LIST)
        val emptyProcessors = processors.getHolderOrThrow(EMPTY_PROCESSORS)
        val elements = HashMap<String, StructurePoolElement>()

        val targets = LinkedHashSet<ResourceLocation>()
        for (poolId in PostroadConfig.targetPools) {
            val id = ResourceLocation.tryParse(poolId)
            if (id == null) Postroad.LOGGER.warn("Invalid template pool id in config: {}", poolId)
            else if (!pools.containsKey(id)) Postroad.LOGGER.debug("Template pool {} not present, skipping courier post", id)
            else targets.add(id)
        }
        val pattern = try {
            PostroadConfig.poolPattern.takeIf { it.isNotBlank() }?.toRegex()
        } catch (e: IllegalArgumentException) {
            Postroad.LOGGER.warn("Invalid courier post pool pattern '{}': {}", PostroadConfig.poolPattern, e.message)
            null
        }
        if (pattern != null) pools.keySet().filter { pattern.containsMatchIn(it.toString()) }.sortedBy { it.toString() }.forEach { targets.add(it) }

        val styles = HashMap<String, Int>()
        val weights = ArrayList<Int>()
        for (id in targets) {
            val pool = pools.get(id) ?: continue
            val style = CultureRegistry.get(CultureRegistry.resolve(id)).post
            val element = elements.getOrPut(style) { element(style, emptyProcessors) }

            val total = pool.rawTemplates.sumOf { it.second }
            val weight = maxOf(1, Math.round(total * share / 100.0).toInt())
            val raw = ArrayList(pool.rawTemplates)
            raw.add(Pair.of(element, weight))
            pool.rawTemplates = raw
            repeat(weight) { pool.templates.add(element) }
            weights.add(weight)
            styles.merge(style, 1, Int::plus)
            Postroad.LOGGER.debug("Courier post in {}: weight {} of {} ({})", id, weight, total, style)
        }
        Postroad.LOGGER.info("Courier post injected into {} template pools ({}% share, weights {}..{}, styles {})",
            weights.size, share, weights.minOrNull() ?: 0, weights.maxOrNull() ?: 0, styles)
    }

    private fun element(style: String, processors: Holder<StructureProcessorList>): StructurePoolElement =
        StructurePoolElement.legacy(Postroad.id("courier_post_$style").toString(), processors)
            .apply(StructureTemplatePool.Projection.RIGID)
}
