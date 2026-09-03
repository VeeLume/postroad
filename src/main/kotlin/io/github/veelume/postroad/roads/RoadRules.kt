package io.github.veelume.postroad.roads

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.veelume.postroad.Postroad
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.GsonHelper
import net.minecraft.world.level.block.state.BlockState

/** Road quality. Order matters: a block is tested against tiers in this order. */
enum class Tier(val key: String) {
    DIRT("dirt"), GRAVEL("gravel"), PAVED("paved");

    companion object {
        fun byKey(key: String): Tier? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Which blocks count as road, and as which tier. Via Romana's rule, reimplemented and extended
 * with tiers; the lists live in `data/postroad/roads/rules.json` so the pack can tune them.
 */
data class RoadRuleSet(
    val minRoadShare: Double,
    val sampleSpacingMin: Int,
    val sampleSpacingMax: Int,
    val sampleRadius: Int,
    /** Per tier: exact block ids, then id-path keywords. Tested in [Tier] order. */
    val exact: Map<Tier, Set<String>>,
    val keywords: Map<Tier, List<String>>,
) {
    fun classify(state: BlockState): Tier? {
        if (state.isAir) return null
        val id = state.blockHolder.registeredName
        val path = id.substringAfter(':')
        for (tier in Tier.entries) {
            if (exact[tier]?.contains(id) == true) return tier
            if (keywords[tier]?.any { path.contains(it) } == true) return tier
        }
        return null
    }

    companion object {
        /** Used until the data file is loaded, and if it is missing. */
        val FALLBACK = RoadRuleSet(
            minRoadShare = 0.30,
            sampleSpacingMin = 4,
            sampleSpacingMax = 8,
            sampleRadius = 2,
            exact = mapOf(
                Tier.DIRT to setOf("minecraft:dirt_path", "minecraft:packed_mud", "minecraft:coarse_dirt", "minecraft:rooted_dirt"),
                Tier.GRAVEL to setOf("minecraft:gravel"),
            ),
            keywords = mapOf(
                Tier.DIRT to listOf("dirt_path"),
                Tier.GRAVEL to listOf("gravel"),
                Tier.PAVED to listOf(
                    "sandstone", "polished", "cobble", "brick", "smooth", "basalt", "path", "road", "concrete",
                    "pavement", "glazed", "tile", "wall", "fence", "slab", "stairs", "wool", "carpet", "plank",
                    "log", "wood", "rail", "button", "pressure_plate",
                ),
            ),
        )
    }
}

object RoadRules : SimpleJsonResourceReloadListener(Gson(), "roads") {
    @Volatile
    var current: RoadRuleSet = RoadRuleSet.FALLBACK
        private set

    override fun apply(objects: Map<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFillerAlias) {
        val json = objects[Postroad.id("rules")] ?: objects.values.firstOrNull()
        if (json == null) {
            current = RoadRuleSet.FALLBACK
            Postroad.LOGGER.warn("No road rules data file; using built-in defaults")
            return
        }
        try {
            current = parse(GsonHelper.convertToJsonObject(json, "road rules"))
            Postroad.LOGGER.info("Loaded road rules: {} tiers, road share ≥ {}", Tier.entries.size, current.minRoadShare)
        } catch (e: Exception) {
            current = RoadRuleSet.FALLBACK
            Postroad.LOGGER.error("Invalid road rules, using defaults: {}", e.message)
        }
    }

    private fun parse(obj: JsonObject): RoadRuleSet {
        val tiers = GsonHelper.getAsJsonObject(obj, "tiers")
        val exact = HashMap<Tier, Set<String>>()
        val keywords = HashMap<Tier, List<String>>()
        for (tier in Tier.entries) {
            val t = tiers.getAsJsonObject(tier.key) ?: continue
            exact[tier] = if (t.has("blocks")) GsonHelper.getAsJsonArray(t, "blocks").map { it.asString }.toSet() else emptySet()
            keywords[tier] = if (t.has("keywords")) GsonHelper.getAsJsonArray(t, "keywords").map { it.asString } else emptyList()
        }
        return RoadRuleSet(
            minRoadShare = GsonHelper.getAsDouble(obj, "minRoadShare", 0.30),
            sampleSpacingMin = GsonHelper.getAsInt(obj, "sampleSpacingMin", 4),
            sampleSpacingMax = GsonHelper.getAsInt(obj, "sampleSpacingMax", 8),
            sampleRadius = GsonHelper.getAsInt(obj, "sampleRadius", 2),
            exact = exact,
            keywords = keywords,
        )
    }
}

private typealias ProfilerFillerAlias = net.minecraft.util.profiling.ProfilerFiller
