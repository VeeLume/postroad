# /// script
# dependencies = ["nbtlib"]
# ///
"""Generate data/packcore/structure/arena.nbt: the game-test arena (7x5x7, stone floor).

Run from the repo root:  uv run tools/gen_test_arena.py
"""
from pathlib import Path

import nbtlib
from nbtlib.tag import Compound, Int, List, String

SIZE = (7, 5, 7)
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/data/packcore/structure/arena.nbt"

palette = List[Compound]([
    Compound({"Name": String("minecraft:stone")}),
    Compound({"Name": String("minecraft:air")}),
])
blocks = []
for y in range(SIZE[1]):
    for z in range(SIZE[2]):
        for x in range(SIZE[0]):
            blocks.append(Compound({"pos": List[Int]([Int(x), Int(y), Int(z)]), "state": Int(0 if y == 0 else 1)}))

root = Compound({
    "size": List[Int]([Int(v) for v in SIZE]),
    "entities": List[Compound]([]),
    "blocks": List[Compound](blocks),
    "palette": palette,
    "DataVersion": Int(3955),
})
nbtlib.File(root, gzipped=True).save(str(OUT))
print("wrote", OUT)
