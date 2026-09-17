# /// script
# dependencies = ["nbtlib"]
# ///
"""Generate data/postroad/structure/pit_stop_<style>.nbt, one per palette style.

The pit stop is the town's depot at the end of a generated road (increment 8):
a 5x5 roofed stand, open to the front (north, -z) where the road runs. Floor
at y=0; the builder fills a foundation under it and rotates it about its
centre so the front faces the road. The depot stands against the back wall
facing the road, with a barrel and a hay bale beside it; a hanging sign under
the front eave carries the town name (the depot writes it, as in the courier
post). The palettes are the courier post's (tools/gen_courier_post.py).

Run from the repo root:  uv run tools/gen_pit_stop.py
"""
import importlib.util
from pathlib import Path

import nbtlib
from nbtlib.tag import Byte, Compound, Int, List, String

_spec = importlib.util.spec_from_file_location("gen_courier_post", Path(__file__).with_name("gen_courier_post.py"))
_post = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_post)
STYLES = _post.STYLES
OUT_DIR = _post.OUT_DIR
DATA_VERSION = _post.DATA_VERSION

SIZE = (5, 6, 5)
AIR = "minecraft:air"


def build(style: str, p: dict[str, str | None]) -> Path:
    palette: list[tuple[str, dict[str, str]]] = []
    blocks: dict[tuple[int, int, int], tuple[int, Compound | None]] = {}

    def state(name: str, **props: str) -> int:
        key = (name, dict(sorted(props.items())))
        if key in palette:
            return palette.index(key)
        palette.append(key)
        return len(palette) - 1

    def put(x: int, y: int, z: int, name: str, nbt: Compound | None = None, **props: str) -> None:
        blocks[(x, y, z)] = (state(name, **props), nbt)

    def corner(x: int, y: int, z: int) -> None:
        name = p["corner"]
        put(x, y, z, name, **({"axis": "y"} if name.endswith(("_log", "_wood")) else {}))

    def stairs(x: int, y: int, z: int, facing: str) -> None:
        put(x, y, z, p["roof_stairs"], facing=facing, half="bottom", shape="straight", waterlogged="false")

    fence = f"minecraft:{p['sign_wood']}_fence"
    fence_off = dict(east="false", north="false", south="false", west="false", waterlogged="false")

    # Air everywhere first, so placing the stand clears plants and leaves in its box.
    for x in range(SIZE[0]):
        for y in range(SIZE[1]):
            for z in range(SIZE[2]):
                put(x, y, z, AIR)

    # --- y = 0: floor, base ring --------------------------------------------------
    for x in range(5):
        for z in range(5):
            put(x, 0, z, p["base"] if x in (0, 4) or z in (0, 4) else p["floor"])

    # --- y = 1..3: corner posts, back wall, side railings ------------------------
    for y in (1, 2, 3):
        for cx, cz in ((0, 0), (4, 0), (0, 4), (4, 4)):
            corner(cx, y, cz)
        for x in (1, 2, 3):
            put(x, y, 4, p["wall"])
    for z in (1, 2, 3):
        put(0, 1, z, fence, **{**fence_off, "north": "true" if z > 1 else "false", "south": "true"})
        put(4, 1, z, fence, **{**fence_off, "north": "true" if z > 1 else "false", "south": "true"})

    # Inside: the depot faces the road, barrel and hay bale beside it, a lantern overhead.
    put(2, 1, 3, "postroad:depot", facing="north")
    put(1, 1, 3, "minecraft:barrel", facing="up", open="false")
    put(3, 1, 3, "minecraft:hay_block", axis="y")
    put(2, 3, 2, "minecraft:lantern", hanging="true", waterlogged="false")
    # Hanging sign under the front eave, readable from the road; the depot writes the town name.
    put(2, 3, 0, f"minecraft:{p['sign_wood']}_hanging_sign", attached="false", rotation="8", waterlogged="false",
        nbt=Compound({"id": String("minecraft:hanging_sign"), "is_waxed": Byte(1)}))

    # --- y = 4..5: roof --------------------------------------------------------------
    for x in range(5):
        for z in range(5):
            put(x, 4, z, p["roof"])
    for x in range(5):
        stairs(x, 4, 0, "south")
        stairs(x, 4, 4, "north")
    for z in (1, 2, 3):
        stairs(0, 4, z, "east")
        stairs(4, 4, z, "west")
    for x in (1, 2, 3):
        for z in (1, 2, 3):
            put(x, 5, z, p["roof_slab"], type="bottom", waterlogged="false")

    # --- serialise ---------------------------------------------------------------------
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
    out = OUT_DIR / f"pit_stop_{style}.nbt"
    nbtlib.File(root, gzipped=True).save(str(out))
    return out


if __name__ == "__main__":
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for style_name, palette_def in STYLES.items():
        print("wrote", build(style_name, palette_def).name)
