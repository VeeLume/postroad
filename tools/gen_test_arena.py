# /// script
# dependencies = ["nbtlib"]
# ///
"""Generate the game-test arenas in data/postroad/structure (stone floor, air above).

Run from the repo root:  uv run tools/gen_test_arena.py
"""
from pathlib import Path

import nbtlib
from nbtlib.tag import Compound, Int, List, String

DIR = Path(__file__).resolve().parent.parent / "src/main/resources/data/postroad/structure"
# name -> size: the piece arena, and a wide one for a road with a pit stop beside it.
ARENAS = {"arena": (7, 5, 7), "arena_wide": (19, 9, 19)}

palette = List[Compound]([
    Compound({"Name": String("minecraft:stone")}),
    Compound({"Name": String("minecraft:air")}),
])

for name, size in ARENAS.items():
    blocks = []
    for y in range(size[1]):
        for z in range(size[2]):
            for x in range(size[0]):
                blocks.append(Compound({"pos": List[Int]([Int(x), Int(y), Int(z)]), "state": Int(0 if y == 0 else 1)}))
    root = Compound({
        "size": List[Int]([Int(v) for v in size]),
        "entities": List[Compound]([]),
        "blocks": List[Compound](blocks),
        "palette": palette,
        "DataVersion": Int(3955),
    })
    out = DIR / f"{name}.nbt"
    nbtlib.File(root, gzipped=True).save(str(out))
    print("wrote", out)
