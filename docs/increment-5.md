# Increment 5 — Road pieces

Decided with Valerie on 2026-09-06 after a week of the per-column road shaper
failing the same way each time: a one-block profile stamped 3×3 around every
column knows nothing about rows, directions or "this is one step", so stairs
came out as diagonal piles however the profile was paced. Roads are now built
from a **catalog of pieces** with fixed anchors and connection rules, defined
in JSON. What is not in the catalog cannot be built, and what cannot be built
cannot be planned.

## The grid

The planner's cell grid is the piece grid. A planned road is a polyline of
points 3 blocks apart, each with a height. A **piece** is what lies between
two consecutive points: the **entry anchor** and the **exit anchor**, each a
block position on the grid with a facing and a height (the road surface's
top block level, i.e. the planned point's `y - 1`).

- Base footprint 3 long × 3 wide (the 3×3 grid, decided 2026-09-06 evening). The anchor's own 3×3 (its row) belongs to
  the piece that *ends* there; the next piece starts on the row after it.
- Anything larger than the base footprint is decoration and may not lie on
  any piece's footprint, its own or another road's. Decoration is placed
  only where that holds.
- A piece's kind follows from its two anchors alone: same facing with a rise
  of 0…4 is a straight piece of that rise; a diagonal move is a diagonal
  piece (rise 0 only); a change of facing is two straight pieces meeting on
  a shared anchor row.

## Connectors (decided with Valerie, 2026-09-06 late evening)

Every piece declares an **entry connector** and an **exit connector**: `along`
(blocks from the entry anchor along the facing) and `level` (blocks above the
entry anchor). The entry is always `{0, 0}`; a straight piece's exit is `{3,
rise}`, a diagonal's `{3, rise}` on both axes. The assembler places each
piece's entry on the previous piece's exit, so a road is a chain of connectors
and every block between two anchors has exactly one owner: the piece whose
exit it is. Two shapes have both connectors on the same anchor: `corner` (the
square where the facing changes, and at a dip nobody else lays) and `end`
(the road's first and last anchor). They are catalog pieces like the others,
so a tier can give them their own look.

## The catalog (`data/postroad/roads/pieces/<name>.json`)

```json
{
  "shape": "straight",
  "rise": 2,
  "cost": 6.0,
  "rows": [
    { "level": 1, "role": "stair" },
    { "level": 1, "role": "surface" },
    { "level": 2, "role": "stair" },
    { "level": 2, "role": "surface" }
  ],
  "decor": [
    { "along": 4, "side": 2, "up": 1, "role": "post", "every": 6 },
    { "along": 4, "side": 2, "up": 2, "role": "lamp", "every": 6 }
  ],
  "fit": { "cut": 4, "fill": 3, "deck": true }
}
```

- `shape`: `straight` (3 rows along the facing) or `diagonal` (a 3-wide
  zigzag band of six stamps between two anchors 3 apart on both axes; rises by slabs).
- `rise`: exit level minus entry level, 0…3 for straights, 0…2 for diagonals.
  Descents use the same piece reversed (entry and exit swapped, stairs face
  the ascent). One definition per rise.
- `rows`: the piece's own three rows from the entry side (six stamps for a diagonal), each three blocks
  wide. `level` is the block level relative to the entry anchor; `role` is
  the material role of the row's blocks: `surface` (full block), `stair`
  (stairs facing the ascent, occupying the level), `slab` (bottom slab at
  the level), `edge` for the two outer blocks is implied on every row.
  Walkability is by construction: a `stair` row at level L follows a row
  whose top is L, and precedes one whose top is L+1.
- `decor`: extra blocks relative to the piece — `along` rows from the entry,
  `side` blocks from the centre line (positive = right of the facing), `up`
  above the row's level; `every` places it on every n-th piece of a road.
  Skipped where a footprint is in the way.
- `fit`: how the ground is made to fit the piece — the ground above the piece
  is cut down to it up to `cut` blocks (three of headroom kept above), below
  it is filled solid up to `fill` blocks, and past that the piece stands on
  a deck (surface plus one support block, air beneath) when `deck` is true,
  else the column is left out. A piece never follows the ground on its own:
  its levels come from its anchors, and the anchors from the plan.
- `cost`: what the planner pays to move through this piece, on top of the
  base cost per cell. The step classes of increment 4 are gone; the catalog
  is the list.

## Provisional roads are not laid

A road planned over estimated terrain is provisional until its corridor is
generated and it is planned again on the real heights. Until then it is in no
snapshot and the builder skips it: laying it would fix the estimate's wrong
level into the world, and a touched road is never replanned. Its cells lend
no heights to other roads either — a replanned road takes the stored heights
of the roads it shares cells with, and an estimate handed on that way would
never be corrected. When a road turns final the snapshot is published again
and its loaded chunks are queued for the builder.

## Materials

Roles map to blocks per **tier** and biome **family**:
`data/postroad/roads/tiers/<n>.json`, `n` a number (0 = dirt path, 1 =
gravel, 2 = stone), each in the format of the present `styles.json`:
palettes for `surface` and `edge`, single blocks for `stair`, `slab`,
`fill`, `post`, `lamp`, per family. A road stores its tier; upgrading a road
is laying its piece sequence again with the next tier's palette. The piece
sequence is not stored: it is derived from the planned points every time,
so it cannot drift from the plan.

## The assembler

`RoadPieces.assemble(points)` walks the planned points and returns one piece
placement per segment: `(piece, entry anchor, exit anchor, reversed)`. It
never fails on a plan the planner produced, because the planner's move set
*is* the catalog: a cardinal move may change height by at most the largest
straight rise, a diagonal move may not change height, and each move costs
its piece. `PlannerRules` derives those limits and costs from the catalog at
reload; `planner.json` keeps only what is not a piece (base, water, reuse,
band, heuristic, expansions).

## Laying

`RoadPieceLayer.lay(level, placement, style, clip)` writes one placement's
blocks: for each row, its three blocks at the row's level plus the entry
height, the ground fitted per column by the piece's `fit`, then the
decoration. It is the same code for the road feature at generation time and
for the chunk-load builder; `clip` limits writing to one chunk. Because a
placement's blocks depend only on its anchors and its definition, two chunks
sharing a piece lay identical blocks — the chunk seams of the profile
builder cannot recur.

Junction signs stay with the builder (they need the network on the server
thread) and are decoration of the joining road's last piece.

## Tests

One game test per piece kind on the arena: place the piece on a flat floor,
on a floor that already has the rise, on a floor that needs the cut, on a
floor that needs the deck; assert every block of every row and that nothing
outside the footprint changed. One test that laying with a clip to two
halves equals laying without. One that decoration is skipped where the next
piece's footprint lies. One that the assembler refuses a diagonal with a
height change and accepts every straight rise in the catalog.

## Out

Bridges longer than a deck, tunnels, serpentine climbs (a later catalog
entry: a piece that turns back on itself), width per tier, hand-built NBT
pieces (the JSON is enough for now; a `template` field can point at one
later without changing the rest).
