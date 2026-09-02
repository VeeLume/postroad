package io.github.veelume.packcore

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Common config (`config/packcore-common.toml`). Gameplay tuning that the modpack
 * is expected to override; nothing here is synced to clients.
 */
object PackcoreConfig {
    private val BUILDER = ModConfigSpec.Builder()

    /** Vanilla, Towns and Towers and Oh The Biomes We've Gone village house pools. */
    private val DEFAULT_TARGET_POOLS: List<String> = listOf(
        "minecraft:village/plains/houses",
        "minecraft:village/desert/houses",
        "minecraft:village/savanna/houses",
        "minecraft:village/snowy/houses",
        "minecraft:village/taiga/houses",
        "kaisyn:village/badlands_pueblo/houses",
        "kaisyn:village/birch_forest_romanian/houses",
        "kaisyn:village/exclusives/classic/houses",
        "kaisyn:village/exclusives/iberian/houses",
        "kaisyn:village/exclusives/nilotic/houses",
        "kaisyn:village/exclusives/piglin/houses",
        "kaisyn:village/exclusives/rustic/houses",
        "kaisyn:village/exclusives/swedish/houses",
        "kaisyn:village/exclusives/tudor/houses",
        "kaisyn:village/flower_forest_japanese/houses",
        "kaisyn:village/forest_ruins/houses",
        "kaisyn:village/jungle_tribal/houses",
        "kaisyn:village/meadow_swiss/houses",
        "kaisyn:village/mushroom_fields_fantasy/houses",
        "kaisyn:village/old_growth_taiga_polish/houses",
        "kaisyn:village/snowy_taiga_viking/houses",
        "kaisyn:village/sparse_jungle_polynesian/houses",
        "kaisyn:village/swamp_boat/houses",
        "kaisyn:village/wooded_badlands_tipi/houses",
        "biomeswevegone:village/forgotten/houses",
        "biomeswevegone:village/pumpkin_patch/houses",
        "biomeswevegone:village/red_rock/houses",
        "biomeswevegone:village/salem/houses",
        "biomeswevegone:village/skyris/houses",
        "biomeswevegone:village/swamp/houses",
    )

    private val SETTLE_DAYS: ModConfigSpec.IntValue
    private val LEDGER_MAX_ENTRIES: ModConfigSpec.IntValue
    private val COURIER_POST_WEIGHT: ModConfigSpec.IntValue
    private val TARGET_POOLS: ModConfigSpec.ConfigValue<List<out String>>

    val SPEC: ModConfigSpec

    init {
        BUILDER.push("freshLoot")
        SETTLE_DAYS = BUILDER
            .comment("In-game days after which loot taken from containers or mobs stops counting as fresh.")
            .defineInRange("settleDays", 3, 0, 1000)
        BUILDER.pop()

        BUILDER.push("ledger")
        LEDGER_MAX_ENTRIES = BUILDER
            .comment("Ledger entries kept per network; the oldest are dropped past this size.")
            .defineInRange("maxEntries", 500, 10, 100000)
        BUILDER.pop()

        BUILDER.push("courierPost")
        COURIER_POST_WEIGHT = BUILDER
            .comment("Jigsaw weight of the courier post inside each target house pool.")
            .defineInRange("weight", 6, 0, 1000)
        @Suppress("UNCHECKED_CAST")
        TARGET_POOLS = BUILDER
            .comment("Template pools that receive the courier post as an extra house. Missing pools are skipped.")
            .defineListAllowEmpty("targetPools", DEFAULT_TARGET_POOLS) { it is String } as ModConfigSpec.ConfigValue<List<out String>>
        BUILDER.pop()

        SPEC = BUILDER.build()
    }

    val settleDays: Int get() = SETTLE_DAYS.get()
    val ledgerMaxEntries: Int get() = LEDGER_MAX_ENTRIES.get()
    val courierPostWeight: Int get() = COURIER_POST_WEIGHT.get()
    val targetPools: List<String> get() = TARGET_POOLS.get().toList()
}
