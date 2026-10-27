"""Renders the WinDock app icon: the default theme's orange cat walking on a dock bar, on a gradient squircle.

Usage: python tools/make_icon.py <output-dir>  (writes windock.ico, icon-256.png, icon-1024.png and a preview)
"""
import math
import sys

from PIL import Image, ImageDraw, ImageFilter

S = 1024  # master size; everything is drawn here and scaled down


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def hex_rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def squircle_mask(size, radius):
    mask = Image.new('L', (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def vertical_gradient(size, top, bottom):
    grad = Image.new('RGB', (1, size))
    for y in range(size):
        grad.putpixel((0, y), lerp(top, bottom, y / (size - 1)))
    return grad.resize((size, size))


def background():
    # Blue at the top-left melting into violet at the bottom-right.
    base = vertical_gradient(S, hex_rgb('#3C9BFF'), hex_rgb('#5B3BE8')).convert('RGBA')
    tint = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    td = ImageDraw.Draw(tint)
    for i in range(60):
        t = i / 59
        r = int(S * (1.2 - t))
        td.ellipse((S - r // 2 - 200, S - r // 2 - 200, S + r // 2, S + r // 2), fill=(120, 60, 230, int(4 * t)))
    base = Image.alpha_composite(base, tint)
    # Soft sheen across the top third.
    sheen = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sheen).ellipse((-S * 0.2, -S * 0.75, S * 1.2, S * 0.38), fill=(255, 255, 255, 46))
    sheen = sheen.filter(ImageFilter.GaussianBlur(40))
    return Image.alpha_composite(base, sheen)


def sparkle(d, cx, cy, r, alpha):
    """Four-point star, a nod to the dock's animated scenes."""
    w = r * .28
    pts = [(cx, cy - r), (cx + w, cy - w), (cx + r, cy), (cx + w, cy + w), (cx, cy + r), (cx - w, cy + w), (cx - r, cy), (cx - w, cy - w)]
    d.polygon(pts, fill=(255, 255, 255, alpha))


def cat(img, detailed):
    """The default theme's orange tabby, mid-stride on top of the dock bar."""
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    fur, stripe, dark, cream = hex_rgb('#F08A2C'), hex_rgb('#C2611A'), hex_rgb('#7A3F12'), hex_rgb('#FCE6C6')
    ground = 655
    # Tail curling up behind.
    d.arc((222, 300, 402, 560), 120, 300, fill=dark + (255,), width=58)
    d.arc((222, 300, 402, 560), 120, 300, fill=fur + (255,), width=40)
    # Far legs (shaded), body, near legs.
    for x, lift in ((372, 0), (612, 18)):
        d.rounded_rectangle((x, 500, x + 52, ground - lift), radius=26, fill=lerp(fur, dark, .35) + (255,), outline=dark + (255,), width=8)
    d.ellipse((300, 400, 700, 590), fill=fur + (255,), outline=dark + (255,), width=10)
    d.ellipse((450, 500, 640, 580), fill=cream + (255,))
    if detailed:
        for x in (400, 470, 540):
            d.line((x, 410, x + 22, 470), fill=stripe + (255,), width=20)
    for x, lift in ((330, 18), (560, 0)):
        d.rounded_rectangle((x, 510, x + 56, ground - lift), radius=28, fill=fur + (255,), outline=dark + (255,), width=8)
        d.ellipse((x - 4, ground - lift - 34, x + 60, ground - lift + 4), fill=cream + (255,), outline=dark + (255,), width=6)
    # Head with ears, looking ahead.
    hx, hy, hr = 690, 400, 118
    for ex in (-1, 1):
        cx = hx + ex * 62
        d.polygon([(cx - 52, hy - 70), (cx + ex * 18, hy - 168), (cx + 52, hy - 60)], fill=fur + (255,), outline=dark + (255,))
        d.polygon([(cx - 22, hy - 82), (cx + ex * 12, hy - 138), (cx + 24, hy - 76)], fill=(242, 160, 150, 255))
    d.ellipse((hx - hr, hy - hr, hx + hr, hy + hr), fill=fur + (255,), outline=dark + (255,), width=10)
    d.ellipse((hx - 30, hy + 10, hx + 110, hy + 100), fill=cream + (255,))
    if detailed:
        for ex in (-28, 0, 28):
            d.line((hx + ex, hy - hr + 12, hx + ex + 4, hy - hr + 52), fill=stripe + (255,), width=14)
        for ex in (-12, 52):
            d.ellipse((hx + ex - 16, hy - 38, hx + ex + 16, hy + 4), fill=(62, 107, 44, 255))
            d.ellipse((hx + ex - 6, hy - 32, hx + ex + 6, hy - 2), fill=(20, 20, 20, 255))
            d.ellipse((hx + ex + 2, hy - 30, hx + ex + 9, hy - 23), fill=(255, 255, 255, 255))
        d.polygon([(hx + 92, hy + 30), (hx + 112, hy + 30), (hx + 102, hy + 44)], fill=(228, 111, 122, 255))
        for dy in (-8, 10):
            d.line((hx + 108, hy + 46 + dy, hx + 190, hy + 34 + dy * 2), fill=(255, 255, 255, 200), width=5)
    else:
        d.ellipse((hx + 20, hy - 34, hx + 64, hy + 10), fill=(30, 30, 30, 255))
    shadow = layer.filter(ImageFilter.GaussianBlur(18))
    shadow.putalpha(shadow.getchannel('A').point(lambda a: int(a * .35)))
    img.alpha_composite(shadow, (0, 14))
    img.alpha_composite(layer)


def dock_bar(img, detailed):
    left, right, top, bottom = 150, S - 150, 655, 835
    shadow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((left + 10, top + 34, right - 10, bottom + 30), radius=80, fill=(20, 10, 70, 120))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(30)))
    bar = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bar)
    bd.rounded_rectangle((left, top, right, bottom), radius=80, fill=(255, 255, 255, 238))
    bd.rounded_rectangle((left, top, right, bottom), radius=80, outline=(255, 255, 255, 255), width=6)
    img.alpha_composite(bar)
    if not detailed:
        return
    # Three app tiles, the middle one "running" with the dock's blue pill under it.
    tile, gap = 124, 46
    total = tile * 3 + gap * 2
    x0 = (S - total) // 2
    ty = top + 22
    colors = [('#FFB547', '#FF7A1A'), ('#4FB7FF', '#1685F8'), ('#5BE39B', '#22B36A')]
    tiles = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    for i, (c1, c2) in enumerate(colors):
        x = x0 + i * (tile + gap)
        grad = vertical_gradient(tile, hex_rgb(c1), hex_rgb(c2)).convert('RGBA')
        grad.putalpha(squircle_mask(tile, 34))
        tiles.alpha_composite(grad, (x, ty))
        # A tiny highlight on each tile.
        hl = Image.new('RGBA', (tile, tile), (0, 0, 0, 0))
        ImageDraw.Draw(hl).rounded_rectangle((10, 8, tile - 10, tile // 2), radius=26, fill=(255, 255, 255, 52))
        hl.putalpha(Image.composite(hl.getchannel('A'), Image.new('L', (tile, tile), 0), squircle_mask(tile, 34)))
        tiles.alpha_composite(hl, (x, ty))
    pill_w, pill_h = 62, 16
    px = x0 + tile + gap + (tile - pill_w) // 2
    ImageDraw.Draw(tiles).rounded_rectangle((px, bottom - 26, px + pill_w, bottom - 26 + pill_h), radius=8, fill=hex_rgb('#1685F8') + (255,))
    img.alpha_composite(tiles)


def render(detailed=True):
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    bg = background()
    bg.putalpha(squircle_mask(S, 230))
    img.alpha_composite(bg)
    stars = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    sd = ImageDraw.Draw(stars)
    for cx, cy, r, a in ((210, 210, 46, 230), (830, 170, 34, 200), (880, 380, 22, 170), (150, 420, 18, 150)):
        sparkle(sd, cx, cy, r if detailed else r * 1.4, a)
    img.alpha_composite(stars)
    dock_bar(img, detailed)
    cat(img, detailed)
    # Clip everything to the squircle.
    img.putalpha(Image.composite(img.getchannel('A'), Image.new('L', (S, S), 0), squircle_mask(S, 230)))
    return img


if __name__ == '__main__':
    out_dir = sys.argv[1]
    detailed = render(detailed=True)
    simple = render(detailed=False)
    detailed.save(f'{out_dir}/icon-1024.png')
    sizes = [256, 128, 64, 48, 32, 24, 16]
    frames = [(simple if s <= 24 else detailed).resize((s, s), Image.LANCZOS) for s in sizes]
    frames[0].save(f'{out_dir}/windock.ico', format='ICO', sizes=[(s, s) for s in sizes], append_images=frames[1:])
    detailed.resize((256, 256), Image.LANCZOS).save(f'{out_dir}/icon-256.png')
    # Preview sheet: big icon plus the small sizes on light and dark backgrounds.
    sheet = Image.new('RGBA', (1200, 560), (245, 246, 250, 255))
    sheet.alpha_composite(detailed.resize((512, 512), Image.LANCZOS), (24, 24))
    dark = Image.new('RGBA', (620, 250), (32, 36, 45, 255))
    sheet.alpha_composite(dark, (560, 290))
    x = 580
    for s, f in zip(sizes[1:], frames[1:]):
        sheet.alpha_composite(f, (x, 120 - s // 2))
        sheet.alpha_composite(f, (x, 415 - s // 2))
        x += s + 28
    sheet.save(f'{out_dir}/icon-preview.png')
    print('done')
