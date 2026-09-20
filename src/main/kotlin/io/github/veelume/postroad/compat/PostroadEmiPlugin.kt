package io.github.veelume.postroad.compat

import dev.emi.emi.api.EmiEntrypoint
import dev.emi.emi.api.EmiPlugin
import dev.emi.emi.api.EmiRegistry
import dev.emi.emi.api.widget.Bounds
import io.github.veelume.postroad.client.DepotScreen

/**
 * EMI integration: a recipe viewer only knows a container screen's own rectangle, and the depot's
 * control column hangs outside it. This hands EMI the column so its panels keep clear. Loaded only
 * by EMI, through the annotation scan; nothing else references this class.
 */
@EmiEntrypoint
class PostroadEmiPlugin : EmiPlugin {
    override fun register(registry: EmiRegistry) {
        registry.addExclusionArea(DepotScreen::class.java) { screen, area ->
            val column = screen.columnArea()
            area.accept(Bounds(column.x, column.y, column.width, column.height))
        }
    }
}
