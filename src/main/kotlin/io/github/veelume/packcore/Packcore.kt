package io.github.veelume.packcore

import io.github.veelume.packcore.command.PackcoreCommands
import io.github.veelume.packcore.depot.DepotEvents
import io.github.veelume.packcore.loot.FreshLootTooltip
import io.github.veelume.packcore.names.CultureRegistry
import io.github.veelume.packcore.registry.PackcoreBlockEntities
import io.github.veelume.packcore.registry.PackcoreBlocks
import io.github.veelume.packcore.registry.PackcoreComponents
import io.github.veelume.packcore.registry.PackcoreCreativeTabs
import io.github.veelume.packcore.registry.PackcoreDataMaps
import io.github.veelume.packcore.registry.PackcoreItems
import io.github.veelume.packcore.registry.PackcoreLootModifiers
import io.github.veelume.packcore.worldgen.CourierPostInjector
import net.minecraft.resources.ResourceLocation
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.event.AddReloadListenerEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.function.Consumer

/**
 * Mod entry point. Kotlin for Forge loads `object` declarations annotated with [Mod].
 *
 * Working id `packcore`; gradle.properties carries the same id for the manifest.
 */
@Mod(Packcore.MOD_ID)
object Packcore {
    const val MOD_ID = "packcore"

    val LOGGER: Logger = LogManager.getLogger(MOD_ID)

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    init {
        PackcoreBlocks.REGISTER.register(MOD_BUS)
        PackcoreItems.REGISTER.register(MOD_BUS)
        PackcoreBlockEntities.REGISTER.register(MOD_BUS)
        PackcoreComponents.REGISTER.register(MOD_BUS)
        PackcoreCreativeTabs.REGISTER.register(MOD_BUS)
        PackcoreLootModifiers.REGISTER.register(MOD_BUS)
        MOD_BUS.addListener(RegisterDataMapTypesEvent::class.java, Consumer(PackcoreDataMaps::register))

        ModLoadingContext.get().activeContainer.registerConfig(ModConfig.Type.COMMON, PackcoreConfig.SPEC)

        FORGE_BUS.addListener(AddReloadListenerEvent::class.java, Consumer { it.addListener(CultureRegistry) })
        FORGE_BUS.addListener(RegisterCommandsEvent::class.java, Consumer(PackcoreCommands::register))
        FORGE_BUS.addListener(ServerAboutToStartEvent::class.java, Consumer(CourierPostInjector::onServerAboutToStart))
        DepotEvents.register()

        if (FMLEnvironment.dist == Dist.CLIENT) {
            FreshLootTooltip.register()
        }

        LOGGER.info("Packcore initialised")
    }
}
