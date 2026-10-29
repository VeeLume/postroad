package io.github.veelume.postroad.client

import io.github.veelume.postroad.map.MapOverlayClient
import io.github.veelume.postroad.map.MapOverlayState
import io.github.veelume.postroad.registry.PostroadMenus
import io.github.veelume.postroad.salvage.SalvageTooltip
import net.neoforged.fml.ModList
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.function.Consumer

/** Client-only wiring. Only ever referenced from a `Dist.CLIENT` branch. */
object ClientSetup {
    fun register() {
        MOD_BUS.addListener(RegisterMenuScreensEvent::class.java, Consumer { event ->
            event.register(PostroadMenus.DEPOT.get(), ::DepotScreen)
            event.register(PostroadMenus.SATCHEL.get(), ::SatchelScreen)
        })
        SalvageTooltip.register()
        RoadDebugRenderer.register()
        io.github.veelume.postroad.roads.ChartingClient.screenOpener = Runnable { net.minecraft.client.Minecraft.getInstance().setScreen(ChartingScreen()) }
        io.github.veelume.postroad.travel.TravelClient.screenOpener = Runnable { net.minecraft.client.Minecraft.getInstance().setScreen(TravelScreen()) }
        io.github.veelume.postroad.roads.gen.RoadWorksClient.screenOpener = Runnable {
            val mc = net.minecraft.client.Minecraft.getInstance()
            (mc.screen as? RoadWorksScreen)?.refresh() ?: mc.setScreen(RoadWorksScreen())
        }
        // The next server sends its own; until then the maps must not show the last one's towns.
        FORGE_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java, Consumer { MapOverlayClient.update(MapOverlayState.EMPTY) })
        val minimap = ModList.get().isLoaded("xaerominimap")
        val worldMap = ModList.get().isLoaded("xaeroworldmap")
        if (minimap || worldMap) io.github.veelume.postroad.compat.xaero.XaeroMaps.register(minimap, worldMap)
    }
}
