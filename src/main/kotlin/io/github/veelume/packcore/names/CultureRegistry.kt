package io.github.veelume.packcore.names

import com.google.gson.Gson
import com.google.gson.JsonElement
import io.github.veelume.packcore.Packcore
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.GsonHelper
import net.minecraft.util.profiling.ProfilerFiller

/**
 * A culture is a name style plus the structure-id keywords that select it.
 * Loaded from `data/<ns>/cultures/<culture>.json`; the file name is the culture id.
 */
data class Culture(
    val id: String,
    val match: List<String>,
    val patterns: List<String>,
    val parts: Map<String, List<String>>,
    val disambiguators: List<String>,
)

object CultureRegistry : SimpleJsonResourceReloadListener(Gson(), "cultures") {
    const val DEFAULT = "common"

    @Volatile
    private var cultures: Map<String, Culture> = emptyMap()

    override fun apply(objects: Map<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val loaded = LinkedHashMap<String, Culture>()
        for ((location, json) in objects) {
            try {
                val obj = GsonHelper.convertToJsonObject(json, "culture")
                val id = location.path
                val match = strings(obj, "match")
                val patterns = strings(obj, "patterns")
                val partsObj = GsonHelper.getAsJsonObject(obj, "parts")
                val parts = partsObj.keySet().associateWith { strings(partsObj, it) }
                val disambiguators = if (obj.has("disambiguators")) strings(obj, "disambiguators") else emptyList()
                require(patterns.isNotEmpty()) { "no patterns" }
                loaded[id] = Culture(id, match, patterns, parts, disambiguators)
            } catch (e: Exception) {
                Packcore.LOGGER.error("Skipping culture {}: {}", location, e.message)
            }
        }
        cultures = loaded
        Packcore.LOGGER.info("Loaded {} cultures", loaded.size)
    }

    private fun strings(obj: com.google.gson.JsonObject, key: String): List<String> =
        GsonHelper.getAsJsonArray(obj, key).map { it.asString }

    /** Longest matching keyword wins; [DEFAULT] when nothing matches. */
    fun resolve(structureId: ResourceLocation): String {
        val haystack = structureId.toString()
        var best: Culture? = null
        var bestLength = -1
        for (culture in cultures.values) {
            for (keyword in culture.match) {
                if (keyword.length > bestLength && haystack.contains(keyword)) {
                    best = culture
                    bestLength = keyword.length
                }
            }
        }
        return best?.id ?: DEFAULT
    }

    fun get(id: String): Culture =
        cultures[id] ?: cultures[DEFAULT] ?: FALLBACK

    val ids: Set<String> get() = cultures.keys

    /** Used only if the data files are missing entirely. */
    private val FALLBACK = Culture(
        id = DEFAULT,
        match = emptyList(),
        patterns = listOf("{prefix}{suffix}"),
        parts = mapOf(
            "prefix" to listOf("Oak", "Ash", "Elm", "Stone", "Mill", "Bright", "Green"),
            "suffix" to listOf("hollow", "ford", "field", "bridge", "wick", "haven", "mere"),
        ),
        disambiguators = listOf("New", "Old", "Upper", "Lower"),
    )
}
