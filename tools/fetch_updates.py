# -*- coding: utf-8 -*-
"""抓取 catalog 里每个仓库的「最新 Release」，产出一份很小的更新索引。

为什么不用下载 APK：
    判断「有没有新版」只需要 latest release 的 tag；versionCode / 签名要到真正安装时
    才需要，而那时 Android 自己会用本地已装的包做比对。所以一轮采集 **0 次 APK 下载**。

为什么默认走 REST 而不是 GraphQL：
    GraphQL 的额度按「节点数」计费，`releaseAssets(first: 40)` 一个仓库就要 40 点，
    50 个一包 = 2100 点，5000 点/小时只够查 119 个仓库 —— 2923 个要跑 24 小时，必然撞墙。
    REST 的 `releases/latest` 按「请求数」计费（5000/小时），一个仓库 1 次，
    而且一次就带回全部 assets，不需要第二次查询。2923 次 = 一小时额度的 58%，8 并发 2 分钟跑完。

为什么按「活跃度」分档（本文件的核心）：
    有的应用一周三版（LocalSend、Jellyfin），有的一年不动（2019 年的老工具）。
    统一每天查一遍等于给死人做体检 —— 2923 次请求里 90% 是白跑的。
    每档一个间隔，脚本自己学：
        首次按 catalog 的 date 估（近 30 天 → 1 天，一年以上 → 7 天）
        之后自适应：查到新版 → 立刻回到每天；连续没变 → 间隔翻倍，封顶 7 天
    这样「高频的勤查、低频的懒查」，最坏的发现延迟也只有 7 天，且不需要人工维护任何分类表。

产出：
    data/updates/updates.json(.gz)   客户端拉的索引，只含「比内置 catalog 新」的条目
    data/updates-state.json          调度状态（每档的下次检查时间），需提交回仓库

用法：
    python tools/fetch_updates.py --dry-run              # 只统计，不写文件
    python tools/fetch_updates.py --tier due             # 每天：只查「到期」的
    python tools/fetch_updates.py --tier hot             # 每 6 小时：只查刚发过版的
    python tools/fetch_updates.py --tier all             # 全量（首次部署 / 修数据用）
    python tools/fetch_updates.py --rest --sample 20 --dry-run --anon
                                                        # 无 token 时抽样自检
"""
import argparse
import gzip
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "app", "src", "main", "assets", "catalog.json")
DEFAULT_OUT = os.path.join(ROOT, "data", "updates")
STATE = os.path.join(ROOT, "data", "updates-state.json")

GRAPHQL = "https://api.github.com/graphql"
REST = "https://api.github.com/repos/%s/releases/latest"
UA = "TVHub-Index/1.1 (+https://github.com)"

# 电视盒子的优先级：先 arm64，再 32 位 arm，最后才是 x86 / 通用包
ABI_ORDER = ["arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "any"]

# 间隔阶梯（天）。查到新版回落到 1，没变化逐级翻倍，封顶 MAX_IV。
# 上限 7 天是刻意选的：再长就会出现「应用发版一周后用户才知道」的投诉。
MAX_IV = 7
HOT_HOURS = 6          # hot 轮间隔：刚发过版的那几个，6 小时再看一眼
HOT_INITIAL_DAYS = 3   # 首次部署时，最近 3 天发过版的直接进热层
HOT_LIMIT = 400        # 单次 hot 轮上限。首次部署时 catalog 落后很多，
                       # 会有一大批同时判为「发现新版」而挤进热层，这里兜一下


def abi_of(name):
    n = name.lower()
    if "arm64" in n or "aarch64" in n or "v8a" in n:
        return "arm64-v8a"
    if "armeabi-v7a" in n or "armv7" in n or "-arm" in n or "arm32" in n:
        return "armeabi-v7a"
    if "x86_64" in n or "x64" in n:
        return "x86_64"
    if "x86" in n:
        return "x86"
    if "universal" in n or "all" in n:
        return "any"
    return "any"


def ver_key(tag):
    """把 tag 拆成数字元组：v1.18.2 / 1.18.2-beta / release-1.18 → (1,18,2)。
    `+build` 这类构建元数据先丢掉，否则 v1.2.3+build5 会被判成比 v1.2.3 新。
    没有数字（nightly、latest）时返回空元组。"""
    if not tag:
        return ()
    s = str(tag).split("+")[0]
    nums = re.findall(r"\d+", s)
    if not nums:
        return ()
    return tuple(int(x) for x in nums[:4])


def compare(remote, local):
    """返回 1 / 0 / -1（remote 比 local 新 / 一样 / 更旧），无法判断返回 None。

    只信数字：一边没有数字（nightly、latest、日期区间）时宁可不报，
    也不要用字符串比较硬猜——那会把「nightly」当成比 v1.2.1 新，天天提示更新。"""
    rk, lk = ver_key(remote), ver_key(local)
    if not rk or not lk:
        return None
    if rk > lk:
        return 1
    if rk < lk:
        return -1
    return 0


def pick_apks(assets):
    """只留 .apk，按 ABI 优先级排序，最多 3 个（一个应用给电视装的包不会更多）"""
    apks = []
    for a in assets or []:
        name = a.get("name") or ""
        if not name.lower().endswith(".apk"):
            continue
        apks.append(
            {
                "name": name,
                "abi": abi_of(name),
                "url": a.get("downloadUrl") or a.get("browser_download_url"),
                "size": a.get("size") or 0,
            }
        )
    apks.sort(key=lambda x: ABI_ORDER.index(x["abi"]) if x["abi"] in ABI_ORDER else 99)
    return apks[:3]


def parse_release(rel):
    if not rel:
        return None
    tag = rel.get("tagName") or rel.get("tag_name")
    if not tag:
        return None
    assets = rel.get("releaseAssets")
    if assets is None:
        assets = rel.get("assets")
    if isinstance(assets, dict):  # GraphQL 的连接对象
        assets = assets.get("nodes") or []
    return {
        "tag": tag,
        "date": (rel.get("publishedAt") or rel.get("published_at") or "")[:10],
        "pre": bool(rel.get("isPrerelease") or rel.get("prerelease")),
        "apks": pick_apks(assets),
    }


# ---------------------------------------------------------------- 抓取


def rest_one(repo, token):
    """单个仓库的最新正式版。404 = 这个仓库没有发过 release（不是错误）。"""
    headers = {"User-Agent": UA, "Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = "bearer " + token
    req = urllib.request.Request(REST % repo, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return parse_release(json.loads(r.read().decode()))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def fetch_rest(apps, token, workers):
    """并发抓。返回 {id: release|None}；异常单独记，不污染判断。"""
    out, errors = {}, []
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(rest_one, a["repo"], token): a for a in apps}
        for i, f in enumerate(as_completed(futs), 1):
            a = futs[f]
            try:
                out[a["id"]] = f.result()
            except Exception as e:  # 网络/限流：跳过，下次再说
                errors.append("%s: %s" % (a["repo"], e))
            if i % 500 == 0:
                print("  ... %d/%d" % (i, len(apps)))
    if errors:
        print("  ! %d 个仓库抓取失败，样例：%s" % (len(errors), errors[:3]), file=sys.stderr)
    return out


def graphql_batch(batch, token):
    """GraphQL 备选路径。注意 first 必须小：每个仓库的成本 = 1 + 1 + first，
    所以 first: 6 时 50 个仓库一批约 400 点，5000 点/小时能跑 12 批（600 个仓库）。
    这就是为什么全量必须走 REST。"""
    parts = []
    for i, app in enumerate(batch):
        owner, _, name = app["repo"].partition("/")
        parts.append(
            'r%d: repository(owner: "%s", name: "%s") { '
            "latestRelease { tagName publishedAt isPrerelease "
            "releaseAssets(first: 6) { nodes { name size downloadUrl } } } }" % (i, owner, name)
        )
    body = json.dumps({"query": "query {\n%s\n}" % "\n".join(parts)}).encode()
    req = urllib.request.Request(
        GRAPHQL,
        data=body,
        headers={"Authorization": "bearer " + token, "User-Agent": UA, "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.loads(r.read().decode())
    if "errors" in data:
        msgs = "; ".join(e.get("message", "?") for e in data["errors"][:3])
        print("  ! graphql: %s" % msgs, file=sys.stderr)
    return data.get("data") or {}


# ---------------------------------------------------------------- 调度


def parse_day(s):
    try:
        return datetime.strptime(str(s)[:10], "%Y-%m-%d").date()
    except (ValueError, TypeError):
        return None


def parse_ts(s):
    if not s:
        return None
    for fmt in ("%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%dT%H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(s, fmt).replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    return None


def now_ts():
    return datetime.now(timezone.utc).replace(microsecond=0)


def cap_for(last_change, today):
    """由「最近一次发现新版」的日期推导检查间隔上限（天）。

    为什么用滚动日期而不是 catalog 里的 date：catalog 是打包时的快照，一年不动；
    而 `s` 每次发现新版就会前移，所以一个沉寂两年的项目突然发版，
    下一轮它的上限就从 7 天变成 1 天 —— 自动跟上真实节奏，不用人工改任何表。

    为什么上限而不是间隔：自适应会翻倍退避，如果统一封顶 7 天，
    高频应用发版后三轮没动静也会掉到 7 天档，等于「所有应用一样慢」。
    """
    if not last_change:
        return MAX_IV
    age = (today - last_change).days
    if age <= 30:
        return 1    # 最近还在发版 → 每天
    if age <= 90:
        return 2
    if age <= 365:
        return 4
    return MAX_IV   # 一年以上没动 → 每周


def tag_hash(tag):
    """state 里只存 tag 的短哈希：判断「变没变」够用，2923 条能省 100KB。"""
    return hashlib.md5(str(tag).encode("utf-8")).hexdigest()[:6] if tag else ""


def load_state():
    if not os.path.exists(STATE):
        return {}
    try:
        with open(STATE, encoding="utf-8") as f:
            return (json.load(f) or {}).get("apps") or {}
    except Exception:
        return {}


def save_state(apps_state):
    doc = {"schema": 1, "updated": now_ts().strftime("%Y-%m-%dT%H:%M:%SZ"), "apps": apps_state}
    with open(STATE + ".tmp", "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    os.replace(STATE + ".tmp", STATE)


def is_due(st, iv, now, tier):
    if tier == "all":
        return True
    last = parse_ts(st.get("l"))
    if tier == "hot":
        # hot 轮只碰「刚发过版、进了热层」的那一小撮。用独立标记而不用 iv==1，
        # 是因为高频档（cap=1）有一千多个，6 小时一轮全查等于把全库查两遍。
        if not st.get("h"):
            return False
        if last is None:
            return True
        return (now - last).total_seconds() >= HOT_HOURS * 3600
    if last is None:
        return True
    return (now - last).total_seconds() >= iv * 86400


def next_iv(iv, changed, cap):
    """有新版 → 立刻回到每天；没变 → 翻倍，但不超过这个应用自己的上限 cap。

    cap=1 的应用（近 30 天还在发版）永远是 1，不会被退避拖慢；
    cap=7 的应用（一年没动）最多一周一次。
    """
    if changed:
        return 1
    return max(1, min(iv * 2, cap))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--tier", choices=["due", "hot", "all"], default="due",
                    help="due=每日轮（所有到期的） hot=每 6 小时轮（刚发过版的） all=全量")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--graphql", action="store_true", help="改用 GraphQL（默认 REST）")
    ap.add_argument("--rest", action="store_true", help="兼容旧参数：默认就是 REST")
    ap.add_argument("--sample", type=int, default=0, help="只取均匀抽样的 N 个仓库")
    ap.add_argument("--anon", action="store_true", help="匿名调用（仅抽样自检用，60 次/小时）")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--limit", type=int, default=0, help="索引最多保留多少条（保护性上限）")
    ap.add_argument("--token", default=os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or "")
    args = ap.parse_args()

    token = "" if args.anon else args.token
    if args.graphql and not token:
        sys.exit("GraphQL 必须认证；无 token 请用 --rest --sample 20 --dry-run --anon")

    with open(CATALOG, encoding="utf-8") as f:
        catalog = json.load(f)
    all_apps = catalog["apps"]
    print("catalog: %s, %d 个应用" % (catalog.get("generated", "?"), len(all_apps)))

    apps = all_apps
    if args.sample:
        step = max(1, len(apps) // args.sample)
        apps = apps[::step][: args.sample]
        print("抽样: %d 个（每 %d 个取 1 个）" % (len(apps), step))

    # 抽样自检不读状态：避免把「抽了 20 个」的结论写进去污染真实间隔
    state = {} if args.sample else load_state()
    today = now_ts().date()
    now = now_ts()

    # 1. 算出每个应用的「间隔上限 cap」和「当前间隔 iv」
    iv_of, cap_of, s_of, picked = {}, {}, {}, []
    hot_n = 0
    for a in apps:
        st = dict(state.get(a["id"]) or {})
        # s = 最近一次「发现新版」的日期；没有记录就退回 catalog 的打包日期
        s = parse_day(st.get("s")) or parse_day(a.get("date"))
        cap = cap_for(s, today)
        iv = int(st.get("iv") or 0) or cap
        iv = max(1, min(iv, cap))
        if "h" not in st:  # 首次：最近发过版的先进热层
            st["h"] = bool(s and (today - s).days <= HOT_INITIAL_DAYS)
        if st["h"]:
            hot_n += 1
        iv_of[a["id"]] = iv
        cap_of[a["id"]] = cap
        s_of[a["id"]] = s
        if is_due(st, iv, now, args.tier):
            picked.append(a)

    buckets = {}
    for a in apps:
        cap = cap_of[a["id"]]
        buckets[cap] = buckets.get(cap, 0) + 1
    print("间隔上限分布（天:数量）: %s；热层 %d 个" % (
        "  ".join("%d:%d" % (k, buckets[k]) for k in sorted(buckets)), hot_n))
    print("本轮 tier=%s 需检查 %d 个（其余未到期，跳过）" % (args.tier, len(picked)))

    if args.tier == "hot" and len(picked) > HOT_LIMIT:
        # 首次部署或 catalog 落后太多时，会有一大批同时进热层，按「最近发版的」优先
        picked.sort(key=lambda a: s_of[a["id"]] or today, reverse=True)
        print("  热层 %d 个超过上限，本轮只查最近的 %d 个" % (len(picked), HOT_LIMIT))
        picked = picked[:HOT_LIMIT]

    if not picked:
        print("无需检查，退出")
        return

    # 2. 抓
    stats = dict(total=len(picked), no_release=0, newer=0, same=0, older=0, no_apk=0, pre=0, odd=0, failed=0)
    entries = {}
    checked_ts = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    new_state = dict(state)

    if args.graphql:
        batches = [picked[i : i + 50] for i in range(0, len(picked), 50)]
        results = {}
        for bi, batch in enumerate(batches, 1):
            data = graphql_batch(batch, token)
            for i, a in enumerate(batch):
                results[a["id"]] = parse_release((data.get("r%d" % i) or {}).get("latestRelease"))
            print("  批次 %d/%d" % (bi, len(batches)))
            time.sleep(0.2)
    else:
        results = fetch_rest(picked, token, args.workers)

    # 3. 判定 + 更新间隔
    for a in picked:
        if a["id"] not in results:      # 抓取失败：不动间隔，下轮重试
            stats["failed"] += 1
            continue
        rel = results[a["id"]]
        st = dict(state.get(a["id"]) or {})
        prev_seen = st.get("t") or tag_hash(a.get("version"))
        if rel is None:
            stats["no_release"] += 1
            changed = False
        else:
            if rel["pre"]:
                stats["pre"] += 1
            push(a, rel, entries, stats)
            changed = tag_hash(rel["tag"]) != prev_seen
        iv = iv_of[a["id"]]
        cap = cap_of[a["id"]]
        s = s_of[a["id"]]
        if changed:
            iv, hot = 1, True       # 刚发现新版 → 进热层，6 小时后再看一眼；活跃度同时前移
            s = today
        else:
            iv = next_iv(iv, False, cap)
            # hot 轮里查完没变化就退出热层：发版后 6 小时内没再发，说明这次是普通节奏
            hot = False if args.tier == "hot" else bool(st.get("h"))
        new_state[a["id"]] = {
            "iv": iv, "l": checked_ts, "h": hot,
            "t": tag_hash(rel["tag"]) if rel is not None else st.get("t", ""),
            "s": s.isoformat() if s else "",
        }

    if args.limit and len(entries) > args.limit:
        keep = sorted(entries.items(), key=lambda kv: kv[1]["date"], reverse=True)[: args.limit]
        entries = dict(keep)

    print("\n统计：")
    for k in ("total", "newer", "same", "older", "no_release", "no_apk", "pre", "odd", "failed"):
        print("  %-11s %d" % (k, stats[k]))
    print("  索引条数    %d" % len(entries))
    hot_next = sum(1 for a in picked if (new_state.get(a["id"]) or {}).get("h"))
    print("  本轮后热层  %d 个（下次 hot 轮会查）" % hot_next)

    if args.dry_run:
        print("\n--dry-run：不写文件")
        for k, v in list(entries.items())[:5]:
            print("  样例 %-42s %s -> %s" % (k, v.get("from", ""), v["tag"]))
        return

    os.makedirs(args.out, exist_ok=True)
    doc = {
        "schema": 1,
        "generated": checked_ts,
        "catalog": catalog.get("generated", ""),
        "stats": stats,
        "apps": entries,
    }
    raw = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode()
    plain = os.path.join(args.out, "updates.json")
    gz = plain + ".gz"
    with open(plain, "wb") as f:
        f.write(raw)
    with open(gz, "wb") as f:  # mtime=0：内容不变时 gz 字节也稳定，diff 才有意义
        with gzip.GzipFile(fileobj=f, mode="wb", mtime=0) as g:
            g.write(raw)
    if not args.sample:
        save_state(new_state)
    print("\n写出 %s (%.1f KB) 与 .gz (%.1f KB)" % (plain, len(raw) / 1024, os.path.getsize(gz) / 1024))


def push(app, rel, entries, stats):
    if not rel["apks"]:
        stats["no_apk"] += 1
        return
    cmp = compare(rel["tag"], app.get("version"))
    if cmp is None:
        stats["odd"] += 1
        return
    if cmp <= 0:
        stats["same" if cmp == 0 else "older"] += 1
        return
    stats["newer"] += 1
    entries[app["id"]] = {
        "tag": rel["tag"],
        "date": rel["date"],
        "from": app.get("version") or "",
        "apks": rel["apks"],
    }


if __name__ == "__main__":
    main()
