# Increment 6 — Pieces as schematics with connectors

Decided with Valerie on 2026-09-07 after the analysis in `road-pipeline.md`.
The entry/exit piece model of increment 5 produced dips at anchors nobody
laid, double-laid anchors, and needed reversed pieces and hard-coded squares.
Every piece is now a small schematic anchored at its planned point, with
**connectors** instead of an entry and an exit, so a road is a chain of
matched connectors, every block has exactly one owner, and junctions,
corners, ends, bridges and tunnels are catalog pieces like the rest.

## The piece file (`data/postroad/roads/pieces/<name>.json`)

```json
{
  "id": "stairs_2",
  "cost": 4.0,
  "connectors": [
    { "at": [-1, 0, 0], "facing": [-1, 0], "level": 0 },
    { "at": [ 1, 2, 0], "facing": [ 1, 0], "level": 2 }
  ],
  "blocks": [
    { "at": [-1, 1, 0], "role": "stair", "facing": [1, 0], "span": [-1, 1] },
    { "at": [ 0, 2, 0], "role": "stair", "facing": [1, 0], "span": [-1, 1] },
    { "at": [ 1, 2, 0], "role": "surface" }, { "at": [1, 2, -1], "role": "edge" }, { "at": [1, 2, 1], "role": "edge" }
  ],
  "decor": [
    { "at": [0, 3, 2], "role": "post", "every": 8 },
    { "at": [0, 4, 2], "role": "lamp", "every": 8 }
  ],
  "fit": { "cut": 4, "fill": 3, "deck": true }
}
```

Coordinates are relative to the **anchor**, the planned point's road block
at `[0, 0, 0]`: x along the piece as authored, y the level, z across. The
piece's core is the 3×3 around the anchor; blocks outside it are allowed
(a diagonal's corner bridge, a tunnel's walls) but may not lie on a block
another piece owns.

- **`connectors`**: where other pieces attach — the piece's own edge block
  (`at`), the outward direction (`facing`, a unit vector on x/z; a diagonal
  connector has both set) and the walk height at that edge relative to the
  anchor (`level`). A straight piece has two opposite connectors, a corner
  two adjacent, an end one, a T junction three, a cross four.
- **`blocks`**: the structure, one entry per block: `role` (`surface`,
  `edge`, `stair`, `slab`, `fill`, `wall`, `air`, or anything a tier maps),
  `facing` on stairs. `span: [a, b]` repeats the block across z from a to b
  and `up: n` repeats it n blocks upward; both are shorthand that expands to
  plain coordinates and nothing else. Role `air` clears the block.
- **`decor`**: same form plus `every` (on every n-th piece of a road, sides
  alternating) and optionally `reach: "ground"` with `max`: the block repeats
  downward until the block below is solid ground, and is not placed at all
  when `max` is exceeded. Decoration is skipped where any road block lies.
- **`terrain`** (optional): when the assembler may pick this piece over the
  plain one with the same connectors — `minDrop` (ground at least that far
  below the road under any column: a bridge) or `minCover` (ground at least
  that far above: a tunnel). The most specific matching condition wins; a
  piece without a condition is the fallback.
- **`fit`**: how the ground is reconciled per road-block column (see
  increment 5): `cut`, `fill`, `deck`; `"none"` leaves the ground alone (a
  bridge or tunnel is its own reconciliation). One solid block under every
  road block is always guaranteed.
- **`cost`**, and for variants `costPerDrop` / `costPerCover`: what the
  planner pays for the piece, plus per block of drop or cover.

Defaults: `cost` 0, `fit` {4, 3, true}, no `decor`, no `terrain`, `every` 1.
The assembler does exactly four things a file does not spell out: rotates
and mirrors a piece into the road's facings, matches connectors, picks the
variant whose `terrain` condition holds, expands `reach`.

## Matching and placing

Two pieces connect when a connector of each faces the other, the two edge
blocks are neighbours, and the world walk heights agree:
`anchorA.y + levelA == anchorB.y + levelB`.

The assembler walks a road's points. At each point it knows the facings to
its neighbours and the height differences. It picks a piece with connectors
on those sides whose level difference equals the height difference, and sets
the piece's base so that its connector toward the previous point sits at the
previous piece's outgoing world level. Uphill and downhill are the same piece
rotated. At a junction the pieces on the arms carry the rises so the junction
piece is flat. The catalog's set of connector-level pairs per shape is the
planner's move set.

Today's pieces map one to one: the same nine blocks, owned by the anchor in
their middle instead of by the exit. Reversal, `needsSquare`, the corner and
end squares disappear from the code.

## The planner, later

For deliberate bridges and tunnels the planner gets one new move: hold the
level across a cell whose terrain is too far below or above for any rise
piece. The point stores the held height (the plan format already allows
it), the search state becomes (cell, height) with a cap on consecutive held
cells, and the move costs the variant's `cost` plus `costPerDrop` × drop or
`costPerCover` × cover. The assembler then selects the same variants by the
same conditions, so what is planned as a bridge is laid as one.

## Hosting a rise (found while building)

A piece cannot both rise into its anchor and fall away from it: hosting every
rise at the piece it climbs into puts a descending road's anchors above the
terrain by the drop. So the rise between two points is hosted by the
**higher** point's piece whenever possible, which keeps every anchor column
on its own terrain uphill and downhill alike. A hilltop would then host two
rises; a small assignment pass over the road moves one of them to the lower
point (that anchor sits above its terrain by the rise, the least bad choice).
Road ends get a virtual side in the road's direction, so an end is a plain
straight (or diagonal) piece rather than a special one.

## What the grid cannot do (found on the first worlds)

- A **bend** (cardinal side to diagonal side) is the one shape whose two
  connectors are of different kinds, so no rotation or mirror swaps them; it
  is authored in both directions (`bend_r`, `bendd_r`). Straights, diagonals,
  corners and diagonal corners are symmetric under the eight transforms.
- A **turn sharper than 90°** (a cardinal move followed by the diagonal that
  points back) has no piece: the diagonal's bridge blocks would land inside
  the neighbour's core. The planner no longer makes them — a cell cannot be
  left in a direction behind the line of travel — and `stepsFeasible`
  rejects them in snapped routes. On hills the planner had used them as a
  serpentine of sorts; the serpentine piece is the proper answer later.

## Authoring in the world (Valerie's review of 2026-09-07)

`/postroad roads showcase` lays every piece on a platform; a piece adjusted
there by hand is read back with `/postroad roads capture <id>` into
`<world>/postroad/pieces/<id>.json` (blocks mapped to roles by the temperate
palette; exposed cobblestone is a `paved` landing, cobblestone under road
blocks is left to the layer's fit). Captured files are copied into the
resources and listed in the generator's `HAND_AUTHORED` set, which then
neither writes nor deletes them but adds what a showcase build cannot show:
the joint blocks on each diagonal side. From the review: bend_3, bendd_2,
bendd_3, corner_2 and corner_3 are hand-authored (narrow stairs cost more:
14 / 8 / 15), dcorner_3 is gone, the lamppost stands on a paved footing at
the anchor row's level.

**Joints.** A diagonal contact between two tiles is only a corner; every
diagonal connector side now owns the four blocks outside the core that make
the joint a full 3-wide band, whichever way the road runs. Two tiles sharing a
joint lay the same blocks at the same levels, so the double ownership is
harmless. This was the gap seen wherever a bend or diagonal met.

**Turns are moves.** The planner takes per turn kind (cardinal/diagonal in,
cardinal/diagonal out, 0/45/90°) the largest rise the catalog has, and refuses
a hosted rise its kind cannot carry: with dcorner_3 gone no route climbs 3
through a diagonal corner, and the catalog check at storage time no longer
has to drop it.

## Laying rules found in real use (2026-09-07, second walk)

- **Protected road blocks.** Two pieces share a joint column at different
  levels (a flat piece's block at 0, the next diagonal's slab at 1). A piece's
  headroom clearing or cut must never remove a road block another piece laid;
  every road block laid in a run (a chunk's feature call, a builder job) is
  recorded and later pieces skip it. Laying order no longer matters.
- **A shared anchor belongs to one road.** At a junction each road assembles
  its own piece at the shared point and two pieces stacked. Until junction
  pieces exist, the road with the smaller id lays the shared anchor and the
  other skips it (`RoadPlanSnapshot.anchorClaims`).
- **The cut cap leaves a passage, not a gap.** Past `fit.cut` the column used
  to be skipped, stairs included; now the road block is placed and three
  blocks above it cleared: a rough passage through the hill, until tunnels.
- **Joint shape** (Valerie's): the 2×2 contact plus one block beside each
  core edge, `(2f, 1), (1, 2f), (0, 2f), (2f, 0)` for a corner facing `f`.

## Order

1. Format, loader, assembler, layer; convert the current nine files; the
   showcase and the piece debug layer follow the new placements.
2. T junction and cross pieces; the planner marks junction points so the
   assembler uses them.
3. The level-hold move; first `bridge_0` and `tunnel_0`.
