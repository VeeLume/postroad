package io.github.veelume.postroad.compat.xaero

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.map.MapOverlayClient
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import xaero.common.minimap.waypoints.Waypoint
import xaero.common.minimap.waypoints.WaypointVisibilityType
import xaero.hud.minimap.BuiltInHudModules
import xaero.hud.minimap.waypoint.WaypointColor
import xaero.hud.minimap.waypoint.WaypointPurpose
import xaero.hud.minimap.world.container.MinimapWorldContainer
import xaero.hud.minimap.world.container.MinimapWorldRootContainer

/**
 * Towns and way signs as two third-party waypoint origins, the way Xaero's own Waystones support adds
 * waystones: each can be switched off in Xaero's waypoint options. Refilled whenever the overlay or the
 * minimap's world (a new session, another server) changes, and every [REFILL_DISTANCE] blocks the player moves.
 *
 * A world has hundreds of towns; as ordinary waypoints they crowd the horizon and the minimap's rim. Only
 * towns within [NEAR] blocks show in the world and on the minimap, the rest (and every sign) on the world map.
 */
object XaeroWaypoints {
    private val TOWNS = Postroad.id("towns")
    private val SIGNS = Postroad.id("signs")

    private var filledRoot: MinimapWorldRootContainer? = null
    private var filledVersion = -1
    private var filledAt: BlockPos? = null
    private var filledDimension: ResourceLocation? = null
    private val filled = ArrayList<MinimapWorldContainer>()

    fun tick() {
        val session = BuiltInHudModules.MINIMAP.currentSession ?: return
        val root = session.worldManager.autoRootContainer ?: return
        val player = Minecraft.getInstance().player ?: return
        val here = player.blockPosition()
        val dimension = player.level().dimension().location()
        val version = MapOverlayClient.version
        val moved = filledAt?.let { here.distSqr(it) > REFILL_DISTANCE.toDouble() * REFILL_DISTANCE } ?: true
        if (root === filledRoot && version == filledVersion && dimension == filledDimension && !moved) return

        for (container in filled) {
            container.thirdPartyWaypointManager.clearOrigin(TOWNS)
            container.thirdPartyWaypointManager.clearOrigin(SIGNS)
        }
        filled.clear()
        val containers = HashMap<ResourceLocation, MinimapWorldContainer>()
        fun container(dimension: ResourceLocation): MinimapWorldContainer = containers.getOrPut(dimension) {
            val directory = session.dimensionHelper.getDimensionDirectoryName(ResourceKey.create(Registries.DIMENSION, dimension))
            root.addSubContainer(root.path.resolve(directory)).also { filled.add(it) }
        }

        val state = MapOverlayClient.state
        for (town in state.towns) {
            val initials = town.name.firstOrNull()?.uppercase() ?: "T"
            val waypoint = waypoint(town.pos, town.name, initials, WaypointColor.GOLD)
            val near = town.dimension == dimension && town.pos.distSqr(here) <= NEAR.toDouble() * NEAR
            if (!near) waypoint.setVisibility(WaypointVisibilityType.WORLD_MAP_LOCAL)
            container(town.dimension).thirdPartyWaypointManager.get(TOWNS).add(town.id, waypoint)
        }
        for (sign in state.signs) {
            // Signs are many and close together; world map only.
            val waypoint = waypoint(sign.pos, sign.name, "+", WaypointColor.GRAY)
            waypoint.setVisibility(WaypointVisibilityType.WORLD_MAP_LOCAL)
            container(sign.dimension).thirdPartyWaypointManager.get(SIGNS).add(sign.id, waypoint)
        }
        filledRoot = root
        filledVersion = version
        filledAt = here
        filledDimension = dimension
        Postroad.LOGGER.debug("Xaero's Minimap: {} towns, {} signs", state.towns.size, state.signs.size)
    }

    /** Blocks within which a town shows in the world and on the minimap. */
    private const val NEAR = 768

    /** Blocks the player moves before the near set is worked out again. */
    private const val REFILL_DISTANCE = 256

    private fun waypoint(pos: BlockPos, name: String, initials: String, color: WaypointColor): Waypoint =
        Waypoint(pos.x, pos.y + 1, pos.z, name, initials, color, WaypointPurpose.NORMAL)
}
