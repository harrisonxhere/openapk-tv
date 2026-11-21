# -*- coding: utf-8 -*-
"""按 12 个一级分类重新分配 catalog.json。

原则：
- 原始上游标签 `room` 一律不动（元数据保留），新增两个字段：
  - `group`：12 个一级分类的稳定 key（ASCII，改文案不用改数据）
  - `sub`：二级分类 key，默认等于 `room`；音乐迁移的那批改成 "Music"
- 除 group / sub 外，每个应用的所有字段与原始值完全一致。
- 原始文件另存一份到 data/，只在首次运行时创建，不覆盖。

用法：
    python tools/reclassify.py            # 只出报告，不写文件
    python tools/reclassify.py --write    # 备份原始文件并写入新 catalog.json
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "assets", "catalog.json")
BACKUP = os.path.join(ROOT, "data", "catalog-source-2026-11-20.json")

GROUP_OF = {
    "Streaming": "video",
    "Video": "video",
    "Photos": "photos",
    "Music": "music",
    "Games": "games",
    "Reading": "reading",
    "Learning": "reading",
    "Chat & social": "social",
    "Browsers": "browser",
    "Smart home": "home",
    "Launchers": "desktop",
    "Screensavers": "desktop",
    "App stores": "stores",
    "AI": "ai",
    "Tools": "system",
    "Files & downloads": "system",
    "VPN & network": "system",
    "Other": "other",
}

GROUP_ORDER = ["video", "photos", "music", "games", "reading", "social",
               "browser", "home", "desktop", "stores", "ai", "system", "other"]
GROUP_CN = {
    "video": "影视", "photos": "相册与图片", "music": "音乐", "games": "游戏",
    "reading": "阅读", "social": "社交", "browser": "浏览", "home": "家居",
    "desktop": "桌面与外观", "stores": "应用商店", "ai": "AI 工具",
    "system": "系统", "other": "其他",
}

# 影视里被误当成在线视频的音乐类客户端，迁到音乐组。
# 只在「名称 / 包名」上判：描述里顺带提到 music 的（Jellyfin、mpv-android 这类）不算，
# 否则会把媒体服务器和本地播放器一起拖进音乐。
MUSIC_NAME = ["music", "音乐", "podcast", "spotify", "bitchord", "walkman"]
# radio 必须词边界匹配：包名 ua.com.radiokot.photoprism 里含 radio，但不是电台
MUSIC_RADIO = re.compile(r"(?:^|[^a-z])radio(?:[^a-z]|$)")
# 名称里带这些词的，即使有 music 也留在影视（"Lx music tv"、"Music video" 之类）
VIDEO_NAME = ["tv", "video", "player", "iptv", "film", "movie", "cast", "stream"]


def text_of(a):
    parts = [a.get("name") or "", a.get("package") or "", a.get("desc") or "",
             a.get("note") or "", " ".join(a.get("topics") or [])]
    return " ".join(parts).lower()


def name_of(a):
    return " ".join([a.get("name") or "", a.get("package") or ""]).lower()


def classify(a):
    room = a.get("room") or ""
    group = GROUP_OF.get(room, "other")
    sub = room
    if group == "video":
        n = name_of(a)
        title = (a.get("name") or "").lower()
        is_music = any(k in n for k in MUSIC_NAME) or bool(MUSIC_RADIO.search(n))
        if is_music and not any(k in title for k in VIDEO_NAME):
            group, sub = "music", "Music"
    return group, sub


def main():
    write = "--write" in sys.argv
    raw = open(SRC, encoding="utf-8").read()
    doc = json.loads(raw)
    apps = doc["apps"]

    counts = {g: 0 for g in GROUP_ORDER}
    sub_counts = {}
    migrated = []
    unknown = []

    for a in apps:
        if a.get("room") not in GROUP_OF:
            unknown.append(a.get("room"))
        group, sub = classify(a)
        a["group"] = group
        a["sub"] = sub
        counts[group] = counts.get(group, 0) + 1
        sub_counts.setdefault(group, {})
        sub_counts[group][sub] = sub_counts[group].get(sub, 0) + 1
        if group == "music" and sub == "Music" and a.get("room") != "Music":
            migrated.append((a["name"], a["room"]))

    total = sum(counts.values())
    print("总应用 %d，分到 %d 类，合计 %d" % (len(apps), len([g for g in counts if counts[g]]), total))
    print()
    for g in GROUP_ORDER:
        if not counts[g]:
            continue
        subs = sorted(sub_counts[g].items(), key=lambda kv: -kv[1])
        detail = "、".join("%s %d" % (s, n) for s, n in subs)
        print("%-6s %-12s %5d    %s" % (g, GROUP_CN[g], counts[g], detail))
    print()
    print("影视→音乐 迁移 %d 个：" % len(migrated))
    for name, room in migrated:
        print("   %-32s (%s)" % (name[:32], room))
    if unknown:
        print()
        print("未映射的 room：", sorted(set(unknown)))

    if not write:
        print()
        print("（未写入，加 --write 执行）")
        return

    if not os.path.exists(BACKUP):
        os.makedirs(os.path.dirname(BACKUP), exist_ok=True)
        shutil.copyfile(SRC, BACKUP)
        print()
        print("原始数据已备份：", os.path.relpath(BACKUP, ROOT))
    else:
        print()
        print("备份已存在，保持不动：", os.path.relpath(BACKUP, ROOT))

    with open(SRC, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
    print("已写入：", os.path.relpath(SRC, ROOT), os.path.getsize(SRC), "bytes")


if __name__ == "__main__":
    main()
