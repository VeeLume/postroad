# Increment 4 — Generated roads

Increments 1–3 built the civilisation layer on roads that already exist:
village streets and what players lay down. This increment makes the world
lay roads of its own: a trunk network between towns, planned ahead of the
player without generating chunks, built into chunks as they load, with
junctions where routes merge and a way sign at every junction.

It plugs into increment 3's model without changing it: a generated road is a
`RoadPath` like any other, junction signs are sign nodes, and nothing is
travellable until someone has walked it with the Charting Map.

## Scope

In:

- **Town discovery ahead of generation** — where villages *will* be, from
  structure placement and the biome source, no chunk generation.
- **Terrain model** — coarse height and biome grid from the chunk generator's
  noise, computed off-thread, cached on disk.
- **Planner** — cheapest-path routes between neighbouring towns on that grid
  with route reuse, so later routes merge into earlier ones and junctions
  arise instead of parallel roads.
- **Builder** — the plan stored per chunk; segments built when their chunk
  loads, budgeted per tick; palettes by biome family; lampposts; junction way
  signs; a rebuild pass for chunks that were loaded before the plan existed.
- **Network integration** — generated paths registered uncharted; charting a
  walk along one marks it charted; junction signs become nodes.
- Commands, config, tests.

Out: local spurs to minor structures, bridges, tunnels, cargo box, road
works. Out for now: the Rust core (see Decisions).

## Rules

- **Never block the server thread on world generation.** Sampling and
  planning run on one background thread; only block placement and network
  changes run on the server thread, under a per-tick budget. Nothing sizes
  itself to the machine. (RoadArchitect #55 and the thread-pool incidents.)
- **Anticipatory, not reactive.** The plan for an area exists before its
  chunks generate, so a road can be built in the same pass as the terrain
  it crosses and never cuts through a building it did not know about.
- **One road per corridor.** A new route within `merge distance` of an
  existing road joins it. Junctions are where routes meet; two roads never run
  side by side.
- **Roads respect structures.** Structure bounding boxes are impassable to
  the planner except at their edges, where a road may end.
- **Generated roads are walked like any other.** The generator records the
  path uncharted; the Charting Map opens it. Exploration stays how the map
  opens up.

## Finding towns ahead of time

- The village structure sets (`minecraft:villages` and the Towns and Towers
  and BWG sets) use random-spread placement: for any chunk,
  `StructurePlacement.isStructureChunk` says whether a village *may* start
  there, with no world access.
- Whether it *does* depends on the biome at the start: the structure's
  biome tag against `BiomeSource.getNoiseBiome` at the chunk centre. Vanilla
  and the two village mods decide the same way, so the prediction matches
  generation. Where it does not (a village that fails for another reason),
  the road ends at an empty spot; the town node never appears; harmless.
- Discovery runs for a square of `plan.radius` (1500 blocks) around each
  player and around spawn, on the background thread, and is bookkept per
  256-block square: a square is searched once. Prediction replays the chunk
  generator's own decision: the set's placement says whether a village may
  start in a chunk, then the set's weighted draw and `Structure.generate`
  (jigsaw layout, then biome check) say whether it does. Vanilla builds the
  whole layout before testing the biome; the finder tests the biome at the
  start corner first and only builds the layout for the structure that
  passes. The layout is thrown away; its bounding box is kept.
- A predicted town is a **candidate node** at the structure's start position.
  It becomes a real `town` node when its depot registers (increment 1); the
  candidate carries the same id so the road already knows it.

## Terrain model

- Grid cell: 4 blocks. Per cell: the noise router's preliminary surface
  (`initial_density_without_jaggedness`, the same estimate vanilla uses for
  surface depth — 2-D, so far cheaper than `getBaseHeight`'s full column;
  it is monotonic in y, so the crossing is bisected in ~6 evaluations;
  non-noise generators fall back to `getBaseHeight`), derived flags: water
  (surface below sea level, or an ocean/river/beach biome) and the palette
  family. The biome is sampled once per 16 blocks — the climate sampler is
  the expensive call and biomes do not change at cell resolution. Slope is
  computed from neighbours at search time. The first test-server pass
  (Tectonic worldgen) took ~10 ms per cell with a plain 48-step column
  scan and per-cell biomes; this is the fix.
- The generator's noise calls are pure functions of position and the world's
  `RandomState`; they are safe off-thread and are what RoadArchitect used
  successfully. They are not cheap on modded worldgen, so the grid is filled
  lazily per 16×16-cell tile as the planner asks for it, and every tile is
  persisted (`postroad/terrain/<dim>/<tx>_<tz>.bin`) so a restart never
  resamples.
- Structure boxes: the predicted village's start box, expanded by
  `plan.structureMargin` (8), marked impassable in an overlay on the tiles
  (cached tiles stay pure terrain). A town's own road ends at the nearest
  passable cell outside its box.

## Planner

- **Graph**: the cell grid with 8-neighbour moves. Cost per step: base 1,
  plus slope penalty (`|Δh|` squared, capped high), plus water (very high),
  lava (impassable), impassable structure cells, and a **reuse discount**: a
  cell already on a planned road costs a fraction of base. Cost is
  data-driven (`data/postroad/roads/planner.json`).
- **Step classes** (playtest 15, Valerie's model): the height change over a
  4-block cell is classed — *flat* (0, free), *step* (≤ 2 blocks, a slab, +1),
  *stairs* (≤ 6, a run of stairs with fill and cut, +6), *serpentine* (≤ 12,
  +40, switchbacks later) — and beyond the last class new ground is
  impassable. Existing road cells carry no step cost, so a second route
  rides an existing stair section instead of cutting its own: shared
  climbs, the natural pattern. A diagonal step covers 5.7 blocks and is
  judged per distance, so a cliff taken diagonally is one class gentler.
  The builder shapes the same classes: it smooths the road's profile to
  one block per column, fills below (`fill` block, ≤ 6) and cuts above
  (headroom 3), puts a slab on a lone step and stairs facing uphill on a
  run. Bridges and elevated roads will be further classes. An **elevation
  band**: cells above the higher town or below the lower one (past a margin)
  cost `bandPenalty` per block, so a road keeps to its towns' height and goes
  around a mountain instead of over it; a town's **exit** is where the line
  to the other town leaves its box, not the box edge nearest the centre; a
  route that leaves an existing road and rejoins it within 16 cells is
  **snapped** onto the road (the small rings at junctions); pairs the planner
  gives up on keep their reason (water, unreachable) and are drawn red in
  the debug view.
- **Which pairs**: for each town, its `k` nearest candidate towns within
  `plan.maxLink` blocks (k = 3, 900 blocks); duplicates removed; the pairs
  are planned longest-first so trunks exist before the spurs that join them
  (shortest-first produced Y-shapes through intermediate towns in tests).
- **Route reuse and junctions**: because road cells are cheap, a later route
  runs toward the nearest existing road, joins it, and rides it; the point
  where it joins is a **junction**. A route that never touches an existing
  road is a new trunk. This is the Steiner-ish behaviour the design asked for
  without a Steiner solver.
- **Two levels.** Finding merges needs a weak heuristic, and a weak
  heuristic explores a wide ellipse; with 64 routes the ellipses covered the
  whole square and every cell had to be sampled (the first measured pass:
  4292 tiles, 327 s of planning). So each route is planned twice: first on a
  **coarse map** of 16-block cells (existing roads and structure boxes marked
  there too, weak heuristic, slopes scaled to the cell), which is where the
  merge is decided; then on the fine 4-block map inside a **corridor** one
  coarse cell either side of the coarse path, with the usual costs, so the
  road follows the actual valley and snaps onto the existing road's cells.
  The fine map is only sampled inside corridors.
- **Output**: one `RoadPath` per planned route (points every 4 blocks,
  tiers `paved` for trunks), `PathLink`s at junctions, and a junction record
  (position, the paths meeting there) for the builder.
- **The search is cheap, the map is not.** Sampling costs ~0.26 ms per fine
  cell on Tectonic worldgen; the searches themselves are sub-second. Both
  run on the planner thread. A `/postroad roads export` draws the plan as a
  PNG (heights, water, town boxes, roads, junctions) so the network can be
  judged by eye before a block is placed.

## Builder

- **Plan storage**: the planned polylines, chopped per chunk into segments,
  in a second SavedData (`postroad_roadplan`) so the travel network's file
  stays small. Each segment knows its palette family, whether it is a trunk,
  and its build state.
- **Trigger**: `ChunkEvent.Load` on the server. If the chunk has unbuilt
  segments, they join a queue; the queue is drained on the server thread with
  a budget of `build.blocksPerTick` (200). A chunk generated before the plan
  covered it is caught by the same event next time it loads; `/postroad
  roads rebuild` walks loaded chunks once.
- **Surface**: for each path point, the actual heightmap (`MOTION_BLOCKING_NO_LEAVES`)
  at build time, not the planner's estimate — the generated terrain is what
  it is. A 3-wide strip for trunks: centre block from the family's palette,
  shoulders from its edge palette, replacing grass, dirt, sand, gravel and
  snow layers only; never replacing anything a structure placed, never logs,
  leaves or water. Steps of one block are left as steps; steeper than that,
  the road climbs with the terrain and the buff still applies.
- **Palettes**: biome family (badlands / arid / wet / cold / mountain /
  temperate) from biome-id keywords, ported from the modpack's
  `tools/gen_road_styles.py` into `data/postroad/roads/styles.json`.
- **Lampposts** every `build.lampInterval` (24) blocks on alternating sides:
  a fence post with a lantern, palette-matched.
- **Junction sign**: at each junction, a Quark post (if present, else a fence)
  with a Supplementaries way sign (if present, else a vanilla sign on the
  post), placed one block off the road, arms pointed along each branch and
  labelled "To: <town>" via increment 3's `SignWriter`. Registered as a sign
  node on the generated path.
- **Town ends**: the finder keeps the village's pieces. House pieces are the
  impassable footprint (2-block margin); street pieces are **exits**, and a
  road starts at the street piece facing its neighbour, so it joins the
  village's own road instead of stopping at a box edge. When the depot
  registers it attaches to the road as any depot does.
- **Block-level pass** (`RoadRefiner`, at build time): per chunk run, A* on
  the real terrain inside a 6-block corridor around the planned line —
  trunks, water and footprints impassable, real steps priced, diagonal
  climbs dearer (stairs only face cardinals) — ending exactly at the exit
  point the next chunk starts from. The profile along the path is then
  flattened (features shorter than 12 blocks cut ≤ 4 or filled ≤ 6) and
  limited to one block per column; a lone step gets a slab, a run stairs.

## Network integration

- Generated paths are registered with `charted = false` (new field on
  `RoadPath`, default true for existing data). `Routing` ignores uncharted
  paths; the travel list never shows what nobody has walked.
- **Charting marks, not duplicates**: when a walk is finished and at least
  60 % of its samples lie within `chart.joinDistance` of one uncharted
  generated path, that path is marked charted (and its tiers upgraded where
  the samples are better) instead of a new path being recorded. The usual
  road-share rule applies first, so an unbuilt plan cannot be "charted".
- Junction sign nodes exist from build time but are unreachable until their
  path is charted; the sign's arms still show where the road goes, which is
  the point of a sign.

## Scheduling

- One daemon thread, `postroad-planner`, lowest priority. Work items: discover
  towns around a position, fill terrain tiles, plan pairs. Results are handed
  to the server thread through a queue and applied there.
- Triggers: server started (spawn area), player moved more than
  `plan.repassDistance` (256) blocks since the last plan pass for them, and
  `/postroad roads plan`. One pass at a time; a pass is a snapshot of config
  and storage (`PassRequest`) plus the worker's terrain, and returns a
  `PassResult` the server thread applies — the same call the tests make
  synchronously.
- Overworld only for now; other dimensions have no villages worth linking.
- Nothing runs while the queue for the server thread is longer than
  `build.maxQueuedChunks`; back-pressure instead of growth.

## Commands and config

`/postroad roads status` (towns known, routes planned, chunks pending),
`/postroad roads plan` (run a pass now), `/postroad roads rebuild`
(re-queue loaded chunks), `/postroad roads clear` (op; drops the plan, not
the network).

Config: `plan.enabled`, `plan.radius` (1500), `plan.maxLink` (900),
`plan.neighbours` (3), `plan.repassDistance` (256), `plan.structureMargin`
(8), `build.blocksPerTick` (200), `build.lampInterval` (24),
`build.maxQueuedChunks` (64), `build.width` (3). Costs
(`roads/planner.json`) and palettes (`roads/styles.json`) are data files.

## Test plan

Game tests (all headless):

1. Planner on a synthetic grid: a flat field with a river gives a route that
   detours; a hill gives a route that contours; two towns on either side of a
   structure box give a route around it.
2. Route reuse: three towns in a line, the third route joins the first
   road instead of running parallel; exactly one junction.
3. Builder on the arena: a two-chunk plan builds a 3-wide strip on the
   surface with the arena's family palette; a re-run builds nothing twice.
4. Charting marks: a walk along an uncharted path flips it charted and
   records no new path; a walk elsewhere still records a path.

In play: whether predicted towns match generated ones, how the trunk looks
against Tectonic terrain, junction sign placement, the tick cost of building
while flying, and the planner thread's behaviour on the test server (jcmd
sampling as in the RoadArchitect investigation). Two tools for this:
`/postroad roads export` draws the plan as a PNG, and `/postroad roads
debug` (op) overlays roads, junctions, towns, nodes and dropped pairs in
the world — the picture shows the network, the overlay shows the terrain
the network ignores.

## Decisions

- **Kotlin planner first.** The design note reserves a Rust core over JNI.
  Not in this increment: the search is small enough for a background thread,
  and the JNI packaging (two native targets, extraction, `catch_unwind`) is
  its own project. The planner takes flat arrays and returns polylines, so a
  Rust implementation can replace it behind the same interface if play shows
  it is needed.
- Water is avoided by cost, not bridged. Bridges are a later increment with
  their own look.
- Trunks are paved and lit from the start; the design's "local paths: dirt
  spurs to minor structures" waits for a later increment.
- 4-block cells: fine enough that roads follow valleys, coarse enough that a
  3000-block square stays in memory.
