"""Renders the README showcase (bento, the one in use) from the app screenshots in .github/assets/meetube/.

    python tools/render_showcase.py [bento|coverflow|tv] [out path]

Writes .github/assets/meetube/showcase.webp by default (tv: MeeTube TV's poster, tv.webp). Re-run after
retaking a screenshot.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ASSETS = os.path.join(os.path.dirname(__file__), '..', '.github', 'assets', 'meetube')
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
    ('screens/compact_player', 'Watch', 'No ads.\nReal dislikes.', 0, 0, 1, 2, 'full', (255, 69, 58)),
    ('lyrics', 'Synced lyrics', 'Every line,\nright on time.', 1, 0, 2, 1, 'side', (150, 70, 255)),
    ('screens/home', 'Home', 'Videos play\nas you scroll.', 3, 0, 1, 2, 'full', (255, 140, 40)),
    ('screens/channel', 'Channels', 'Posts, Shorts\nand live chat.', 4, 0, 1, 1, 'peek', (255, 70, 150)),
    ('screens/youtube_mode', 'YouTube mode', 'The look\nyou know.', 1, 1, 1, 1, 'peek', (70, 140, 255)),
    ('screens/equalizer', 'Equalizer', 'Tune how\nit sounds.', 2, 1, 1, 1, 'peek', (40, 200, 190)),
    ('screens/you', 'Offline', '4K, smart and\nauto downloads.', 4, 1, 1, 1, 'peek', (60, 210, 110)),
]

PEEK_FROM = {'screens/equalizer': 0.38, 'screens/you': 0.37}


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
            y0 = int(s.height * PEEK_FROM.get(name, 0))   # where the peek starts, for screens whose subject sits low
            img.alpha_composite(s.crop((0, y0, s.width, y0 + h - top)), ((w - s.width) // 2, top))
    return finish(img)


def finish(img, radius=40):
    """A card's rounded corners and hairline edge."""
    w, h = img.size
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), rounded_mask((w, h), radius))
    ImageDraw.Draw(out).rounded_rectangle((0, 0, w - 1, h - 1), radius, outline=(255, 255, 255, 26), width=2)
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


# ---------------------------------------------------------------------------------------------
# TV: MeeTube TV's poster (tv.webp). A title card with the logo over four TV screens, from the
# TV's own screenshots (tv_*.jpg, 1280x720 screencaps).

TV_W, TV_GAP, TV_HEAD_H = 2400, 30, 400
TV_CARDS = [  # screen, caption, headline, tint
    ('tv_home', 'Glass sidebar', 'Everything, one click away.', (70, 140, 255)),
    ('tv_sports', 'Explore', 'Kids, Sports, Live and more.', (60, 210, 110)),
    ('tv_shorts', 'Shorts', 'Made for the big screen.', (255, 140, 40)),
    ('tv_subscriptions', 'Subscriptions', 'Every channel at a glance.', (255, 70, 150)),
]
TV_PILLS = ['No ads', 'Liquid glass', 'Shorts on TV', 'Updates itself']


def tv_header(w, h):
    img = Image.new('RGBA', (w, h), (22, 22, 26, 255))
    img.alpha_composite(glow((w, h), (230, h * 0.5), 460, RED, 120))
    img.alpha_composite(glow((w, h), (w * 0.88, h * 1.05), 560, (120, 60, 255), 90))
    logo = Image.open(os.path.join(ASSETS, 'icon.png')).convert('RGBA').resize((230, 230), Image.LANCZOS)
    img.alpha_composite(logo, (90, (h - 230) // 2))
    d = ImageDraw.Draw(img)
    x, title_f = 370, font('seguibl.ttf', 136)
    d.text((x, 178), 'MeeTube', font=title_f, fill=(255, 255, 255), anchor='ls')
    d.text((x + d.textlength('MeeTube ', font=title_f), 178), 'TV', font=title_f, fill=RED, anchor='ls')
    d.text((x + 4, 252), 'YouTube, reimagined for the big screen.', font=font('seguisb.ttf', 50),
           fill=(225, 225, 232), anchor='ls')
    pills = Image.new('RGBA', (w, h), (0, 0, 0, 0))   # glass pills blend over the glow on their own layer
    pd, pill_f, px = ImageDraw.Draw(pills), font('seguisb.ttf', 32), x
    for p in TV_PILLS:
        pw = pd.textlength(p, font=pill_f) + 56
        pd.rounded_rectangle((px, 290, px + pw, 350), 30, fill=(255, 255, 255, 26), outline=(255, 255, 255, 60), width=2)
        px += pw + 16
    img.alpha_composite(pills)
    px = x
    for p in TV_PILLS:
        pw = d.textlength(p, font=pill_f) + 56
        d.text((px + pw / 2, 320), p, font=pill_f, fill=(240, 240, 245), anchor='mm')
        px += pw + 16
    return finish(img)


def tv_card(name, caption, headline, w, tint):
    pad = 56
    s = screen(name, w - 2 * pad, radius=24)
    top = pad + 44 + 92
    h = top + s.height + pad
    img = Image.new('RGBA', (w, h), (22, 22, 26, 255))
    img.alpha_composite(glow((w, h), (w * 0.5, h * 0.95), max(w, h) * 0.55, tint, 120))
    d = ImageDraw.Draw(img)
    d.text((pad, pad), caption, font=font('seguisb.ttf', 34), fill=tint)
    d.text((pad, pad + 44), headline, font=font('segoeuib.ttf', 56), fill=(255, 255, 255))
    img.alpha_composite(s, (pad, top))
    return finish(img)


def tv():
    cw = (TV_W - TV_GAP) // 2
    cards = [tv_card(n, c, hl, cw, t) for n, c, hl, t in TV_CARDS]
    ch = cards[0].height
    img = Image.new('RGBA', (TV_W, TV_HEAD_H + 2 * (TV_GAP + ch)), (0, 0, 0, 0))
    img.alpha_composite(tv_header(TV_W, TV_HEAD_H))
    for i, c in enumerate(cards):
        img.alpha_composite(c, ((i % 2) * (cw + TV_GAP), TV_HEAD_H + TV_GAP + (i // 2) * (ch + TV_GAP)))
    return img


if __name__ == '__main__':
    style = sys.argv[1] if len(sys.argv) > 1 else 'bento'
    result = {'bento': bento, 'coverflow': coverflow, 'tv': tv}[style]()
    path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ASSETS, 'tv.webp' if style == 'tv' else 'showcase.webp')
    result.save(path, quality=88, method=6)
    print(path, result.size, os.path.getsize(path) // 1024, 'KB')
