"""Build every built-in app icon from one PNG per icon (the shape alone on transparency).

Usage: python tools/icons_from_pngs.py <folder with the PNGs below>

Per icon: drawable-nodpi/ic_launcher_<name>.png (adaptive-icon foreground),
drawable-nodpi/ic_meetube_mark_<name>.png (the brand pill, full bleed) and the pre-26 mipmaps. The
default one is also the README icon. The launch animation is one vector for all (avd_splash_glass).
The themed-icon (monochrome) layer still comes from tools/render_icon.py.
"""
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "smarttubetv" / "src" / "stmobile" / "res"
LAYER = 864  # 108 units at 8 px each: sharp on any launcher scale
HEART, TILE = 0.53, 0.574  # longer side / layer: inside the launcher masks, as the 3.2.2 icons
# Source file -> resource name, legacy mipmaps, fill (AppIcon's slot in the comment).
ICONS = [
    ("Classic (Default).png", "default", ["app_icon"], HEART),  # Default
    ("Black Heart.png", "classic", ["app_icon_classic"], HEART),  # Classic
    ("Fantasy.png", "art", ["app_icon_fantasy", "app_icon_alt"], HEART),  # Fantasy
    ("Glass Heart.png", "glass_heart", ["app_icon_glass_heart"], HEART),  # GlassHeart
    ("OG Glass.png", "ruby", ["app_icon_ruby"], TILE),  # Ruby
    ("OG Play.png", "play", ["app_icon_play"], TILE),  # Play
    ("OG Neon.png", "play_dark", ["app_icon_play_dark"], TILE),  # PlayDark
    ("Fiery Blaze.png", "blaze", ["app_icon_blaze"], TILE),  # Blaze
]
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def load(path):
    img = Image.open(path).convert("RGBA")
    if img.getpixel((0, 0))[3] == 255:
        img = drop_checkerboard(img)
    # Crop to what shows: near-clear haze around some exports would shrink the shape.
    return img.crop(img.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox())


def drop_checkerboard(img):
    """A 'transparent' background painted in as a light grey checkerboard: clear what joins the edge."""
    grey = Image.new("L", img.size, 0)
    px, g = img.load(), grey.load()
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            r, gr, b, _ = px[x, y]
            if min(r, gr, b) > 215 and max(r, gr, b) - min(r, gr, b) < 10:
                g[x, y] = 255
    w, h = img.size
    for seed in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        if grey.getpixel(seed) == 255:
            ImageDraw.floodfill(grey, seed, 128)
    alpha = grey.point(lambda v: 0 if v == 128 else 255)
    # The edge pixels are half checkerboard: pull the edge in a little and soften it.
    img.putalpha(alpha.filter(ImageFilter.MinFilter(5)).filter(ImageFilter.GaussianBlur(1.2)))
    return img


def square(img, px, fill):
    """img centred in a transparent px square, scaled so its longer side is fill * px."""
    scale = fill * px / max(img.size)
    img = img.resize((round(img.size[0] * scale), round(img.size[1] * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    canvas.paste(img, ((px - img.size[0]) // 2, (px - img.size[1]) // 2), img)
    return canvas


def main(src):
    out = RES / "drawable-nodpi"
    for file, name, legacy, fill in ICONS:
        shape = load(src / file)
        square(shape, LAYER, fill).save(out / f"ic_launcher_{name}.png", optimize=True)
        square(shape, 192, 1.0).save(out / f"ic_meetube_mark_{name}.png", optimize=True)
        for density, px in DENSITIES.items():
            icon = square(shape, px, 0.92)
            for mipmap in legacy:
                icon.save(RES / f"mipmap-{density}" / f"{mipmap}.png")
        if name == "default":
            square(shape, 512, 0.92).save(ROOT / ".github" / "assets" / "icon.png", optimize=True)
    print("ok")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
