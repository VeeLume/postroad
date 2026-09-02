package io.github.veelume.packcore.names

import kotlin.random.Random

/** Deterministic place names: the same world seed and place id always yield the same name. */
object NameGenerator {
    private val PLACEHOLDER = Regex("\\{(\\w+)}")

    fun generate(worldSeed: Long, placeId: String, culture: Culture, taken: Set<String>): String {
        val random = Random(mix(worldSeed, placeId))
        var name = build(culture, random)

        // Retry a few times before falling back to a disambiguator prefix.
        var attempts = 0
        while (name in taken && attempts < 8) {
            name = build(culture, random)
            attempts++
        }
        if (name in taken) {
            for (prefix in culture.disambiguators) {
                val candidate = "$prefix $name"
                if (candidate !in taken) return candidate
            }
            var n = 2
            while ("$name $n" in taken) n++
            return "$name $n"
        }
        return name
    }

    private fun build(culture: Culture, random: Random): String {
        val pattern = culture.patterns[random.nextInt(culture.patterns.size)]
        val raw = PLACEHOLDER.replace(pattern) { match ->
            val options = culture.parts[match.groupValues[1]]
            if (options.isNullOrEmpty()) "" else options[random.nextInt(options.size)]
        }
        return raw.split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    private fun mix(seed: Long, id: String): Long {
        var h = seed xor -0x61c8864680b583ebL
        for (ch in id) {
            h = (h xor ch.code.toLong()) * 0x100000001b3L
        }
        return h
    }
}
