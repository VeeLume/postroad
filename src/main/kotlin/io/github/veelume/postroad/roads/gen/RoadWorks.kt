package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.network.GameDay
import io.github.veelume.postroad.network.LedgerEntry
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.network.asDimensionKey
import io.github.veelume.postroad.roads.RoadPath
import io.github.veelume.postroad.roads.Tier
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.neoforged.neoforge.event.tick.ServerTickEvent
import kotlin.math.ceil

/**
 * Road works: players pay for a generated road's next tier at a depot of one of its towns — coins
 * (wallet first, then the road fund) and the new tier's main road block, pooled on the [PlannedRoad].
 * Once both are in, the road's tier goes up, its path in the network takes the new tier (fares and the
 * buff follow), and every chunk it crosses is queued for [upgradeChunk]: each block a piece of the road
 * puts there that is still a lower tier's block for its role becomes the new tier's. Nothing is regraded
 * and blocks players changed are left alone; chunks not generated yet are laid at the new tier anyway.
 */
object RoadWorks {

    /** What the next tier costs in all; null at the top tier. */
    data class Price(val coins: Long, val material: Item, val materialCount: Int)

    val MAX_TIER = Tier.entries.size - 1

    fun length(road: PlannedRoad): Double {
        var total = 0.0
        for (i in 0 until road.points.size - 1) total += RoadPath.distance(road.points[i], road.points[i + 1])
        return total
    }

    /** The family most of the road is laid in: whose palette names the material. */
    fun family(road: PlannedRoad): Int =
        road.families.groupBy { it.toInt() }.maxByOrNull { it.value.size }?.key ?: Families.TEMPERATE

    fun price(road: PlannedRoad): Price? {
        if (road.tier >= MAX_TIER) return null
        val hundreds = length(road) / 100.0
        val perHundred = if (road.tier == 0) PostroadConfig.worksCoinsGravel else PostroadConfig.worksCoinsPaved
        val target = RoadStyles.current.style(family(road), road.tier + 1)
        val item = target.surface.main.block.asItem().takeIf { it != Items.AIR } ?: Items.GRAVEL
        return Price(ceil(hundreds * perHundred).toLong(), item, ceil(hundreds * PostroadConfig.worksMaterial).toInt())
    }

    /** The generated roads that start or end in [placeId]: what its depot offers works for. */
    fun roadsOf(server: MinecraftServer, placeId: String): List<PlannedRoad> =
        RoadPlanStorage.get(server).roads.values.filter { !it.provisional && (it.from == placeId || it.to == placeId) }
            .sortedBy { length(it) }

    /** Puts up to [coins] toward [road]'s next tier for [player]; returns what was paid. */
    fun fund(player: ServerPlayer, road: PlannedRoad, coins: Long): Long {
        val price = price(road) ?: return 0L
        val network = Network.get(player.server)
        val amount = minOf(coins, price.coins - road.worksCoins).coerceAtLeast(0L)
        if (amount == 0L) return 0L
        if (!network.charge(player.gameProfile.name, amount, GameDay.of(player.serverLevel()), LedgerEntry.OP_ROAD_WORKS, note(player.server, road))) return 0L
        road.worksCoins += amount
        RoadPlanStorage.get(player.server).setDirty()
        completeIfPaid(player.server, road)
        return amount
    }

    /** Delivers the material [road] still needs from [player]'s inventory, then from [placeId]'s depot storage; returns how many. */
    fun deliver(player: ServerPlayer, road: PlannedRoad, placeId: String): Int {
        val price = price(road) ?: return 0
        var wanted = price.materialCount - road.worksMaterial
        if (wanted <= 0) return 0
        var taken = 0
        val containers = listOf(player.inventory, Network.get(player.server).storageFor(placeId))
        for (container in containers) for (slot in 0 until container.containerSize) {
            if (wanted <= 0) break
            val stack = container.getItem(slot)
            // Only the plain block: a renamed or enchanted one is somebody's, not building stock.
            if (!stack.`is`(price.material) || !stack.componentsPatch.isEmpty) continue
            val n = minOf(wanted, stack.count)
            stack.shrink(n)
            container.setChanged()
            wanted -= n; taken += n
        }
        if (taken == 0) return 0
        road.worksMaterial += taken
        RoadPlanStorage.get(player.server).setDirty()
        completeIfPaid(player.server, road)
        return taken
    }

    private fun note(server: MinecraftServer, road: PlannedRoad): String {
        val places = Network.get(server).places
        return "${places[road.from]?.name ?: road.from} – ${places[road.to]?.name ?: road.to} → ${Tier.entries[road.tier + 1].key}"
    }

    private fun completeIfPaid(server: MinecraftServer, road: PlannedRoad) {
        val price = price(road) ?: return
        if (road.worksCoins < price.coins || road.worksMaterial < price.materialCount) return
        upgrade(server, road)
    }

    /** Raises [road] one tier: plan, network path, snapshot, and the chunks it is already laid in. */
    fun upgrade(server: MinecraftServer, road: PlannedRoad) {
        if (road.tier >= MAX_TIER) return
        road.tier++
        road.worksCoins = 0
        road.worksMaterial = 0
        road.upgradeChunks.addAll(road.chunks())
        val storage = RoadPlanStorage.get(server)
        storage.setDirty()
        val network = Network.get(server)
        val tier = Tier.entries[road.tier]
        // A severed path keeps the id, its tail adds a "b" per cut.
        generateSequence(road.id) { it + "b" }.map { network.paths[it] }.takeWhile { it != null }.forEach { path ->
            for (i in path!!.tiers.indices) path.tiers[i] = tier
        }
        network.setDirty()
        RoadPlanSnapshot.publish(storage, road.dimension)
        Postroad.LOGGER.info("Road {} ({} -> {}) upgraded to {}; {} chunk(s) to re-lay", road.id, road.from, road.to, tier, road.upgradeChunks.size)
    }

    /** Queues every chunk of every upgraded road again; returns how many. */
    fun requeueUpgraded(server: MinecraftServer): Int {
        val storage = RoadPlanStorage.get(server)
        var n = 0
        for (road in storage.roads.values) if (road.tier > 0) { val chunks = road.chunks(); n += chunks.size; road.upgradeChunks.addAll(chunks) }
        storage.setDirty()
        return n
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        if (server.tickCount % 10 != 0) return
        var budget = PostroadConfig.worksChunksPerTick * 10
        val storage = RoadPlanStorage.get(server)
        for (road in storage.roads.values) {
            if (road.upgradeChunks.isEmpty()) continue
            val level = server.getLevel(road.dimension.asDimensionKey()) ?: continue
            val iterator = road.upgradeChunks.iterator()
            while (iterator.hasNext() && budget > 0) {
                val chunk = iterator.nextLong()
                if (!level.chunkSource.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) continue
                upgradeChunk(level, road, chunk)
                iterator.remove()
                budget--
            }
            storage.setDirty()
            if (budget <= 0) return
        }
    }

    /** Swaps every lower-tier block of [road]'s pieces in [chunk] for the road's current tier; returns blocks changed. */
    fun upgradeChunk(level: ServerLevel, road: PlannedRoad, chunk: Long): Int {
        if (road.tier <= 0) return 0
        val assembled = RoadAssembly.of(road)
        val placements = assembled.placements ?: return 0
        val styles = RoadStyles.current
        val catalog = RoadPieces.current
        val cx = ChunkPos.getX(chunk); val cz = ChunkPos.getZ(chunk)
        var changed = 0
        for (i in assembled.inChunk(chunk)) {
            val p = placements[i]
            val family = road.families.getOrElse(p.index) { 0 }.toInt()
            val target = styles.style(family, road.tier)
            val lower = Roles((0 until road.tier).map { styles.style(family, it) }, target)
            // The bridge or tunnel variant may be what was laid; its blocks count as much as the plain piece's.
            val pieces = listOf(p.piece) + catalog.pieces.filter { it !== p.piece && isVariantOf(it, p.piece) }
            for (piece in pieces) {
                val placed = if (piece === p.piece) p else PiecePlacement(piece, p.transform, p.x, p.base, p.z, p.index, p.roadId)
                for (b in piece.blocks) {
                    val pos = placed.world(b.x, b.y, b.z)
                    if (pos.x shr 4 != cx || pos.z shr 4 != cz) continue
                    changed += swap(level, pos, lower.of(b.role)) { RoadPieceLayer.stateOf(b, placed, target, level.seed, pos) }
                    if (b.role in RoadPiece.ROAD_ROLES) {
                        // The column under a road block: an embankment shows its fill.
                        for (d in 1..FILL_DEPTH) changed += swap(level, pos.below(d), lower.fill) { target.fill }
                    }
                }
            }
        }
        return changed + sweep(level, road, cx, cz)
    }

    /**
     * What the pieces missed: a road laid by an older version (other pieces, other points) is not where
     * today's placements put it. Every column within [SWEEP_RADIUS] of the road's line in the chunk is
     * looked at from a little under the line's height to just over it; a block still of a lower tier's
     * palette there takes the new tier's for the same job — surface on the line, edge off it.
     */
    private fun sweep(level: ServerLevel, road: PlannedRoad, cx: Int, cz: Int): Int {
        val styles = RoadStyles.current
        val points = road.points
        val roles = HashMap<Int, Pair<RoadStyle, Roles>>()
        val x0 = cx shl 4; val z0 = cz shl 4
        val reach = SWEEP_RADIUS.toInt() + 1
        var changed = 0
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            if (maxOf(a.x, b.x) + reach < x0 || minOf(a.x, b.x) - reach > x0 + 15 || maxOf(a.z, b.z) + reach < z0 || minOf(a.z, b.z) - reach > z0 + 15) continue
            val family = road.families.getOrElse(i) { 0 }.toInt()
            val (target, lower) = roles.getOrPut(family) {
                val t = styles.style(family, road.tier)
                t to Roles((0 until road.tier).map { styles.style(family, it) }, t)
            }
            for (x in maxOf(x0, minOf(a.x, b.x) - reach)..minOf(x0 + 15, maxOf(a.x, b.x) + reach)) {
                for (z in maxOf(z0, minOf(a.z, b.z) - reach)..minOf(z0 + 15, maxOf(a.z, b.z) + reach)) {
                    val dx = (b.x - a.x).toDouble(); val dz = (b.z - a.z).toDouble()
                    val len2 = dx * dx + dz * dz
                    val t = if (len2 == 0.0) 0.0 else (((x - a.x) * dx + (z - a.z) * dz) / len2).coerceIn(0.0, 1.0)
                    val d = kotlin.math.hypot(x - (a.x + t * dx), z - (a.z + t * dz))
                    if (d > SWEEP_RADIUS) continue
                    val lineY = Math.round(a.y + (b.y - a.y) * t).toInt()
                    val patch = java.util.Random(net.minecraft.util.Mth.getSeed(x shr 1, 0, z shr 1) xor level.seed)
                    val walk = if (d <= 0.75) target.surface.pick(patch) else target.edge.pick(patch)
                    for (y in lineY - SWEEP_BELOW..lineY + 1) {
                        val pos = BlockPos(x, y, z)
                        changed += swap(level, pos, lower.road) { walk } + swap(level, pos, lower.stairs) { target.stairs } +
                            swap(level, pos, lower.slab) { target.slab } + swap(level, pos, lower.fill) { target.fill }
                    }
                }
            }
        }
        return changed
    }

    private fun isVariantOf(v: RoadPiece, piece: RoadPiece): Boolean =
        v.terrain != null && v.connectors.size == piece.connectors.size &&
            v.connectors.zip(piece.connectors).all { (a, b) -> a.facing == b.facing && a.level == b.level }

    /** Lower tiers' blocks by role, without any the target tier uses itself. */
    private class Roles(lower: List<RoadStyle>, target: RoadStyle) {
        private val keep = target.surface.blocks + target.edge.blocks + target.stairs.block + target.slab.block + target.fill.block
        val road: Set<Block> = lower.flatMap { it.surface.blocks + it.edge.blocks }.toSet() - keep
        val stairs: Set<Block> = lower.map { it.stairs.block }.toSet() - keep
        val slab: Set<Block> = lower.map { it.slab.block }.toSet() - keep
        val fill: Set<Block> = lower.map { it.fill.block }.toSet() - keep

        fun of(role: String): Set<Block> = when (role) {
            RoadPiece.ROLE_SURFACE, RoadPiece.ROLE_EDGE -> road
            RoadPiece.ROLE_STAIR -> stairs
            RoadPiece.ROLE_SLAB -> slab
            RoadPiece.ROLE_AIR, RoadPiece.ROLE_POST, RoadPiece.ROLE_LAMP -> emptySet()
            else -> fill // fill, paved, wall, pillar: what the layer puts there is the tier's fill
        }
    }

    private fun swap(level: ServerLevel, pos: BlockPos, from: Set<Block>, to: () -> BlockState): Int {
        if (from.isEmpty()) return 0
        val current = level.getBlockState(pos)
        if (current.block !in from) return 0
        level.setBlock(pos, keepShape(current, to()), 2 or 16)
        return 1
    }

    /** The new block with every property the old one shares: a stair's facing and half, a slab's type, water. */
    private fun keepShape(old: BlockState, new: BlockState): BlockState {
        var state = new
        for (property in old.properties) if (state.hasProperty(property)) state = copy(old, state, property)
        return state
    }

    private fun <T : Comparable<T>> copy(from: BlockState, to: BlockState, property: Property<T>): BlockState = to.setValue(property, from.getValue(property))

    /** Blocks under a road block checked for the old tier's fill. */
    private const val FILL_DEPTH = 3

    /** How far off the road's line the sweep looks, in blocks: the 3-wide road and a little more. */
    private const val SWEEP_RADIUS = 2.5
    private const val SWEEP_BELOW = 3
}
