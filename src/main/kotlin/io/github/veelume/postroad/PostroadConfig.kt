package io.github.veelume.postroad

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Common config (`config/postroad-common.toml`). Gameplay tuning that the modpack
 * is expected to override; nothing here is synced to clients.
 */
object PostroadConfig {
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
    private val VALUABLES_BASE_DAYS: ModConfigSpec.IntValue
    private val VALUABLES_BLOCKS_PER_DAY: ModConfigSpec.IntValue
    private val INSTANT_DISTANCE: ModConfigSpec.IntValue
    private val EXPRESS_COINS_PER_DAY: ModConfigSpec.IntValue
    private val BUFF_DIRT: ModConfigSpec.IntValue
    private val BUFF_GRAVEL: ModConfigSpec.IntValue
    private val BUFF_PAVED: ModConfigSpec.IntValue
    private val CHART_MAX_LENGTH: ModConfigSpec.IntValue
    private val JOIN_DISTANCE: ModConfigSpec.IntValue
    private val FREE_DISTANCE: ModConfigSpec.IntValue
    private val BLOCKS_PER_COIN: ModConfigSpec.IntValue
    private val TIER_FACTOR_DIRT: ModConfigSpec.IntValue
    private val TIER_FACTOR_GRAVEL: ModConfigSpec.IntValue
    private val TIER_FACTOR_PAVED: ModConfigSpec.IntValue
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
            .comment("Jigsaw weight of the courier post inside each target house pool. Extra posts in a town demote themselves to barrels, so this only tunes how likely a town is to get one at all.")
            .defineInRange("weight", 4, 0, 1000)
        @Suppress("UNCHECKED_CAST")
        TARGET_POOLS = BUILDER
            .comment("Template pools that receive the courier post as an extra house. Missing pools are skipped.")
            .defineListAllowEmpty("targetPools", DEFAULT_TARGET_POOLS) { it is String } as ModConfigSpec.ConfigValue<List<out String>>
        BUILDER.pop()

        BUILDER.push("mail")
        VALUABLES_BASE_DAYS = BUILDER
            .comment("Days a parcel with unstackables takes on top of the distance term.")
            .defineInRange("valuablesBaseDays", 1, 0, 100)
        VALUABLES_BLOCKS_PER_DAY = BUILDER
            .comment("Blocks of distance per extra day for unstackables.")
            .defineInRange("valuablesBlocksPerDay", 1000, 1, 100000)
        INSTANT_DISTANCE = BUILDER
            .comment("Destinations within this many blocks receive unstackables at once.")
            .defineInRange("instantDistance", 500, 0, 100000)
        EXPRESS_COINS_PER_DAY = BUILDER
            .comment("Coins per day skipped when a parcel is sent express. 0 disables express.")
            .defineInRange("expressCoinsPerDay", 5, 0, 100000)
        BUILDER.pop()

        BUILDER.push("roads")
        BUFF_DIRT = BUILDER.comment("Movement speed bonus in percent on dirt-tier roads.").defineInRange("buffDirt", 10, 0, 200)
        BUFF_GRAVEL = BUILDER.comment("Movement speed bonus in percent on gravel-tier roads.").defineInRange("buffGravel", 15, 0, 200)
        BUFF_PAVED = BUILDER.comment("Movement speed bonus in percent on paved roads.").defineInRange("buffPaved", 20, 0, 200)
        BUILDER.pop()

        BUILDER.push("chart")
        CHART_MAX_LENGTH = BUILDER.comment("A charting walk longer than this many blocks is abandoned.").defineInRange("maxLength", 4000, 100, 100000)
        JOIN_DISTANCE = BUILDER.comment("Blocks within which a walk's end attaches to an existing path, and a depot or sign attaches to a path.").defineInRange("joinDistance", 8, 1, 64)
        BUILDER.pop()

        BUILDER.push("travel")
        FREE_DISTANCE = BUILDER.comment("Journeys up to this many route-blocks are free.").defineInRange("freeDistance", 500, 0, 100000)
        BLOCKS_PER_COIN = BUILDER.comment("Route-blocks per coin beyond the free distance, on a dirt road.").defineInRange("blocksPerCoin", 250, 1, 100000)
        TIER_FACTOR_DIRT = BUILDER.comment("Fare divisor for dirt roads, in percent.").defineInRange("tierFactorDirt", 100, 1, 1000)
        TIER_FACTOR_GRAVEL = BUILDER.comment("Fare divisor for gravel roads, in percent.").defineInRange("tierFactorGravel", 150, 1, 1000)
        TIER_FACTOR_PAVED = BUILDER.comment("Fare divisor for paved roads, in percent.").defineInRange("tierFactorPaved", 200, 1, 1000)
        BUILDER.pop()

        SPEC = BUILDER.build()
    }

    val settleDays: Int get() = SETTLE_DAYS.get()
    val ledgerMaxEntries: Int get() = LEDGER_MAX_ENTRIES.get()
    val courierPostWeight: Int get() = COURIER_POST_WEIGHT.get()
    val targetPools: List<String> get() = TARGET_POOLS.get().toList()
    val valuablesBaseDays: Long get() = VALUABLES_BASE_DAYS.get().toLong()
    val valuablesBlocksPerDay: Double get() = VALUABLES_BLOCKS_PER_DAY.get().toDouble()
    val instantDistance: Double get() = INSTANT_DISTANCE.get().toDouble()
    val expressCoinsPerDay: Long get() = EXPRESS_COINS_PER_DAY.get().toLong()
    val buffDirt: Double get() = BUFF_DIRT.get() / 100.0
    val buffGravel: Double get() = BUFF_GRAVEL.get() / 100.0
    val buffPaved: Double get() = BUFF_PAVED.get() / 100.0
    val chartMaxLength: Int get() = CHART_MAX_LENGTH.get()
    val joinDistance: Double get() = JOIN_DISTANCE.get().toDouble()
    val freeDistance: Double get() = FREE_DISTANCE.get().toDouble()
    val blocksPerCoin: Double get() = BLOCKS_PER_COIN.get().toDouble()
    val tierFactorDirt: Double get() = TIER_FACTOR_DIRT.get() / 100.0
    val tierFactorGravel: Double get() = TIER_FACTOR_GRAVEL.get() / 100.0
    val tierFactorPaved: Double get() = TIER_FACTOR_PAVED.get() / 100.0
}
