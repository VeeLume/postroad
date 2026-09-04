package io.github.veelume.postroad.roads.gen

import com.google.gson.Gson
import com.google.gson.JsonElement
import io.github.veelume.postroad.Postroad
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.GsonHelper
import net.minecraft.util.profiling.ProfilerFiller

/** Step costs for the planner from `data/postroad/roads/planner.json`; defaults are [PlannerCosts]'s. */
object PlannerRules : SimpleJsonResourceReloadListener(Gson(), "roads") {
    @Volatile
    var current: PlannerCosts = PlannerCosts()
        private set

    override fun apply(objects: Map<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val json = objects[Postroad.id("planner")]
        if (json == null) {
            current = PlannerCosts()
            return
        }
        try {
            val obj = GsonHelper.convertToJsonObject(json, "planner costs")
            val d = PlannerCosts()
            current = PlannerCosts(
                base = GsonHelper.getAsDouble(obj, "base", d.base),
                slopePenalty = GsonHelper.getAsDouble(obj, "slopePenalty", d.slopePenalty),
                slopeCap = GsonHelper.getAsDouble(obj, "slopeCap", d.slopeCap),
                water = GsonHelper.getAsDouble(obj, "water", d.water),
                reuseFactor = GsonHelper.getAsDouble(obj, "reuseFactor", d.reuseFactor),
                maxExpansions = GsonHelper.getAsInt(obj, "maxExpansions", d.maxExpansions),
            )
            Postroad.LOGGER.info("Loaded planner costs: {}", current)
        } catch (e: Exception) {
            current = PlannerCosts()
            Postroad.LOGGER.error("Invalid planner costs, using defaults: {}", e.message)
        }
    }
}
