# postroad — working notes for Claude

NeoForge 1.21.1 companion mod for a modpack; Kotlin via Kotlin for Forge. Design docs
live in `docs/` (one per increment). This file is about the code.

## Build

- JDK 21. `./gradlew build` → `build/libs/postroad-<version>.jar`.
- ModDevGradle 2.x (`net.neoforged.moddev`), Kotlin 2.4 (matches Kotlin for Forge 5.12).
- `./gradlew runGameTestServer` runs the game tests under `gametest/` headlessly.
- `./gradlew runServer` / `runClient` start dev instances in `run/`.
- `tools/*.py` are `uv run` scripts (inline dependencies) that generate checked-in assets:
  `gen_courier_post.py` → the courier-post structure NBT, `gen_pit_stop.py` → the pit-stop NBT (same
  palettes), `gen_test_arena.py` → the game-test arenas, `gen_textures.py` → placeholder textures.
  Regenerate rather than hand-edit.

## Layout (`io.github.veelume.postroad`)

- `Postroad` — entry point; the only place that touches event buses.
- `PostroadConfig` — common config; gameplay numbers the modpack overrides.
- `registry/Registries.kt` — every DeferredRegister (blocks, items, block entities, data
  components, creative tab, data maps, loot-modifier codecs).
- `network/` — `Network` (the single SavedData: places, depots, storage, accounts, ledger),
  `Place`/`LedgerEntry`, `PlaceResolver` (depot position → place), `GameDay` (the world clock in days).
- `names/` — culture data (`data/postroad/cultures/*.json`) and the deterministic name generator.
- `depot/` — depot block, block entity (holds only its place id), and `DepotInteraction`
  (all click behaviour, server side).
- `loot/` — the `Looted` mark (unstackable container loot; undated, never removed) and `LootedModifier`, the global
  loot modifier that sets it (block and mob drops and `gameplay/`, `equipment/`, `spawners/` tables excluded: farmable).
  `FreshLoot` is the stamp up to 0.2.6, still registered because old items carry it (an unknown component fails to
  load); `FreshLootMigration` turns it into the mark wherever a player meets it.
- `salvage/` — `Salvage` (the one price rule: `postroad:salvage` rates, looted gear by its repair material through
  `postroad:salvage_material`, an enchanted bonus; never resources), `SatchelItem`/`SatchelMenu` (a one-row scratch
  grid sold by vanilla's container-button packet and handed back on close; coins in it are paid in), `SalvageTooltip`
  (client). The depot's sell path uses the same rule.
- `worldgen/CourierPostInjector` — appends the courier post to village house pools at server start.
- `roads/` — `Tier`, `RoadRules` (data file `data/postroad/roads/rules.json`, reload listener), `RoadClassifier`
  (ground sampling + walk verdict), `RoadBuff` (movement-speed modifier every 10 ticks, player and mount; only within `roads.buffReach` of a `Network`
  path, looked up through `Network.nearRoad`'s per-chunk index; the ground decides the tier).
  `RoadPath`/`PathLink`/`RoadNode` (the travel graph, stored on `Network`), `Charting` (per-player
  sessions, sampling + particles, record/link/attach; road no charted path covers yet is paid to the walker,
  `chart.surveyBlocksPerCoin` plus `surveyTownBonus` per town brought on; `Network` keeps what it paid for as 8-block
  cells and town ids, so a path removed or replanned and charted again is not paid twice; what falls short of a coin
  carries over per player), `ChartingMapItem` + payloads + `ChartingClient`
  (client-side state holder with no client imports; `ClientSetup` plugs the screen opener in).
- `roads/gen/` — generated roads. `Terrain` (cells of 3 blocks = the piece grid; coarse map 12: height, flags, biome family) with two forms:
  `TerrainGrid` (dense, tests) and `TiledTerrain` (lazy tiles from a `TileSampler`, BLOCKED/ROAD in an overlay).
  Everything on the planner side is sized in blocks and divided by the cell size (`RoadPlanner.ENDPOINT_REACH`,
  `CORRIDOR_HALF`, town `reach`); the height estimate interpolates the density crossing inside the last noise
  cell so it is block-precise (a whole-cell estimate moves in 4-block steps, which a rise limit of 3 cannot climb).
  `/postroad roads trace <pair>` re-plans one dropped pair and draws what the fine search saw.
  `RoadPlanner` (A* with slope/water costs and a reuse discount; `planNetwork` → new routes + junctions, given
  existing ones; a junction is a `fork` only where three arms part, and only forks get a signpost; a road end
  is a street exit or open ground within `EXIT_RING` cells of one, never a walk toward the neighbour).
  `WorldTerrainSampler` (noise router's preliminary surface + biome source, no chunks; tiles cached
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
  **Roads are built from a catalog of pieces** (`docs/increment-6.md`): `RoadPieces` loads
  `data/postroad/roads/pieces/*.json`, generated by `tools/gen_pieces.py` (edit the generator, not the files).
  A piece is a schematic anchored at its planned point (`[0,0,0]` = the anchor's road block): `connectors`
  (edge block, outward facing, level the road continues at), `blocks` and `decor` as coordinates with a material
  `role`, `fit` (cut/fill/deck or `none`), optional `terrain` (bridge/tunnel variants) and `cost`. The assembler
  (`PieceCatalog.assemble`) gives every point the piece whose connectors, under one of eight `Transform`s, cover
  the sides the point needs at the neighbours' levels; the rise between two points is hosted by the higher
  point's piece (a small assignment pass keeps hilltops from hosting two). Shapes: straight, diagonal, corner,
  bend (45°), each per rise, plus the flat `square` with a connector on every side as the fallback. The planner's
  move set and costs come from the catalog (`stepClasses` / `diagonalStepClasses`); a plan is checked against it
  before storage. `RoadPieceLayer.lay` writes a placement's blocks with the ground fitted per road-block column.
  Same code for the road feature at generation time and the chunk-load builder. `RoadAssembly` caches each road's
  placements, footprint and chunk index while its points list and the catalog are the same objects (both are
  replaced, never mutated); `match` is memoized per catalog on the relative needs. **Provisional roads** (planned
  over estimates) are in no snapshot, skipped by the builder and lend no heights to other roads until replanned
  on real terrain; `RoadPlanSnapshot.laid` is per chunk *and road*. `/postroad roads debug pieces` outlines every
  placement with its connectors; `/postroad roads showcase` lays every catalog piece in the air.
  **Roads generate as a placed feature** (`worldgen/RoadFeature`, data `worldgen/configured_feature/road.json`,
  `placed_feature/road.json`, biome modifier `neoforge/biome_modifier/road.json` on every overworld biome at
  `surface_structures`): once per chunk it asks `RoadPlanSnapshot` (segments whose piece footprints touch the
  chunk, published by the server thread) and lays each piece's blocks inside the chunk. The chunk-load builder
  is the fallback for chunks finished before the plan covered them; junction signs stay with it.
  **Pre-generation:** `ChunkPregen` requests corridor chunks at `CARVERS` through the chunk system — from its own
  thread, because `getChunkFuture` joins when called on the server thread — holding each with its own ticket type
  (the chunk source's one-tick ticket cancels an off-thread request). A batch is queued row by row across its short
  side, and a finished chunk keeps its ticket until nothing queued lies within the generation radius (8) of it,
  released a few per tick: in 1.21.1 chunk NBT is encoded (unload) and decoded (load) on the server thread, and a
  ticket dropped on completion unloaded a 17×17 halo the next request read straight back — that, not generation,
  pinned the server thread on the VPS. `KnownTerrain` copies heightmaps on the server thread when a chunk is ready. A road planned over any `ESTIMATED` cell is provisional: its corridor is
  generated, then the pair is planned again with the old road excluded (`PassRequest.replace`) and swapped on apply.
  **Terrain sources:** own pre-generated chunks first (`KnownTerrain`), then `DhTerrain`, which reads Distant Horizons' generated terrain through its API when the mod is
  present (optional compile dep `distanthorizonsapi`; DH classes only referenced inside `DhTerrainAccess`); cells
  without DH data fall back to the density estimate and carry `Terrain.ESTIMATED`; such tiles are `provisional`
  (memory only, re-sampled after a minute) and never reach the `known`/`known16` disk caches.
  `/postroad roads debug terrain` draws the planner's cells (step class colours, flags, estimate-vs-real ticks,
  estimates faint). `TownFinder` replays every structure set: structures tagged `#postroad:towns`
  (`#minecraft:village` plus villages mods leave out of it) become towns, other surface structures
  obstacles (`PlannedObstacle`, blocked with `plan.structureMargin`); structures that come out buried a few times
  in a row are skipped for good. Structures tagged `#postroad:passable` (landscape structures such as BWG's
  plateaus and arches, underground ones whose box reaches the surface such as mineshafts) are neither town nor
  obstacle — the tag file is `data/postroad/tags/worldgen/structure/passable.json`, a pack extends it.
  **Pit stops** (`docs/increment-8.md`): `PitStops` runs in the chunk-load builder's tick like the junction signs.
  Each town's depot is a schematic (`structure/pit_stop_<style>.nbt`) beside the end of its first road the builder
  reaches (or a street exit when no road will come), bound to the predicted place in `Network` before the depot
  ticks, because it stands outside the village pieces `PlaceResolver` looks in. Other road ends of the town get a
  signpost (`RoadBuilder.erectSignpost`, shared with the junction signs). State: `PlannedTown.stop`/`stopDone`,
  `PlannedRoad.endsDone`; a standing stop blocks its inner cells for later passes (`PassRequest.stops`).
- `roads/Routing` — Dijkstra over anchors (nodes + link ends) on the charted path polylines. Links join
  even on uncharted roads (no travel along them); a town node is a hub, and inside a town every road counts as
  charted: it joins the generated road ends into its town (`TravelService.townEnds`, from `RoadPlanStorage`
  from/to — roads end at street exits, not the depot) and every node, link end and charted path in its
  `TownArea` (village box + 16, else 48 around the depot; `TravelService.townAnchors`). Several roads end on one
  street-exit block and a walk or sign links to whichever comes first, so an uncharted one there must not cut it off.
- `travel/` — `Fares`, `TravelService` (open list, depart: fare, teleport — the inventory travels along), `SignNodes`
  (map-on-sign links/unlinks, right-click on a linked sign opens travel, sneak + right-click reaches the editor,
  dye/ink/wax still apply, breaking drops the node; block tag `#postroad:sign_nodes`), payloads + `TravelClient`.
- `mailbox/` — the player's mailbox block; contents are in `Network`, the block entity only holds its place id.
- `mail/MailService` — parcels: lane split, arrival days, delivery on day change; `network/Parcel`.
- `menu/` — `DepotMenu` (one 54-slot grid, backing container swapped per tab; ints via data
  slots, names via `DepotStatePayload`), `DepotState` + the two payloads, `PostroadNetworking`.
- `client/` — `DepotScreen`, `SatchelScreen` (a one-row chest, the sell button on the inventory label's line), `ClientSetup`. Only referenced from the `Dist.CLIENT` branch in `Postroad`.
  `DepotScreen`'s body is the vanilla 6-row chest to the pixel (176×222, so it fits at every GUI
  scale); the depot's controls sit in a panel attached to its right, outside the rectangle a recipe
  viewer can see — hence `columnArea()`, the `hasClickedOutside` override (a click on the column
  must not throw the carried stack) and `compat/PostroadEmiPlugin`.
- `map/MapOverlay` — what map mods show: towns with a depot and every `Network` path (simplified
  polyline, charted or not, end-town names from the plan). Sent on login and to everyone when a cheap fingerprint
  changes (every 5 s); only to connections with the channel (game-test mock players have none). `MapOverlayClient`
  holds it client-side, no client imports. `map.roadsBeyondExplored` decides whether roads show on unexplored map.
- `advancement/PostroadAdvancements` — mod-granted milestones (impossible trigger) for FTB Quests.
- `command/PostroadCommands` — `/postroad …`.
- `compat/PostroadJadePlugin` — Jade block tooltip for the mailbox. `compileOnly` on Jade's Modrinth
  maven artifact; the class is only ever loaded by Jade, so keep every Jade import inside `compat/`.
- `compat/PostroadEmiPlugin` — hands EMI the depot screen's control column as an exclusion area.
  Same rules as the Jade plugin: `compileOnly` on EMI's Modrinth artifact, loaded only by EMI
  (`@EmiEntrypoint`, found by NeoForge's annotation scan), every EMI import inside `compat/`.
  NeoForge 21.1 has no `getGuiExtraAreas`, so a recipe viewer has to be told through its own API.
- `compat/xaero/` — the map overlay on Xaero's Minimap and World Map. Xaero has no documented API; this uses the
  public classes Xaero's own Waystones and Open Parties and Claims support use: towns as the third-party
  waypoint origin `postroad:towns` (switchable in Xaero's waypoint options), only towns whose 512-block region the
  player has explored on the World Map (`XaeroExplored`), and within 768 blocks in the world and on the minimap,
  further out on the world map only; roads as an
  `AbstractHighlighter` painting per block. The world map freezes its highlighter list at session start, so
  `XaeroRoadHighlights` adds ours by reflection (Xaero jars are automatic modules, so the field is reachable).
  Highlight colours are `B<<24 | G<<16 | R<<8 | A`. `compileOnly` on the Modrinth artifacts; registered from
  `ClientSetup` only when the mods are loaded; a failing part logs once and switches itself off. Every Xaero import
  inside `compat/xaero/`. `./gradlew runServer -PwithXaero` and `runClient -PwithXaero -PquickJoin=localhost`
  bring Xaero into the dev runs.

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
