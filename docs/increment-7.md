# Increment 7 — roads and villages

Decided with Valerie on 2026-09-09: the join between a generated road and a
village is rough, and the village's own streets are often broken (vanilla
street templates are `terrain_matching`: every column snaps to the heightmap,
so a street on Tectonic terrain is a staircase with gaps). Three stages, each
shippable on its own.

## Stage 1 — join at the street stub, on its axis (built 2026-09-09)

**What the finder knows.** Village layouts are predicted from the seed
(`TownFinder`). Pieces whose template name contains `street` or `terminator`
are open ground for a road; everything else is blocked. A street's jigsaw
blocks face along the street; one that leads into a *terminator* (the dead-end
stub vanilla, Towns & Towers and BWG all place at the village edge) or into
nothing marks a **stub**: the road end is the block just outside the far side
of the terminator (or just outside the open jigsaw), with the street's
direction there. A stub whose ground varies by more than `STUB_STEP` (3)
between the street's end and the stub is not offered — that is a street that
stair-steps down a slope, no place to join.

**What the planner does.** `Town.exits` are the stubs, `Town.facings` their
directions (older records and villages without terminators fall back to the
street centres, no direction). The search starts and stops at the cell just
outside a stub; afterwards the stub itself is put back on the road when the
turn into it is at most 90° (`withStubs`), so the last piece before the
village is a straight one on the street's axis and the road's level at the
stub is the terrain's, which is the street's (terrain matching).

**Stored:** `PlannedTown.exits` / `facings` (`Exits`, `ExitFacings` in NBT).
Planned on a fresh world; older worlds keep planning to street centres.

## Stage 2 — village road works (not built)

Street graph from the structure start's pieces plus the templates' jigsaw
blocks; our pieces laid along every street edge on the village's final ground
(within the surface-structures step the village pieces of a chunk are placed
before our feature runs for it), the strip re-graded with cut and fill. Risks:
doorsteps a block off after re-grading, modded street widths and decorations,
lamp posts and bridges in street templates overwritten. Two to three sessions.

## Stage 3 — route to the courier post (not built)

The planner rides the street graph inside the box, the post is the endpoint,
junction signs at the village edge. One session on top of stage 2.
