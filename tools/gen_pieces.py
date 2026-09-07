"""Writes the road piece catalog (data/postroad/roads/pieces/*.json) — increment 6 format.

Every piece is a schematic anchored at its planned point ([0, 0, 0] = the anchor's road block),
authored in one orientation; the assembler rotates and mirrors it. Coordinates: x along the piece,
y the level, z across. A rise piece hosts the rise *into* its anchor: its in-edge expects the
previous point's level (0 relative to the piece's base = the previous point's height) and its
out-edge expects the anchor's level (rise). See docs/increment-6.md.

Run from the repo root: python tools/gen_pieces.py
"""
import json, pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/data/postroad/roads/pieces"
FIT = {"cut": 4, "fill": 3, "deck": True}
def lamps(anchor_level):
    """A lamppost beside the anchor row: a paved footing at the row's level, the post above it, the lantern on top
    (Valerie's placement, captured from the showcase on 2026-09-07)."""
    return [{"at": [0, anchor_level, 2], "role": "fill", "every": 8},
            {"at": [0, anchor_level + 1, 2], "role": "post", "every": 8},
            {"at": [0, anchor_level + 2, 2], "role": "lamp", "every": 8}]


def bridge(fx, fz, level, role, facing=None):
    """The joint on a diagonal side: the four blocks outside the core that make the corner contact with the
    neighbouring tile a full 3-wide band. Every diagonal connector side owns them; two tiles that share a
    joint lay the same blocks at the same levels, so the double ownership is harmless."""
    # Valerie's shape (captured 2026-09-07): the 2×2 contact plus one block beside each core edge.
    return [block(2 * fx, level, fz, role, facing), block(fx, level, 2 * fz, role, facing),
            block(0, level, 2 * fz, role, facing), block(2 * fx, level, 0, role, facing)]


# Pieces adjusted by hand in the showcase and captured with `/postroad roads capture`: the generator
# neither writes nor deletes these files; their json is the source of truth.
HAND_AUTHORED = {"bend_3", "bendd_2", "bendd_3", "corner_2", "corner_3", "diagonal_1", "diagonal_2", "dcorner_1", "dcorner_2"}
COST = {0: 0.0, 1: 1.0, 2: 4.0, 3: 9.0}
DIAG_COST = {0: 0.5, 1: 2.0, 2: 6.0}
CORNER_COST = {0: 0.0, 1: 1.5, 2: 5.0, 3: 10.0}


def block(x, y, z, role, facing=None):
    b = {"at": [x, y, z], "role": role}
    if facing: b["facing"] = list(facing)
    return b


def row_blocks(x, level, role, facing, zs=(-1, 0, 1)):
    """One row across z: centre `surface`/`edge` for full rows; stairs and slabs across the whole row."""
    out = []
    for z in zs:
        if role == "surface": out.append(block(x, level, z, "surface" if z == 0 else "edge"))
        else: out.append(block(x, level, z, role, facing if role == "stair" else None))
    return out


def rows_for(rise):
    """Row (level, role) triples from the in-edge to the out-edge, as in increment 5."""
    return {0: [(0, "surface"), (0, "surface"), (0, "surface")],
            1: [(1, "slab"), (1, "surface"), (1, "surface")],
            2: [(1, "stair"), (2, "stair"), (2, "surface")],
            3: [(1, "stair"), (2, "stair"), (3, "stair")]}[rise]


def straight(rise):
    rows = rows_for(rise)
    blocks = []
    for i, (lvl, role) in enumerate(rows): blocks += row_blocks(i - 1, lvl, role, (1, 0))
    return {"id": f"straight_{rise}", "cost": COST[rise],
            "connectors": [{"at": [-1, rows[0][0], 0], "facing": [-1, 0], "level": 0},
                           {"at": [1, rise, 0], "facing": [1, 0], "level": rise}],
            "blocks": blocks, "decor": lamps(rows[1][0]), "fit": FIT}


def corner(rise):
    """In from the west, out to the north: the path (-1,0) -> (0,0) -> (0,1); the rise along it."""
    rows = rows_for(rise)
    (l1, r1), (l2, r2), (l3, r3) = rows
    blocks = []
    blocks += row_blocks(-1, l1, r1, (1, 0), zs=(-1, 0))                       # west column; (-1, 1) belongs to the north row
    blocks += [block(0, l2, -1, r2 if r2 != "surface" else "edge", (1, 0) if r2 == "stair" else None),
               block(0, l2, 0, "surface" if r2 == "surface" else r2, (0, 1) if r2 == "stair" else None),
               block(1, l2, -1, "edge" if r2 == "surface" else r2, (1, 0) if r2 == "stair" else None),
               block(1, l2, 0, "edge" if r2 == "surface" else r2, (1, 0) if r2 == "stair" else None)]
    blocks += [block(-1, l3, 1, "edge" if r3 == "surface" else r3, (0, 1) if r3 == "stair" else None),
               block(0, l3, 1, "surface" if r3 == "surface" else r3, (0, 1) if r3 == "stair" else None),
               block(1, l3, 1, "edge" if r3 == "surface" else r3, (0, 1) if r3 == "stair" else None)]
    return {"id": f"corner_{rise}", "cost": CORNER_COST[rise],
            "connectors": [{"at": [-1, l1, 0], "facing": [-1, 0], "level": 0},
                           {"at": [0, rise, 1], "facing": [0, 1], "level": rise}],
            "blocks": blocks, "fit": FIT}


def bend(rise):
    """In from the west, out to the north-east corner: (-1,0) -> (0,0) -> (1,1). The next diagonal piece owns the bridge."""
    rows = rows_for(rise)
    (l1, r1), (l2, r2), (l3, r3) = rows
    blocks = []
    blocks += row_blocks(-1, l1, r1, (1, 0), zs=(-1, 0))
    blocks += [block(0, l2, z, ("surface" if z == 0 else "edge") if r2 == "surface" else r2, (1, 0) if r2 == "stair" else None) for z in (-1, 0)]
    blocks += [block(x, l3, z, ("surface" if (x, z) == (1, 1) else "edge") if r3 == "surface" else r3, (1, 1) if r3 == "stair" else None) for (x, z) in ((0, 1), (1, 0), (1, 1), (1, -1), (-1, 1))]
    blocks += bridge(1, 1, rise, "edge")
    return {"id": f"bend_{rise}", "cost": CORNER_COST[rise],
            "connectors": [{"at": [-1, l1, 0], "facing": [-1, 0], "level": 0},
                           {"at": [1, rise, 1], "facing": [1, 1], "level": rise}],
            "blocks": blocks, "fit": FIT}


def bend_from_diagonal(rise):
    """The bend the other way round: in from the north-east corner at level 0, out to the west at the rise.
    A bend's connectors are of different kinds, so no transform of `bend` gives this; it is its own piece."""
    rows = rows_for(rise)
    (l1, r1), (l2, r2), (l3, r3) = rows
    blocks = []
    # Stage 1: the north-east group (the diagonal side), stage 2: the centre, stage 3: the west column.
    blocks += [block(x, l1, z, ("surface" if (x, z) == (1, 1) else "edge") if r1 == "surface" else r1, (-1, -1) if r1 == "stair" else None) for (x, z) in ((0, 1), (1, 0), (1, 1), (1, -1), (-1, 1))]
    blocks += [block(0, l2, z, ("surface" if z == 0 else "edge") if r2 == "surface" else r2, (-1, 0) if r2 == "stair" else None) for z in (-1, 0)]
    blocks += row_blocks(-1, l3, r3, (-1, 0), zs=(-1, 0))
    blocks += bridge(1, 1, 0, "edge")
    return {"id": f"bendd_{rise}", "cost": CORNER_COST[rise],
            "connectors": [{"at": [1, l1, 1], "facing": [1, 1], "level": 0},
                           {"at": [-1, rise, 0], "facing": [-1, 0], "level": rise}],
            "blocks": blocks, "fit": FIT}


def diagonal(rise):
    """In from the south-west corner, out to the north-east. Owns its in-side bridge (-2,-1) and (-1,-2)."""
    stages = {0: [(0, "surface")] * 4, 1: [(1, "slab"), (1, "surface"), (1, "surface"), (1, "surface")],
              2: [(1, "slab"), (1, "surface"), (2, "slab"), (2, "surface")]}[rise]
    (la, ra), (lb, rb), (lc, rc), (ld, rd) = stages
    def cell(x, z, lvl, role, centre=False):
        return block(x, lvl, z, ("surface" if centre else "edge") if role == "surface" else role)
    blocks = bridge(-1, -1, la, "edge" if ra == "surface" else ra)          # the joint on the in side, the slab step
    blocks += [cell(-1, -1, lb, rb, True),                                  # core corner
               cell(0, -1, lc, rc), cell(-1, 0, lc, rc),
               cell(0, 0, ld, rd, True), cell(1, 0, ld, rd), cell(0, 1, ld, rd), cell(1, 1, ld, rd, True),
               cell(1, -1, ld, rd), cell(-1, 1, ld, rd)]
    blocks += bridge(1, 1, rise, "edge")                                     # the joint on the out side
    return {"id": f"diagonal_{rise}", "cost": DIAG_COST[rise],
            "connectors": [{"at": [-1, la, -1], "facing": [-1, -1], "level": 0},
                           {"at": [1, rise, 1], "facing": [1, 1], "level": rise}],
            "blocks": blocks, "fit": FIT}


def diagonal_corner(rise):
    """In from the south-west corner, out to the north-west corner: a 90° turn between two diagonals.
    Owns its in-side bridge (-2,-1) and (-1,-2); the next piece owns the bridge on the out side."""
    stages = {0: [(0, "surface")] * 4, 1: [(1, "slab"), (1, "surface"), (1, "surface"), (1, "surface")],
              2: [(1, "slab"), (1, "surface"), (2, "slab"), (2, "surface")],
              3: [(1, "stair"), (2, "stair"), (3, "stair"), (3, "surface")]}[rise]
    (la, ra), (lb, rb), (lc, rc), (ld, rd) = stages
    def cell(x, z, lvl, role, centre=False, facing=None):
        return block(x, lvl, z, ("surface" if centre else "edge") if role == "surface" else role, facing if role == "stair" else None)
    blocks = bridge(-1, -1, la, "edge" if ra == "surface" else ra, (1, 1) if ra == "stair" else None)   # the joint from the south-west
    blocks += [cell(-1, -1, lb, rb, True, facing=(1, 1)),                                   # core corner
               cell(0, -1, lc, rc, facing=(0, 1)), cell(-1, 0, lc, rc, facing=(0, 1)), cell(0, 0, lc, rc, True, facing=(0, 1)),
               cell(1, -1, ld, rd), cell(1, 0, ld, rd), cell(0, 1, ld, rd), cell(-1, 1, ld, rd, True), cell(1, 1, ld, rd)]
    blocks += bridge(-1, 1, rise, "edge")                                                    # the joint toward the north-west
    return {"id": f"dcorner_{rise}", "cost": CORNER_COST[rise],
            "connectors": [{"at": [-1, la, -1], "facing": [-1, -1], "level": 0},
                           {"at": [-1, rise, 1], "facing": [-1, 1], "level": rise}],
            "blocks": blocks, "fit": FIT}


def square(name, connectors, cost=0.0):
    blocks = [block(x, 0, z, "surface" if (x, z) == (0, 0) else "edge") for z in (-1, 0, 1) for x in (-1, 0, 1)]
    return {"id": name, "cost": cost, "connectors": connectors, "blocks": blocks, "fit": FIT}


ALL_FACINGS = [(-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, 1), (-1, 1), (1, -1)]


def augment_hand_authored():
    """Hand-authored pieces get what every piece must have and a showcase build cannot show: the joint
    blocks on each diagonal side (at the connector's level, if none are there yet)."""
    for name in sorted(HAND_AUTHORED):
        f = OUT / f"{name}.json"
        if not f.exists(): continue
        o = json.loads(f.read_text(encoding="utf-8"))
        have = {tuple(b["at"]) for b in o["blocks"]}
        added = 0
        for c in o["connectors"]:
            fx, fz = c["facing"]
            if fx == 0 or fz == 0: continue
            for b in bridge(fx, fz, c["level"], "edge"):
                if tuple(b["at"]) not in have and not any(a[0] == b["at"][0] and a[2] == b["at"][2] for a in have):
                    o["blocks"].append(b); added += 1
        f.write_text(json.dumps(o, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"{name}: {added} joint block(s) added")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for f in OUT.glob("*.json"):
        if f.stem not in HAND_AUTHORED: f.unlink()
    pieces = [straight(r) for r in range(4)] + [diagonal(r) for r in range(3)] + [corner(r) for r in range(4)] + [bend(r) for r in range(4)] + [bend_from_diagonal(r) for r in range(1, 4)] + [diagonal_corner(r) for r in range(3)]
    pieces = [p for p in pieces if p["id"] not in HAND_AUTHORED]
    # The flat square with a connector on every side: the fallback for any flat point — sharp turns, junctions.
    pieces.append(square("square", [{"at": [max(-1, min(1, dx)), 0, max(-1, min(1, dz))], "facing": [dx, dz], "level": 0} for (dx, dz) in ALL_FACINGS]))
    for p in pieces:
        (OUT / f"{p['id']}.json").write_text(json.dumps(p, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(pieces)} pieces written to {OUT}")
    augment_hand_authored()


if __name__ == "__main__":
    main()
