# -*- coding: utf-8 -*-
"""Generate the full TV Hub icon set from art/source/tv-hub-icon.jpg.

Rules:
- The cat-ear TV motif is kept intact; only the surrounding solid-black
  backdrop is removed. No cropping of the subject.
- Background removal is connectivity-based (flood fill from the four corners),
  so the dark bezel inside the white shell is preserved.
- Two in-app marks are produced for light/dark themes. Note the upstream
  naming is counter-intuitive: Ui.kt uses mark_dark for the DARK theme.

Usage: python art/generate_icons.py
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import os
import shutil

try:
    import numpy as np
except ImportError:
    np = None

ART = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(ART)
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ART, "source", "tv-hub-icon.jpg")
FONT = os.path.join(RES, "font", "geist.ttf")
PREVIEW = os.path.join(ART, "rebrand-preview")
os.makedirs(PREVIEW, exist_ok=True)

BLACK = (10, 10, 12, 255)
KEY = (255, 0, 255)

LAUNCHER = [("mdpi", 80), ("hdpi", 120), ("xhdpi", 160),
            ("xxhdpi", 240), ("xxxhdpi", 320)]
BANNER = [("mdpi", 160, 90), ("hdpi", 240, 135), ("xhdpi", 320, 180),
          ("xxhdpi", 480, 270), ("xxxhdpi", 640, 360)]


def cutout_background():
    """Remove only the black area connected to the image corners."""
    im = Image.open(SRC).convert("RGB")
    w, h = im.size
    for corner in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        ImageDraw.floodfill(im, corner, KEY, thresh=50)

    if np is not None:
        arr = np.asarray(im)
        mask = np.any(arr != np.array(KEY, dtype=arr.dtype), axis=2)
        alpha = Image.fromarray((mask * 255).astype("uint8"))
    else:
        alpha = Image.new("L", (w, h), 0)
        src_px, a_px = im.load(), alpha.load()
        for y in range(h):
            for x in range(w):
                if src_px[x, y] != KEY:
                    a_px[x, y] = 255

    alpha = alpha.filter(ImageFilter.GaussianBlur(0.6))
    rgba = im.convert("RGBA")
    rgba.putalpha(alpha)
    box = alpha.getbbox()
    pad = 4
    box = (max(0, box[0] - pad), max(0, box[1] - pad),
           min(w, box[2] + pad), min(h, box[3] + pad))
    return rgba.crop(box), im.size, box


def light_theme_mark(cut):
    """Darken the white shell so the mark stays visible on a light UI.

    Only the shell is recoloured: the white glyphs inside the four coloured
    quadrants sit within the detected colour bounding box and stay white.
    """
    w, h = cut.size
    src = cut.load()

    def colourful_box():
        x0, y0, x1, y1 = w, h, -1, -1
        for y in range(h):
            for x in range(w):
                r, g, b, a = src[x, y]
                if a > 128 and max(r, g, b) - min(r, g, b) > 60 and max(r, g, b) > 100:
                    if x < x0:
                        x0 = x
                    if y < y0:
                        y0 = y
                    if x > x1:
                        x1 = x
                    if y > y1:
                        y1 = y
        return x0, y0, x1, y1

    sx0, sy0, sx1, sy1 = colourful_box()
    if sx1 < sx0:
        return cut
    out = cut.copy()
    dst = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = src[x, y]
            inside = (sx0 - 8 <= x <= sx1 + 8) and (sy0 - 8 <= y <= sy1 + 8)
            if (not inside) and min(r, g, b) > 165 and a > 0:
                dst[x, y] = (42, 42, 46, a)
    return out


def fit_square(img, size, fill, ratio=0.92):
    tw = th = int(size * ratio)
    scale = min(tw / img.width, th / img.height)
    nw, nh = max(1, int(img.width * scale)), max(1, int(img.height * scale))
    small = img.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), fill)
    canvas.paste(small, ((size - nw) // 2, (size - nh) // 2), small)
    return canvas


def make_banner(img, W, H):
    """16:9 banner with the full logo (icon + app name), per TV-BN."""
    k = H / 180.0
    canvas = Image.new("RGBA", (W, H), BLACK)
    icon_h = int(120 * k)
    scale = icon_h / img.height
    icon_w = max(1, int(img.width * scale))
    icon = img.resize((icon_w, icon_h), Image.LANCZOS)
    x0 = int(16 * k)
    canvas.paste(icon, (x0, (H - icon_h) // 2), icon)

    draw = ImageDraw.Draw(canvas)
    text_x = x0 + icon_w + int(14 * k)
    avail = W - text_x - int(10 * k)
    size = int(42 * k)
    while size > int(16 * k):
        font = ImageFont.truetype(FONT, size)
        box = draw.textbbox((0, 0), "TV Hub", font=font)
        if box[2] - box[0] <= avail:
            break
        size -= 1
    font = ImageFont.truetype(FONT, size)
    stroke = max(0, int(1 * k))
    draw.text((text_x, H // 2), "TV Hub", font=font, fill=(255, 255, 255, 255),
              anchor="lm", stroke_width=stroke, stroke_fill=(255, 255, 255, 255))
    return canvas


def preview(cut, mark_light, mark_dark, launcher, banner):
    """Side-by-side preview on dark and light backgrounds."""
    W, H = 1000, 460
    out = Image.new("RGB", (W, H), (246, 246, 248))
    d = ImageDraw.Draw(out)
    title = ImageFont.truetype(FONT, 20)
    small = ImageFont.truetype(FONT, 13)
    d.text((20, 12), "TV Hub icon set", font=title, fill=(30, 30, 34))

    rows = [("dark theme", 50, (20, 20, 22)), ("light theme", 250, (242, 242, 238))]
    for label, top, bg in rows:
        d.rounded_rectangle((20, top, W - 20, top + 180), radius=12, fill=bg)
        d.text((34, top + 8), label, font=small,
               fill=(210, 210, 214) if label.startswith("dark") else (90, 90, 96))
        mark = mark_dark if label.startswith("dark") else mark_light
        items = [(banner.convert("RGBA"), "banner 320x180"),
                 (launcher, "launcher 320"),
                 (mark, "mark 192")]
        x = 40
        for im, caption in items:
            target_h = 110
            scale = target_h / im.height
            thumb = im.resize((int(im.width * scale), target_h), Image.LANCZOS)
            if thumb.width > 340:
                scale = 340 / thumb.width
                thumb = thumb.resize((340, int(thumb.height * scale)), Image.LANCZOS)
            out.paste(thumb, (x, top + 34), thumb if thumb.mode == "RGBA" else None)
            d.text((x, top + 152), caption, font=small,
                   fill=(190, 190, 196) if label.startswith("dark") else (110, 110, 116))
            x += 330
    p = os.path.join(PREVIEW, "tvhub-icons-preview.png")
    out.save(p)
    return p


def main():
    cut, orig_size, box = cutout_background()
    mark_dark = cut                       # dark theme: original white shell
    mark_light = light_theme_mark(cut)    # light theme: darkened shell

    print("source:", orig_size, "subject bbox:", box,
          "subject:", cut.size, "| numpy:", np is not None)

    # in-app marks (drawable-nodpi, no density scaling in code)
    nodpi = os.path.join(RES, "drawable-nodpi")
    fit_square(mark_light, 192, (0, 0, 0, 0), 0.94).save(
        os.path.join(nodpi, "mark.png"))
    fit_square(mark_dark, 192, (0, 0, 0, 0), 0.94).save(
        os.path.join(nodpi, "mark_dark.png"))

    # launcher icons, one per density
    for density, size in LAUNCHER:
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        fit_square(cut, size, BLACK, 0.90).convert("RGB").save(
            os.path.join(folder, "ic_launcher.png"))

    # banners, one per density
    for density, W, H in BANNER:
        folder = os.path.join(RES, "drawable-" + density)
        os.makedirs(folder, exist_ok=True)
        make_banner(cut, W, H).convert("RGB").save(
            os.path.join(folder, "banner.png"))

    launcher = fit_square(cut, 320, BLACK, 0.90)
    banner = make_banner(cut, 320, 180)
    p = preview(cut, mark_light, mark_dark, launcher, banner)
    print("preview:", p)
    print("generated: 2 marks + %d launchers + %d banners" %
          (len(LAUNCHER), len(BANNER)))


if __name__ == "__main__":
    main()
