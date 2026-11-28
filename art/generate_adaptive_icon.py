"""由官网首页 logo（openapk-web/public/logo.svg）生成自适应图标图层。

图片链：logo.svg → Chrome 无头渲染成 art/source/logo-web.png（1240×1084，透明底）→ 本脚本

背景：纯白 #FFFFFF（@color/ic_launcher_background，与官网亮色首屏一致）
前景：logo 缩到 48dp（108dp 画布，可见区 72dp 的 67%），圆形/方形遮罩都不会切到电视外壳
单色：取 logo 的 alpha 轮廓涂白，供 Android 13+ 主题图标使用

用法：
    python art/generate_adaptive_icon.py
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "art", "source", "logo-web.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
WIDTH_DP = 48  # 图形宽度；想再大/再小改这里，前景与单色层会一起重出

src = Image.open(SRC).convert("RGBA")
sw, sh = src.size

mono_src = Image.new("RGBA", src.size, (255, 255, 255, 0))
mono_src.putalpha(src.getchannel("A"))

for name, scale in DENSITIES.items():
    size = round(108 * scale)
    w = round(WIDTH_DP * scale)
    h = round(w * sh / sw)

    for art_src, suffix in ((src, ""), (mono_src, "_monochrome")):
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        art = art_src.resize((w, h), Image.LANCZOS)
        canvas.paste(art, ((size - w) // 2, (size - h) // 2), art)
        path = os.path.join(RES, f"mipmap-{name}", f"ic_launcher_foreground{suffix}.png")
        canvas.save(path)
        print("wrote", os.path.relpath(path, ROOT), canvas.size, "art", art.size)
