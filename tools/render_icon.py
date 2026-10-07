"""Render the MeTube mark to PNG (legacy mipmaps for Android 7.x launchers, the README icon).

The vector source of truth is smarttubetv/src/stmobile/res/drawable/ic_launcher_{background,foreground}.xml;
this mirrors that geometry on the 108-unit adaptive-icon grid. Usage: python tools/render_icon.py
"""
import pathlib

from PIL import Image, ImageDraw, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "smarttubetv" / "src" / "stmobile" / "res"
STOPS = [(0.0, (0xFF, 0x45, 0x3A)), (0.55, (0xFF, 0x2D, 0x6F)), (1.0, (0xC4, 0x2B, 0xFF))]
SS = 4  # supersampling


def lerp_stops(t):
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        if t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(round(a + (b - a) * k) for a, b in zip(c0, c1))
    return STOPS[-1][1]


def render(size, crop=18):
    """The adaptive icon as a legacy launcher would show it: the centre 72 units, squircle-masked."""
    full = size * 108 // (108 - 2 * crop) * SS
    u = full / 108
    img = Image.new("RGBA", (full, full))
    px = img.load()
    for y in range(full):
        for x in range(full):
            t = (x + (full - y)) / (2 * full)  # bottom-left -> top-right
            px[x, y] = lerp_stops(t) + (255,)
    d = ImageDraw.Draw(img)
    w = round(11 * u)
    pts = [(34 * u, 72 * u), (34 * u, 40 * u), (54 * u, 60 * u), (74 * u, 40 * u), (74 * u, 72 * u)]
    d.line(pts, fill="white", width=w, joint="curve")
    for p in (pts[0], pts[-1]):
        d.ellipse([p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2], fill="white")
    bubble = Image.new("RGBA", img.size)
    b = ImageDraw.Draw(bubble)
    b.ellipse([62 * u, 26 * u, 80 * u, 44 * u], fill=(255, 255, 255, 0x59), outline=(255, 255, 255, 0x99),
              width=max(1, round(u)))
    img = Image.alpha_composite(img, bubble)
    c = round(crop * u)
    img = img.crop((c, c, full - c, full - c))
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius=img.size[0] * 0.23, fill=255)
    img.putalpha(mask.filter(ImageFilter.GaussianBlur(SS / 2)))
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        icon = render(size)
        for name in ("app_icon.png", "app_icon_alt.png"):
            icon.save(RES / f"mipmap-{density}" / name)
    render(512).save(ROOT / ".github" / "assets" / "icon.png")
    print("ok")
