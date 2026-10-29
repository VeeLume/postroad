package io.github.veelume.postroad.compat.xaero

import io.github.veelume.postroad.Postroad
import net.neoforged.neoforge.client.event.ClientTickEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.function.Consumer

/**
 * Postroad on Xaero's maps: towns as third-party waypoints on the minimap (the world map
 * shows them too), roads as a highlighter on the world map. Fed from [io.github.veelume.postroad.map.MapOverlayClient].
 *
 * Xaero has no documented API; this uses the public classes its own Waystones and Open Parties and Claims
 * support use. Loaded from ClientSetup only when the mods are present; every Xaero import stays in this
 * package. A Xaero update that moves them turns the part that broke off with one log line instead of a crash.
 */
object XaeroMaps {
    private var waypoints = false
    private var roads = false

    fun register(minimap: Boolean, worldMap: Boolean) {
        waypoints = minimap
        roads = worldMap
        // Towns get a waypoint once explored on the world map; with the minimap alone every town does.
        if (minimap && worldMap) XaeroWaypoints.explored = XaeroExplored::contains
        FORGE_BUS.addListener(ClientTickEvent.Post::class.java, Consumer { onClientTick() })
    }

    private fun onClientTick() {
        if (waypoints) waypoints = guarded("minimap waypoints", XaeroWaypoints::tick)
        if (roads) roads = guarded("world map roads", XaeroRoadHighlights::tick)
    }

    /** Runs [part]; false (and one warning) if it fails, so the caller stops calling it. */
    private fun guarded(name: String, part: () -> Unit): Boolean = try {
        part()
        true
    } catch (e: LinkageError) {
        Postroad.LOGGER.warn("Xaero's maps integration ({}) disabled: this Xaero version does not match", name, e)
        false
    } catch (e: Exception) {
        Postroad.LOGGER.warn("Xaero's maps integration ({}) disabled", name, e)
        false
    }
}
