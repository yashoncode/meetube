"""Renders the README poster: a tilted wall of the app's screens on a liquid colour backdrop,
with frosted glass pills (the app's own look) floating over it.

    python tools/render_showcase.py

Writes .github/assets/metube/showcase.webp. Re-run after retaking a screenshot in
.github/assets/metube/.
"""
import os

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

ASSETS = os.path.join(os.path.dirname(__file__), '..', '.github', 'assets', 'metube')
FONTS = 'C:/Windows/Fonts/'

W, H = 2400, 1260
RADIUS = 56                       # the poster's own corners
SHOTS = ['home', 'watch', 'shorts', 'lyrics', 'search', 'channel', 'downloads']
TILE_W, TILE_GAP = 500, 64
COLUMN_SHIFT = [0, -520, -180, -700, -330, -60]   # stagger, so no row lines up
PILLS = [  # text, centre x, centre y (poster pixels)
    ('No ads, ever', 470, 300),
    ('Synced lyrics', 1960, 220),
    ('Liquid glass', 330, 930),
    ('Offline in 1080p', 2010, 980),
    ('Background play', 1520, 1130),
    ('Real dislikes', 860, 1120),
]


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)


def rounded_mask(size, radius):
    mask = Image.new('L', size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def tile(shot):
    img = Image.open(os.path.join(ASSETS, shot + '.jpg')).convert('RGB')
    img = img.resize((TILE_W, round(img.height * TILE_W / img.width)), Image.LANCZOS)
    out = Image.new('RGBA', (TILE_W + 16, img.height + 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(out)
    d.rounded_rectangle((0, 0, out.width - 1, out.height - 1), 58, fill=(14, 14, 16, 255),
                        outline=(80, 80, 90, 255), width=2)
    out.paste(img, (8, 8), rounded_mask(img.size, 50))
    return out


def wall():
    tiles = [tile(s) for s in SHOTS]
    tw, th = tiles[0].size
    cols, rows = len(COLUMN_SHIFT), 4
    img = Image.new('RGBA', (cols * (tw + TILE_GAP), rows * (th + TILE_GAP)), (0, 0, 0, 0))
    n = 0
    for c in range(cols):
        for r in range(rows):
            t = tiles[(n * 3 + c) % len(tiles)]   # neighbours differ, every screen appears
            n += 1
            img.paste(t, (c * (tw + TILE_GAP), r * (th + TILE_GAP) + COLUMN_SHIFT[c] + 300), t)
    return img


def perspective(img, quad, size):
    """Maps img onto quad (tl, tr, br, bl in output pixels) of an output of the given size."""
    w, h = img.size
    src = [(0, 0), (w, 0), (w, h), (0, h)]
    rows = []
    for (x, y), (u, v) in zip(quad, src):
        rows.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        rows.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    coeffs = np.linalg.solve(np.array(rows, float), np.array([c for p in src for c in p], float))
    return img.transform(size, Image.PERSPECTIVE, coeffs.tolist(), Image.BICUBIC)


def tilt_quad(wall_size, yaw=-24, pitch=18, roll=-12, scale=0.62, distance=3600):
    """The wall's corners after turning it in 3D (degrees) and projecting it onto the poster."""
    ww, wh = wall_size[0] * scale, wall_size[1] * scale
    a, b, c = np.radians([pitch, yaw, roll])
    rx = np.array([[1, 0, 0], [0, np.cos(a), -np.sin(a)], [0, np.sin(a), np.cos(a)]])
    ry = np.array([[np.cos(b), 0, np.sin(b)], [0, 1, 0], [-np.sin(b), 0, np.cos(b)]])
    rz = np.array([[np.cos(c), -np.sin(c), 0], [np.sin(c), np.cos(c), 0], [0, 0, 1]])
    quad = []
    for x, y in [(-ww / 2, -wh / 2), (ww / 2, -wh / 2), (ww / 2, wh / 2), (-ww / 2, wh / 2)]:
        px, py, pz = rx @ ry @ rz @ np.array([x, y, 0.0])
        k = distance / (distance + pz)
        quad.append((W / 2 + 120 + px * k, H / 2 + py * k))
    return quad


def backdrop():
    img = Image.new('RGB', (W, H), (8, 6, 10))
    blobs = Image.new('RGB', (W, H), (8, 6, 10))
    d = ImageDraw.Draw(blobs)
    for (x, y, r, color) in [(1750, 250, 620, (255, 59, 48)), (2250, 1150, 560, (176, 20, 120)),
                             (1150, 1250, 520, (255, 120, 20)), (250, 1150, 600, (70, 20, 160)),
                             (300, 120, 420, (200, 30, 60))]:
        d.ellipse((x - r, y - r, x + r, y + r), fill=color)
    return Image.blend(img, blobs.filter(ImageFilter.GaussianBlur(260)), 0.95)


def glass(base, box, radius):
    """Frosted glass over base inside box: blur and saturate what's behind, a light veil, a rim."""
    x0, y0, x1, y1 = box
    region = base.crop(box).filter(ImageFilter.GaussianBlur(22))
    region = ImageEnhance.Color(region).enhance(1.6)
    region = Image.blend(region, Image.new('RGB', region.size, (255, 255, 255)), 0.16)
    mask = rounded_mask(region.size, radius)

    shadow = Image.new('L', base.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle((x0 + 6, y0 + 22, x1 - 6, y1 + 18), radius, fill=150)
    base.paste((0, 0, 0), (0, 0), shadow.filter(ImageFilter.GaussianBlur(26)))
    base.paste(region, (x0, y0), mask)

    rim = Image.new('RGBA', region.size, (0, 0, 0, 0))
    ImageDraw.Draw(rim).rounded_rectangle((0, 0, region.width - 1, region.height - 1), radius,
                                          outline=(255, 255, 255, 120), width=3)
    fade = Image.linear_gradient('L').resize(region.size).point(lambda v: 255 - v * 0.7)
    rim.putalpha(Image.composite(rim.getchannel('A'), Image.new('L', region.size, 0), fade))
    base.paste(rim, (x0, y0), rim)


def pill(base, text, cx, cy):
    f = font('seguisb.ttf', 46)
    tw = ImageDraw.Draw(base).textlength(text, font=f)
    w, h = int(tw) + 150, 104
    box = (int(cx - w / 2), int(cy - h / 2), int(cx + w / 2), int(cy + h / 2))
    glass(base, box, h // 2)
    d = ImageDraw.Draw(base)
    dot = (box[0] + 52, cy)
    d.ellipse((dot[0] - 13, dot[1] - 13, dot[0] + 13, dot[1] + 13), fill=(255, 69, 58))
    d.text((box[0] + 88, cy + 2), text, font=f, fill=(255, 255, 255), anchor='lm')


def badge(base):
    icon = Image.open(os.path.join(ASSETS, 'icon.png')).convert('RGBA').resize((168, 168), Image.LANCZOS)
    f = font('seguibl.ttf', 132)
    tw = ImageDraw.Draw(base).textlength('MeTube', font=f)
    w, h = int(tw) + 168 + 150, 240
    x0, y0 = (W - w) // 2, (H - h) // 2 - 10
    glass(base, (x0, y0, x0 + w, y0 + h), 64)
    base.paste(icon, (x0 + 46, y0 + 36), icon)
    ImageDraw.Draw(base).text((x0 + 46 + 168 + 34, y0 + h // 2 + 4), 'MeTube', font=f,
                              fill=(255, 255, 255), anchor='lm')


def render():
    img = backdrop()
    tilted = perspective(wall(), tilt_quad(wall_size=(3480, 4752)), (W, H))
    # the wall sits in the colour, a little dimmed, so the glass reads as the foreground
    shade = Image.new('L', (W, H), 0)
    ImageDraw.Draw(shade).ellipse((-400, -500, W + 400, H + 500), fill=255)
    alpha = Image.composite(tilted.getchannel('A'), Image.new('L', (W, H), 0),
                            shade.filter(ImageFilter.GaussianBlur(200)))
    img.paste(ImageEnhance.Brightness(tilted.convert('RGB')).enhance(0.82), (0, 0), alpha)
    vignette = Image.radial_gradient('L').resize((W, H)).point(lambda v: min(255, int(v * 0.9)))
    img.paste((6, 4, 8), (0, 0), vignette)

    for text, x, y in PILLS:
        pill(img, text, x, y)
    badge(img)

    out = img.convert('RGBA')
    out.putalpha(rounded_mask((W, H), RADIUS))
    path = os.path.join(ASSETS, 'showcase.webp')
    out.save(path, quality=86, method=6)
    print(path, out.size, os.path.getsize(path) // 1024, 'KB')


if __name__ == '__main__':
    render()
