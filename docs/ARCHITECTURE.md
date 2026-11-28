# 架构说明

> 6 个文件、约 3,100 行 Kotlin，没有分层框架。这份文档解释「东西怎么流起来」。

---

## 1. 启动链路

```
MainActivity.onCreate
├── Prefs.init(this)        // 读 SharedPreferences("prefs")
├── L.init(this)            // 用 Prefs.lang 建 Resources（顺序不能反）
├── Images.init(this)       // 内存 LRU + 磁盘缓存目录
└── setContent { TvHub() }
        └── LaunchedEffect { Catalog.load(ctx) }   // IO 线程解析 assets/catalog.json
                ├── Catalog.apps 空 → DesignCanvas { Splash() }
                └── 有数据 → DesignCanvas { Wall + TopBar + 当前视图 }
```

`TvHub()` 就是全部路由。没有 Navigation 库，靠几个状态量决定盖哪一层：

| 状态 | 为 null 时 | 不为 null 时 |
|---|---|---|
| `detail: String?` | 显示当前 tab | 全屏覆盖 `DetailScreen`（最高优先级） |
| `room: String?` | 分类页显示磁贴网格 | 在分类之上盖 `RoomScreen` |
| `about: Boolean` | 正常显示设置页 | 在设置之上盖 `AboutScreen`（`tab` 仍是 Settings） |
| `tab: Tab` | — | 决定 `HomeScreen` / `CatsScreen` / `MineScreen` / `SearchScreen` / `SettingsScreen` |

`BackHandler` 的回退顺序：`about` → `detail` → `room` → 搜索词清空 → 回 `Tab.Discover` → 交给系统。

---

## 2. 数据层

### 2.1 catalog.json

`app/src/main/assets/catalog.json`，3.3 MB，**静态快照**（当前 `generated` 为 2026-11-11），
2,923 款应用 / 18 个分类。结构：

```json
{
  "generated": "2026-11-11",
  "apps": [{
    "id": "teamnewpipe-newpipe",
    "repo": "TeamNewPipe/NewPipe",
    "name": "NewPipe",
    "package": "org.schabi.newpipe",
    "versionCode": 1010, "versionName": "0.27.0", "minSdk": 21,
    "room": "Streaming",
    "note": "…", "desc": "…", "maker": "Team NewPipe",
    "stars": 38000, "licence": "GPL-3.0", "archived": false,
    "version": "v0.27.0", "date": "2026-08-21",
    "certSha256": "AB:CD:…",
    "art": { "banner": "https://…", "feature": "https://…", "icon": "https://…", "shots": ["…"] },
    "apks": [{ "name": "NewPipe_v0.27.0.apk", "abi": "universal", "url": "https://github.com/…", "size": 8123456 }],
    "releases": [{ "tag": "v0.27.0", "date": "2026-08-21" }],
    "forkOf": null, "parentStars": 0
  }]
}
```

**不要改这个文件的数据内容**（尤其不要塞中文翻译进去）——它是上游生成管线的产物，
生成脚本没有开源，改了就无法再同步。中文描述层应该做成独立的覆盖表。

### 2.2 派生字段

- `App.streams`：在 `name + note + desc + repo + package + topics` 里正则匹配 IPTV / m3u8 / Stremio / 直播源 等词，
  用于「隐藏直播类应用」开关（`Prefs.hideStreams`，默认 **true**）。
- `Catalog.visible`：`stars >= 10 || parentStars >= 10` 且未被隐藏。**页面一律用 `visible`，不要用 `apps`**（除了算总数）。
- `App.runsHere`：`minSdk <= Build.VERSION.SDK_INT`，用来决定详情页能否装。
- `pickApk(app)`：按 `Build.SUPPORTED_ABIS` 顺序找匹配 ABI，找不到退 `universal` → `any` → 第一个。
  实测小米电视 armeabi-v7a 会选 39.3MB 的包，模拟器 x86_64 选 46.6MB 的包。

### 2.3 本地状态

- `Device`：用 `PackageManager` 拿已安装应用的 `versionCode` / `versionName` / 签名集合，
  以及 `StatFs` 的存储占用。没有持久化，每次组合时查。
- `Prefs`：`SharedPreferences("prefs")`，键 —— `accent`、`theme`、`lang`、`hideStreams`、`source`、
  `sysLang`、`heroCursor` / `heroDay`。全部用 `mutableStateOf` 暴露，改值即重绘。
  旧版的 `mirrorAuto`（布尔）在 `init` 里一次性迁到 `source` 三档，之后不再读写。

### 2.4 下载源（自动 / 直连 / 国内镜像）

`Prefs.source` 三档，`Mirror.usingMirror()` 是唯一判据：

| `source` | 行为 |
|---|---|
| 0 自动（默认） | 系统语言是简体中文 → 镜像；其它语言 → 直连 GitHub |
| 1 直连 | 永远只给原链 |
| 2 国内镜像 | 永远先镜像，失败回退原链 |

「系统语言」取 `Resources.getSystem().configuration.locales[0]`，**不是** `Locale.getDefault()` ——
后者被 `L.apply()` 改成了 App 内语言，而且语言变化时 Activity 重建、进程通常还活着，读到的会是旧值。

系统语言一改，Activity 必然重建（manifest 的 `configChanges` 不含 `locale`），所以
`Prefs.noteSystemLanguage()` 只要在 `onCreate` 里比一次「上次见到的语言」：从非简中变成简中、
且当前没走镜像时，置起 `Prefs.askMirror`，由 `MirrorAskDialog` 问一次。首次安装不弹（没有上一条记录）。


---

## 3. 图片链路

```
URL
 ├── Images.cached(url)     // LruCache<String, ImageBitmap>，命中直接返回（首帧不闪）
 └── 未命中 → withContext(IO) {
        for (c in Mirror.candidates(url))          // 镜像优先
            HttpURLConnection → BitmapFactory.decodeStream
            // 解码失败也视为失败，继续下一个候选（拿到 HTML 错误页时不会崩溃）
        → 写磁盘缓存 → 塞 LRU
     }
```

关键尺寸约定：

| 用途 | 取图宽度 | 渲染 |
|---|---|---|
| 首页大卡 / 详情横幅 | 原图 | `ContentScale.Fit` + `MaskedArt` 左右羽化 + 边缘色渐变补底 |
| 首页小卡 / 横排卡 | `art(url, 640)` | `Crop` 填满 + 底部压暗 |
| 分类磁贴背景 | `art(url, 32)` | `Crop` 放大 → 天然模糊（原型是 `blur(7px)`） |
| 应用图标 | `art(url, 128)` | `Fit` + 圆角容器 |

**为什么不用 Coil**：上游刻意零图片库（APK 26MB），且需要「候选 URL 逐个尝试 + 解码失败即换源」的自定义逻辑，
`Images` 只有约 70 行就能覆盖。新增图片需求先看这里能否复用。

---

## 4. 下载与安装

### 4.1 状态机

`Installer.jobs: MutableStateMap<String, Job>`，key 是 `app.id`：

```
（无）──start()──> Downloading(done,total) ──> Checking ──> Installing ──> （无，安装广播收尾）
                        │                       │              │
                        └──> Failed(msg)        └──> Held(msg) │
                                                 （签名不匹配 / 同包不同签名）
```

`Job.Held` 与 `Job.Failed` 的区别：`Held` 是「能装但不能装得干净」（已有同包名的其他构建），
UI 上给的是解释 + 手动卸载引导，不是错误红字。

### 4.2 下载

流程在 `Installer.download()`：

1. 目标文件 `Device.cacheDir / "<id>-<version>-<abi>.apk"`。**已存在且长度等于 `apk.size` 直接跳过**（支持断点续装的假象，实际是全文件校验）。
2. 候选列表来自 `Mirror.candidates(url)`：`gh-proxy.com` → `ghproxy.net` → 原链。
   自动档在非中文系统下、或手动选了「直连」，就只剩原链（见 2.4）。
3. 每个候选写 `.part`，**失败必删 `.part`**（半截文件绝不能交给下一个候选或 `PackageInstaller`）。
4. 成功后 `renameTo` 正式文件名。

超时：connect 15s / read 30s。`User-Agent: TVHub/<versionName> (Android TV)`，版本号取 `BuildConfig.VERSION_NAME`。

### 4.3 校验

`checkSignature()` 在装之前比对两件事：
- APK 实际签名（`certSha256`）与 catalog 里声明的是否一致；
- 设备上是否已存在**同包名但不同签名**的版本（`Catalog.sibling()` 用已装签名集合反查）。

不一致就返回 `Job.Held` 并附带原因文案，不继续安装。**这段逻辑是安全边界，不要为了「能装上」放宽。**

### 4.4 提交

`commit()` 走 `PackageInstaller` 会话：
`createSession` → `openSession().openWrite()` 拷字节 → `commit(PendingIntent)` →
`InstallResultReceiver` 收 `ACTION_INSTALL_COMMIT` 回写 `jobs`。

`handOff()` 是兜底：某些 ROM（小米电视实测）对 `PackageInstaller` 会话行为不一致，
失败时退回「用 `FileProvider` 交给系统安装器」的老路。

### 4.5 权限

`canRequestPackageInstalls()` 为 false 时，`Job.Failed(err_allow_install)` 并直接跳
`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`（失败再退 `ACTION_SECURITY_SETTINGS`）。

### 4.6 安装期间保持亮屏

电视的「自动锁屏」是两条独立计时：**屏幕超时休眠**（`screen_off_timeout`）和**屏保 Daydream**。
两者都会让面板黑掉，下载与 `PackageInstaller` 会话随之被系统掐断——用户看到的就是「装到一半电视自己关了」。

做法不改系统设置、不用额外权限：`Installer` 维护一个 `active: Boolean`（`mutableStateOf`），
每一次任务状态变更都经 `put()` 写入 `jobs` 并同步刷新它；`MainActivity.KeepScreenOnWhileInstalling()`
按 `active` 给窗口加 `FLAG_KEEP_SCREEN_ON`，任务全部结束再撤掉。

```kotlin
// Installer.kt
var active by mutableStateOf(false)   // private set
private fun put(id: String, job: Job?) {
    if (job == null) jobs.remove(id) else jobs[id] = job
    active = jobs.values.any { it is Job.Downloading || it == Job.Checking || it == Job.Installing }
}

// MainActivity.kt
val active = Installer.active
DisposableEffect(active) {
    if (active) window.addFlags(FLAG_KEEP_SCREEN_ON) else window.clearFlags(FLAG_KEEP_SCREEN_ON)
    onDispose { }
}
```

**为什么不用 `jobs.values.any { … }` 现算**：那样要靠 `SnapshotStateMap` 的集合读取被 Compose 追踪，
实测出现过「明明已经在下载（详情页百分比在走）、窗口却始终没拿到 KEEP_SCREEN_ON」的漏触发。
改成每次写入主动刷新的独立 `mutableStateOf` 后，读取方拿到的就是普通 `State<Boolean>`，不再依赖集合读取的传播。
**新增 `jobs` 写入点时务必走 `put()`**，直接 `jobs[...] = …` 会漏掉 `active` 同步。

`FLAG_KEEP_SCREEN_ON` 的生效方式值得记一笔：它不靠 Activity 自己持有唤醒锁，而是由
`WindowManagerService` 在窗口可见期间代持一把 `SCREEN_BRIGHT_WAKE_LOCK`（`dumpsys power` 里能看到
`'WindowManager/displayId:0'`、`ws=WorkSource{… app.tvhub}`），同时 `mHoldScreenWindow` 指向本窗口。
因为锁在窗口层，所以即使安装确认对话框盖在上面（Activity 已 pause、但窗口仍可见），亮屏依然成立。

验证方法（装到一半执行）：

```bash
adb shell dumpsys window | grep mHoldScreenWindow     # → 本应用的窗口
adb shell dumpsys power  | grep -A2 "Wake Locks"      # → SCREEN_BRIGHT_WAKE_LOCK 'WindowManager/displayId:0'
```

任务结束（成功 / 失败 / 取消）后两处都应回到 `null` / `size=0`。
不要把它做成「全程亮屏」：用户离开本应用之后，电视该睡就睡。

---

### 4.7 更新索引（不发版也知道有新版本）

`catalog.json` 是打包进 APK 的快照，装完就冻结，所以「有没有新版」不能只靠它。
链路是：**GitHub Actions 每天生成一张小表 → 客户端开机拉一次 → 与 catalog 的版本比对**。

| 环节 | 落点 |
|---|---|
| 采集 | `tools/fetch_updates.py`：REST `releases/latest` 并发抓（8 线程），**0 次 APK 下载** |
| 调度 | `.github/workflows/updates.yml`：每天一轮 + 每 6 小时一轮，按应用活跃度决定查谁（见下） |
| 分发 | `updates.json.gz`（几十 KB）；地址写在 `gradle.properties` 的 `tvhub.updateIndexUrl`，构建期注入 `BuildConfig.UPDATE_INDEX_URL`，**留空 = 功能关闭、不发任何请求** |
| 客户端 | `UpdateIndex`：先读 `filesDir/updates/updates.json` 缓存（开机即可显示），首帧后 2.5s 再联网，带 `If-None-Match`，304 直接结束 |

当前实例的索引地址（`releases/download/updates/` 是固定 tag，客户端只认这一个常量）：

```
https://github.com/harrisonxhere/openapk-tv/releases/download/updates/updates.json.gz
```

**为什么用 REST 而不是 GraphQL**：GraphQL 按「节点数」计费，`releaseAssets(first: 40)`
一个仓库就要 40 点，50 个一包 = 2100 点，5000 点/小时只够查 119 个仓库 —— 3582 个要跑 30 小时。
REST 的 `releases/latest` 按请求数计费（5000/小时），一个仓库 1 次且一次带回全部 assets，
3582 次占一小时额度的 72%，8 并发两分钟跑完。

**按活跃度分层（`--tier`）**：不追求「全库每天一遍」，而是给每个应用定一个间隔上限 `cap`：

| 最近一次发现新版距今 | 上限 | 含义 |
|---|---|---|
| ≤ 30 天 | 1 天 | 每天都在发版，天天看 |
| ≤ 90 天 | 2 天 | |
| ≤ 365 天 | 4 天 | |
| 更早 / 无记录 | 7 天 | 一年没动，一周一次足够 |

- `cap` 由 state 里的 `s`（上次发现新版的日期）实时算，**不落盘**。所以一个沉寂两年的项目
  突然发版，下一轮它的上限自动从 7 天变成 1 天，不需要人工改任何分类表。
- 实际间隔 `iv` 自适应：发现新版 → 立刻回 1；连续没变化 → 翻倍，但不超过 `cap`。
  关键是「封的是**上限**不是**间隔**」——若统一封顶 7 天，高频应用发版后三轮没动静也会掉到 7 天档，
  就变成「所有应用一样慢」了。
- 两轮分工：`due` 轮（每天）查所有到期的；`hot` 轮（每 6 小时）只查「刚发过版」的热层成员
  （`h=true`，查完没变化即退层，单轮上限 400 个）。热层用独立标记而不是 `iv==1`，
  否则 cap=1 的那 1365 个会被 6 小时轮全查一遍。

稳态约 **1900 次请求/天**（全量 3582，省约 47%），发现延迟：高频 ≤24 小时、低频 ≤7 天。

`data/updates-state.json` 记录每个应用的 `{iv, l, t, s, h}`（间隔 / 上次检查 / 上次看到的 tag 哈希 /
活跃度 / 热层），约 280KB，由 workflow 提交回仓库。**它必须入库**——Actions 每次是干净 checkout，
丢了状态就会退化成每天全量。tag 只存 6 位哈希，够判断「变没变」。

**索引里没有 `versionCode`**：它必须下载 APK 才能拿到，而判断「有没有新版」只要 tag。
`UpdateIndex.newer()` 只比 tag 的数字段（`v1.18.2` / `1.18.2` / `1.18.2-beta` 视为同一版，
`+build` 元数据丢弃），任一边没有数字（`nightly`、`latest`）时**不报更新**——宁可漏报，不要天天误报。

合并走 `UpdateIndex.effective(app)`：命中更新时替换 `version / versionName / date / apks`，
并把最新一条塞进 `releases`（详情页的版本脉络才不会停在旧版本）。
**`certSha256` 保持 catalog 里的值不变**——远端只能改版本号和下载地址，改不动信任锚，
换签名的包依旧会被 `checkSignature()` 拦成 `Job.Held`。

`Installer.start()` 内部统一做一次 `effective()`，调用方（详情页、我的应用、全部更新）不用各自处理。
UI 侧的「可更新」判定是 `i.versionCode < a.versionCode || UpdateIndex.isNewer(a)`：
前者管「装机版本旧」，后者管「索引里有更新的 Release」。

### 4.8 自更新（OpenAPK TV 更新自己）

目录应用靠 `apps` 段，OpenAPK TV 自己靠**同一份文件里的 `self` 段**——不为它单开一个请求：

```json
"self": {
  "repo": "harrisonxhere/openapk-tv",
  "tag": "v1.0.0",
  "date": "2026-11-21",
  "apk": { "name": "tv-hub-1.0.0.apk", "url": "...", "size": 3289061 }
}
```

| 环节 | 做法 |
|---|---|
| 生成 | `fetch_updates.py --self <owner/repo>`（默认取 Actions 的 `GITHUB_REPOSITORY`），一次 REST 读自己的 `/releases/latest`，取体积最大的 APK |
| 调度 | **不跟随应用调度**：`if not picked` 时走 `refresh_self()` 单独刷这一段，否则发版要等到某个应用到期才被客户端看见 |
| 判定 | `UpdateIndex.selfNewer()` 把 `self.tag` 与 `BuildConfig.VERSION_NAME` 比数字段 |
| 安装 | `Installer.startSelfUpdate()` 复用 `download()` + `commit()`，但**跳过** `certSha256` 比对 |
| 展示 | 设置页「检查更新」行就地显示 `Downloading 64%` / `Installing…` / 失败原因 |

**发版必须同时改 `versionName`**：只 bump `versionCode` 的话，`selfNewer()` 看不出来（索引里没有 versionCode）。
`versionCode` 只负责「能不能覆盖安装」，`versionName` 才负责「该不该提示」。

自更新不做签名比对是刻意的：Android 自身会拒绝签名不一致的覆盖安装，这里再拦一次只会让
「将来换成正式 keystore」时把用户卡在装不上的状态。安装确认框由系统弹出——非系统应用拿不到静默安装权限。

> 为什么不让设备直接打 GitHub API：匿名 60 次/小时，一个用户翻两屏就把额度烧光；
> 而且 `api.github.com` 在国内不稳。索引文件一次拉全库，代价小得多。

---

## 5. 本地化机制

```kotlin
object L {
    lateinit var res: Resources
    var rev by mutableIntStateOf(0)          // 语言版本号，兼作重绘信号
    fun applyLang() { /* 重建 Resources + Locale.setDefault + rev++ */ }
}
fun str(id: Int, vararg args: Any): String { L.rev; return L.res.getString(id, *args) }
```

三个要点：

1. **不重建 Activity**。用 `createConfigurationContext(cfg).resources` 换掉 `L.res`，
   同时 `Locale.setDefault(loc)` 让 `Fmt` 里的日期/数字格式跟着变。
2. **`str()` 里读 `rev` 是刻意的订阅**：组合期间读到它，`rev++` 时所有调用方自动失效重绘。
   用静态分析工具时别把它当成「无副作用的无用表达式」删掉。
3. **`Prefs.init` 必须早于 `L.init`**，否则首次启动不知道用哪种语言。

`strArray()`、`plural()` 同理。所有 `Installer` 里的报错文案也走 `str()`，
否则会出现「界面中文、安装报错英文」。

---

## 6. 焦点模型

TV 上没有鼠标，焦点就是光标。三条规则：

1. **`Focusable { content(focused) }`** 是所有可点元素的唯一入口：
   内部 `onFocusChanged` 维护 `focused`，`clickable(indication = null)` 去掉水波纹。
2. **`Modifier.ring(focused, radius, scale)`** 画焦点：贴边 2dp 实心环 + 外侧 7dp 半透明光晕，
   卡片缩放 1.015、胶囊 1.06，180ms 缓动。
3. **跨视图交接用 `FocusHandoff`**：

```kotlin
class FocusHandoff { val target = FocusRequester(); var pending by mutableStateOf(true) }

LaunchedEffect(handoff.pending, tab, room, detail) {
    if (!handoff.pending) return@LaunchedEffect
    repeat(10) {                                   // 新视图的控件还没组合好
        delay(60)
        if (runCatching { handoff.target.requestFocus() }.isSuccess) { handoff.pending = false; return@LaunchedEffect }
    }
    handoff.pending = false
}
```

**顶栏用独立的 `navReq: Map<Tab, FocusRequester>`**，不能和内容区共用 `handoff.target`——
共用的后果是进设置页后焦点停在顶栏、按 OK 没反应（已修过一次）。

---

## 7. 主题

`Prefs.theme`：0 跟随系统 / 1 浅色 / 2 深色。`C.dark` 由它和 `Prefs.systemDark` 共同决定。
所有颜色都从 `C` 取（`c(dark, light)` 双值），**视图里不写死 Color(0xFF…) 当语义色**——
例外只有确实两主题通用的存储条颜色（`MeterApp` / `MeterCache` / `MeterFree` / `SwitchOn`）。

`Wall` 背景用三个径向渐变模拟原型 `filter: blur(72px)` 的色雾，颜色来自首页前三张卡片的
`edgeColors()`（左右边缘平均色），经 `vivid()` 提纯。真实高斯模糊在 1080p 电视上代价太高，不用。

---

## 8. 依赖边界（为什么这么少）

| 没引入 | 原因 |
|---|---|
| Navigation Compose | 只有 8 个视图、3 层状态，`mutableStateOf` 足够；引入后焦点与返回栈更难控 |
| ViewModel | 全部状态是短生命周期的 UI 状态，进程不做后台保活 |
| Retrofit / OkHttp | 只需 GET 一个 JSON 和若干 APK，`HttpURLConnection` 够；减少 APK 体积与镜像依赖 |
| Coil / Glide | 见 §3，需要自定义候选回退与解码失败判定 |
| Room / DataStore | 偏好只有 8 个键，`SharedPreferences` 够 |
| Hilt / Koin | 6 个文件，全局 `object` 就是依赖图 |

**新增依赖前先想清楚**：这个项目的卖点是「装在 32 位 Android 9 电视盒上也能跑、国内网络能下载」，
每加一个库都要多考虑镜像可达性与启动耗时。



## 9. 目录加载与缓存优化

### 9.1 冷启动性能问题
原方案直接读取 3.3MB 的 catalog.json 为字符串，解析为 JSONObject 树，内存峰值高，首次冷启动需约 20 秒。

### 9.2 流式解析优化
```kotlin
suspend fun load(ctx: Context) {
    // 1. 先尝试二进制缓存
    val cached = BinCache.read(ctx, cacheKey(ctx))
    if (cached != null) { /* 直接使用 */ return }
    
    // 2. 流式解析 JSON
    val counting = CountingStream(ctx.assets.open("catalog.json"))
    JsonReader(InputStreamReader(counting)).use { r ->
        while (r.hasNext()) {
            out += readApp(r)  // 逐条解析
            if (out.size % 100 == 0) {
                progress = counting.count.toFloat() / total  // 更新进度条
            }
        }
    }
    // 3. 后台写入缓存
    launch(Dispatchers.IO) { BinCache.write(ctx, key, generated, parsed) }
}
```

### 9.3 二进制缓存格式
```
文件格式:
+----------+--------+-------------+--------+-----------------+
| 魔数TVHB | 缓存键 | generated   | 条数N  | App1 | App2 | ... |
+----------+--------+-------------+--------+-----------------+

缓存键 = "v${FORMAT}|${lastUpdateTime}|${versionCode}|${catalog.json大小}"
```

### 9.4 优化效果
1. **首次启动**（解析+写缓存）：仍需完整解析，但内存使用降低约 2/3
2. **二次启动**（读缓存）：秒开（约 100-300ms）
3. **字段兼容性**：修改 App/AppArt/Apk/Release 任一字段，必须将 `BinCache.FORMAT` +1

### 9.5 索引优化
- `idIndex` / `repoIndex` / `forkIndex`: O(1) 查找，重复键保留第一条
- `progress`: 按字节读取进度，Splash 显示 220×3dp 进度条