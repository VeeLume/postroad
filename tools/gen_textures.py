# /// script
# dependencies = ["pillow"]
# ///
"""Placeholder 16x16 textures for the coin and the depot block.

Run from the repo root: uv run tools/gen_textures.py
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/postroad/textures"


def img():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def coin():
    im = img()
    d = ImageDraw.Draw(im)
    d.ellipse((2, 2, 13, 13), fill=(214, 168, 52, 255), outline=(140, 100, 24, 255))
    d.ellipse((4, 4, 11, 11), outline=(240, 205, 96, 255))
    d.point((6, 5), fill=(255, 235, 160, 255))
    d.point((5, 6), fill=(255, 235, 160, 255))
    im.save(ROOT / "item/coin.png")


def plank_base(seed_rows):
    im = Image.new("RGBA", (16, 16), (139, 105, 62, 255))
    d = ImageDraw.Draw(im)
    for y in seed_rows:
        d.line((0, y, 15, y), fill=(112, 82, 45, 255))
    for x in (3, 8, 12):
        d.line((x, 0, x, 15), fill=(125, 93, 52, 255))
    return im, d


def depot_side():
    im, d = plank_base((0, 5, 10, 15))
    im.save(ROOT / "block/depot_side.png")


def depot_top():
    im, d = plank_base((0, 15))
    d.rectangle((1, 1, 14, 14), outline=(112, 82, 45, 255))
    im.save(ROOT / "block/depot_top.png")


def depot_front():
    im, d = plank_base((0, 15))
    # frame
    d.rectangle((1, 1, 14, 14), outline=(90, 64, 34, 255))
    # letter slot
    d.rectangle((3, 4, 12, 5), fill=(40, 30, 20, 255))
    # brass plate with a coin mark
    d.rectangle((4, 8, 11, 12), fill=(200, 160, 60, 255), outline=(140, 100, 24, 255))
    d.ellipse((6, 9, 9, 11), outline=(120, 85, 20, 255))
    im.save(ROOT / "block/depot_front.png")


def charter():
    im = img()
    d = ImageDraw.Draw(im)
    # rolled parchment with a wax seal
    d.rectangle((2, 1, 12, 14), fill=(232, 214, 168, 255), outline=(150, 120, 70, 255))
    for y in (4, 6, 8):
        d.line((4, y, 10, y), fill=(120, 95, 60, 255))
    d.ellipse((8, 9, 13, 14), fill=(170, 40, 40, 255), outline=(110, 20, 20, 255))
    d.point((10, 11), fill=(220, 90, 90, 255))
    im.save(ROOT / "item/postal_charter.png")


coin()
charter()
depot_side()
depot_top()
depot_front()
print("textures written to", ROOT)
