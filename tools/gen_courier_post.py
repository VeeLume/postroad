# /// script
# dependencies = ["nbtlib"]
# ///
"""Generate data/postroad/structure/courier_post_<style>.nbt, one per palette style.

A 7x7x7 village house in the vanilla plains idiom: a 5x5 building centred in
the box with a one-block ring of air, floor at y=0, a `building_entrance`
jigsaw on the west edge so village streets attach to it exactly like they
attach vanilla houses. The depot block stands against the east wall,
opposite the door. The style only changes the palette; the shape is shared.

Run from the repo root:  uv run tools/gen_courier_post.py
"""
from pathlib import Path

import nbtlib
from nbtlib.tag import Compound, Int, List, String

DATA_VERSION = 3955  # Minecraft 1.21.1
SIZE = (7, 7, 7)
OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/data/postroad/structure"

# Palette keys: base (y=0 ring and y=1 wall), corner (log, axis=y), wall (y=2..3),
# floor, roof (full layer), roof_stairs, roof_slab, door (None = open doorway),
# window (pane-like block; None = wall), torch (wall torch block).
STYLES: dict[str, dict[str, str | None]] = {
    "oak": dict(base="minecraft:cobblestone", corner="minecraft:stripped_oak_log", wall="minecraft:oak_planks",
                floor="minecraft:oak_planks", roof="minecraft:oak_planks", roof_stairs="minecraft:oak_stairs",
                roof_slab="minecraft:oak_slab", door="minecraft:oak_door", window="minecraft:glass_pane",
                step="minecraft:oak_stairs", sign_wood="oak"),
    "spruce": dict(base="minecraft:cobblestone", corner="minecraft:stripped_spruce_log", wall="minecraft:spruce_planks",
                   floor="minecraft:spruce_planks", roof="minecraft:spruce_planks", roof_stairs="minecraft:spruce_stairs",
                   roof_slab="minecraft:spruce_slab", door="minecraft:spruce_door", window="minecraft:glass_pane",
                   step="minecraft:spruce_stairs", sign_wood="spruce"),
    "birch": dict(base="minecraft:cobblestone", corner="minecraft:stripped_birch_log", wall="minecraft:birch_planks",
                  floor="minecraft:birch_planks", roof="minecraft:spruce_planks", roof_stairs="minecraft:spruce_stairs",
                  roof_slab="minecraft:spruce_slab", door="minecraft:birch_door", window="minecraft:glass_pane",
                  step="minecraft:birch_stairs", sign_wood="birch"),
    "acacia": dict(base="minecraft:orange_terracotta", corner="minecraft:stripped_acacia_log", wall="minecraft:acacia_planks",
                   floor="minecraft:acacia_planks", roof="minecraft:acacia_planks", roof_stairs="minecraft:acacia_stairs",
                   roof_slab="minecraft:acacia_slab", door="minecraft:acacia_door", window="minecraft:glass_pane",
                   step="minecraft:acacia_stairs", sign_wood="acacia"),
    "jungle": dict(base="minecraft:mossy_cobblestone", corner="minecraft:stripped_jungle_log", wall="minecraft:jungle_planks",
                   floor="minecraft:jungle_planks", roof="minecraft:jungle_planks", roof_stairs="minecraft:jungle_stairs",
                   roof_slab="minecraft:jungle_slab", door="minecraft:jungle_door", window="minecraft:glass_pane",
                   step="minecraft:jungle_stairs", sign_wood="jungle"),
    "cherry": dict(base="minecraft:stone_bricks", corner="minecraft:stripped_cherry_log", wall="minecraft:cherry_planks",
                   floor="minecraft:cherry_planks", roof="minecraft:dark_oak_planks", roof_stairs="minecraft:dark_oak_stairs",
                   roof_slab="minecraft:dark_oak_slab", door="minecraft:cherry_door", window="minecraft:glass_pane",
                   step="minecraft:cherry_stairs", sign_wood="cherry"),
    "dark_oak": dict(base="minecraft:mossy_cobblestone", corner="minecraft:stripped_dark_oak_log", wall="minecraft:dark_oak_planks",
                     floor="minecraft:dark_oak_planks", roof="minecraft:dark_oak_planks", roof_stairs="minecraft:dark_oak_stairs",
                     roof_slab="minecraft:dark_oak_slab", door="minecraft:dark_oak_door", window="minecraft:glass_pane",
                     step="minecraft:dark_oak_stairs", sign_wood="dark_oak"),
    "desert": dict(base="minecraft:sandstone", corner="minecraft:cut_sandstone", wall="minecraft:smooth_sandstone",
                   floor="minecraft:sandstone", roof="minecraft:smooth_sandstone", roof_stairs="minecraft:sandstone_stairs",
                   roof_slab="minecraft:sandstone_slab", door=None, window=None,
                   step="minecraft:sandstone_stairs", sign_wood="bamboo"),
    "badlands": dict(base="minecraft:terracotta", corner="minecraft:stripped_spruce_log", wall="minecraft:orange_terracotta",
                     floor="minecraft:terracotta", roof="minecraft:terracotta", roof_stairs="minecraft:red_sandstone_stairs",
                     roof_slab="minecraft:red_sandstone_slab", door=None, window=None,
                     step="minecraft:red_sandstone_stairs", sign_wood="spruce"),
    "iberian": dict(base="minecraft:stone_bricks", corner="minecraft:stripped_dark_oak_log", wall="minecraft:white_terracotta",
                    floor="minecraft:dark_oak_planks", roof="minecraft:dark_oak_planks", roof_stairs="minecraft:dark_oak_stairs",
                    roof_slab="minecraft:dark_oak_slab", door="minecraft:dark_oak_door", window="minecraft:glass_pane",
                    step="minecraft:stone_brick_stairs", sign_wood="dark_oak"),
}

AIR = "minecraft:air"


def build(style: str, p: dict[str, str | None]) -> Path:
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

    def corner(x: int, y: int, z: int) -> None:
        name = p["corner"]
        if name.endswith("_log") or name.endswith("_wood"):
            put(x, y, z, name, axis="y")
        else:
            put(x, y, z, name)

    def stairs(x: int, y: int, z: int, name: str, facing: str) -> None:
        put(x, y, z, name, facing=facing, half="bottom", shape="straight", waterlogged="false")

    # Fill the whole box with air first (the ring of air clears vegetation, like vanilla).
    for x in range(SIZE[0]):
        for y in range(SIZE[1]):
            for z in range(SIZE[2]):
                put(x, y, z, AIR)

    # --- y = 0: foundation ring + floor, jigsaw on the west edge ---------------
    for x in range(1, 6):
        for z in range(1, 6):
            edge = x in (1, 5) or z in (1, 5)
            put(x, 0, z, p["base"] if edge else p["floor"])
    for cx, cz in ((1, 1), (1, 5), (5, 1), (5, 5)):
        corner(cx, 0, cz)

    put(
        0, 0, 3, "minecraft:jigsaw",
        Compound({
            "id": String("minecraft:jigsaw"),
            "name": String("minecraft:building_entrance"),
            "target": String("minecraft:building_entrance"),
            "pool": String("minecraft:empty"),
            "final_state": String(f"{p['step']}[facing=east,half=bottom,shape=straight,waterlogged=false]"),
            "joint": String("aligned"),
        }),
        orientation="west_up",
    )

    # --- y = 1..3: walls ---------------------------------------------------------
    for y in (1, 2, 3):
        wall = p["base"] if y == 1 else p["wall"]
        for x in range(1, 6):
            for z in range(1, 6):
                if x in (1, 5) or z in (1, 5):
                    put(x, y, z, wall)
        for cx, cz in ((1, 1), (1, 5), (5, 1), (5, 5)):
            corner(cx, y, cz)

    # Doorway in the west wall, facing the street.
    if p["door"]:
        put(1, 1, 3, p["door"], facing="west", half="lower", hinge="left", open="false", powered="false")
        put(1, 2, 3, p["door"], facing="west", half="upper", hinge="left", open="false", powered="false")
    else:
        put(1, 1, 3, AIR)
        put(1, 2, 3, AIR)

    # Windows at y=2 on the north, south and east walls.
    for x, z in ((3, 1), (3, 5), (5, 2), (5, 4)):
        if p["window"]:
            put(x, 2, z, p["window"], east="false", north="false", south="false", west="false", waterlogged="false")
        else:
            put(x, 2, z, AIR)

    # Interior: depot against the east wall, a barrel, one lantern.
    put(4, 1, 3, "postroad:depot", facing="west")
    put(4, 1, 2, "minecraft:barrel", facing="up", open="false")
    put(3, 3, 3, "minecraft:lantern", hanging="true", waterlogged="false")
    # Two torches outside, flanking the door on the street side.
    put(0, 2, 2, "minecraft:wall_torch", facing="west")
    put(0, 2, 4, "minecraft:wall_torch", facing="west")
    # Porch: a slab over the step and a hanging sign under it. The depot writes the town name on it.
    put(0, 4, 3, p["roof_slab"], type="bottom", waterlogged="false")
    put(0, 3, 3, f"minecraft:{p['sign_wood']}_hanging_sign", attached="false", rotation="4", waterlogged="false",
        nbt=Compound({"id": String("minecraft:hanging_sign"), "is_waxed": nbtlib.tag.Byte(1)}))

    # --- y = 4..5: stepped roof --------------------------------------------------
    for x in range(1, 6):
        for z in range(1, 6):
            put(x, 4, z, p["roof"])
    for x in range(1, 6):
        for z in (1, 5):
            stairs(x, 4, z, p["roof_stairs"], "south" if z == 1 else "north")
    for z in range(2, 5):
        for x in (1, 5):
            stairs(x, 4, z, p["roof_stairs"], "east" if x == 1 else "west")
    for x in range(2, 5):
        for z in range(2, 5):
            put(x, 5, z, p["roof_slab"], type="bottom", waterlogged="false")

    # --- serialise ---------------------------------------------------------------
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
    out = OUT_DIR / f"courier_post_{style}.nbt"
    nbtlib.File(root, gzipped=True).save(str(out))
    return out


OUT_DIR.mkdir(parents=True, exist_ok=True)
stale = OUT_DIR / "courier_post.nbt"
if stale.exists():
    stale.unlink()
for style_name, palette_def in STYLES.items():
    print("wrote", build(style_name, palette_def).name)
