# /// script
# dependencies = ["nbtlib"]
# ///
"""Generate data/packcore/structure/courier_post.nbt.

A 7x7x7 village house in the vanilla plains idiom: a 5x5 building centred in
the box with a one-block ring of air, floor at y=0, a `building_entrance`
jigsaw on the west edge so village streets attach to it exactly like they
attach vanilla houses. The depot block stands against the east wall,
opposite the door.

Run from the repo root:  uv run tools/gen_courier_post.py
"""
from pathlib import Path

import nbtlib
from nbtlib.tag import Compound, Int, List, String

DATA_VERSION = 3955  # Minecraft 1.21.1
SIZE = (7, 7, 7)
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/data/packcore/structure/courier_post.nbt"

palette: list[tuple[str, dict[str, str]]] = []
blocks: dict[tuple[int, int, int], tuple[int, Compound | None]] = {}


def state(name: str, **props: str) -> int:
    key = (name, dict(sorted(props.items())))
    for i, entry in enumerate(palette):
        if entry == key:
            return i
    palette.append(key)
    return len(palette) - 1


def put(x: int, y: int, z: int, name: str, nbt: Compound | None = None, **props: str) -> None:
    blocks[(x, y, z)] = (state(name, **props), nbt)


AIR = "minecraft:air"
COBBLE = "minecraft:cobblestone"
PLANKS = "minecraft:oak_planks"
LOG = "minecraft:stripped_oak_log"
PANE = "minecraft:glass_pane"
SLAB = "minecraft:oak_slab"

# Fill the whole box with air first (the ring of air clears vegetation, like vanilla).
for x in range(SIZE[0]):
    for y in range(SIZE[1]):
        for z in range(SIZE[2]):
            put(x, y, z, AIR)

# --- y = 0: foundation ring + plank floor, jigsaw on the west edge ---------
for x in range(1, 6):
    for z in range(1, 6):
        edge = x in (1, 5) or z in (1, 5)
        put(x, 0, z, COBBLE if edge else PLANKS)
for cx, cz in ((1, 1), (1, 5), (5, 1), (5, 5)):
    put(cx, 0, cz, LOG, axis="y")

put(
    0, 0, 3, "minecraft:jigsaw",
    Compound({
        "id": String("minecraft:jigsaw"),
        "name": String("minecraft:building_entrance"),
        "target": String("minecraft:building_entrance"),
        "pool": String("minecraft:empty"),
        "final_state": String("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"),
        "joint": String("aligned"),
    }),
    orientation="west_up",
)

# --- y = 1..3: walls -------------------------------------------------------
for y in (1, 2, 3):
    wall = COBBLE if y == 1 else PLANKS
    for x in range(1, 6):
        for z in range(1, 6):
            if x in (1, 5) or z in (1, 5):
                put(x, y, z, wall)
    for cx, cz in ((1, 1), (1, 5), (5, 1), (5, 5)):
        put(cx, y, cz, LOG, axis="y")

# Door in the west wall, facing the street.
put(1, 1, 3, "minecraft:oak_door", facing="west", half="lower", hinge="left", open="false", powered="false")
put(1, 2, 3, "minecraft:oak_door", facing="west", half="upper", hinge="left", open="false", powered="false")

# Windows at y=2 on the north, south and east walls.
for x, z in ((3, 1), (3, 5), (5, 2), (5, 4)):
    put(x, 2, z, PANE, east="false", north="false", south="false", west="false", waterlogged="false")

# Interior: depot against the east wall, a barrel and a lantern.
put(4, 1, 3, "packcore:depot", facing="west")
put(4, 1, 2, "minecraft:barrel", facing="up", open="false")
put(3, 3, 3, "minecraft:lantern", hanging="true", waterlogged="false")
put(1, 2, 2, "minecraft:wall_torch", facing="east")
put(1, 2, 4, "minecraft:wall_torch", facing="east")

# --- y = 4..6: stepped roof --------------------------------------------------
for x in range(1, 6):
    for z in range(1, 6):
        put(x, 4, z, PLANKS)
for x in range(2, 5):
    for z in range(2, 5):
        put(x, 5, z, SLAB, type="bottom", waterlogged="false")
for x in range(1, 6):
    for z in (1, 5):
        put(x, 4, z, "minecraft:oak_stairs", facing="south" if z == 1 else "north", half="bottom", shape="straight", waterlogged="false")
for z in range(2, 5):
    for x in (1, 5):
        put(x, 4, z, "minecraft:oak_stairs", facing="east" if x == 1 else "west", half="bottom", shape="straight", waterlogged="false")

# --- serialise -------------------------------------------------------------
palette_tag = List[Compound]([
    Compound({"Name": String(name), **({"Properties": Compound({k: String(v) for k, v in props.items()})} if props else {})})
    for name, props in palette
])
block_tags = []
for (x, y, z), (idx, nbt) in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
    entry = Compound({"pos": List[Int]([Int(x), Int(y), Int(z)]), "state": Int(idx)})
    if nbt is not None:
        entry["nbt"] = nbt
    block_tags.append(entry)

root = Compound({
    "size": List[Int]([Int(v) for v in SIZE]),
    "entities": List[Compound]([]),
    "blocks": List[Compound](block_tags),
    "palette": palette_tag,
    "DataVersion": Int(DATA_VERSION),
})
OUT.parent.mkdir(parents=True, exist_ok=True)
nbtlib.File(root, gzipped=True).save(str(OUT))
print(f"wrote {OUT} ({len(block_tags)} blocks, {len(palette)} palette entries)")
