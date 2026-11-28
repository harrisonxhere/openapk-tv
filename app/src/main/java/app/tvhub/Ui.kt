package app.tvhub

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.min

/* ============================================================
   设计坐标系
   原型按 1280×720 画，电视实际是 960×540dp（1080p / xhdpi）。
   这里把 LocalDensity 改写成「1dp = 屏幕宽 / 1280」，
   于是下面所有尺寸都能直接抄原型的像素值，且换分辨率不跑版。
   ============================================================ */
@Composable
fun DesignCanvas(tints: List<Color>? = null, content: @Composable BoxScope.() -> Unit) {
    /* 底色跟主题走：启动页没有 Wall() 盖着，写死 #06080B 的话浅色主题会先黑一下再变亮，
       而且深灰壳的 logo 贴在近黑底上等于没有轮廓（实拍确认）。
       tints 非空时，先在整个物理屏幕（缩放之前）铺一层渐变墙纸——这样手机等非16:9设备上，
       1280×720 画布两侧/上下缩放后留出的空间，看到的是渐变延伸过去的颜色，而不是纯黑/纯色硬边。 */
    BoxWithConstraints(
        Modifier.fillMaxSize().background(if (C.dark) Color(0xFF06080B) else C.Wall),
        contentAlignment = Alignment.Center,
    ) {
        if (tints != null) Wall(tints)
        val d = LocalDensity.current
        val s = min(maxWidth.value / 1280f, maxHeight.value / 720f)
        CompositionLocalProvider(LocalDensity provides Density(d.density * s, 1f)) {
            Box(Modifier.size(1280.dp, 720.dp), content = content)
        }
    }
}

/* ============================================================
   颜色令牌 —— 与原型 :root / html[data-theme=light] 一一对应
   ============================================================ */
object C {
    val dark: Boolean get() = Prefs.dark
    private fun c(d: Long, l: Long) = Color(if (dark) d else l)

    val Fg get() = c(0xFFF4F7FB, 0xFF101620)
    val Fg2 get() = c(0xBFF4F7FB, 0xB3101620)
    val Fg3 get() = c(0x85F4F7FB, 0x80101620)

    val Line get() = c(0x1BFFFFFF, 0x1A101620)
    val Line2 get() = c(0x2EFFFFFF, 0x2E101620)
    val RowBg get() = c(0x0EFFFFFF, 0xB8FFFFFF)

    /* 聚焦/选中的反色底：留 12% 透明度 —— 深色主题里不是一块死白补光灯，
       浅色主题里也不是一块死黑。文字/图标仍用实心 HiFg，对比度不受影响。
       压在背景色雾上会有极轻的偏色，这正是「融进画面」要的效果。 */
    val HiBg get() = c(0xE0FFFFFF, 0xE0101620)
    val HiFg get() = c(0xFF0C0F15, 0xFFFFFFFF)

    val Ring get() = c(0xFFFFFFFF, 0xFF101620)

    /** 背景墙：深色是近黑，浅色是**压过一档**的冷灰（原 #E9EDF4 在暗房间整屏发白、刺眼）。
        详情页的底部渐隐也用它，别再硬编码。 */
    val Wall get() = c(0xFF06080B, 0xFFDCE2EC)

    /** 卡片面：浅色不用纯白 —— 纯白 + 大屏就是一块背光板，留 2% 灰反而让卡沿更稳。 */
    val Card get() = c(0xFF0B0E14, 0xFFF9FAFD)

    val BtnBg get() = c(0xF2F7FAFF, 0xFF101620)
    val BtnFg get() = c(0xFF0C0F15, 0xFFFFFFFF)

    /** 存储卡片分段颜色，深浅色通用 */
    val MeterApp get() = Color(0xFF5B8FC9)
    val MeterCache get() = Color(0xFF8A7BD8)
    val MeterFree get() = Color(0xFF35C07A)
    val SwitchOn get() = Color(0xFF35C07A)
}

/**
 * 内容层。v8 之后不再有「悬浮玻璃面板」——设计稿要求内容直接落在背景上，
 * 所以这里只保留一个坐标容器，圆角、描边、投影全部去掉。
 */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, content = content)
}

/** 背景：三团封面化开的色雾（真实模糊代价太大，用径向渐变等效） */
@Composable
fun Wall(tints: List<Color>) {
    val d = C.dark
    Box(Modifier.fillMaxSize().background(C.Wall)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val a0 = tints.getOrElse(0) { Color(0xFF7A3B44) }
            val a1 = tints.getOrElse(1) { Color(0xFF2C6E6A) }
            val a2 = tints.getOrElse(2) { Color(0xFF3A4668) }
            fun blob(cx: Float, cy: Float, r: Float, c: Color, a: Float) {
                drawRect(
                    Brush.radialGradient(
                        listOf(c.copy(alpha = a), c.copy(alpha = a * 0.42f), Color.Transparent),
                        center = Offset(cx, cy), radius = r,
                    ),
                )
            }
            blob(-0.11f * w, -0.42f * h, 0.66f * w, a0, if (d) 0.62f else 0.26f)
            blob(1.17f * w, -0.34f * h, 0.62f * w, a1, if (d) 0.50f else 0.22f)
            blob(0.46f * w, 1.50f * h, 0.72f * w, a2, if (d) 0.34f else 0.13f)
            drawRect(
                Brush.verticalGradient(
                    if (d) listOf(Color(0x1F090B10), Color(0xA606080B))
                    /* 浅色这层是「顶部提亮」的白雾：原来 35% 白会把压暗过的墙面又提回去，
                       减到 18% 才守得住整体明度，也只留一点柔光的方向感。 */
                    else listOf(Color(0x2EFFFFFF), Color(0x1AE6ECF5)),
                ),
            )
        }
    }
}

/* ============================================================
   文本
   ============================================================ */
val Geist = FontFamily(
    Font(R.font.geist, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.geist, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.geist, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@Composable
fun T(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = C.Fg,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    tight: Boolean = false,
    align: TextAlign = TextAlign.Unspecified,
    /** 行高倍数（默认 1.3）。多行正文段落用 1.5–1.6 更透气 */
    line: Float = 1.3f,
) {
    BasicText(
        text, modifier,
        style = TextStyle(
            fontFamily = Geist, fontSize = size, color = color, fontWeight = weight,
            letterSpacing = if (tight) (-0.025).em else 0.em, lineHeight = size * line,
            textAlign = align,
        ),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/* ============================================================
   图标 —— 24×24 视口、1.7 描边，与原型 SVG 同一套 path
   ============================================================ */
private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r} $y h${w - 2 * r} a$r $r 0 0 1 $r $r v${h - 2 * r} a$r $r 0 0 1 -$r $r " +
        "h-${w - 2 * r} a$r $r 0 0 1 -$r -$r v-${h - 2 * r} a$r $r 0 0 1 $r -$r z"

private fun ico(vararg parts: Pair<String, Boolean>): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    parts.forEach { (d, filled) ->
        addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}.build()

/* 品牌标识（应用内）：白色电视壳 + 屏内绿色渐变笑脸（art/logo-mark.svg，620×542 画布）。
   这是**彩色**向量：脸用 Brush 渐变、壳是实心，不给 Ico() 上 tint，所以顶栏/启动页用 Brand()。
   和桌面 launcher / banner 用的 art/logo-badge.svg（绿圆 + 白电视）是两份真源：
   这一份去掉了圆底，小尺寸下轮廓更干净，但白壳在浅色主题会直接消失 —— 所以壳色跟主题走。 */
private const val LOGO_FACE =
        "M416.054 249.004C423.323 253.165 425.848 262.44 421.695 269.722L398.484 310.411C440.927 337.065 470.678 " +
        "382.375 475.924 435.315C476.81 444.251 469.804 452 460.84 452H159.16C150.196 452 143.19 444.251 144.076 " +
        "435.315C149.092 384.699 176.509 341.057 216.002 314.028L190.727 269.722C186.574 262.44 189.099 253.165 " +
        "196.368 249.004C203.637 244.843 212.897 247.373 217.051 254.654L242.486 299.242C263.17 290.055 286.029 " +
        "284.965 310 284.965C331.634 284.965 352.362 289.111 371.402 296.672L395.372 254.654C399.525 247.373 " +
        "408.785 244.843 416.054 249.004ZM241.786 368.483C233.414 368.483 226.627 375.281 226.627 383.668C226.627 " +
        "392.054 233.414 398.853 241.786 398.853C250.159 398.853 256.945 392.054 256.945 383.668C256.945 375.281 " +
        "250.159 368.483 241.786 368.483ZM378.216 368.483C369.844 368.483 363.057 375.281 363.057 383.668C363.057 " +
        "392.054 369.844 398.853 378.216 398.853C386.588 398.853 393.374 392.054 393.374 383.668C393.374 375.281 " +
        "386.588 368.483 378.216 368.483Z"

private const val LOGO_SHELL =
        "M444.496 7.14941C454.354 -2.51718 470.184 -2.36199 479.851 7.49609C489.517 17.3544 489.362 33.1836 " +
        "479.504 42.8506L408.985 112H498C565.379 112 620 166.621 620 234V420C620 486.852 566.229 541.146 499.577 " +
        "541.99L498 542H122L120.423 541.99C54.2959 541.152 0.847538 487.704 0.00976562 421.577L0 " +
        "420V234C0.000197918 166.621 54.6214 112 122 112H212.015L141.496 42.8506C131.638 33.1836 131.483 17.3544 " +
        "141.149 7.49609C150.816 -2.36199 166.646 -2.51718 176.504 7.14941L279.504 108.149C280.724 109.346 " +
        "281.796 110.638 282.719 112H338.281C339.204 110.638 340.276 109.346 341.496 108.149L444.496 7.14941ZM122 " +
        "162C82.2356 162 50.0002 194.236 50 234V420C50 459.764 82.2355 492 122 492H498C537.765 492 570 459.764 " +
        "570 420V234C570 194.236 537.764 162 498 162H122Z"

/** 屏幕区（与壳的镂空完全同形：x 50..570 / y 162..492，r 50），浅色主题下填深色当「屏」 */
private const val LOGO_SCREEN =
    "M100 162H520A50 50 0 0 1 570 212V442A50 50 0 0 1 520 492H100" +
        "A50 50 0 0 1 50 442V212A50 50 0 0 1 100 162Z"

/** 壳色与屏幕随主题：[C.dark] 下白壳压在近黑底上，屏幕留空即可（墙就是黑的）；
    浅色下白壳贴浅灰墙等于没有轮廓，所以换深壳 + 给屏幕填一块更深的底 ——
    不填的话绿脸浮在浅灰上，整枚看着像线框图标，压不住旁边 17sp 的粗体字标（实拍对比过）。 */
private fun logoMark(shell: Color, screen: Color?): ImageVector = ImageVector.Builder(
    defaultWidth = 30.dp, defaultHeight = 26.2.dp, viewportWidth = 620f, viewportHeight = 542f,
).apply {
    /* 顺序同 svg：屏底 → 脸 → 壳（壳的屏幕区域是镂空的，靠 NonZero 反向子路径透出前两层） */
    if (screen != null) addPath(
        pathData = PathParser().parsePathString(LOGO_SCREEN).toNodes(),
        fill = SolidColor(screen),
    )
    addPath(
        pathData = PathParser().parsePathString(LOGO_FACE).toNodes(),
        fill = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color(0xFF9BEA67),
                0.48f to Color(0xFF5BD05C),
                1f to Color(0xFF16A879),
            ),
            start = Offset(153.5f, 255.1f),
            end = Offset(320.4f, 532f),
        ),
    )
    addPath(
        pathData = PathParser().parsePathString(LOGO_SHELL).toNodes(),
        fill = SolidColor(shell),
    )
}.build()

object Icons {
    val Search = ico(
        "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14z" to false,
        "M16.6 16.6 21 21" to false,
    )
    val Gear = ico(
        "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61" +
            "l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54" +
            "c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94" +
            "l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58" +
            "c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32" +
            "c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84" +
            "c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22" +
            "l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6" +
            " 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z" to true,
    )
    val Grid = ico(
        rr(3f, 3f, 7.4f, 7.4f, 2.2f) to false,
        rr(13.6f, 3f, 7.4f, 7.4f, 2.2f) to false,
        rr(3f, 13.6f, 7.4f, 7.4f, 2.2f) to false,
        rr(13.6f, 13.6f, 7.4f, 7.4f, 2.2f) to false,
    )
    /** 「其它」：三点省略，三个实心圆用 1.4 半径的小圆画（描边会糊成一团，所以填实） */
    val Dots = ico(
        "M7 10.6a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8z" to true,
        "M12 10.6a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8z" to true,
        "M17 10.6a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8z" to true,
    )
    val Shield = ico("M12 3.2 19 6v6.1c0 4.2-2.9 7.5-7 8.7-4.1-1.2-7-4.5-7-8.7V6l7-2.8Z" to false)
    val ShieldAlert = ico(
        "M12 3.2 19 6v6.1c0 4.2-2.9 7.5-7 8.7-4.1-1.2-7-4.5-7-8.7V6l7-2.8Z" to false,
        "M12 8.4v4.4" to false,
        "M12 16.4v.01" to false,
    )
    val Tv = ico(
        rr(2.4f, 3.8f, 19.2f, 13f, 2.8f) to false,
        "M8.5 20.4h7" to false,
        "M10.6 7.6l4 2.2-4 2.2z" to false,
    )
    val Tool = ico(
        "M15.6 3.4a4.6 4.6 0 0 0-5.9 5.9l-6 6a2.2 2.2 0 0 0 3.1 3.1l6-6a4.6 4.6 0 0 0 5.9-5.9" +
            "l-2.7 2.7-2.5-.7-.7-2.5 2.8-2.6Z" to false,
    )
    val Pad = ico(
        "M7.4 7.6h9.2a5 5 0 0 1 4.9 6.1l-.4 1.7a3 3 0 0 1-5.2 1.3L14.9 15H9.1l-1 1.7a3 3 0 0 1-5.2-1.3" +
            "l-.4-1.7a5 5 0 0 1 4.9-6.1Z" to false,
        "M8 10.4v3M6.5 11.9h3" to false,
        "M16.4 10.8a1.1 1.1 0 1 1 0 2.2a1.1 1.1 0 1 1 0-2.2z" to false,
    )
    val Book = ico(
        "M4.2 5.6A2.6 2.6 0 0 1 6.8 3H19.6v15.4H6.8a2.6 2.6 0 0 0-2.6 2.6V5.6Z" to false,
        "M19.6 18.4v2.6H6.8" to false,
    )
    val Music = ico(
        "M9.2 17.6V5.8l9.6-1.9v11.6" to false,
        "M6.6 15.4a2.6 2.6 0 1 1 0 5.2a2.6 2.6 0 1 1 0-5.2z" to false,
        "M16.2 13a2.6 2.6 0 1 1 0 5.2a2.6 2.6 0 1 1 0-5.2z" to false,
    )
    val Star = ico(
        "m12 3.4 2.65 5.9 6.35.6-4.8 4.3 1.4 6.2L12 17.2 6.4 20.4l1.4-6.2-4.8-4.3 6.35-.6L12 3.4Z" to true,
    )
    val Sort = ico(
        "M7.5 4.2v15.6M7.5 4.2 4.3 7.4M7.5 4.2l3.2 3.2" to false,
        "M16.5 19.8V4.2M16.5 19.8l3.2-3.2M16.5 19.8l-3.2-3.2" to false,
    )
    val Filter = ico("M3.4 5.2h17.2l-6.8 7.9v6.2l-3.6 1.8v-8L3.4 5.2Z" to false)
    val Down = ico(
        "M12 3.6v10.2" to false,
        "m7.6 9.8 4.4 4.4 4.4-4.4" to false,
        "M4.4 20.2h15.2" to false,
    )
    val Check = ico("m5 12.6 4.6 4.6L19 7.4" to false)
    val Git = ico(
        "M8.4 6a2.4 2.4 0 1 1-4.8 0 2.4 2.4 0 1 1 4.8 0" to false,
        "M8.4 18a2.4 2.4 0 1 1-4.8 0 2.4 2.4 0 1 1 4.8 0" to false,
        "M20.4 8a2.4 2.4 0 1 1-4.8 0 2.4 2.4 0 1 1 4.8 0" to false,
        "M6 8.4v7.2M8.4 6h4.2a3 3 0 0 1 3 3v.6" to false,
    )
    val Play = ico("M8 5.6v12.8L19 12 8 5.6Z" to true)
    val Home = ico(
        "M3.8 10.4 12 3.6l8.2 6.8" to false,
        "M6.4 9.4v10.8h11.2V9.4" to false,
    )
    val ChevLeft = ico("M15 5l-7 7 7 7" to false)
    val ChevRight = ico("M9 5l7 7-7 7" to false)
    val Trash = ico("M4 7h16M9 7V4h6v3M6.5 7l1 13h9l1-13" to false)
    val Up = ico(
        "M12 20.4V9.8" to false,
        "m7.6 14.2 4.4-4.4 4.4 4.4" to false,
    )
    val Chip = ico(
        rr(8f, 8f, 8f, 8f, 2f) to false,
        "M9 4v4M15 4v4M9 16v4M15 16v4M4 9h4M4 15h4M16 9h4M16 15h4" to false,
    )
    val Calendar = ico(
        rr(3.5f, 5f, 17f, 15f, 2f) to false,
        "M3.5 10h17M8 3v4M16 3v4" to false,
    )
}

@Composable
fun Ico(v: ImageVector, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(v), null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}

/** 彩色品牌标识：不走 tint，颜色由 [logoMark] 自带（绿色渐变脸 + 电视壳），壳与屏幕底色随主题变 */
@Composable
fun Brand(size: Dp, modifier: Modifier = Modifier) {
    val v = remember(C.dark) {
        logoMark(
            shell = if (C.dark) Color.White else Color(0xFF363B45),
            screen = if (C.dark) null else Color(0xFF0B1017),
        )
    }
    Image(rememberVectorPainter(v), null, modifier.size(size))
}

/* ============================================================
   焦点
   ============================================================ */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    requester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onFocusChanged: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged?.invoke(it.isFocused)
                if (it.isFocused) onFocus()
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) { content(focused) }
}

/**
 * 焦点环：贴边的 2px 实心环 + 外侧 7px 半透明光晕。
 * 同时按原型给一个轻微放大（卡片 1.015、胶囊 1.06）。
 */
@Composable
fun Modifier.ring(
    focused: Boolean,
    radius: Dp,
    scale: Float = 1f,
    ringWidth: Dp = 2.dp,
    gap: Dp = 2.dp,
    halo: Dp = 7.dp,
): Modifier {
    val s by animateFloatAsState(if (focused) scale else 1f, tween(180), label = "ring-scale")
    val a by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "ring-alpha")
    val hard = C.Ring
    val soft = C.Ring.copy(alpha = 0.17f)
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .drawWithContent {
            drawContent()
            if (a > 0.02f) {
                val r = radius.toPx()
                val w = ringWidth.toPx()
                val g = gap.toPx()
                val h = halo.toPx()
                val hr = g + h / 2f
                drawRoundRect(
                    color = soft.copy(alpha = soft.alpha * a),
                    topLeft = Offset(-hr, -hr),
                    size = Size(size.width + 2 * hr, size.height + 2 * hr),
                    cornerRadius = CornerRadius(r + hr),
                    style = Stroke(h),
                )
                val wr = g + w / 2f
                drawRoundRect(
                    color = hard.copy(alpha = hard.alpha * a),
                    topLeft = Offset(-wr, -wr),
                    size = Size(size.width + 2 * wr, size.height + 2 * wr),
                    cornerRadius = CornerRadius(r + wr),
                    style = Stroke(w),
                )
            }
        }
}

/** 卡片投影：亮色主题必须有，否则白卡贴白底；深色主题只留极淡的一层 */
@Composable
fun Modifier.cardShadow(shape: androidx.compose.ui.graphics.Shape, radius: Dp = 20.dp): Modifier =
    if (C.dark) this
    else this.shadow(
        radius, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = 0.10f),
        spotColor = Color(0xFF18324A).copy(alpha = 0.16f),
    )

/* ============================================================
   小部件
   ============================================================ */
/** 顶栏 / 区块头的圆形按钮（34px） */
@Composable
fun RoundBtn(icon: ImageVector, onClick: () -> Unit, requester: FocusRequester? = null) {
    Focusable(onClick, requester = requester) { f ->
        Box(
            Modifier.size(34.dp).ring(f, 17.dp, scale = 1.06f)
                .background(if (f) C.HiBg else C.RowBg, CircleShape)
                .border(1.dp, if (f) C.HiBg else C.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Ico(icon, 17.dp, if (f) C.HiFg else C.Fg2) }
    }
}

/** 玻璃胶囊（顶栏右侧的信息条） */
@Composable
fun GlassPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(26.dp).clip(CircleShape)
            .background(if (C.dark) Color(0x85090C12) else Color(0xE6FFFFFF))
            .border(1.dp, C.Line, CircleShape)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun Dot(color: Color = C.Fg3, size: Dp = 2.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

/** 环形进度：下载 / 安装都用它 */
@Composable
fun Ring(fraction: Float, color: Color, track: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = 2.dp.toPx()
        val r = this.size.minDimension / 2 - w
        val tl = Offset(center.x - r, center.y - r)
        val sz = Size(r * 2, r * 2)
        drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

fun launch(ctx: android.content.Context, pkg: String) {
    val pm = ctx.packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
    ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun uninstall(ctx: android.content.Context, pkg: String) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_DELETE,
        android.net.Uri.parse("package:$pkg"),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
}

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().height(1.dp).background(C.Line))
}
