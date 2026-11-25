# 设计系统

> 视觉部分的唯一真源是 `app/src/main/java/app/tvhub/Ui.kt`。
> 这份文档解释设计坐标系与实现之间的换算规则。

---

## 1. 设计坐标系：1280×720 当 dp 用

```kotlin
@Composable
fun DesignCanvas(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF06080B)), contentAlignment = Alignment.Center) {
        val d = LocalDensity.current
        val s = min(maxWidth.value / 1280f, maxHeight.value / 720f)   // 0.75 on 1080p TV
        CompositionLocalProvider(LocalDensity provides Density(d.density * s, 1f)) {
            Box(Modifier.size(1280.dp, 720.dp), content = content)
        }
    }
}
```

**规则：设计稿里是 863 像素，代码里就写 `863.dp`。** 不要换算、不要用 `weight` 去"自适应"。

原因：原型是 1280×720 的固定舞台；电视实际是 960×540dp（1080p @320dpi）。
`Density` 乘 0.75 之后，140px 的字在电视上仍然是视觉上的 140/1280 屏宽，比例完全一致。
副作用是排版可以用绝对坐标，不会因为不同电视的宽高比跑版（多出的黑边由外层 `contentAlignment` 居中处理）。

### 定位助手

```kotlin
private fun Modifier.at(x: Int, y: Int): Modifier = offset(x.dp, y.dp)
private fun Modifier.at(x: Int, y: Dp): Modifier = offset(x.dp, y)   // 少数场景 y 是动态值
```

所有视图的第一行几乎都是 `Modifier.at(0, 72).width(1152.dp)` 这种形式。

### 三个基准数字

| 数字 | 含义 | 出处 |
|---|---|---|
| `1280 × 720` | 设计舞台 | `DesignCanvas` |
| `horizontal 64dp / vertical 30dp` | 内容与屏幕边缘的距离（用户要求「增加两边的距离」后的值） | `TvHub()` 根 `Box` 的 padding |
| `1152dp` | 内容区宽度 = 1280 − 64×2 | 几乎所有视图的 `width` |

改左右留白时：`1152 = 1280 - 64*2`，各视图内部的 `x` 坐标（883、728、748 等）都要跟着重算。

---

## 2. 颜色令牌

全部在 `Ui.kt` 的 `object C` 里，成对定义深/浅：

```kotlin
private fun c(d: Long, l: Long) = Color(if (dark) d else l)
```

| 令牌 | 深色 | 浅色 | 用途 |
|---|---|---|---|
| `Fg` | `#F4F7FB` | `#101620` | 主文字 |
| `Fg2` | `#F4F7FB @75%` | `#101620 @70%` | 次级文字（简介、副标题） |
| `Fg3` | `#F4F7FB @52%` | `#101620 @50%` | 三级文字（元信息、时间） |
| `Line` | `#FFFFFF @11%` | `#101620 @10%` | 1dp 分隔线 / 描边 |
| `Line2` | `#FFFFFF @18%` | `#101620 @18%` | 强调描边 |
| `RowBg` | `#FFFFFF @5%` | `#FFFFFF @72%` | 行 / 列表底色 |
| `HiBg` / `HiFg` | 白 / `#0C0F15` | `#101620` / 白 | 焦点反白（选中的胶囊、聚焦的按钮） |
| `Ring` | 白 | `#101620` | 焦点环颜色 |
| `Card` | `#0B0E14` | 白 | 卡片实底 |
| `BtnBg` / `BtnFg` | `#F7FAFF @95%` / `#0C0F15` | `#101620` / 白 | 主按钮 |
| `MeterApp` / `MeterCache` / `MeterFree` / `SwitchOn` | 蓝 / 紫 / 绿 / 绿 | 同 | 存储条与开关，**两主题通用，不参与切换** |

**取色规矩**：视图里不写 `Color(0xFFxxxxxx)` 当语义色。确需一次性颜色（如 banner 上的压暗遮罩）
用带 alpha 的字面量，并写注释说明为什么不能进令牌。

---

## 3. 字体

```kotlin
T(text, size: TextUnit, color = C.Fg, weight = FontWeight.Normal, maxLines = Int.MAX_VALUE, tight = false)
```

- 字族 **Geist**（`res/font/`，OFL-1.1，见 `FONT-LICENSE-OFL.txt`）。中文自动落到系统字体。
- `lineHeight = size * 1.3f`，所有文字统一，不要单独调。
- `tight = true` 时 `letterSpacing = -0.025em`，只用于 20sp 以上的标题。
- 常用档位：`34.sp` 页面大标题 / `20.sp` 区块标题 / `16.5.sp` 品牌名 / `15.sp` 卡片标题 /
  `11.5.sp` 副标题与元信息 / `9.5.sp` 角标。

字号下限 **9.5.sp**（原型里是 9.5px）。三米观看距离下再小就不可读。

---

## 4. 焦点环

```kotlin
Modifier.ring(focused: Boolean, radius: Dp, scale: Float = 1f,
              ringWidth: Dp = 2.dp, gap: Dp = 2.dp, halo: Dp = 7.dp)
```

实现：`graphicsLayer` 做缩放 + `drawWithContent` 画两道 `drawRoundRect(Stroke)`：

- **实心环**：2dp，颜色 `C.Ring`，贴边（`gap = 2dp` 的外扩）。
- **光晕**：7dp，`C.Ring.copy(alpha = 0.17f)`，在实心环外侧。
- 缩放动画 180ms `tween`，透明度 150ms。

用法固定为两层：外层 `Focusable` 里用 `Modifier.ring(false, r)` 占位，
内层 `Box(Modifier.ring(f, r, 1.015f))` 真正画环。

```kotlin
Focusable(onClick = onOpen) { f ->
    Box(Modifier.ring(f, 20.dp, 1.015f)) { /* 卡片内容 */ }
}
```

**为什么不用 Material 的 `Surface`/`Card` 焦点态**：TV Material 的 `Border + Glow` 在浅色白卡上
会和背景糊在一起。自制环把「间隔」显式留出来（`gap`），深浅两个主题都看得清。

---

## 5. 图片渲染

### 5.1 `MaskedArt` —— 整图不裁切的羽化

用户明确要求 banner「整张原图」显示，不能 `Crop` 裁边（会切掉字标）。
但 `Fit` 会在宽高比不匹配时留出空白，看起来像贴了一张小图。

解法（等价 CSS `object-fit: contain` + 边缘渐隐）：

```kotlin
Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
// 外面那层 Box：
  .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
  .drawWithContent {
      drawContent()
      drawRect(                       // DstIn：只在图片像素存在的地方保留
          brush = Brush.horizontalGradient(
              0f to Color.Transparent, maskStart to Color.Black,
              maskEnd to Color.Black, 1f to Color.Transparent),
          blendMode = BlendMode.DstIn,
      )
  }
```

再在底下铺一层 `edgeColors(bmp)`（左右边缘 1px 列的平均色）的横向渐变，
让羽化区自然过渡到卡片边缘色，而不是露白。

当前参数：首页大卡 `maskStart/maskEnd = 0.132f/0.868f`（863×323），详情横幅 `0.175f/0.825f`（832×约 390，更宽更高，羽化区收窄）。

### 5.1b 主推轮播：糊底 + 四边羽化（补掉 Fit 的横缝）

主推卡 648×344，横幅宽高比五花八门（2:1、2.2:1、16:9 都有）。`Fit` 之后上下（或左右）会留出
十几到二十几 dp 的底色带，图与底色之间是一条 1–2px 的硬缝 —— 观感就是「轮播图底部有一条线」。

做法（`HeroContent`）：

1. 底层先铺 `edgeColors` 横向渐变（原样保留）。
2. 再铺一层同图的 `art(url, 32)` + `Crop` 铺满当「糊底」—— 和分类磁贴的模糊是同一个手段，
   不用 `blur`（Android 9 真机上是空实现）。上面再压一层 0.58 alpha 的同色渐变，保住色氛。
3. 清晰图不用 `ContentScale.Fit`，而是先按位图比例算出 contain 之后的**真实矩形**
   （`BoxWithConstraints` 里 `ir = bmp.w / bmp.h`、`cr = 内框 w / h`，`ir > cr` 时宽撑满、否则高撑满），
   再用 `size(iw, ih)` + `ContentScale.FillBounds` 画 —— 效果和 Fit 完全一样，但拿到了图片自己的四条边。
4. 羽化贴在**图片自己的边**上，纵横各一次 `DstIn`（alpha 相乘），`f = 0.08`：
   `0f Transparent → f Black → 1-f Black → 1f Transparent`。

两个坑（都踩过）：
- 羽化贴内框而不是贴图片：图片的边落在斜坡 76% 处，还留一条浅缝。
- 只羽化有留白的那条轴：另一条轴贴着 12dp 内距，那里是「清晰图 → 糊底」的硬边，换成竖线。

现在图片四条边 alpha 都归零，缝两侧都是同一张画的内容，任何一条边都不会成线；整图没被裁切，规则 5 不破。

### 5.1c 主推卡：圆角亮边的根治（层级，而不是补丁）

现象：卡底两条边的圆角上有一道比卡内更亮的细弧，越暗的画面越明显。

成因：**压暗层和卡的裁切不在同一条边界上**。压暗过去是 hero 内容的兄弟节点
（`fillMaxSize().offset(2dp)`），它的底边和 `clip(shape)` 的边界差 1–2px；
圆角的抗锯齿带比直边宽，这 1–2px 在弧线处就摊成一条肉眼可见的亮弧，
露出来的是没被压暗的 banner 原色（实测最后一行 = banner 主色，Aerial 152 / UnCiv 216 / BitChord 234）。

现在的层级（`HeroCarousel` / `HeroArt` / `HeroText`）：

```
Box(fillMaxSize)                       // 只负责布局
├─ Box(ring → cardShadow → clip(shape) → background(#12151C) → drawWithContent)
│   ├─ Crossfade { HeroArt }           // 糊底 + 整图 contain + 羽化，不含压暗
│   └─ drawWithContent 里依次画：
│        · 上下压暗渐变（四周各多铺 3px，越界被 clip 裁掉）
│        · 卡底最后 2px 近黑兜底（0xF205070B）
│        · 底段 12% 才上色的 1px 内收边（跟压暗同色，上半段全透明）
├─ HeroText                            // 画在 card 之上，不被压暗影响
└─ HeroDots
```

四条规矩，后面改主推卡不要再破：

1. `clip` 必须在最外层，压暗/收边用同一个节点的 `drawWithContent` 画 —— 同一个 canvas，
   边界天然一致；**不要**再把压暗做成兄弟节点。
2. 压暗矩形四周多铺 3px；卡底最后 2px 再兜一层近乎不透明的底色 ——
   抗锯齿带里永远有压暗，最后一行不可能是原图亮度。
3. 文字、指示点放到 card 之外（同一父 Box 的后面的兄弟），否则会被压暗一起压黑。
4. `ContentScale.Crop` 的 `Image` 会画出自己的 bounds，画面层要 `clipToBounds()`；
   满亮的 `edgeColors` 渐变只在「没有横幅图」时才画，否则它会在卡边露出一圈亮色。

验证判据：取卡底 680–690 行做逐行亮度，应当是单调下降（例：44 → 42 → 41 → 39 → 7 → 页面底色）；
有亮边时会出现 `… → 232 → 12` 这种单行凸起。

### 5.2 模糊磁贴

分类磁贴要「背景字标模糊」（原型 `filter: blur(7px)`）。Compose 没有便宜的实时模糊，做法是
**降采样再放大**：`art(url, 32)` 取 32px 宽的位图，`ContentScale.Crop` 撑满磁贴 —— 天然糊。
先试过 48px 仍然偏清楚，32px 刚好。

### 5.3 主题色提纯 `vivid()`

```kotlin
private fun vivid(base: Color, fallbackHue: Float): Color
```

问题：目录里大量 banner 是**白底 + 居中 logo**，`meanColor()` 出来接近灰色，
背景一片炭黑没有氛围。

做法：`colorToHSV` → 饱和度钳到 `0.48..0.78`、明度钳到 `0.34..0.58`；
若原色饱和度低于 0.08（即接近灰色），强制换成 `fallbackHue`（`FALLBACK_HUES = [6f, 174f, 226f]` 红 / 青 / 蓝）。

### 5.4 背景色雾 `Wall`

原型是 `filter: blur(72px)` 的色团。实现用三个 `drawRect(Brush.radialGradient(...))`：
取首页前三张卡片的提纯色，分别落在左上 / 右上 / 左下，顶部再叠一层压暗（`0x1F`，之前 `0x40` 太黑）。

---

## 6. 卡片投影

```kotlin
@Composable
fun Modifier.cardShadow(shape: Shape, radius: Dp = 20.dp): Modifier =
    if (C.dark) this
    else this.shadow(radius, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = 0.10f),
        spotColor = Color(0xFF18324A).copy(alpha = 0.16f))
```

**只在浅色主题生效**：深色下卡片不描边，靠 `RowBg` / `Card` 的填充差和背景色雾区分，加投影反而脏；
浅色下白卡贴白底必须有投影才有层次（v8 初版漏了这一步，用户反馈「一片糊」之前的实际观感问题）。

**卡片一律不画外包边**：`WideCard` / `PosterCard` / `RoomTile` / 详情海报与横幅 / 设置行 / 应用行 /
搜索结果行 / 语言弹层，全部只留填充 + 投影，没有 `1dp C.Line`。
理由：一整排卡上的细线会连成一条发灰的框，把图「框住」，越大的卡越明显（首页货架实测后统一推广）。
只有两类保留描边：
- **控件**：输入框、分段控件、键盘按键、分类胶囊 / Chip / 标签、开关轨道、GhostBtn —— 它们不是卡片，边框是可点击的暗示；
- **语义状态**：详情页「签名校验通过」卡片的 26% 绿边，是签名状态本身，不是装饰线。

**返回胶囊 `BackPill` 属于第一条的例外**：虽然可点，但形态是「一块圆角实心」，与卡片同类，
`C.Line` 会在黑底上画出一圈灰框、把 44dp 的小胶囊显得笨重。深色靠 `RowBg` 填充差、浅色靠
`cardShadow(CircleShape, 10.dp)` 定边，和全站卡片同一套语言。
顶栏的 `RoundBtn`（放大镜 / 齿轮，34dp）**仍留描边**：圆太小，纯填充差在浅色下会直接消失。

---

## 7. 图标

`object Icons` 里全部是 24×24 视口的 `ImageVector`，用 `ico(...)` 从 path 字符串构造，
描边 1.7。**不用 Material Icons 依赖**（省体积，也因为原型是一套自定义线性图标）。

已实现：`Search`、`Gear`、`Sort`、`Filter`、`Back`、`Down`、`Check`、`Download`、`Trash`、
`Folder`、`Info`、`Play`、`Star`、`Clock`、`Close`、`Arrow` 等（新图标照现有写法加 path 即可）。

应用图标用 `AppIcon(app, size)`：网络图加载失败时退化成 `NameCard`（首字母色块），
不会出现破图 —— 目录里确实有几种图标 URL 已失效。

**品牌标识有三套尺寸，别互相抄**：

| 位置 | 尺寸 | 为什么 |
|---|---|---|
| 启动页 `Splash` | `Brand(96.dp)` + 名字 38sp | 屏幕中央只有它，停留 1–2 秒，必须够重 |
| 顶栏 `TopBar` | `Brand(30.dp)` + 名字 17sp | 常驻信息，旁边还有导航胶囊，抢空间就乱 |
| TV 桌面 | `drawable-*/banner.png` | launcher 读 banner（320×180），不是 `ic_launcher` |

banner 由 `art/generate_icons.py` 生成：图标高 **92/180 ≈ 51%**、左边距 26、文字上限 32。
图标不要给回 62%（旧值）：上下只剩 34px，launcher 圆角一切就缺角，在
「已收藏的应用」那种小卡里也显得闷。改完必须重跑脚本，5 档密度一起换。

---

## 8. 圆角与间距

| 元素 | 圆角 |
|---|---|
| 所有图片卡（主推轮播 / 横卡 / 小卡 / 分类磁贴 / 详情页图） | `tileRadius(短边)` = 短边 × 25%，钳 8–28dp |
| 版本卡 / 内容卡片 / 设置行 | `16.dp`（`15.dp` / `14.dp` 同族，按行高递减） |
| 胶囊 / Chip / 角标 | 全圆（`CircleShape` 或 `height/2`） |
| 顶栏导航容器 | `CircleShape` |

`tileRadius` 是唯一的图片圆角来源：短边 122 / 148 / 344 都钳到 28dp，和聚焦环 `ring(f, tileRadius(...))`
共用同一个值，环才不会脱开卡片边缘。**不要在卡片上再写死数字圆角。**

间距：卡片之间 `20.dp`（首页横排）、`16.dp`（详情版本卡）、`12.dp`（Chip 横排、设置行）。
都是 4 的倍数，别引入 13 / 17 这种值。
