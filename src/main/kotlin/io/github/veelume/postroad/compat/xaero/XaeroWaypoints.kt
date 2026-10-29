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
 * Towns as a third-party waypoint origin, the way Xaero's own Waystones support adds waystones: it can
 * be switched off in Xaero's waypoint options. Refilled whenever the overlay, the minimap's world (a new
 * session, another server) or the set of [explored] towns changes, and every [REFILL_DISTANCE] blocks the
 * player moves.
 *
 * A world has hundreds of towns; as ordinary waypoints they crowd the horizon and the minimap's rim. Only
 * towns the player has explored on their map get one; within [NEAR] blocks it shows in the world and on
 * the minimap, further out on the world map only.
 */
object XaeroWaypoints {
    private val TOWNS = Postroad.id("towns")

    private var filledRoot: MinimapWorldRootContainer? = null
    private var filledVersion = -1
    private var filledAt: BlockPos? = null
    private var filledDimension: ResourceLocation? = null
    private val filled = ArrayList<MinimapWorldContainer>()
    private var filledTowns: List<String> = emptyList()

    /** Whether a spot is explored on the player's map ([XaeroExplored] when the World Map is there); null shows every town. */
    var explored: ((ResourceLocation, BlockPos) -> Boolean)? = null
    private var ticks = 0
    private var shown: List<String> = emptyList()

    fun tick() {
        val session = BuiltInHudModules.MINIMAP.currentSession ?: return
        val root = session.worldManager.autoRootContainer ?: return
        val player = Minecraft.getInstance().player ?: return
        val here = player.blockPosition()
        val dimension = player.level().dimension().location()
        val version = MapOverlayClient.version
        val moved = filledAt?.let { here.distSqr(it) > REFILL_DISTANCE.toDouble() * REFILL_DISTANCE } ?: true
        val state = MapOverlayClient.state
        // Exploration grows while the player walks; asking the map once a second is plenty.
        if (ticks++ % EXPLORED_INTERVAL == 0 || version != filledVersion) {
            val test = explored
            shown = state.towns.filter { test == null || test(it.dimension, it.pos) }.map { it.id }
        }
        if (root === filledRoot && version == filledVersion && dimension == filledDimension && !moved && shown == filledTowns) return

        for (container in filled) {
            container.thirdPartyWaypointManager.clearOrigin(TOWNS)
        }
        filled.clear()
        val containers = HashMap<ResourceLocation, MinimapWorldContainer>()
        fun container(dimension: ResourceLocation): MinimapWorldContainer = containers.getOrPut(dimension) {
            val directory = session.dimensionHelper.getDimensionDirectoryName(ResourceKey.create(Registries.DIMENSION, dimension))
            root.addSubContainer(root.path.resolve(directory)).also { filled.add(it) }
        }

        val visible = shown.toHashSet()
        for (town in state.towns) {
            if (town.id !in visible) continue
            val initials = town.name.firstOrNull()?.uppercase() ?: "T"
            val waypoint = waypoint(town.pos, town.name, initials, WaypointColor.GOLD)
            val near = town.dimension == dimension && town.pos.distSqr(here) <= NEAR.toDouble() * NEAR
            if (!near) waypoint.setVisibility(WaypointVisibilityType.WORLD_MAP_LOCAL)
            container(town.dimension).thirdPartyWaypointManager.get(TOWNS).add(town.id, waypoint)
        }
        filledRoot = root
        filledVersion = version
        filledAt = here
        filledDimension = dimension
        filledTowns = shown
        Postroad.LOGGER.debug("Xaero's Minimap: {} of {} towns explored", shown.size, state.towns.size)
    }

    /** Blocks within which a town shows in the world and on the minimap. */
    private const val NEAR = 768

    /** Blocks the player moves before the near set is worked out again. */
    private const val REFILL_DISTANCE = 256

    /** Ticks between asking the map which towns are explored. */
    private const val EXPLORED_INTERVAL = 20

    private fun waypoint(pos: BlockPos, name: String, initials: String, color: WaypointColor): Waypoint =
        Waypoint(pos.x, pos.y + 1, pos.z, name, initials, color, WaypointPurpose.NORMAL)
}
