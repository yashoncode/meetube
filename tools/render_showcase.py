"""Renders the README showcase (bento, the one in use) from the app screenshots in .github/assets/metube/.

    python tools/render_showcase.py [bento|coverflow] [out path]

Writes .github/assets/metube/showcase.webp by default. Re-run after retaking a screenshot.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ASSETS = os.path.join(os.path.dirname(__file__), '..', '.github', 'assets', 'metube')
FONTS = 'C:/Windows/Fonts/'
RED = (255, 69, 58)


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)


def rounded_mask(size, radius):
    mask = Image.new('L', size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def shot(name, width):
    img = Image.open(os.path.join(ASSETS, name + '.jpg')).convert('RGB')
    return img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)


def screen(name, width, radius=34, border=(58, 58, 64)):
    """A frameless screen: the screenshot with rounded corners and a hairline edge."""
    img = shot(name, width)
    out = Image.new('RGBA', img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), rounded_mask(img.size, radius))
    ImageDraw.Draw(out).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius,
                                          outline=border + (255,), width=2)
    return out


def glow(size, center, radius, color, alpha):
    layer = Image.new('RGBA', size, color + (0,))
    mask = Image.new('L', size, 0)
    x, y = center
    ImageDraw.Draw(mask).ellipse((x - radius, y - radius, x + radius, y + radius), fill=alpha)
    layer.putalpha(mask.filter(ImageFilter.GaussianBlur(radius * 0.6)))
    return layer


# ---------------------------------------------------------------------------------------------
# Bento: cards of different sizes, one screen each, a caption and a headline per card.

BENTO_COLS, BENTO_ROWS = 5, 2
CARD_W, CARD_H, GAP = 470, 640, 26
CARDS = [  # screen, caption, headline, col, row, col span, row span, layout, tint
    ('watch', 'Watch', 'No ads.\nReal dislikes.', 0, 0, 1, 2, 'full', (255, 69, 58)),
    ('lyrics', 'Synced lyrics', 'Every line,\nright on time.', 1, 0, 2, 1, 'side', (150, 70, 255)),
    ('shorts', 'Shorts', 'Full screen,\nswipe ready.', 3, 0, 1, 2, 'full', (255, 140, 40)),
    ('channel', 'Channels', 'Banner, Subscribe,\nShorts tab.', 4, 0, 1, 1, 'peek', (255, 70, 150)),
    ('home', 'Liquid glass', 'A tab bar\nthat floats.', 1, 1, 1, 1, 'peek', (70, 140, 255)),
    ('search', 'Search', "YouTube's chips\nand filters.", 2, 1, 1, 1, 'peek', (40, 200, 190)),
    ('downloads', 'Offline', 'Save it\nin 1080p.', 4, 1, 1, 1, 'peek', (60, 210, 110)),
]


def card(name, caption, headline, w, h, layout, tint):
    img = Image.new('RGBA', (w, h), (22, 22, 26, 255))
    img.alpha_composite(glow((w, h), (w * 0.5, h * 0.95), max(w, h) * 0.55, tint, 120))
    d = ImageDraw.Draw(img)
    cap_f, head_f = font('seguisb.ttf', 30), font('segoeuib.ttf', 46)
    pad = 40
    if layout == 'side':
        d.text((pad, 150), caption, font=cap_f, fill=tint)
        d.multiline_text((pad, 196), headline, font=head_f, fill=(255, 255, 255), spacing=6)
        s = screen(name, 420)
        crop = s.crop((0, int(s.height * 0.315), s.width, s.height))   # the lyrics panel
        img.alpha_composite(crop, (w - crop.width - 60, max(40, h - crop.height)))
    else:
        d.text((pad, pad), caption, font=cap_f, fill=tint)
        d.multiline_text((pad, pad + 44), headline, font=head_f, fill=(255, 255, 255), spacing=6)
        top = pad + 44 + (2 if '\n' in headline else 1) * 56 + 34
        if layout == 'full':
            s = screen(name, w - 2 * pad)
            if top + s.height > h - pad:
                s = screen(name, round((w - 2 * pad) * (h - pad - top) / s.height))
            img.alpha_composite(s, ((w - s.width) // 2, top))
        else:
            s = screen(name, w - 2 * pad - 30)
            img.alpha_composite(s.crop((0, 0, s.width, h - top)), ((w - s.width) // 2, top))
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), rounded_mask((w, h), 40))
    ImageDraw.Draw(out).rounded_rectangle((0, 0, w - 1, h - 1), 40, outline=(255, 255, 255, 26), width=2)
    return out


def bento():
    W = BENTO_COLS * CARD_W + (BENTO_COLS - 1) * GAP
    H = BENTO_ROWS * CARD_H + (BENTO_ROWS - 1) * GAP
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    for name, caption, headline, c, r, cs, rs, layout, tint in CARDS:
        w, h = cs * CARD_W + (cs - 1) * GAP, rs * CARD_H + (rs - 1) * GAP
        img.alpha_composite(card(name, caption, headline, w, h, layout, tint), (c * (CARD_W + GAP), r * (CARD_H + GAP)))
    return img


# ---------------------------------------------------------------------------------------------
# Cover flow: the watch page facing you, the other screens turned away on both sides,
# reflected in a glossy floor.

FLOW = ['search', 'home', 'lyrics', 'watch', 'shorts', 'channel', 'downloads']


def perspective(img, quad, size):
    w, h = img.size
    src = [(0, 0), (w, 0), (w, h), (0, h)]
    rows = []
    for (x, y), (u, v) in zip(quad, src):
        rows.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        rows.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    coeffs = np.linalg.solve(np.array(rows, float), np.array([c for p in src for c in p], float))
    return img.transform(size, Image.PERSPECTIVE, coeffs.tolist(), Image.BICUBIC)


def with_reflection(s):
    h = s.height
    out = Image.new('RGBA', (s.width, int(h * 1.42)), (0, 0, 0, 0))
    out.alpha_composite(s)
    ref = s.transpose(Image.FLIP_TOP_BOTTOM)
    fade = Image.linear_gradient('L').resize(ref.size).point(lambda v: max(0, 70 - v * 0.55))
    ref.putalpha(Image.composite(ref.getchannel('A'), Image.new('L', ref.size, 0), fade))
    out.alpha_composite(ref, (0, h + 8))
    return out


def coverflow():
    W, H = 2600, 1300
    img = Image.new('RGBA', (W, H), (10, 10, 13, 255))
    img.alpha_composite(glow((W, H), (W / 2, H * 0.45), 760, RED, 120))
    img.alpha_composite(glow((W, H), (W * 0.18, H * 0.6), 520, (120, 60, 255), 60))
    img.alpha_composite(glow((W, H), (W * 0.82, H * 0.6), 520, (255, 140, 40), 60))
    mid = len(FLOW) // 2
    base_y = 120
    layers = []
    for i, name in enumerate(FLOW):
        k = i - mid
        s = with_reflection(screen(name, 420 if k == 0 else 360, radius=30))
        sw, sh = s.size
        if k == 0:
            layers.append((10, s, (W - sw) // 2, base_y))
            continue
        side = 1 if k > 0 else -1
        depth = abs(k)
        cx = W / 2 + side * (360 + (depth - 1) * 250)
        near, far = sh * (0.92 - 0.05 * (depth - 1)), sh * (0.70 - 0.05 * (depth - 1))
        w2 = sw * 0.62
        top = base_y + 40 + (sh * 0.92 - near) / 2
        if side > 0:   # turned away to the right: near edge on the left
            quad = [(cx - w2 / 2, top), (cx + w2 / 2, top + (near - far) / 2),
                    (cx + w2 / 2, top + (near + far) / 2), (cx - w2 / 2, top + near)]
        else:
            quad = [(cx - w2 / 2, top + (near - far) / 2), (cx + w2 / 2, top),
                    (cx + w2 / 2, top + near), (cx - w2 / 2, top + (near + far) / 2)]
        layers.append((10 - depth, perspective(s, quad, (W, H)), 0, 0))
    for _, layer, x, y in sorted(layers, key=lambda l: l[0]):
        img.alpha_composite(layer, (int(x), int(y)))
    out = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    out.paste(img, (0, 0), rounded_mask((W, H), 56))
    return out


if __name__ == '__main__':
    style = sys.argv[1] if len(sys.argv) > 1 else 'bento'
    result = {'bento': bento, 'coverflow': coverflow}[style]()
    path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ASSETS, 'showcase.webp')
    result.save(path, quality=88, method=6)
    print(path, result.size, os.path.getsize(path) // 1024, 'KB')
