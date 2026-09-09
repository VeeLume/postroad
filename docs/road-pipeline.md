# How roads are planned and built — an analysis

Written 2026-09-07 at Valerie's request after two days of fixes that each
moved a defect instead of removing it. Everything below is what the code does
today (commit 8f4b413 plus the `roads known` measurement command), with the
evidence measured on the test server, and a list of the defects with their
mechanism. No fixes are proposed inside the sections; they are collected at
the end so the decisions stay separate from the description.

Vocabulary: **first-air height** of a column is the y of the first air block
above the ground (a heightmap's value). The planner stores first-air heights.
The road's **surface block** is one below that: the block a player walks on.

---

## Part 1 — The planner

### 1.1 What it produces

A **plan** is a set of roads, each a polyline of **points** 3 blocks apart
on a grid (`RoadGen.CELL_SIZE = 3`), every point a `BlockPos` whose `y` is
the planner's first-air height at that cell. Each road links two towns'
street exits, stores one biome family per point, and carries two flags:
`provisional` (some point was planned over estimated terrain) and `replans`.
Roads share points where one joins another (a `PlannedJunction`). The plan is
saved data (`postroad_roadplan`), and it is also published as an immutable
per-chunk **snapshot** for worldgen (Part 2).

### 1.2 The terrain the planner sees

`Terrain` is a grid of **cells** with a height (first air), flags
(WATER, LAVA, BLOCKED, ROAD, ESTIMATED) and a biome family. Two grids exist:

- the **fine** grid, 3-block cells — the piece grid the roads are laid on;
- the **coarse** grid, 12-block cells (`COARSE_CELL`), used only to find a
  corridor before the fine search.

Both are `TiledTerrain`: tiles of 16×16 cells sampled lazily on the planner
thread when a search first touches them. Each cell samples **one column**:
the cell's centre block. A 3×3 cell is therefore represented by the height of
its middle column; the other eight columns are unknown to the planner.

Height sources, in order of precedence per column (`WorldTerrainSampler`):

1. **Own generated chunks** (`KnownTerrain`): chunks the corridor
   pre-generation produced to the **carvers** step, recorded on the server
   thread from the chunk's heightmap, walking down past logs and leaves.
   Kept in memory only — gone after a restart.
2. **Distant Horizons** (`DhTerrain`): DH's stored column data, first
   ground point from the top. Measured 2026-09-07 at 7 columns: DH equals the
   finished world's first-air height in every one (67/67, 66/66, 65/65,
   108/108, 104/104, …). DH is exact where it has data.
3. **The estimate**: the noise router's `initial_density_without_jaggedness`
   bisected in y, the crossing interpolated inside the last noise cell, plus a
   calibration offset measured once against the generator's `getBaseHeight`.
   Measured: 1–2 blocks **high** at the columns above (68 vs 66/67, 110 vs 108,
   67 vs 65). Every cell that used it is flagged ESTIMATED, and a tile holding
   any such cell is *provisional*: re-sampled after 60 s (`REFRESH_MS`) or
   when a pass explicitly refreshes it.

Water: a known column is water when liquid sits on it; an estimated one when
its height is below sea level or the biome is an ocean/river. BLOCKED is an
overlay: every predicted structure's box with a margin (`structureMargin`,
8 blocks; village pieces 2 blocks, streets open). ROAD marks existing roads'
cells; those cells' heights are **overridden** with the road's stored heights
(`setHeight`) — only for roads that are not provisional (since 8f4b413).

### 1.3 A pass, step by step

Passes are queued (`RoadGen.schedule`) at server start, when a town is
found near a player, when a corridor finishes generating (a replan), and by
`/postroad roads plan`. One pass at a time runs on the planner thread
(`runPass`); the terrain is frozen for its duration (stale provisional tiles
are dropped *before* it starts, never during).

1. **Town discovery.** Every 256-block discovery square within `radius`
   (1500) that has not been searched is searched chunk by chunk with
   `TownFinder`, which replays the structure sets and generates the village
   layout in memory: the village's box, its pieces' boxes and its street
   positions become a `PlannedTown`; any other surface structure becomes a
   `PlannedObstacle` (buried ones skipped). This is the expensive step
   (3–6 minutes on a fresh world, ~2 s per village layout).
2. **Blocking.** Obstacle boxes plus margin and village pieces plus 2 are
   marked BLOCKED on both grids; the bounds of both grids are the pass centre
   ± (`radius` + `maxLink`).
3. **Pairs.** Each town in reach gets its `neighbours` (3) nearest towns
   within `maxLink` (900); pairs are planned longest first, so trunks exist
   before spurs. Pairs already planned or already dropped are skipped.
4. **Endpoints.** A town's endpoint is its street cell that faces the other
   town (`exitToward`). If that cell is blocked or outside the corridor, the
   resolver walks toward the other town up to 160 blocks
   (`ENDPOINT_REACH`) for the first passable cell, else the nearest one in
   rings.
5. **Coarse route.** A* on the coarse grid from the town's coarse cell to the
   other's, with a weak heuristic (0.15) so it prefers riding existing roads.
   The step classes are defined per fine cell (3 blocks); a coarse move spans
   12, so its height difference is divided by the grid ratio 12 / 3 = 4 (and
   by √2 on a diagonal) before the same classes apply. The coarse search only
   rejects corridors across slopes no fine route could climb; nothing here is
   the old 4-block cell.
6. **Corridor.** Every coarse cell of that path ± 2 cells (`CORRIDOR_HALF` =
   24 blocks each side), plus a disc over each town's whole box. Fine tiles
   are sampled only inside it.
7. **Fine route.** A* on the 3-block grid inside the corridor. Moves are the
   catalog's pieces: a cardinal move may change height by at most the largest
   straight rise (3) at that piece's cost (0 / 1 / 4 / 9); a diagonal move by
   at most the largest diagonal rise (2) at cost 0.5 / 2 / 6; base cost 1 per
   cell, water +40, a penalty for leaving the band between the two towns'
   heights, cells of an existing road at 0.15× (reuse). Budget 600 000
   expansions. An endpoint that turns out to be an island (the search dies
   within 64 cells) is resolved again past it, up to 4 tries.
8. **Water check.** A route with more than 6 consecutive water cells (18
   blocks) is dropped as "water".
9. **Snapping.** Where the route leaves an existing road and rejoins it within
   16 cells, the excursion is replaced by that road's own cells, provided the
   result still passes the step classes on today's terrain.
10. **Junctions.** Where the route's own cells first touch another road's
    cells (and where they leave them) a junction is recorded.
11. **Back to blocks.** Each cell becomes `BlockPos(centre x, height, centre
    z)`; cells shared with a non-provisional road take that road's stored
    height. The result is checked segment by segment against the catalog; a
    route with any segment no piece fits is dropped with a log line.
12. **Provisional roads.** A route over any ESTIMATED cell is stored as
    provisional and its corridor (road cells ± 1 chunk) is queued for
    pre-generation to the carvers step (`ChunkPregen`, 16 chunks in flight,
    own ticket type). When the corridor is done, a **replan** pass runs with
    the corridor's tiles refreshed and the road id in `replace`: the pair is
    planned again on the real heights; the new road swaps in atomically, or
    the pair is dropped when no route exists on the real terrain. At most 2
    replans per road (`MAX_REPLANS`); a road that has been built or laid
    meanwhile is frozen as it is.

### 1.4 What the planner cannot know

- **Anything but the centre column of a cell.** A road is 3 wide and its
  pieces are 3 long; the plan's height holds for one of the nine columns a
  square covers. On uneven ground the other eight are cut or filled to it.
- **The world after the carvers step.** Its best source (own chunks) is
  recorded at carvers; features that change the surface run later (see 2.6).
- **Slopes inside a cell.** A rise of 3 over 3 blocks is the steepest move; a
  cliff of 4 between two centres is impassable and the pair is "unreachable"
  (27 of 46 dropped pairs on the current world are that: real cliffs and the
  187–199-high mountain villages). There is no serpentine yet.

---

## Part 2 — Building the road

### 2.1 The snapshot

`RoadPlanSnapshot.publish` turns the plan into a map *chunk → segments*: for
every non-provisional road, every consecutive pair of points is a `Segment`
(with its neighbours for decoration checks), registered in every chunk its
footprint (± 3 blocks) touches. Published on the server thread after every
pass that added roads; read by worker threads.

### 2.2 Who lays a road

Two writers use the same laying code (`RoadPieceLayer`):

- **The road feature** (`worldgen/RoadFeature`), a placed feature at the
  `surface_structures` step in every overworld biome, once per chunk at its
  origin. It lays every segment of its chunk, clipped to the chunk, then
  records *which roads* it laid there (`laid`, per chunk and road).
- **The chunk-load builder** (`RoadBuilder`), the fallback: when a chunk
  loads, every road through it that the feature did not lay there (or that
  turned final after the chunk generated) is laid over a few ticks, then
  marked built. Junction signposts are its alone.

Provisional roads are in no snapshot and skipped by the builder until they
turn final (since 8f4b413).

### 2.3 From two points to a piece

`PieceCatalog.placement(a, b)`:

- the facing is the sign of `b − a` on x and z;
- straight (one axis) → the straight piece whose rise is `|b.y − a.y|`;
  diagonal (both axes) → the diagonal piece of that rise; none → no piece;
- **entry** anchor = the lower point's surface block (`y − 1`), **exit** = the
  higher one's; when the plan runs downhill the piece is placed **reversed**:
  entry at `b`, exit at `a`, facing turned around, stairs still facing the
  ascent.

A piece owns the blocks from the row after its entry anchor up to and
including its exit anchor's row: rows 1..3 (`LENGTH`) for a straight piece,
six 3×3 stamps for a diagonal (x first, then z, alternating). The entry
anchor's row belongs to the previous piece — whose exit it is — **when that
piece runs uphill or flat**. The catalog json now declares this as `entry`
`{along 0, level 0}` and `exit` `{along 3, level rise}`.

### 2.4 Rows and levels

Each row has a level relative to the entry anchor and a role:

| piece | rows (level/role) |
|---|---|
| straight_0 | 0 S · 0 S · 0 S |
| straight_1 | 1 slab · 1 S · 1 S |
| straight_2 | 1 stair · 2 stair · 2 S |
| straight_3 | 1 stair · 2 stair · 3 stair |
| diagonal_0 | six stamps at 0 |
| diagonal_1 | 0 · 0 · 1 slab · 1 · 1 · 1 |
| diagonal_2 | 0 · 1 slab · 1 · 1 · 2 slab · 2 |
| corner, end | one row at 0 (the anchor's 3×3 square) |

A row's three blocks sit at `entry.y + level`; the centre block is the
`surface` palette, the two outer blocks `edge`; a stair row is stairs facing
the ascent; a slab row bottom slabs. Walkability is by construction: the
top of row k and the bottom of row k+1 differ by at most half a block.

### 2.5 One column (`column()`)

For every road block at (x, z, top) the ground is read as the column's
**top solid block** (`groundAt`: the feature uses the `OCEAN_FLOOR_WG`
heightmap minus 1 at its step; the builder scans down 16 blocks from the
plan's height past air, leaves, plants and road materials) and the column
is made to fit:

- `ground > top`: **cut** — everything from `top+1` to `ground+3` is removed
  (three of headroom), unless the cut would exceed `fit.cut` (4) or a
  non-replaceable block is met;
- `ground < top`: **fill** — solid `fill` blocks from `ground+1` to `top−1`
  when the gap is ≤ `fit.fill` (3); deeper, a **deck**: only the block
  right under the road (`fit.deck`), air beneath;
- then the road block at `top`, and (since 52d52eb) a `fill` block directly
  under it whenever that block is not solid.

Water or lava on the column: nothing is placed.

### 2.6 Squares, decoration, diagonals

- **Squares** (`needsSquare`): the anchor's own 3×3 at the anchor's level is
  laid *after* the pieces at a road's two ends, wherever the facing changes,
  and at every anchor a descent runs into that is not followed by another
  descent (a reversed piece does not lay its entry). Laid last, it overwrites
  whatever a stair row put on that anchor.
- **Diagonals**: the six stamps each own their centre, and a ring block
  belongs to the first stamp that reaches it, so the band is laid once.
- **Decoration**: a post and a lamp on every 8th piece, on the side that
  alternates, only on solid ground and only where no piece's footprint lies.

### 2.7 What the laying cannot know

- Whether the plan's height is the world's. It lays the plan.
- The ground beyond its own columns: a 3-wide road on a side slope is a
  terrace by construction — cut uphill, fill or deck downhill.

---

## Part 3 — The defects, with their mechanism

**D1. Roads one block low, cutting the plain (this world, savanna near
spawn).** Measured with `/postroad roads known` on five untouched chunks:
in two of them **82 and 60 of 256 columns are one block higher in the
finished chunk than at the carvers step** — the block under the finished top
is a grass block that was not there at carvers (a surface feature of the
pack adds it; in a mountain chunk a stone layer). The corridor
pre-generation records at carvers, so in those biomes the plan is one low
for a third of the columns, and the road feature (which runs before that
feature or after it, depending on its step) cuts a trench with one-block
walls. The earlier "one block high" world was the opposite error: an
estimate-era height (1–2 high) inherited through a junction. Neither was
the planner's or the pieces' logic.

**D2. The plan holds one column per 3×3 cell.** Where the centre column is
a block lower or higher than its neighbours (common in Tectonic/BWG
terrain), the whole square is cut or filled to it. This is a resolution
limit, not a bug; it is what the "trench for no reason" of one block on
otherwise flat ground is when D1 is excluded.

**D3. Hills: pieces that do not meet.** Three mechanisms can produce it and
the current screenshots do not say which:
- a *corner square* laid after a rise-3 piece replaces the top stair with a
  full block at the anchor's level — walkable, but the run reads as broken;
- a diagonal piece next to a straight one: the diagonal's last stamp and the
  straight's first row overlap by one column and are laid twice (last
  writer wins, both at the same level — visible as a seam, not a gap);
- two roads sharing an anchor at a junction with different families or at
  a different index lay different blocks on it.
Nothing measures connector continuity today; the audit only compares plan
heights with ground.

**D4. The audit measures the wrong thing.** Its "ground" walks down past
anything that is not ground *by the DH definition*, which includes road
materials, so on a laid road it lands under the road and reports +1 for a
perfect road and +1 for a road one block high alike. That is why the last
three cycles all showed "+1: ~600" while the roads were high, then low.

**D5. Estimate bias.** The estimate is 1–2 blocks high in the biomes
measured (calibrated against the generator's base height, which is itself
one above the finished world here). It only matters until the corridor is
generated, but every provisional road is dropped or replanned on it, and
"unreachable on the estimate" pairs are never retried.

**D6. Own-chunk heights are memory only.** After a restart the planner has
no record of its own corridors; DH covers most of it, the estimate the rest.

---

## Part 4 — What follows (for Valerie to decide)

1. **Measure the world at the right step.** Either generate corridors to
   the features step (costly: full chunks) or find which feature raises the
   surface in this pack and record after it; and move the road feature to
   `top_layer_modification` so it lays on the final ground. Until then D1
   recurs in every biome with a surface feature.
2. **Audit the road, not the plan.** Replace the audit's ground walk with:
   at each anchor, the road's surface block vs the first-air height of the
   columns just outside the footprint (± 2 perpendicular). Reports "high" and
   "low" as they are. Add a **connector audit**: for every consecutive piece
   pair, the height difference between the exit row and the next entry row
   must be ≤ 0.5; list every failure with its coordinates. D3 becomes a list
   instead of a screenshot.
3. **Cell resolution.** Either accept D2 as the look of the road (a road
   *is* a terrace), or plan with the cell's *median* of nine columns rather
   than its centre, which needs nine samples per cell from every source.
4. **Persist own-chunk heights** next to the terrain cache so a restart does
   not lose them.
5. Serpentines, tiers — unchanged, after the above.

---

## Addendum 2026-09-09 — what the unreachable pairs were

The world at build 44c5b5b had 29 roads and 47 dropped pairs: 20 for water,
27 "unreachable". `/postroad roads trace` on all 27 (the trace now also counts
every neighbour the fine search refused, by reason, and runs the search a
second time with the turn rules off):

- **None was a budget or a slope problem.** Every search died with cells to
  spare, and in several the steep steps around the closed cells were a few
  dozen out of ten thousand.
- **The wall was obstacles.** Two families of structures were stored as
  obstacles and blocked, with margin: *landscape structures* — Oh The Biomes
  We've Gone places its gour plateaus and arches as structures with boxes of
  80–250 blocks a side and the full world height, dozens of them overlapping
  around a village — and *underground structures whose box reaches the
  surface* (Better Mineshafts, 150-block boxes; mesa mineshafts). A town
  ringed by plateau boxes cannot be left; a corridor ±24 blocks wide crossed
  by a mineshaft box is cut. **Fix:** the structure tag `#postroad:passable`
  (`data/postroad/tags/worldgen/structure/passable.json`) lists structures a
  road crosses like ground; the finder never records them as obstacles and
  stored ones of that kind block nothing (a pack extends the tag).
- **The turn rules were the wall for 2 of 25**, and those two need a hairpin
  (a turn past 90°) on a slope — a serpentine, which no piece builds. With the
  rules off the other 23 closed exactly the same cells. This also settles a
  question about the search itself: it closes a cell on its first arrival
  although the legal moves out of it depend on the direction it was entered
  in. That is complete while turns up to 90° are allowed and every turn kind
  of the catalog carries the step classes' largest rise (both true today; the
  turn limits then only refuse what the classes refuse), so the search stays
  one state per cell. The reasoning sits on `RoadPlanner.search`.
- **Two pairs were reachable when traced** — they had been judged on the
  estimate and never tried again (D5). Dropped-unreachable pairs still have no
  retry; that is the next planner item.

Trace pictures for the 27 pairs are in the world's `postroad/trace_<pair>.png`
(world `world` of 2026-09-07).


## Addendum 2026-09-09 (2) — D1 was the recorder, not a feature

`/postroad roads known` now generates a fresh chunk status by status and
compares each step's tops with the previous one, naming the block at the top
of every changed column. Noise, surface, carvers and features agreed; the
full chunk was one higher in a third of the columns — but the block was
already there at the features status. The heightmap was stale, not the
terrain. The bytecode then gave the real D1: `ChunkAccess.getHeight` returns
the y of the **top block** (`getFirstAvailable - 1`; `Level.getHeight` is the
one that adds 1), and `KnownTerrain.tops()` took it as the first air. Every
own-chunk height was one low wherever the block under the top is ground,
which is nearly everywhere, while the estimate, Distant Horizons and the
builder's ground scan all mean the first air. **Fix:** the recorder walks the
blocks down from the highest block past plants, logs, leaves and snow to the
first ground or liquid block and records the air above it; heightmaps are
only the starting point. The road feature's step stays at
`surface_structures`.
