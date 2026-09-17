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
    private const val DEFAULT_POOL_PATTERN = "^[a-z0-9_.-]+:village/(?!.*(wandering_trader|lighthouse))[a-z0-9_/]+/houses?$"

    private val COURIER_POST_SHARE: ModConfigSpec.IntValue
    private val POOL_PATTERN: ModConfigSpec.ConfigValue<String>
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
    private val AUTO_NAME_SIGNS: ModConfigSpec.BooleanValue
    private val FLIP_SIGN_FACES: ModConfigSpec.BooleanValue
    private val TARGET_POOLS: ModConfigSpec.ConfigValue<List<out String>>
    private val PLAN_ENABLED: ModConfigSpec.BooleanValue
    private val PLAN_AUTO: ModConfigSpec.BooleanValue
    private val PLAN_RADIUS: ModConfigSpec.IntValue
    private val PLAN_MAX_LINK: ModConfigSpec.IntValue
    private val PLAN_NEIGHBOURS: ModConfigSpec.IntValue
    private val PLAN_REPASS_DISTANCE: ModConfigSpec.IntValue
    private val PLAN_STRUCTURE_MARGIN: ModConfigSpec.IntValue
    private val PLAN_PREGEN_IN_FLIGHT: ModConfigSpec.IntValue
    private val BUILD_BLOCKS_PER_TICK: ModConfigSpec.IntValue
    private val BUILD_MILLIS_PER_TICK: ModConfigSpec.IntValue
    private val BUILD_LAMP_INTERVAL: ModConfigSpec.IntValue
    private val BUILD_WIDTH: ModConfigSpec.IntValue
    private val BUILD_PIT_STOPS: ModConfigSpec.BooleanValue
    private val BUILD_SWEEP_SECONDS: ModConfigSpec.IntValue
    private val BUILD_JUNCTION_MERGE: ModConfigSpec.IntValue
    private val BUILD_END_SIGN_CLEAR: ModConfigSpec.IntValue
    private val PLAN_CORRIDOR_CHUNKS: ModConfigSpec.IntValue
    private val PLAN_PREGEN_SORT_SECONDS: ModConfigSpec.IntValue
    private val PLAN_MAX_REPLANS: ModConfigSpec.IntValue
    private val CHART_MARK_SHARE: ModConfigSpec.IntValue

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
        COURIER_POST_SHARE = BUILDER
            .comment("The courier post's jigsaw weight in each target house pool, in percent of the pool's total weight (at least 1). Village mods weigh their pools very differently, so a fixed weight is near-certain in one and invisible in another. Extra posts in a town demote themselves to barrels, so this only tunes how likely a town is to get one at all. 0 (the default) leaves the depot to the pit stop at the town's road end (build.pitStops).")
            .defineInRange("share", 0, 0, 1000)
        @Suppress("UNCHECKED_CAST")
        TARGET_POOLS = BUILDER
            .comment("Template pools that receive the courier post as an extra house, on top of those matching poolPattern. Missing pools are skipped.")
            .defineListAllowEmpty("targetPools", DEFAULT_TARGET_POOLS) { it is String } as ModConfigSpec.ConfigValue<List<out String>>
        POOL_PATTERN = BUILDER
            .comment("Regex over template pool ids: every matching pool receives the courier post. Empty matches none. The default takes any mod's village house pools (vanilla, Towns and Towers, BWG, CTOV) but not trader camps or lighthouse annexes.")
            .define("poolPattern", DEFAULT_POOL_PATTERN)
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

        BUILDER.push("plan")
        PLAN_ENABLED = BUILDER.comment("Plan and build roads between predicted villages in the overworld.").define("enabled", true)
        PLAN_AUTO = BUILDER.comment("Plan by itself: a pass around spawn at start and around players who have moved. Off leaves the planner ready but idle, for driving it by command.").define("auto", true)
        PLAN_RADIUS = BUILDER.comment("Half-size in blocks of the square around spawn and each player that is searched for villages and planned.").defineInRange("radius", 1500, 256, 10000)
        PLAN_MAX_LINK = BUILDER.comment("Two villages further apart than this many blocks are never linked directly.").defineInRange("maxLink", 900, 64, 10000)
        PLAN_NEIGHBOURS = BUILDER.comment("Each village is linked to this many nearest villages.").defineInRange("neighbours", 3, 1, 8)
        PLAN_REPASS_DISTANCE = BUILDER.comment("A player who moved this many blocks since their last pass triggers a new one.").defineInRange("repassDistance", 256, 16, 10000)
        PLAN_STRUCTURE_MARGIN = BUILDER.comment("Blocks of clearance kept around a predicted village's bounding box.").defineInRange("structureMargin", 8, 0, 64)
        PLAN_CORRIDOR_CHUNKS = BUILDER
            .comment("Chunks either side of a provisional road that are generated before it is planned again (3: a 7-chunk-wide corridor). The corridor must be wide enough to hold the difference between the estimated line and the real one, or the replan finds no route and the pair is dropped. Lower is much cheaper: the cost per road is roughly proportional to the width.")
            .defineInRange("corridorChunks", 3, 1, 8)
        PLAN_PREGEN_SORT_SECONDS = BUILDER
            .comment("How often the pre-generation queue is re-sorted so the corridor nearest a player comes first. 0 keeps the order the passes queued them in.")
            .defineInRange("pregenSortSeconds", 1, 0, 60)
        PLAN_MAX_REPLANS = BUILDER
            .comment("How often a provisional road may have its corridor generated and be planned again before it is given up on.")
            .defineInRange("maxReplans", 2, 0, 10)
        PLAN_PREGEN_IN_FLIGHT = BUILDER.comment("How many chunks the planner may have generating at once (to the carvers step, on the worldgen workers) to see a corridor's real terrain before routing it. 0 turns pre-generation off.").defineInRange("pregenInFlight", 16, 0, 256)
        BUILDER.pop()

        BUILDER.push("build")
        BUILD_BLOCKS_PER_TICK = BUILDER.comment("Road blocks placed per server tick at most.").defineInRange("blocksPerTick", 200, 10, 10000)
        BUILD_MILLIS_PER_TICK = BUILDER.comment("Milliseconds of a tick the builder may use at most; it stops early when either budget is spent.").defineInRange("millisPerTick", 3, 1, 50)
        BUILD_LAMP_INTERVAL = BUILDER.comment("Blocks of road between lampposts; 0 disables them.").defineInRange("lampInterval", 24, 0, 1000)
        BUILD_WIDTH = BUILDER.comment("Width of a generated road in blocks (odd).").defineInRange("width", 3, 1, 9)
        BUILD_SWEEP_SECONDS = BUILDER.comment("How often the builder offers the loaded chunks that still owe a road, a junction sign or a pit stop (work that fell due while its chunk was already loaded).").defineInRange("sweepSeconds", 10, 1, 600)
        BUILD_JUNCTION_MERGE = BUILDER.comment("Fork junctions within this many blocks of each other share one signpost.").defineInRange("junctionMerge", 9, 0, 64)
        BUILD_END_SIGN_CLEAR = BUILDER.comment("No signpost at a road's town end when a fork or another signpost stands within this many blocks of it; a junction sign that goes up later within it takes such a post down again.").defineInRange("endSignClear", 24, 0, 128)
        BUILD_PIT_STOPS = BUILDER.comment("Build every village's depot as a pit stop beside the end of its first road (at a street exit when it has none), and a signpost at its other road ends.").define("pitStops", true)
        CHART_MARK_SHARE = BUILDER.comment("Percent of a charting walk's samples that must lie along one generated road for the walk to chart that road instead of recording a new path.").defineInRange("markShare", 60, 1, 100)
        BUILDER.pop()

        BUILDER.push("signs")
        AUTO_NAME_SIGNS = BUILDER.comment("When a way sign is linked, point its arms along the path and write 'To: <next node>' on them.").define("autoName", true)
        FLIP_SIGN_FACES = BUILDER.comment("Invert which side of a way-sign arm carries the text (if arms come out facing away from the road).").define("flipFaces", false)
        BUILDER.pop()

        SPEC = BUILDER.build()
    }

    val settleDays: Int get() = SETTLE_DAYS.get()
    val ledgerMaxEntries: Int get() = LEDGER_MAX_ENTRIES.get()
    val courierPostShare: Int get() = COURIER_POST_SHARE.get()
    val poolPattern: String get() = POOL_PATTERN.get()
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
    val planEnabled: Boolean get() = PLAN_ENABLED.get()
    val planAuto: Boolean get() = PLAN_AUTO.get()
    val planRadius: Int get() = PLAN_RADIUS.get()
    val planMaxLink: Int get() = PLAN_MAX_LINK.get()
    val planNeighbours: Int get() = PLAN_NEIGHBOURS.get()
    val planRepassDistance: Int get() = PLAN_REPASS_DISTANCE.get()
    val planStructureMargin: Int get() = PLAN_STRUCTURE_MARGIN.get()
    val planPregenInFlight: Int get() = PLAN_PREGEN_IN_FLIGHT.get()
    val buildBlocksPerTick: Int get() = BUILD_BLOCKS_PER_TICK.get()
    val buildMillisPerTick: Int get() = BUILD_MILLIS_PER_TICK.get()
    val buildLampInterval: Int get() = BUILD_LAMP_INTERVAL.get()
    val buildWidth: Int get() = BUILD_WIDTH.get()
    val buildPitStops: Boolean get() = BUILD_PIT_STOPS.get()
    val buildSweepTicks: Int get() = BUILD_SWEEP_SECONDS.get() * 20
    val buildJunctionMerge: Int get() = BUILD_JUNCTION_MERGE.get()
    val buildEndSignClear: Int get() = BUILD_END_SIGN_CLEAR.get()
    val planCorridorChunks: Int get() = PLAN_CORRIDOR_CHUNKS.get()
    val planPregenSortTicks: Int get() = PLAN_PREGEN_SORT_SECONDS.get() * 20
    val planMaxReplans: Int get() = PLAN_MAX_REPLANS.get()
    val chartMarkShare: Double get() = CHART_MARK_SHARE.get() / 100.0
    val autoNameSigns: Boolean get() = AUTO_NAME_SIGNS.get()
    val flipSignFaces: Boolean get() = FLIP_SIGN_FACES.get()
}
