package io.github.veelume.postroad.compat.xaero

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import xaero.map.WorldMapSession

/**
 * What the player has explored on Xaero's World Map. The map only knows it per region (512 x 512 blocks,
 * one map file each), so a spot counts as explored once any of its region is. Surface layer only: what is
 * explored in cave mode does not count.
 */
object XaeroExplored {
    /** The world map's layer id for the surface map. */
    private const val SURFACE = Int.MAX_VALUE

    /**
     * False until the map has scanned its files after joining, and for other dimensions than the player's.
     * A region the map has loaded leaves its list of detected files, so the loaded regions and the
     * complete detection list are asked as well; the detected list alone emptied out as the player moved.
     */
    fun contains(dimension: ResourceLocation, pos: BlockPos): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        if (player.level().dimension().location() != dimension) return false
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return false
        val x = pos.x shr 9
        val z = pos.z shr 9
        if (processor.getLeafMapRegion(SURFACE, x, z, false) != null) return true
        val layer = processor.mapWorld?.currentDimension?.layeredMapRegions?.getLayer(SURFACE) ?: return false
        return layer.getCompleteRegionDetection(x, z) != null || layer.regionDetectionExists(x, z)
    }
}
