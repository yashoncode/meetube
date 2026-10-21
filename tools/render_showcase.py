"""Renders the README showcase: the app screenshots in phone frames on a glowing backdrop.

    python tools/render_showcase.py

Writes .github/assets/metube/showcase-dark.jpg and showcase-light.jpg (the README picks one per
GitHub theme). Re-run after retaking a screenshot in .github/assets/metube/.
"""
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ASSETS = os.path.join(os.path.dirname(__file__), '..', '.github', 'assets', 'metube')
FONTS = 'C:/Windows/Fonts/'

ROWS = [
    [('home', 'Home', 'Topic chips, glass tab bar'),
     ('watch', 'Watch', 'Glass pills, real dislikes'),
     ('shorts', 'Shorts', 'Full screen, preloaded'),
     ('lyrics', 'Lyrics', 'Synced, line by line')],
    [('search', 'Search', "YouTube's chips and filters"),
     ('channel', 'Channels', 'Banner, Subscribe, Shorts tab'),
     ('downloads', 'Downloads', 'Offline, up to 1080p')],
]

PHONE_W = 400          # screen width; the image is shown at about half size, so it stays sharp
BEZEL = 12
SCREEN_R = 46
GAP = 72
MARGIN_X = 120
MARGIN_TOP = 110
ROW_GAP = 70
CAPTION_H = 120
WAVE = 44              # alternate phones sit this much lower

THEMES = {
    'dark': dict(bg=(11, 11, 14), glow=(255, 69, 58), glow_a=70, title=(245, 245, 247),
                 sub=(152, 152, 160), shadow_a=200, frame=(22, 22, 25), rim=(70, 70, 78)),
    'light': dict(bg=(245, 245, 247), glow=(255, 69, 58), glow_a=34, title=(18, 18, 20),
                  sub=(110, 110, 118), shadow_a=90, frame=(22, 22, 25), rim=(90, 90, 98)),
}


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)


def rounded_mask(size, radius):
    mask = Image.new('L', size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def phone(shot, t):
    screen = Image.open(os.path.join(ASSETS, shot + '.jpg')).convert('RGB')
    screen = screen.resize((PHONE_W, round(screen.height * PHONE_W / screen.width)), Image.LANCZOS)
    w, h = PHONE_W + 2 * BEZEL, screen.height + 2 * BEZEL
    body = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(body)
    draw.rounded_rectangle((0, 0, w - 1, h - 1), SCREEN_R + BEZEL, fill=t['frame'] + (255,))
    draw.rounded_rectangle((1, 1, w - 2, h - 2), SCREEN_R + BEZEL - 1, outline=t['rim'] + (255,), width=2)
    body.paste(screen, (BEZEL, BEZEL), rounded_mask(screen.size, SCREEN_R))
    return body


def backdrop(size, t):
    w, h = size
    img = Image.new('RGB', size, t['bg'])
    glow = Image.new('L', size, 0)
    gd = ImageDraw.Draw(glow)
    gd.ellipse((w * 0.18, -h * 0.25, w * 0.82, h * 0.45), fill=t['glow_a'])
    gd.ellipse((w * 0.30, h * 0.62, w * 0.70, h * 1.15), fill=t['glow_a'] // 2)
    glow = glow.filter(ImageFilter.GaussianBlur(w / 9))
    img.paste(Image.new('RGB', size, t['glow']), (0, 0), glow)
    return img


def render(theme):
    t = THEMES[theme]
    phones = [[phone(shot, t) for shot, _, _ in row] for row in ROWS]
    pw, ph = phones[0][0].size
    cols = max(len(r) for r in ROWS)
    width = 2 * MARGIN_X + cols * pw + (cols - 1) * GAP
    row_h = ph + WAVE + CAPTION_H
    height = MARGIN_TOP + len(ROWS) * row_h + (len(ROWS) - 1) * ROW_GAP + 60
    img = backdrop((width, height), t)

    shadow = Image.new('L', (width, height), 0)
    sd = ImageDraw.Draw(shadow)
    placed = []
    for r, row in enumerate(phones):
        row_w = len(row) * pw + (len(row) - 1) * GAP
        x0 = (width - row_w) // 2
        y0 = MARGIN_TOP + r * (row_h + ROW_GAP)
        for i, body in enumerate(row):
            x = x0 + i * (pw + GAP)
            y = y0 + (WAVE if (i + r) % 2 else 0)
            placed.append((body, x, y, ROWS[r][i]))
            sd.rounded_rectangle((x + 10, y + 34, x + pw - 10, y + ph + 20), SCREEN_R + BEZEL, fill=t['shadow_a'])
    img.paste(Image.new('RGB', img.size, (0, 0, 0)), (0, 0), shadow.filter(ImageFilter.GaussianBlur(34)))

    draw = ImageDraw.Draw(img)
    title_font, sub_font = font('seguisb.ttf', 38), font('segoeui.ttf', 27)
    for body, x, y, (_, title, sub) in placed:
        img.paste(body, (x, y), body)
        cx, ty = x + pw // 2, y + ph + 30
        draw.text((cx, ty), title, font=title_font, fill=t['title'], anchor='mt')
        draw.text((cx, ty + 52), sub, font=sub_font, fill=t['sub'], anchor='mt')

    out = os.path.join(ASSETS, 'showcase-%s.jpg' % theme)
    img.save(out, quality=88, optimize=True, progressive=True)
    print(out, img.size, os.path.getsize(out) // 1024, 'KB')


if __name__ == '__main__':
    for name in THEMES:
        render(name)
