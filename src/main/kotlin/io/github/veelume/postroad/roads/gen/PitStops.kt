package io.github.veelume.postroad.roads.gen

import io.github.veelume.postroad.Postroad
import io.github.veelume.postroad.PostroadConfig
import io.github.veelume.postroad.names.CultureRegistry
import io.github.veelume.postroad.network.Network
import io.github.veelume.postroad.network.PlaceResolver
import io.github.veelume.postroad.travel.SignWriter
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pit stops (increment 8): every village's depot, built beside the end of the first of its roads the
 * builder reaches, a few blocks outside the village. The town's other road ends get a signpost that
 * points into the village and along the road. A village no road reaches gets its stop at a street
 * exit once nothing is left to plan for it.
 *
 * The stop is a schematic per culture palette (`structure/pit_stop_<style>.nbt`, from
 * `tools/gen_pit_stop.py`), front toward the road. Its depot is bound to the town here, so it
 * resolves to the predicted place even though it stands outside the village's pieces.
 *
 * Runs in the chunk-load builder's tick like the junction signs: [pending] says whether a chunk has
 * such work, [build] does it once the chunks around the site are loaded.
 */
object PitStops {
    private const val STOP_HALF = 2
    private const val STOP_HEIGHT = 6
    /** Plan points (3 blocks apart) along the road from its end that a stop may stand beside. */
    private const val MAX_ALONG = 8
    /** Stop centre's distance from the road's centre line, nearest first. */
    private val OFFSETS = intArrayOf(4, 5, 6, 7)
    /** A road centre line keeps this far from a stop's box (half the road plus its lampposts). */
    private const val CLEAR = 2.0
    /** Most a column under the stop may lie below its floor, and the floor above or below the road. */
    private const val MAX_SPREAD = 2
    private const val MAX_STEP = 1
    /** Blocks of foundation filled under the floor at most. */
    private const val MAX_FOUNDATION = 8
    /** Street-exit fallback: a line this many blocks out from the exit along its facing. */
    private const val EXIT_LINE = 27


    private val DEPOT = BlockPos(STOP_HALF, 1, 3)

    private sealed interface Outcome {
        data class Placed(val blocks: Int) : Outcome
        /** A chunk the site needs is not loaded; try again when it is. */
        data object NotReady : Outcome
        data object NoRoom : Outcome
    }

    // ---- which chunks have work ---------------------------------------------------------------

    private var index: Map<ResourceLocation, LongOpenHashSet> = emptyMap()
    private var indexedAt = 0L

    /** Whether [chunk] or one next to it holds a town road end or street exit still waiting for its stop or sign. */
    fun pending(storage: RoadPlanStorage, dimension: ResourceLocation, chunk: Long): Boolean {
        if (!PostroadConfig.buildPitStops) return false
        val now = System.nanoTime()
        if (now - indexedAt > 1_000_000_000L) { index = buildIndex(storage); indexedAt = now }
        return index[dimension]?.contains(chunk) == true
    }

    private fun invalidate() { indexedAt = 0L }

    /** The chunks that own a pending road end or street exit (the site's own chunk, not its neighbours). */
    fun dueChunks(storage: RoadPlanStorage): List<Pair<ResourceLocation, Long>> {
        if (!PostroadConfig.buildPitStops) return emptyList()
        val out = ArrayList<Pair<ResourceLocation, Long>>()
        fun at(dim: ResourceLocation, p: BlockPos) = out.add(dim to ChunkPos.asLong(p.x shr 4, p.z shr 4))
        for (road in storage.roads.values) {
            if (road.provisional) continue
            for ((bit, townId, point) in ends(road)) if (road.endsDone and bit == 0 && townId in storage.towns) at(road.dimension, point)
        }
        for (town in storage.towns.values) if (!town.stopDone && settled(storage, town)) town.exits.forEach { at(town.dimension, it) }
        return out
    }

    private fun buildIndex(storage: RoadPlanStorage): Map<ResourceLocation, LongOpenHashSet> {
        val map = HashMap<ResourceLocation, LongOpenHashSet>()
        fun mark(dim: ResourceLocation, p: BlockPos) {
            val set = map.getOrPut(dim) { LongOpenHashSet() }
            for (dz in -1..1) for (dx in -1..1) set.add(ChunkPos.asLong((p.x shr 4) + dx, (p.z shr 4) + dz))
        }
        for (road in storage.roads.values) {
            if (road.provisional) continue
            for ((bit, townId, point) in ends(road)) if (road.endsDone and bit == 0 && townId in storage.towns) mark(road.dimension, point)
        }
        for (town in storage.towns.values) if (!town.stopDone && settled(storage, town)) town.exits.forEach { mark(town.dimension, it) }
        return map
    }

    private fun ends(road: PlannedRoad): List<Triple<Int, String, BlockPos>> = listOf(
        Triple(PlannedRoad.END_FROM, road.from, road.points.first()),
        Triple(PlannedRoad.END_TO, road.to, road.points.last()),
    )

    /**
     * Nothing more will reach [town] by road for now: no road to it is still provisional, every road end
     * at it has been handled, and no dropped pair of it waits for a replan.
     */
    private fun settled(storage: RoadPlanStorage, town: PlannedTown): Boolean {
        for (road in storage.roads.values) {
            if (road.from != town.id && road.to != town.id) continue
            if (road.provisional) return false
            if (road.from == town.id && road.endsDone and PlannedRoad.END_FROM == 0) return false
            if (road.to == town.id && road.endsDone and PlannedRoad.END_TO == 0) return false
        }
        return storage.droppedDetails.values.none { it.provisional && (it.from == town.id || it.to == town.id) }
    }

    /** No road to [town] is provisional, and no dropped pair of it waits for a replan. */
    private fun roadsFinal(storage: RoadPlanStorage, town: PlannedTown): Boolean =
        storage.roads.values.none { it.provisional && (it.from == town.id || it.to == town.id) } &&
            storage.droppedDetails.values.none { it.provisional && (it.from == town.id || it.to == town.id) }

    /**
     * Takes down the road-end posts within `build.endSignClear` of [at] (a junction sign went up there that says
     * the same and more): the post, its way signs and their nodes. The ends stay handled.
     */
    fun removeEndPostsNear(level: ServerLevel, storage: RoadPlanStorage, at: BlockPos) {
        val dim = level.dimension().location()
        val network = Network.get(level.server)
        val gone = HashSet<Long>()
        for (road in storage.roadsIn(dim)) {
            val it = road.endPosts.entries.iterator()
            while (it.hasNext()) {
                val (_, pos) = it.next()
                val clear = PostroadConfig.buildEndSignClear
                if (abs(pos.x - at.x) > clear || abs(pos.z - at.z) > clear) continue
                it.remove()
                storage.setDirty()
                if (!gone.add(pos.asLong())) continue
                // The post's nodes, whatever became of its blocks: its way signs stand at pos and above.
                if (network.nodes.values.removeIf { n -> n.dimension == dim && n.pos.x == pos.x && n.pos.z == pos.z && n.pos.y in pos.y..pos.y + 3 }) network.setDirty()
                if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) continue
                val post = level.getBlockState(pos.below())
                if (post.block is net.minecraft.world.level.block.FenceBlock || post.block is net.minecraft.world.level.block.WallBlock)
                    level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3)
                var p = pos
                while (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).block) == RoadBuilder.WAY_SIGN ||
                    level.getBlockState(p).block is net.minecraft.world.level.block.SignBlock) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 3)
                    p = p.above()
                }
                Postroad.LOGGER.info("Road-end signpost at {} gave way to the junction sign at {}", pos.toShortString(), at.toShortString())
            }
        }
    }

    // ---- building -----------------------------------------------------------------------------

    /** Handles the road ends and street exits at or next to [chunk]; returns blocks placed. */
    fun build(level: ServerLevel, storage: RoadPlanStorage, chunk: Long, styles: RoadStyleSet, boxes: List<BoundingBox>): Int {
        if (!PostroadConfig.buildPitStops) return 0
        val dim = level.dimension().location()
        var placed = 0
        val roads = storage.roadsIn(dim)
        for (road in roads) {
            if (road.provisional) continue
            for ((bit, townId, point) in ends(road)) {
                if (road.endsDone and bit != 0 || !near(chunk, point)) continue
                val town = storage.towns[townId] ?: continue
                // Outward from the town: the road's points starting at this end.
                val line = if (bit == PlannedRoad.END_FROM) road.points else road.points.asReversed()
                val style = styles.style(road.families.getOrElse(if (bit == PlannedRoad.END_FROM) 0 else road.points.size - 1) { 0 }.toInt(), road.tier)
                if (!town.stopDone && alreadyHasDepot(level, town, storage)) continue
                if (!town.stopDone) {
                    when (val o = placeStop(level, storage, town, line, style, styles, boxes, roads, road.id)) {
                        is Outcome.Placed -> { placed += o.blocks; road.endsDone = road.endsDone or bit; storage.setDirty(); invalidate(); continue }
                        Outcome.NotReady -> continue
                        Outcome.NoRoom -> Unit
                    }
                }
                // A road-end post waits until every road to the town is final: a road still to be replanned may
                // add a fork right there, whose sign says it all.
                if (!roadsFinal(storage, town)) continue
                val sign = endSignpost(level, storage, town, road, bit, line, style, styles, boxes) ?: continue
                placed += sign
                road.endsDone = road.endsDone or bit
                storage.setDirty(); invalidate()
            }
        }
        for (town in storage.townsIn(dim)) {
            if (town.stopDone || town.exits.none { near(chunk, it) } || !settled(storage, town)) continue
            if (alreadyHasDepot(level, town, storage)) continue
            val style = styles.style(0, 0)
            var waiting = false
            for ((k, exit) in town.exits.withIndex()) {
                val facing = town.facings.getOrNull(k)?.takeIf { it >= 0 }?.let { Direction.from2DDataValue(it) }
                val dx = facing?.stepX?.toDouble() ?: (exit.x - town.box.center.x).toDouble()
                val dz = facing?.stepZ?.toDouble() ?: (exit.z - town.box.center.z).toDouble()
                val len = sqrt(dx * dx + dz * dz).takeIf { it > 0 } ?: continue
                val line = (0..EXIT_LINE step 3).map { d -> BlockPos((exit.x + dx / len * d).roundToInt(), exit.y, (exit.z + dz / len * d).roundToInt()) }
                when (val o = placeStop(level, storage, town, line, style, styles, boxes, roads, null)) {
                    is Outcome.Placed -> { placed += o.blocks; invalidate(); break }
                    Outcome.NotReady -> waiting = true
                    Outcome.NoRoom -> Unit
                }
            }
            if (!town.stopDone && !waiting) {
                town.stopDone = true
                storage.setDirty(); invalidate()
                Postroad.LOGGER.info("No room for a pit stop at any street exit of {} ({})", town.id, placeName(level, town.id))
            }
        }
        return placed
    }

    private fun near(chunk: Long, p: BlockPos): Boolean =
        abs((p.x shr 4) - ChunkPos.getX(chunk)) <= 1 && abs((p.z shr 4) - ChunkPos.getZ(chunk)) <= 1

    /** A town whose own depot (a courier post from the village pools) registered first keeps it and gets no stop. */
    private fun alreadyHasDepot(level: ServerLevel, town: PlannedTown, storage: RoadPlanStorage): Boolean {
        if (!Network.get(level.server).hasDepot(town.id)) return false
        town.stopDone = true
        storage.setDirty(); invalidate()
        Postroad.LOGGER.info("{} has a depot of its own; no pit stop", town.id)
        return true
    }

    /**
     * Looks for a site beside [line] (outward from the town, 3 blocks a point), nearest the town first,
     * and builds the stop on the first that fits. [roadId] is the road the stop serves, for its travel node.
     */
    private fun placeStop(level: ServerLevel, storage: RoadPlanStorage, town: PlannedTown, line: List<BlockPos>, style: RoadStyle,
                          styles: RoadStyleSet, boxes: List<BoundingBox>, roads: List<PlannedRoad>, roadId: String?): Outcome {
        val template = template(level, town) ?: return Outcome.NoRoom
        val stops = storage.townsIn(town.dimension).mapNotNull { it.stop }
        var notReady = false
        // Even ground first, all the way out; then rougher ground, so a stop near the village beats a level
        // one far along the road.
        for (rough in booleanArrayOf(false, true)) for (k in 1 until minOf(line.size, MAX_ALONG + 1)) {
            val p = line[k]
            val prev = line[k - 1]
            val dx = (p.x - prev.x).toDouble()
            val dz = (p.z - prev.z).toDouble()
            val len = sqrt(dx * dx + dz * dz).takeIf { it > 0 } ?: continue
            // Beside a road, its block's level (a plan point is the first air above it); beside a street
            // exit's line (no road), the ground there.
            val level0 = if (roadId != null) p.y - 1 else {
                if (!level.hasChunk(p.x shr 4, p.z shr 4)) { notReady = true; continue }
                RoadBuilder.groundY(level, p.x, p.z, p.y, styles)
            }
            val nearby = roads.filter { r -> r.points.any { abs(it.x - p.x) <= 48 && abs(it.z - p.z) <= 48 } }
            for (offset in OFFSETS) for (side in intArrayOf(1, -1)) {
                val cx = (p.x - dz / len * offset * side).roundToInt()
                val cz = (p.z + dx / len * offset * side).roundToInt()
                val box = BoundingBox(cx - STOP_HALF, p.y, cz - STOP_HALF, cx + STOP_HALF, p.y, cz + STOP_HALF)
                val corners = listOf(box.minX() to box.minZ(), box.maxX() to box.minZ(), box.minX() to box.maxZ(), box.maxX() to box.maxZ())
                if (corners.any { (x, z) -> !level.hasChunk(x shr 4, z shr 4) }) { notReady = true; continue }
                if (overlaps(box, boxes, 1) || overlaps(box, stops, 2)) continue
                if (nearby.any { r -> clearance(r.points, box) < CLEAR }) continue
                val floor = siteFloor(level, box, level0, styles, rough) ?: continue
                val facing = Direction.getNearest((p.x - cx).toFloat(), 0f, (p.z - cz).toFloat())
                val blocks = buildStop(level, template, town, BlockPos(box.minX(), floor, box.minZ()), facing, style)
                town.stop = BoundingBox(box.minX(), floor, box.minZ(), box.maxX(), floor + STOP_HEIGHT - 1, box.maxZ())
                town.stopDone = true
                storage.setDirty()
                bindDepot(level, town, BlockPos(box.minX(), floor, box.minZ()), facing, roadId)
                Postroad.LOGGER.info("Pit stop for {} ({}) at {} facing {}, {} block(s) out from the road's end{}",
                    town.id, placeName(level, town.id), BlockPos(cx, floor, cz).toShortString(), facing, (k * 3) + offset, if (rough) " (on rough ground)" else "")
                return Outcome.Placed(blocks)
            }
        }
        return if (notReady) Outcome.NotReady else Outcome.NoRoom
    }

    private fun overlaps(box: BoundingBox, others: List<BoundingBox>, margin: Int): Boolean = others.any {
        box.minX() <= it.maxX() + margin && box.maxX() >= it.minX() - margin && box.minZ() <= it.maxZ() + margin && box.maxZ() >= it.minZ() - margin
    }

    /** Horizontal distance from the polyline [points] to the columns of [box]. */
    private fun clearance(points: List<BlockPos>, box: BoundingBox): Double {
        var best = Double.MAX_VALUE
        fun at(x: Double, z: Double) {
            val ox = maxOf(box.minX() - 0.5 - x, 0.0, x - box.maxX() - 0.5)
            val oz = maxOf(box.minZ() - 0.5 - z, 0.0, z - box.maxZ() - 0.5)
            best = minOf(best, sqrt(ox * ox + oz * oz))
        }
        for (i in points.indices) {
            val a = points[i]
            if (i == 0) { at(a.x.toDouble(), a.z.toDouble()); continue }
            val b = points[i - 1]
            for (s in 1..6) { val t = s / 6.0; at(b.x + (a.x - b.x) * t, b.z + (a.z - b.z) * t) }
        }
        return best
    }

    /**
     * The floor level for a stop on [box]'s columns: the highest ground, when no column lies more than
     * [MAX_SPREAD] below it, it is within [MAX_STEP] of the road at [roadY], the ground is dry and solid,
     * and nothing but plants and leaves stands in the stop's height. Null when the site does not fit.
     */
    private fun siteFloor(level: ServerLevel, box: BoundingBox, roadY: Int, styles: RoadStyleSet, rough: Boolean = false): Int? {
        val maxSpread = if (rough) MAX_SPREAD + 2 else MAX_SPREAD
        val maxStep = if (rough) MAX_STEP + 1 else MAX_STEP
        val ys = ArrayList<Int>(25)
        for (x in box.minX()..box.maxX()) for (z in box.minZ()..box.maxZ()) {
            val y = RoadBuilder.groundY(level, x, z, roadY, styles)
            val ground = level.getBlockState(BlockPos(x, y, z))
            if (!ground.fluidState.isEmpty || !ground.isSolid) return null
            ys.add(y)
        }
        val floor = ys.max()
        if (floor - ys.min() > maxSpread || abs(floor - roadY) > maxStep) return null
        for (x in box.minX()..box.maxX()) for (z in box.minZ()..box.maxZ()) for (y in floor + 1 until floor + STOP_HEIGHT) {
            val s = level.getBlockState(BlockPos(x, y, z))
            if (!styles.isClearable(s) && s.block !is LeavesBlock) return null
        }
        return floor
    }

    private fun template(level: ServerLevel, town: PlannedTown): StructureTemplate? {
        val postStyle = CultureRegistry.get(CultureRegistry.resolve(town.structure)).post
        val manager = level.server.structureManager
        return manager.get(Postroad.id("pit_stop_$postStyle")).orElse(null)
            ?: manager.get(Postroad.id("pit_stop_oak")).orElse(null).also { if (it == null) Postroad.LOGGER.warn("No pit stop template for style {}", postStyle) }
    }

    /** The template's front faces north; this turns it to [facing] about its centre. */
    private fun settings(facing: Direction): StructurePlaceSettings = StructurePlaceSettings()
        .setRotation(when (facing) { Direction.EAST -> Rotation.CLOCKWISE_90; Direction.SOUTH -> Rotation.CLOCKWISE_180; Direction.WEST -> Rotation.COUNTERCLOCKWISE_90; else -> Rotation.NONE })
        .setRotationPivot(BlockPos(STOP_HALF, 0, STOP_HALF))
        .setIgnoreEntities(true)

    /** Places the stand at [origin] (its box's low corner, floor level) with a foundation under it. */
    private fun buildStop(level: ServerLevel, template: StructureTemplate, town: PlannedTown, origin: BlockPos, facing: Direction, style: RoadStyle): Int {
        var blocks = 0
        val cursor = BlockPos.MutableBlockPos()
        for (x in 0..2 * STOP_HALF) for (z in 0..2 * STOP_HALF) {
            for (d in 1..MAX_FOUNDATION) {
                cursor.set(origin.x + x, origin.y - d, origin.z + z)
                val s = level.getBlockState(cursor)
                if (!s.isAir && s.fluidState.isEmpty && s.isSolid && s.block !is LeavesBlock) break
                level.setBlock(cursor, style.fill, 2)
                blocks++
            }
        }
        template.placeInWorld(level, origin, origin, settings(facing), level.random, 2)
        return blocks + template.size.x * template.size.y * template.size.z
    }

    /**
     * Binds the stop's depot to [town] before the depot's first tick, so it registers with the predicted
     * place instead of founding one of its own, and gives the town a travel node on the road it serves.
     */
    private fun bindDepot(level: ServerLevel, town: PlannedTown, origin: BlockPos, facing: Direction, roadId: String?) {
        val depotPos = StructureTemplate.calculateRelativePosition(settings(facing), DEPOT).offset(origin)
        if (level.getBlockEntity(depotPos) !is io.github.veelume.postroad.depot.DepotBlockEntity) {
            Postroad.LOGGER.warn("Pit stop for {} has no depot at {}", town.id, depotPos.toShortString())
            return
        }
        val network = Network.get(level.server)
        PlaceResolver.predict(level, town.structure, town.id, town.pos)
        if (network.hasDepot(town.id)) {
            level.setBlock(depotPos, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, facing), 3)
            return
        }
        network.bindDepot(level.dimension(), depotPos, town.id)
        roadId?.let { network.paths[it] }?.let { network.attachTowns(it, PostroadConfig.joinDistance) }
    }

    /**
     * A signpost where [road] leaves [town] (the end [bit]) and no stop stands: beside the road a few
     * points out, one arm into the town and one along the road to the other end. Null while the chunks
     * it needs are not loaded; 0 when there was no room (the end counts as handled).
     */
    private fun endSignpost(level: ServerLevel, storage: RoadPlanStorage, town: PlannedTown, road: PlannedRoad, bit: Int,
                            line: List<BlockPos>, style: RoadStyle, styles: RoadStyleSet, boxes: List<BoundingBox>): Int? {
        if (line.size < 4) return 0
        // Where the way is already signed — a fork near the town end, or another road's post at the same
        // exit — a post of this road's own only repeats it.
        val end = line[0]
        fun nearEnd(p: BlockPos) = abs(p.x - end.x) <= PostroadConfig.buildEndSignClear && abs(p.z - end.z) <= PostroadConfig.buildEndSignClear
        if (storage.junctions.any { it.fork && it.dimension == town.dimension && nearEnd(it.pos) }) return 0
        if (Network.get(level.server).nodes.values.any { it.kind == io.github.veelume.postroad.roads.RoadNode.KIND_SIGN && it.dimension == town.dimension && nearEnd(it.pos) }) return 0
        // Every final road that leaves the town through this exit shares the one post: an arm into the town,
        // then one per road toward its other town (roads heading the same way keep the first one's arm).
        val siblings = storage.roadsIn(town.dimension).filter { r -> !r.provisional && r.id != road.id }.mapNotNull { r ->
            val b = when {
                r.from == town.id && r.points.first().distManhattan(end) <= SAME_EXIT -> PlannedRoad.END_FROM
                r.to == town.id && r.points.last().distManhattan(end) <= SAME_EXIT -> PlannedRoad.END_TO
                else -> return@mapNotNull null
            }
            Triple(r, b, if (b == PlannedRoad.END_FROM) r.points else r.points.asReversed())
        }
        val stops = storage.townsIn(town.dimension).mapNotNull { it.stop }
        for (k in 2..minOf(6, line.size - 2)) {
            val p = line[k]
            if (!level.hasChunk((p.x - 8) shr 4, (p.z - 8) shr 4) || !level.hasChunk((p.x + 8) shr 4, (p.z + 8) shr 4)) return null
            val arms = ArrayList<Pair<String, SignWriter.Arm>>()
            arms.add(road.id to SignWriter.Arm(placeName(level, town.id), line[0]))
            for ((r, b, l) in listOf(Triple(road, bit, line)) + siblings) {
                if (l.size <= k) continue
                val aim = l[minOf(k + 4, l.size - 1)]
                val ax = (aim.x - p.x).toDouble(); val az = (aim.z - p.z).toDouble()
                val alen = sqrt(ax * ax + az * az).takeIf { it > 0 } ?: continue
                if (arms.any { (_, a) -> val ox = (a.aim.x - p.x).toDouble(); val oz = (a.aim.z - p.z).toDouble()
                        (ax * ox + az * oz) / (alen * (sqrt(ox * ox + oz * oz).takeIf { it > 0 } ?: 1.0)) > SAME_WAY_COS }) continue
                arms.add(r.id to SignWriter.Arm(placeName(level, if (b == PlannedRoad.END_FROM) r.to else r.from), aim))
            }
            val groups = arms.chunked(2).map { g ->
                val r = if (g[0].first == road.id) road else siblings.first { it.first.id == g[0].first }.first
                val rb = if (r === road) bit else siblings.first { it.first === r }.second
                RoadBuilder.SignGroup(r.id, if (rb == PlannedRoad.END_FROM) k else r.points.size - 1 - k, g.map { it.second })
            }
            var at: BlockPos? = null
            val n = RoadBuilder.erectSignpost(level, styles, style, p, line[k - 1], line[k + 1], groups, boxes, stops) { at = it }
            if (n > 0) {
                for ((r, b, _) in siblings) r.endsDone = r.endsDone or b
                at?.let { pos -> road.endPosts[bit] = pos; for ((r, b, _) in siblings) r.endPosts[b] = pos }
                return n
            }
        }
        return 0
    }

    /** Road ends this close (blocks, Manhattan) leave the town through the same exit. */
    private const val SAME_EXIT = 6
    private val SAME_WAY_COS = kotlin.math.cos(Math.toRadians(30.0))

    private fun placeName(level: ServerLevel, placeId: String): String =
        Network.get(level.server).places[placeId]?.name ?: Component.translatable("sign.postroad.village").string
}
