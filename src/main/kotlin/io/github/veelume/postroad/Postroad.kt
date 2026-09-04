package io.github.veelume.postroad

import io.github.veelume.postroad.command.PostroadCommands
import io.github.veelume.postroad.depot.DepotEvents
import io.github.veelume.postroad.client.ClientSetup
import io.github.veelume.postroad.menu.PostroadNetworking
import io.github.veelume.postroad.mail.MailService
import io.github.veelume.postroad.names.CultureRegistry
import io.github.veelume.postroad.roads.Charting
import io.github.veelume.postroad.roads.RoadBuff
import io.github.veelume.postroad.roads.RoadRules
import io.github.veelume.postroad.roads.gen.PlannerRules
import io.github.veelume.postroad.roads.gen.RoadGen
import io.github.veelume.postroad.registry.PostroadBlockEntities
import io.github.veelume.postroad.registry.PostroadBlocks
import io.github.veelume.postroad.registry.PostroadComponents
import io.github.veelume.postroad.registry.PostroadCreativeTabs
import io.github.veelume.postroad.registry.PostroadDataMaps
import io.github.veelume.postroad.registry.PostroadItems
import io.github.veelume.postroad.registry.PostroadLootModifiers
import io.github.veelume.postroad.registry.PostroadMenus
import io.github.veelume.postroad.worldgen.CourierPostInjector
import net.minecraft.resources.ResourceLocation
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.event.AddReloadListenerEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.function.Consumer

/**
 * Mod entry point. Kotlin for Forge loads `object` declarations annotated with [Mod].
 *
 * Working id `postroad`; gradle.properties carries the same id for the manifest.
 */
@Mod(Postroad.MOD_ID)
object Postroad {
    const val MOD_ID = "postroad"

    val LOGGER: Logger = LogManager.getLogger(MOD_ID)

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    init {
        PostroadBlocks.REGISTER.register(MOD_BUS)
        PostroadItems.REGISTER.register(MOD_BUS)
        PostroadBlockEntities.REGISTER.register(MOD_BUS)
        PostroadComponents.REGISTER.register(MOD_BUS)
        PostroadCreativeTabs.REGISTER.register(MOD_BUS)
        PostroadLootModifiers.REGISTER.register(MOD_BUS)
        PostroadMenus.REGISTER.register(MOD_BUS)
        MOD_BUS.addListener(RegisterPayloadHandlersEvent::class.java, Consumer(PostroadNetworking::register))
        MOD_BUS.addListener(RegisterDataMapTypesEvent::class.java, Consumer(PostroadDataMaps::register))

        ModLoadingContext.get().activeContainer.registerConfig(ModConfig.Type.COMMON, PostroadConfig.SPEC)

        FORGE_BUS.addListener(AddReloadListenerEvent::class.java, Consumer { it.addListener(CultureRegistry); it.addListener(RoadRules); it.addListener(PlannerRules) })
        FORGE_BUS.addListener(PlayerTickEvent.Post::class.java, Consumer(RoadBuff::onPlayerTick))
        FORGE_BUS.addListener(PlayerTickEvent.Post::class.java, Consumer(Charting::onPlayerTick))
        FORGE_BUS.addListener(LivingDeathEvent::class.java, Consumer(Charting::onDeath))
        FORGE_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java, Consumer(Charting::onLogout))
        FORGE_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent::class.java, Consumer(Charting::onChangedDimension))
        FORGE_BUS.addListener(RegisterCommandsEvent::class.java, Consumer(PostroadCommands::register))
        FORGE_BUS.addListener(ServerAboutToStartEvent::class.java, Consumer(CourierPostInjector::onServerAboutToStart))
        DepotEvents.register()
        io.github.veelume.postroad.travel.SignNodes.register()
        FORGE_BUS.addListener(ServerStartedEvent::class.java, Consumer(MailService::onServerStarted))
        FORGE_BUS.addListener(ServerTickEvent.Post::class.java, Consumer(MailService::onServerTick))
        FORGE_BUS.addListener(ServerStartedEvent::class.java, Consumer(RoadGen::onServerStarted))
        FORGE_BUS.addListener(ServerStoppingEvent::class.java, Consumer(RoadGen::onServerStopping))
        FORGE_BUS.addListener(ServerTickEvent.Post::class.java, Consumer(RoadGen::onServerTick))

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.register()
        }

        LOGGER.info("Postroad initialised")
    }
}
