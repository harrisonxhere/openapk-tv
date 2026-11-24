# -*- coding: utf-8 -*-
"""Generate the OpenAPK TV icon set from art/source/logo-1024.png.

Pipeline:
    art/logo.svg  --(Chrome headless, 一次性)-->  art/source/logo-1024.png
    art/source/logo-1024.png  --(本脚本, PIL)-->  mipmap-*/ic_launcher.png
                                                  drawable-*/banner.png

Chrome 命令（改 logo.svg 后重跑一次，透明底必须加 --default-background-color）：
    "/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new \
      --disable-gpu --hide-scrollbars --default-background-color=00000000 \
      --window-size=1024,1024 --screenshot="D:/tmp/logo-1024.png" \
      "file:///<repo>/art/logo.svg"

Logo 是全彩渐变（#51D66C → #26C47E → #167F91），不再做单色化处理；
浅色主题下靠亮度对比也能看清，所以不再产出 mark.png / mark_dark.png。

Usage: python art/generate_icons.py
"""
from PIL import Image, ImageDraw, ImageFont
import os

ART = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(ART)
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ART, "source", "logo-1024.png")
FONT = os.path.join(RES, "font", "geist.ttf")
PREVIEW = os.path.join(ART, "rebrand-preview")
os.makedirs(PREVIEW, exist_ok=True)

NAME = "OpenAPK TV"
ICON_BG = (10, 10, 12, 255)          # 启动器图标底色，沿用旧版的近黑
BANNER_TOP = (30, 39, 58, 255)       # banner 竖向渐变，与旧版一致
BANNER_BOTTOM = (19, 25, 36, 255)

LAUNCHER = [("mdpi", 80), ("hdpi", 120), ("xhdpi", 160),
            ("xxhdpi", 240), ("xxxhdpi", 320)]
BANNER = [("mdpi", 160, 90), ("hdpi", 240, 135), ("xhdpi", 320, 180),
          ("xxhdpi", 480, 270), ("xxxhdpi", 640, 360)]


def load_logo():
    """Trim the transparent padding so the mark is optically centred later."""
    im = Image.open(SRC).convert("RGBA")
    box = im.getchannel("A").getbbox()
    if not box:
        raise SystemExit("logo-1024.png 是全透明的，检查 Chrome 渲染参数")
    return im.crop(box)


def fit_square(img, size, bg, ratio=0.80):
    tw = th = int(size * ratio)
    scale = min(tw / img.width, th / img.height)
    nw, nh = max(1, int(img.width * scale)), max(1, int(img.height * scale))
    small = img.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), bg)
    canvas.paste(small, ((size - nw) // 2, (size - nh) // 2), small)
    return canvas


def banner_bg(W, H):
    canvas = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(canvas)
    for y in range(H):
        t = y / max(1, H - 1)
        d.line([(0, y), (W, y)], fill=tuple(
            int(a + (b - a) * t) for a, b in zip(BANNER_TOP, BANNER_BOTTOM)))
    return canvas


def make_banner(img, W, H):
    """16:9 banner with the full logo (icon + app name), per TV-BN."""
    k = H / 180.0
    canvas = banner_bg(W, H)
    icon_h = int(112 * k)
    scale = icon_h / img.height
    icon_w = max(1, int(img.width * scale))
    icon = img.resize((icon_w, icon_h), Image.LANCZOS)
    x0 = int(22 * k)
    canvas.paste(icon, (x0, (H - icon_h) // 2), icon)

    draw = ImageDraw.Draw(canvas)
    text_x = x0 + icon_w + int(20 * k)
    avail = W - text_x - int(14 * k)
    size = int(40 * k)
    while size > int(16 * k):
        font = ImageFont.truetype(FONT, size)
        box = draw.textbbox((0, 0), NAME, font=font)
        if box[2] - box[0] <= avail:
            break
        size -= 1
    font = ImageFont.truetype(FONT, size)
    draw.text((text_x, H // 2), NAME, font=font, fill=(255, 255, 255, 255), anchor="lm")
    return canvas


def preview(logo, launcher, banner):
    W, H = 1000, 300
    out = Image.new("RGB", (W, H), (246, 246, 248))
    d = ImageDraw.Draw(out)
    d.text((20, 12), "OpenAPK TV icon set", font=ImageFont.truetype(FONT, 20), fill=(30, 30, 34))
    d.rounded_rectangle((20, 50, W - 20, 280), radius=12, fill=(20, 20, 22))
    x = 40
    for im, caption in ((banner.convert("RGBA"), "banner 320x180"),
                        (launcher, "launcher 320")):
        target_h = 130
        scale = target_h / im.height
        thumb = im.resize((int(im.width * scale), target_h), Image.LANCZOS)
        out.paste(thumb, (x, 80), thumb)
        d.text((x, 228), caption, font=ImageFont.truetype(FONT, 13), fill=(200, 200, 206))
        x += thumb.width + 40
    p = os.path.join(PREVIEW, "openapk-tv-icons.png")
    out.save(p)
    return p


def main():
    logo = load_logo()
    print("logo:", logo.size, "from", SRC)

    for density, size in LAUNCHER:
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        fit_square(logo, size, ICON_BG).convert("RGB").save(
            os.path.join(folder, "ic_launcher.png"))

    for density, W, H in BANNER:
        folder = os.path.join(RES, "drawable-" + density)
        os.makedirs(folder, exist_ok=True)
        make_banner(logo, W, H).convert("RGB").save(
            os.path.join(folder, "banner.png"))

    print("preview:", preview(logo, fit_square(logo, 320, ICON_BG), make_banner(logo, 320, 180)))
    print("generated: %d launchers + %d banners" % (len(LAUNCHER), len(BANNER)))


if __name__ == "__main__":
    main()
