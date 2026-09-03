# Increment 3 — Signs and travel

Increment 2 moves goods; this one moves people. Roads get recognised and
graded, a walked road becomes a *path* of the network, and paths can be
travelled along for a fare. The road buff makes any recognised road worth
walking even before the network exists. Via Romana leaves the pack at the end
of this increment.

Generated roads and their junctions are increment 4; this increment is built so
they plug into the same path-and-node model without changes.

## Scope

In:

- **Road recognition** with three quality tiers (dirt, gravel, paved).
- **Road buff** — movement speed on a recognised road, scaled by tier, for
  players and their mounts.
- **Charting** — a map item records a walk, free-form; the walked route
  becomes a *path* if it is road enough. Paths branch off existing paths.
  Recorded per network.
- **Nodes** — a town's depot, and any sign (Supplementaries sign post,
  vanilla sign) a player links to a path with the map.
- **Travel** — left-click a linked sign (or the depot's Travel button): a
  list of every node reachable along paths, each with its fare; pick one,
  pay, arrive.
- **Fares** — free below a distance, above it distance divided by road
  quality; wallet first, road fund as fallback.
- **Fresh loot at teleport** — stamped stacks come off the traveller and are
  mailed to the destination town.
- Advancements, commands, config.

Out (increment 4): the generated trunk network, automatic junctions, road
building. Out (increment 5): cargo box, road works.

## Rules

- **Time is the cost of movement; coins buy time.** Walking is free and
  always possible. Teleport costs coins above the free distance, and the
  road's quality sets the price. Rail stays physical and free.
- **Treasure travels slowly.** Fresh loot never teleports. It is not blocked,
  it is mailed, with the lanes from increment 2.
- **Recorded per network.** Whoever charts a path first opens it for the
  whole group; the ledger names them. Nothing requires a second player.
- **Generated roads need charting too.** Increment 4's roads arrive
  unrecorded; the map is how the world opens up.
- **Respect the sign's own click.** Right-clicking a sign post opens
  Supplementaries' text editor (vanilla signs: their editor); every Postroad
  action on a sign goes through the map item in hand, or the left-click for
  travel, never the plain right-click.
- **Via Romana's flow, on purpose.** Chart with a map, place signs, link them,
  left-click to travel. The group knows it; the only differences are the fare,
  the loot rule, tiers, and that everything is shared per network.

## Road recognition and tiers

Via Romana's rule, reimplemented (its licence forbids copying code; the rule
itself is free):

- Sample the surface every 4–8 blocks along the walked route, radius 2; a
  route counts as road if at least 30 % of samples are path blocks.
- Path blocks: dirt path, packed mud, coarse dirt, rooted dirt, and any block
  id containing one of `sandstone polished cobble brick smooth basalt path
  road concrete pavement glazed tile wall fence slab stairs wool carpet plank
  log wood rail button pressure_plate`.
- **Tiers**, our extension: `dirt` (the dirt/mud family), `gravel` (gravel and
  `gravel`-named blocks, which Via Romana ignores), `paved` (everything else on
  the list). A stretch's tier is the tier that the majority of its
  samples fall in; a run of packed-mud path with a cobble bridge is dirt.
- The keyword lists and thresholds are a data file
  (`data/postroad/roads/rules.json`) so the pack can tune them.

## Road buff

- Every 10 ticks, per player: if the block under the player (or under their
  vehicle) is a path block, apply a movement-speed attribute modifier scaled
  by tier; remove it otherwise. Mounts get the same modifier.
- Defaults `dirt +10 %`, `gravel +15 %`, `paved +20 %`, config. This is layer
  one of the design: no ceremony, any road, generated or built.

## Paths and nodes

```
Network (existing)
├── paths: Map<PathId, Path>
│     Path { id, dimension, points: List<BlockPos>, tiers: List<Tier>,
│            recordedBy, recordedDay, branchOf: (PathId, pointIndex)? }
└── nodes: Map<NodeId, Node>
      Node { id, kind: town | sign | junction, dimension, pos,
             pathId, pointIndex, name, placeId? }
```

- A **path** is the walked polyline: one point every 4–8 blocks of movement,
  each classified (road or not, and which tier). A path that starts or ends
  within `chart.joinDistance` (8) blocks of a point on an existing path is a
  **branch**: it attaches there, and the attachment point becomes a
  `junction` node without a sign. That is how "chart from one of its nodes"
  extends, branches, or connects.
- A **town node** is the depot; it attaches to the nearest path point within
  `chart.joinDistance` whenever a path is recorded or a depot registers.
- A **sign node** is a sign the player linked with the map (see Charting);
  it attaches to the nearest path point within `chart.joinDistance`, and gets
  a name (default: the sign's text, else the nearest town).
- The routing graph: nodes on one path are adjacent in point order, with the
  polyline length between them as edge weight and the worst tier along it.
  Branch junctions join paths. Removing a sign removes its node; removing a
  path removes its nodes and detaches its branches.

## Charting

The item: **Charting Map** (`postroad:charting_map`), Via Romana's recipe
shape — paper, string, feather, ink sac. It holds no data; charting state is
per player on the server.

1. **Right-click the map**: a small screen with *Start charting* (or *Finish
   charting* while active), *Remove branch*, *Sever path*, and the running
   readout while active ("312 blocks · 71 % road · gravel").
2. **Walk.** The server samples the player's position every 4–8 blocks of
   movement and classifies each sample. Every sample spawns a short particle
   column where it was taken — green for a road sample, grey for not — so the
   player sees the path forming and where the road is failing the rule, as
   Via Romana does. Charting is abandoned on death, on any teleport, on
   leaving the dimension, or after `chart.maxLength` blocks (4000).
3. **Finish charting** from the map screen, anywhere. If at least 30 % of
   samples are road, the path is recorded per network: its points, tiers,
   who walked it, and whether it branches off an existing path at either end.
   The ledger gets a `path` entry; `path_charted` fires. Below 30 %, the walk
   is refused with the share and the failing stretches described ("41 % road
   — mostly grass between 120 and 260 blocks in").
4. **Place a sign** anywhere along the path — a Supplementaries way sign on a
   Quark post, or a vanilla/hanging sign — and **use the map on it**: it links
   to the nearest path point within 8 blocks and takes the sign's own text as
   its name (else "Signpost near <town>"). Using the map again unlinks it.
   **Auto-naming** (config `signs.autoName`): a way sign's arms are pointed
   along the path, one per direction, at the next node that way, and written
   "To: <name>" through Supplementaries' own `pointToward`. The courier post
   carries a hanging sign over its door that the depot labels with the town
   name when it registers. This replaces Via Romana's
   extra button in the sign editor, which we cannot add to Supplementaries'
   screen without a mixin.
5. **Remove branch / Sever path** act on the nearest path point: remove
   deletes the branch the player stands on (back to its junction), sever cuts
   the path in two at that point. Both are per network, both go in the log
   with the player's name.
6. Re-charting over an existing path upgrades its tiers where the new samples
   are better (the road was paved since); nothing else changes.

## Travel

- **Open** the travel screen by **left-clicking a linked sign** (the click is
  cancelled, the sign is not damaged) or from the depot screen's *Travel*
  button. Left-click on an unlinked sign does nothing special.
- **List**: every node reachable through the path graph, found by a
  shortest-path search weighted by polyline length. Sorted by route length.
  Each row: name, route length, tier of the worst stretch on the way, fare.
  A drawn map of the network like Via Romana's is a later polish item; the
  list is the function.
- **Fare**: `0` when the route length is below `travel.freeDistance` (500);
  otherwise `ceil((length − freeDistance) / (travel.blocksPerCoin ×
  tierFactor))` with `blocksPerCoin = 250` and tier factors `dirt 1.0`,
  `gravel 1.5`, `paved 2.0`, using the worst tier on the route. All config.
- **Pay**: wallet first, road fund for the rest; refused with the shortfall
  shown if neither covers it. Ledger entry `fare` with the destination.
- **Arrive** next to the node, on the standable spot nearest its path point
  — in front of the sign, on the road — never inside the sign's column.
- **Fresh loot**: before the teleport, every stamped-and-fresh stack in the
  traveller's inventory (hotbar included) is removed and sent as a parcel to
  the destination town — the node's town, or for a sign or junction the
  nearest town along the paths. Lanes as in increment 2; the express toggle
  is offered on the travel screen for the valuables part. The traveller is
  told what was mailed.
- No cooldown, no lockout. Distance and coins are the limits.

## Later

- A non-directional way sign block of our own (a post with a plaque); vanilla
  and hanging signs are the fallback until then.
- A drawn map of the network like Via Romana's.

## Advancements

`road_walked` (first buff), `path_charted`, `sign_linked`, `first_journey`,
`long_journey` (a fare above zero). Impossible trigger, mod-granted.

## Commands

`/postroad chart` (status / abort), `/postroad paths` (list with length, tier and
who charted them), `/postroad nodes`, `/postroad fare <node>`.

## Config

`roads.buffDirt/Gravel/Paved` (10/15/20 %), `chart.minRoadShare` (30),
`chart.maxLength` (4000), `chart.joinDistance` (8), `travel.freeDistance` (500), `travel.blocksPerCoin`
(250), `travel.tierFactorDirt/Gravel/Paved` (1.0/1.5/2.0).

## Pack side (not the mod)

Remove Via Romana and its config; a questbook step for the Surveyor's Map;
the buyback table pass noted in the vault.

## Test plan

Game tests: the classifier on synthetic strips (dirt path, gravel, cobble,
grass) and the 30 % threshold; tier majority; a synthetic walk recorded as a
path, a second walk attaching as a branch with a junction; a sign linked to
the nearest point; routing across a branch; fare formula at the free boundary
and per tier; charge order wallet-then-fund; fresh loot removed and mailed on
a simulated teleport. In play: the buff feel per tier, the particle readout
while charting, the travel list with a handful of nodes, and whether 500 free
blocks and 250 blocks per coin feel right against the coin income from loot.

## Decisions taken

- Nodes are existing blocks (depot; Supplementaries sign post on any post;
  vanilla signs too, via a block tag), no own sign block.
- Charting follows Via Romana's flow step for step — free-form walks, signs
  linked afterwards, left-click to travel — because the group knows it. The
  one change is where the *Add to path* action lives: on the map used on the
  sign, since Supplementaries' sign editor cannot take an extra button
  without a mixin.
- Travel first, generated roads next: the path model gets play feedback
  before the generator has to conform to it.
- Generated roads are charted like any other.
