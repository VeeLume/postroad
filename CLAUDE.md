# postroad — working notes for Claude

NeoForge 1.21.1 companion mod for a modpack; Kotlin via Kotlin for Forge. Design docs
live in `docs/` (one per increment). This file is about the code.

## Build

- JDK 21. `./gradlew build` → `build/libs/postroad-<version>.jar`.
- ModDevGradle 2.x (`net.neoforged.moddev`), Kotlin 2.4 (matches Kotlin for Forge 5.12).
- `./gradlew runGameTestServer` runs the game tests under `gametest/` headlessly.
- `./gradlew runServer` / `runClient` start dev instances in `run/`.
- `tools/*.py` are `uv run` scripts (inline dependencies) that generate checked-in assets:
  `gen_courier_post.py` → the courier-post structure NBT, `gen_textures.py` → placeholder textures.
  Regenerate rather than hand-edit.

## Layout (`io.github.veelume.postroad`)

- `Postroad` — entry point; the only place that touches event buses.
- `PostroadConfig` — common config; gameplay numbers the modpack overrides.
- `registry/Registries.kt` — every DeferredRegister (blocks, items, block entities, data
  components, creative tab, data maps, loot-modifier codecs).
- `network/` — `Network` (the single SavedData: places, depots, storage, accounts, ledger),
  `Place`/`LedgerEntry`, `PlaceResolver` (depot position → place).
- `names/` — culture data (`data/postroad/cultures/*.json`) and the deterministic name generator.
- `depot/` — depot block, block entity (holds only its place id), and `DepotInteraction`
  (all click behaviour, server side).
- `loot/` — fresh-loot data component, the global loot modifier that stamps it, client tooltip.
- `worldgen/CourierPostInjector` — appends the courier post to village house pools at server start.
- `roads/` — `Tier`, `RoadRules` (data file `data/postroad/roads/rules.json`, reload listener), `RoadClassifier`
  (ground sampling + walk verdict), `RoadBuff` (movement-speed modifier every 10 ticks, player and mount).
  `RoadPath`/`PathLink`/`RoadNode` (the travel graph, stored on `Network`), `Charting` (per-player
  sessions, sampling + particles, record/link/attach), `ChartingMapItem` + payloads + `ChartingClient`
  (client-side state holder with no client imports; `ClientSetup` plugs the screen opener in).
- `roads/gen/` — generated roads. `Terrain` (cells of 4 blocks: height, flags, biome family) with two forms:
  `TerrainGrid` (dense, tests) and `TiledTerrain` (lazy tiles from a `TileSampler`, BLOCKED/ROAD in an overlay).
  `RoadPlanner` (A* with slope/water costs and a reuse discount; `planNetwork` → new routes + junctions, given
  existing ones). `WorldTerrainSampler` (noise router's preliminary surface + biome source, no chunks; tiles cached
  under `<world>/postroad/terrain/`), `Families` (biome-id keywords → palette family, mirrors the pack's
  `gen_road_styles.py`), `TownFinder` (replays structure-set placement + `Structure.generate` to predict villages;
  ids match `PlaceResolver`'s `<dim>/<chunkX>/<chunkZ>`), `RoadPlanStorage` (SavedData `postroad_roadplan`: predicted
  towns, planned roads with build state, junctions, searched squares), `PlannerRules` (`roads/planner.json` costs),
  `RoadGen` (the `postroad-planner` daemon thread: pass requests are snapshots built on the server thread, results
  applied there — generated paths enter `Network` uncharted). The pass is a pure function of request + worker terrain.
  `RoadStyles` (`roads/styles.json`: palettes per family, what may be replaced/cleared), `RoadBuilder` (chunk-load
  candidates → tick-time filtering against the plan → per-chunk build under `build.blocksPerTick`: 3-wide strip on
  the real heightmap, lampposts, junction signposts linked as sign nodes via `SignNodes.linkGenerated`).
  `RoadRefiner` is the third, block-level pass at build time: per chunk run, A* on the real terrain inside a
  6-block corridor around the planned line (trunks, water, boxes impassable; real height steps priced), so roads bend
  around trees and bumps and meet neighbours exactly at the shared point. `RoadBuilder.groundY` scans down from the
  planned height (ignores barriers/leaves/plants) — the game-test harness encases tests in barriers, so heightmaps lie there.
  `Charting.markGenerated` charts a generated road a walk followed instead of recording a duplicate.
  `RoadDebug` (+ `client/RoadDebugRenderer`): `/postroad roads debug` toggles a per-player in-world overlay of
  roads/junctions/towns/nodes within 512 blocks, sent every 2 s; `/postroad roads export` draws the plan as a PNG;
  `/postroad roads probe <x> <z>` compares the sampler's surface with the generator and the real heightmap and
  says whether the chunk has its own road structure start; `/postroad roads audit <radius> [fix]` counts generated
  road chunks with and without their own start (`fix` hands the start-less ones back to the chunk-load builder).
  **Roads generate as a structure** (`RoadStructure`, data `worldgen/structure/road.json` + `structure_set/roads.json`,
  placement `postroad:planned` answering from `RoadPlanSnapshot`, an immutable per-chunk copy of the plan published by
  the server thread): per chunk run one `RoadRunPiece` (lays the strip with the builder's flatten/smooth/shape code
  at `surface_structures`, before vegetation) plus one `RoadBeardPiece` per 4-block segment (a `beard_thin` box
  that grades the noise to the planned height, places nothing). `RoadShapes` is the one flat/slab/stairs table.
  The chunk-load builder is the fallback for chunks whose structure starts were generated before the plan covered
  them (`RoadBuilder.generatedWithRoad` = the chunk's *own* start; starts referenced from neighbours don't count).
  The sampler calibrates a surface offset against `getBaseHeight` once per world (Tectonic's real surface sits
  a few blocks above the density crossing).
  **Terrain sources:** `DhTerrain` reads Distant Horizons' generated terrain through its API when the mod is
  present (optional compile dep `distanthorizonsapi`; DH classes only referenced inside `DhTerrainAccess`); cells
  without DH data fall back to the density estimate and carry `Terrain.ESTIMATED`; such tiles are `provisional`
  (memory only, re-sampled after a minute) and never reach the `known`/`known16` disk caches.
  `/postroad roads debug terrain` draws the planner's cells (step class colours, flags, estimate-vs-real ticks,
  estimates faint). `TownFinder` replays every structure set: villages become towns, other surface structures
  obstacles (`PlannedObstacle`, blocked with `plan.structureMargin`); structures that come out buried a few times
  in a row are skipped for good.
- `roads/Routing` — Dijkstra over anchors (nodes + link ends) on the path polylines.
- `travel/` — `Fares`, `TravelService` (open list, depart: fare, fresh-loot mailing, teleport), `SignNodes`
  (map-on-sign links/unlinks, left-click opens travel; block tag `#postroad:sign_nodes`), payloads + `TravelClient`.
- `mailbox/` — the player's mailbox block; contents are in `Network`, the block entity only holds its place id.
- `mail/MailService` — parcels: lane split, arrival days, delivery on day change; `network/Parcel`.
- `menu/` — `DepotMenu` (one 54-slot grid, backing container swapped per tab; ints via data
  slots, names via `DepotStatePayload`), `DepotState` + the two payloads, `PostroadNetworking`.
- `client/` — `DepotScreen`, `ClientSetup`. Only referenced from the `Dist.CLIENT` branch in `Postroad`.
- `advancement/PostroadAdvancements` — mod-granted milestones (impossible trigger) for FTB Quests.
- `command/PostroadCommands` — `/postroad …`.
- `compat/PostroadJadePlugin` — Jade block tooltip for the mailbox. `compileOnly` on Jade's Modrinth
  maven artifact; the class is only ever loaded by Jade, so keep every Jade import inside `compat/`.

## Conventions

- **Data over code.** If the modpack could express it as a datapack file (rates, pool weights,
  culture keywords, loot rules), it is a data file with a codec, not a constant.
- **One state object.** All mutable server state goes through `Network`; block entities and
  items only carry ids or stamps. Call `setDirty()` on every mutation (the `TownContainer`
  wrapper does it for storage).
- **Server thread only**, with one exception: `RoadGen`'s planner thread. It only ever sees a
  `PassRequest` snapshot and its own `TiledTerrain`; it reads the chunk generator's pure noise and
  structure-placement functions, never chunks or SavedData. Results cross back through a queue
  drained in `ServerTickEvent.Post`.
- **Event listeners** use the explicit `addListener(Event::class.java, Consumer)` overload —
  the reflective one cannot resolve Kotlin lambdas' generic type.
- **Sneak + item never reaches a block's `useItemOn`** — vanilla treats it as a secondary use
  and goes straight to the item. Interactions that need "sneak with X in hand" are handled in
  `PlayerInteractEvent.RightClickBlock` (see `depot/DepotEvents.kt`) and the event is cancelled.
  `GameTestHelper.useBlock` calls the block directly, so a passing game test does not prove the
  in-game path; keep both.
- **Access transformers** over mixins. Current ATs: `StructureTemplatePool.rawTemplates/templates`.
- Lang keys: `message.postroad.*` for chat feedback, `command.postroad.*` for command output.
- No personal or machine-specific details in this repo (paths, hosts, who plays); those live in
  the owner's notes. Repo docs stay generic.
