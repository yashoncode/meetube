"""Render the MeTube mark to PNG (legacy mipmaps for Android 7.x launchers, the README icon).

The vector source of truth is smarttubetv/src/stmobile/res/drawable/ic_launcher_{background,foreground}.xml:
a flat warm ground, a coral 9-lobe "cookie" (Material 3 expressive shape) and a white M, on the
108-unit adaptive-icon grid. This mirrors that geometry. Usage: python tools/render_icon.py
"""
import math
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "smarttubetv" / "src" / "stmobile" / "res"
GROUND = (0xFF, 0xE8, 0xE4)
CORAL = (0xFF, 0x45, 0x3A)
SS = 4  # supersampling


def cookie(cx, cy, r, amp, u, lobes=9, n=360):
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n - math.pi / 2
        rr = r + amp * math.cos(lobes * (t + math.pi / 2))
        pts.append(((cx + rr * math.cos(t)) * u, (cy + rr * math.sin(t)) * u))
    return pts


def render(size, crop=18):
    """The adaptive icon as a legacy launcher shows it: the centre 72 units, squircle-masked."""
    full = size * 108 // (108 - 2 * crop) * SS
    u = full / 108
    img = Image.new("RGBA", (full, full), GROUND + (255,))
    d = ImageDraw.Draw(img)
    d.polygon(cookie(54, 54, 31, 1.9, u), fill=CORAL)
    w = round(6.5 * u)
    pts = [(42 * u, 63 * u), (42 * u, 45.5 * u), (54 * u, 57.5 * u), (66 * u, 45.5 * u), (66 * u, 63 * u)]
    d.line(pts, fill="white", width=w, joint="curve")
    for p in (pts[0], pts[-1]):
        d.ellipse([p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2], fill="white")
    c = round(crop * u)
    img = img.crop((c, c, full - c, full - c))
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1],
                                           radius=img.size[0] * 0.23, fill=255)
    img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        icon = render(size)
        for name in ("app_icon.png", "app_icon_alt.png"):
            icon.save(RES / f"mipmap-{density}" / name)
    render(512).save(ROOT / ".github" / "assets" / "icon.png")
    print("ok")
