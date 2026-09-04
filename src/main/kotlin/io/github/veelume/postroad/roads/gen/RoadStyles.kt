package io.github.veelume.postroad.roads.gen

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.veelume.postroad.Postroad
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.GsonHelper
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.random.RandomGenerator

/** A weighted list of blocks; picks by a deterministic random so rebuilds match. */
class Palette(private val entries: List<Pair<BlockState, Int>>) {
    private val total = entries.sumOf { it.second }

    fun pick(random: RandomGenerator): BlockState {
        if (entries.isEmpty()) return Blocks.DIRT_PATH.defaultBlockState()
        var r = random.nextInt(total)
        for ((state, weight) in entries) {
            r -= weight
            if (r < 0) return state
        }
        return entries.last().first
    }

    val isEmpty: Boolean get() = entries.isEmpty()
}

/** One family's look: road centre, shoulders, lamppost. */
class RoadStyle(val surface: Palette, val edge: Palette, val post: BlockState, val lamp: BlockState,
                val stairs: BlockState = Blocks.COBBLESTONE_STAIRS.defaultBlockState(), val slab: BlockState = Blocks.COBBLESTONE_SLAB.defaultBlockState(),
                val fill: BlockState = Blocks.COBBLESTONE.defaultBlockState())

/**
 * Road palettes per biome family and what the builder may replace or clear, from
 * `data/postroad/roads/styles.json`. Unknown block ids are skipped with a warning so a pack
 * without some mod still builds roads.
 */
class RoadStyleSet(
    private val families: Map<Int, RoadStyle>,
    private val replaceable: Set<Block>,
    private val replaceableKeywords: List<String>,
    private val clearable: Set<Block>,
    private val clearableKeywords: List<String>,
) {
    fun style(family: Int): RoadStyle = families[family] ?: families[Families.TEMPERATE] ?: FALLBACK_STYLE

    /** Ground the road surface may take the place of. */
    fun isReplaceable(state: BlockState): Boolean {
        val block = state.block
        if (block in replaceable) return true
        val path = BuiltInRegistries.BLOCK.getKey(block).path
        return replaceableKeywords.any { path.contains(it) }
    }

    /** Plants and litter the road may remove from above its surface. */
    fun isClearable(state: BlockState): Boolean {
        if (state.isAir) return true
        val block = state.block
        if (block in clearable) return true
        val path = BuiltInRegistries.BLOCK.getKey(block).path
        return clearableKeywords.any { path.contains(it) }
    }

    companion object {
        val FALLBACK_STYLE = RoadStyle(
            Palette(listOf(Blocks.DIRT_PATH.defaultBlockState() to 5, Blocks.GRAVEL.defaultBlockState() to 2, Blocks.COBBLESTONE.defaultBlockState() to 1)),
            Palette(listOf(Blocks.COARSE_DIRT.defaultBlockState() to 3, Blocks.GRAVEL.defaultBlockState() to 2)),
            Blocks.OAK_FENCE.defaultBlockState(),
            Blocks.LANTERN.defaultBlockState(),
        )

        val FALLBACK = RoadStyleSet(
            mapOf(Families.TEMPERATE to FALLBACK_STYLE),
            setOf(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.STONE, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.TERRACOTTA, Blocks.MUD, Blocks.PACKED_MUD, Blocks.DIRT_PATH, Blocks.SNOW_BLOCK),
            listOf("terracotta", "sandstone", "dirt", "grass_block", "stone", "sand", "gravel", "mud"),
            setOf(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH, Blocks.SNOW),
            listOf("grass", "fern", "flower", "bush", "sapling", "mushroom", "snow"),
        )
    }
}

object RoadStyles : SimpleJsonResourceReloadListener(Gson(), "roads") {
    @Volatile
    var current: RoadStyleSet = RoadStyleSet.FALLBACK
        private set

    override fun apply(objects: Map<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val json = objects[Postroad.id("styles")]
        if (json == null) {
            current = RoadStyleSet.FALLBACK
            Postroad.LOGGER.warn("No road styles data file; using built-in defaults")
            return
        }
        try {
            current = parse(GsonHelper.convertToJsonObject(json, "road styles"))
            Postroad.LOGGER.info("Loaded road styles for {} families", Families.NAMES.size)
        } catch (e: Exception) {
            current = RoadStyleSet.FALLBACK
            Postroad.LOGGER.error("Invalid road styles, using defaults: {}", e.message)
        }
    }

    private fun block(id: String): Block? {
        val key = ResourceLocation.tryParse(id) ?: return null
        if (!BuiltInRegistries.BLOCK.containsKey(key)) {
            Postroad.LOGGER.warn("Road styles: unknown block {}", id)
            return null
        }
        return BuiltInRegistries.BLOCK.get(key)
    }

    private fun palette(arr: com.google.gson.JsonArray?): Palette {
        if (arr == null) return Palette(emptyList())
        val entries = ArrayList<Pair<BlockState, Int>>()
        for (e in arr) {
            val o = e.asJsonObject
            val b = block(GsonHelper.getAsString(o, "block")) ?: continue
            entries.add(b.defaultBlockState() to GsonHelper.getAsInt(o, "weight", 1))
        }
        return Palette(entries)
    }

    private fun blocks(arr: com.google.gson.JsonArray?): Set<Block> =
        arr?.mapNotNull { block(it.asString) }?.toSet() ?: emptySet()

    private fun strings(arr: com.google.gson.JsonArray?): List<String> = arr?.map { it.asString } ?: emptyList()

    private fun parse(obj: JsonObject): RoadStyleSet {
        val fams = GsonHelper.getAsJsonObject(obj, "families")
        val styles = HashMap<Int, RoadStyle>()
        for ((index, name) in Families.NAMES.withIndex()) {
            val f = fams.getAsJsonObject(name) ?: continue
            val surface = palette(f.getAsJsonArray("surface"))
            val edge = palette(f.getAsJsonArray("edge"))
            val post = f.get("post")?.asString?.let { block(it) }?.defaultBlockState() ?: Blocks.OAK_FENCE.defaultBlockState()
            val lamp = f.get("lamp")?.asString?.let { block(it) }?.defaultBlockState() ?: Blocks.LANTERN.defaultBlockState()
            val stairs = f.get("stairs")?.asString?.let { block(it) }?.defaultBlockState() ?: Blocks.COBBLESTONE_STAIRS.defaultBlockState()
            val slab = f.get("slab")?.asString?.let { block(it) }?.defaultBlockState() ?: Blocks.COBBLESTONE_SLAB.defaultBlockState()
            val fill = f.get("fill")?.asString?.let { block(it) }?.defaultBlockState() ?: Blocks.COBBLESTONE.defaultBlockState()
            styles[index] = RoadStyle(if (surface.isEmpty) RoadStyleSet.FALLBACK_STYLE.surface else surface, if (edge.isEmpty) surface else edge, post, lamp, stairs, slab, fill)
        }
        return RoadStyleSet(
            styles,
            blocks(obj.getAsJsonArray("replaceable")),
            strings(obj.getAsJsonArray("replaceableKeywords")),
            blocks(obj.getAsJsonArray("clearable")),
            strings(obj.getAsJsonArray("clearableKeywords")),
        )
    }
}
