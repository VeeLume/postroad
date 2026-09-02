package io.github.veelume.postroad.client

import io.github.veelume.postroad.loot.FreshLootTooltip
import io.github.veelume.postroad.registry.PostroadMenus
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.function.Consumer

/** Client-only wiring. Only ever referenced from a `Dist.CLIENT` branch. */
object ClientSetup {
    fun register() {
        MOD_BUS.addListener(RegisterMenuScreensEvent::class.java, Consumer { event ->
            event.register(PostroadMenus.DEPOT.get(), ::DepotScreen)
        })
        FreshLootTooltip.register()
    }
}
