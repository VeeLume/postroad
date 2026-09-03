# Increment 3 — Signs and travel

Increment 2 moves goods; this one moves people. Roads get recognised and
graded, a walked road becomes a *section* of the network, and sections can be
travelled along for a fare. The road buff makes any recognised road worth
walking even before the network exists. Via Romana leaves the pack at the end
of this increment.

Generated roads and their junctions are increment 4; this increment is built so
they plug into the same node-and-section model without changes.

## Scope

In:

- **Road recognition** with three quality tiers (dirt, gravel, paved).
- **Road buff** — movement speed on a recognised road, scaled by tier, for
  players and their mounts.
- **Nodes** — a town's depot, and Supplementaries sign posts that have a
  recorded section ending at them.
- **Charting** — a map item records the walk from one node to the next; the
  walked route becomes a section if it is road enough. Recorded per network.
- **Travel** — from any node, a list of every node reachable by chaining
  recorded sections, each with its fare; pick one, pay, arrive.
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
- **Recorded per network.** Whoever walks a section first opens it for the
  whole group; the ledger names them. Nothing requires a second player.
- **Generated sections need walking too.** Increment 4's roads arrive
  unrecorded; the map is how the world opens up.
- **Respect Supplementaries' own click.** Right-clicking a sign post opens its
  text editor; every Postroad action on a sign post goes through the map item
  or the depot screen, never the plain click.

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
  the list). A section's tier is the tier that the majority of its path
  samples fall in; a run of packed-mud path with a cobble bridge is dirt.
- The keyword lists and thresholds are a data file
  (`data/postroad/roads/rules.json`) so the pack can tune them.

## Road buff

- Every 10 ticks, per player: if the block under the player (or under their
  vehicle) is a path block, apply a movement-speed attribute modifier scaled
  by tier; remove it otherwise. Mounts get the same modifier.
- Defaults `dirt +10 %`, `gravel +15 %`, `paved +20 %`, config. This is layer
  one of the design: no ceremony, any road, generated or built.

## Nodes and sections

```
Network (existing)
├── nodes: Map<NodeId, Node>
│     Node { id, kind: town | signpost, dimension, pos, placeId? }
└── sections: Map<SectionId, Section>
      Section { id, a: NodeId, b: NodeId, length, tier,
                route: List<BlockPos> (simplified), recordedBy, recordedDay }
```

- A town's node is its depot block; `placeId` links it to the place. Town
  nodes exist as soon as the depot registers.
- A sign post becomes a node when a section is recorded ending at it (or,
  in increment 4, when the generator places it). Node ids are dimension plus
  position, so a sign post moved a block is a new node and the old one is
  dropped with its sections when the block is gone.
- `route` keeps every 8th sampled point, enough to draw on a map later and to
  compute length; it is not used for anything else in this increment.

## Charting

The item: **Surveyor's Map** (`postroad:surveyors_map`), craftable from paper
and a compass. One per player is plenty; it holds no data itself.

1. **Use the map on a node** (depot or sign post) with no charting in
   progress: the travel screen opens (see Travel). It has a *Chart from
   here* button; pressing it starts charting from this node.
2. **Walk.** The server samples the player's position every 4–8 blocks of
   movement and classifies each sample. The action bar shows the running
   length and the road share ("312 blocks · 71 % road"). Charting is
   abandoned on death, on any teleport, on leaving the dimension, after
   `chart.maxLength` blocks (default 4000), or by using the map with nothing
   near.
3. **Use the map on another node.** If the road share is at least 30 %, the
   section is recorded with its length and tier, the end sign post becomes a
   node if it was not one, the ledger gets a `section` entry, and the
   `section_recorded` advancement fires. Below 30 %, the walk is refused with
   the share shown, so the player knows how far off they are.
4. Charting back over an existing section re-records it if the new tier is
   better (the road was paved since), otherwise nothing changes.

## Travel

- **Open** the travel screen by using the map on a node; the depot screen
  also has a *Travel* button for towns.
- **List**: every node reachable through recorded sections, found by a
  shortest-path search over the section graph weighted by length. Sorted by
  route length. Each row: name (town name, or "Signpost near <town>" for a
  junction), route length, tier of the worst section on the way, fare.
- **Fare**: `0` when the route length is below `travel.freeDistance` (500);
  otherwise `ceil((length − freeDistance) / (travel.blocksPerCoin ×
  tierFactor))` with `blocksPerCoin = 250` and tier factors `dirt 1.0`,
  `gravel 1.5`, `paved 2.0`, using the worst tier on the route. All config.
- **Pay**: wallet first, road fund for the rest; refused with the shortfall
  shown if neither covers it. Ledger entry `fare` with the destination.
- **Arrive** at the node's position, facing away from the sign or depot, on
  a safe block found by scanning upward from the node.
- **Fresh loot**: before the teleport, every stamped-and-fresh stack in the
  traveller's inventory (hotbar included) is removed and sent as a parcel to
  the destination town — the node's town, or for a junction the nearest town
  along the sections. Lanes as in increment 2; the express toggle is offered
  on the travel screen for the valuables part. The traveller is told what was
  mailed.
- No cooldown, no lockout. Distance and coins are the limits.

## Advancements

`road_walked` (first buff), `section_recorded`, `first_journey`,
`long_journey` (a fare above zero). Impossible trigger, mod-granted.

## Commands

`/postroad chart` (status / abort), `/postroad sections` (list with tier and
who recorded them), `/postroad nodes`, `/postroad fare <node>`.

## Config

`roads.buffDirt/Gravel/Paved` (10/15/20 %), `chart.minRoadShare` (30),
`chart.maxLength` (4000), `travel.freeDistance` (500), `travel.blocksPerCoin`
(250), `travel.tierFactorDirt/Gravel/Paved` (1.0/1.5/2.0).

## Pack side (not the mod)

Remove Via Romana and its config; a questbook step for the Surveyor's Map;
the buyback table pass noted in the vault.

## Test plan

Game tests: the classifier on synthetic strips (dirt path, gravel, cobble,
grass) and the 30 % threshold; tier majority; section recording between two
depots in the arena; fare formula at the free boundary and per tier; charge
order wallet-then-fund; fresh loot removed and mailed on a simulated
teleport. In play: the buff feel per tier, the action-bar charting readout,
the travel list with a handful of nodes, and whether 500 free blocks and 250
blocks per coin feel right against the coin income from loot.

## Decisions taken

- Nodes are existing blocks (depot; Supplementaries sign post on any post),
  no own sign block.
- Charting follows Via Romana's model with one item and node-to-node walks,
  because the group knows it and it keeps the sign post's own click free.
- Travel first, generated roads next: the section model gets play feedback
  before the generator has to conform to it.
- Generated sections are walked like any other.
