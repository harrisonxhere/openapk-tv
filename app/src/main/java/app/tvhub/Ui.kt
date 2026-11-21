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
fun DesignCanvas(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF06080B)), contentAlignment = Alignment.Center) {
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
    val Card get() = c(0xFF0B0E14, 0xFFFFFFFF)

    val BtnBg get() = c(0xF2F7FAFF, 0xFF101620)
    val BtnFg get() = c(0xFF0C0F15, 0xFFFFFFFF)

    /** 存储卡片分段颜色，深浅色通用 */
    val MeterApp get() = Color(0xFF5B8FC9)
    val MeterCache get() = Color(0xFF8A7BD8)
    val MeterFree get() = Color(0xFF35C07A)
    val SwitchOn get() = Color(0xFF35C07A)

    val mark: Int get() = if (dark) R.drawable.mark_dark else R.drawable.mark
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
    Box(Modifier.fillMaxSize().background(if (d) Color(0xFF06080B) else Color(0xFFE9EDF4))) {
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
                    else listOf(Color(0x59FFFFFF), Color(0x2EE6ECF5)),
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
) {
    BasicText(
        text, modifier,
        style = TextStyle(
            fontFamily = Geist, fontSize = size, color = color, fontWeight = weight,
            letterSpacing = if (tight) (-0.025).em else 0.em, lineHeight = size * 1.3f,
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

/* 品牌标识的三段路径来自设计稿 Frame.svg（120×120 网格）：天线、屏幕内的 GitHub 猫、机身外框 */
private const val LOGO_ANTENNA =
        "M25.1001 10.3198C26.6491 8.64945 29.2597 8.55112 30.9302 10.1001L51.5552 " +
        "29.2236C53.2255 30.7725 53.3233 33.3832 51.7749 35.0537C50.2259 36.7242 47.6153 " +
        "36.8225 45.9448 35.2734L25.3198 16.1499C23.6494 14.6009 23.5511 11.9903 25.1001 " +
        "10.3198ZM89.0698 10.1001C90.7403 8.55112 93.3509 8.64945 94.8999 10.3198C96.4489 " +
        "11.9903 96.3505 14.6009 94.6802 16.1499L74.0552 35.2734C72.3847 36.8225 69.7741 " +
        "36.7242 68.2251 35.0537C66.6767 33.3832 66.7745 30.7725 68.4448 29.2236L89.0698 " +
        "10.1001Z"

private const val LOGO_CAT =
        "M53.9254 94.5247C52.8249 94.525 51.9263 93.6362 51.9203 92.5357L51.9117 " +
        "90.9798C42.19 93.1195 40.1394 86.8071 40.1394 86.8071C38.5493 82.7211 36.2585 " +
        "81.6352 36.2585 81.6352C33.0883 79.4385 36.4987 79.4831 36.4987 79.4831C40.0046 " +
        "79.731 41.8519 83.1277 41.8519 83.1277C44.9683 88.5327 50.0276 86.9707 52.0195 " +
        "86.0682C52.3331 83.7823 53.2396 82.2228 54.2392 81.3377C46.4775 80.4451 38.319 " +
        "77.4104 38.319 63.8632C38.319 60.0029 39.6837 56.8492 41.9205 54.3723C41.5579 " +
        "53.4798 40.3623 49.8848 42.2586 45.0153C42.2586 45.0153 45.1937 44.0657 51.87 " +
        "48.6401C54.721 47.8551 57.662 47.455 60.6166 47.45C63.5835 47.4624 66.5749 47.8541 " +
        "69.368 48.6401C76.0393 44.0658 78.9696 45.0153 78.9696 45.0153C80.8708 49.8872 " +
        "79.6752 53.4823 79.3126 54.3723C81.5519 56.8467 82.9092 60.0029 82.9092 " +
        "63.8633C82.9092 77.4451 74.7334 80.4352 66.9547 81.3104C68.2067 82.4087 69.3239 " +
        "84.5583 69.3239 87.8583C69.3239 89.9333 69.3206 91.4699 69.3169 92.5412C69.3131 " +
        "93.6387 68.4173 94.523 67.3199 94.5233C61.1113 94.5247 60.0095 94.5233 53.9254 " +
        "94.5247Z"

private const val LOGO_FRAME =
        "M101.25 50.625C101.25 43.3763 95.3737 37.5 88.125 37.5H31.875C24.6263 37.5 18.75 " +
        "43.3763 18.75 50.625V84.375C18.75 91.6237 24.6263 97.5 31.875 97.5H88.125C95.3737 " +
        "97.5 101.25 91.6237 101.25 84.375V50.625ZM108.75 84.375C108.75 95.7659 99.5159 105 " +
        "88.125 105H31.875C20.4841 105 11.25 95.7659 11.25 84.375V50.625C11.25 39.2341 " +
        "20.4841 30 31.875 30H88.125C99.5159 30 108.75 39.2341 108.75 50.625V84.375Z"

object Icons {
    /**
     * 品牌标识：电视天线 + 机身 + 屏幕内的 GitHub 猫（设计稿 Frame.svg）。
     * 猫在原稿里是 #6E6E6E，这里换成同色 + 0.56 的 fillAlpha —— Ico() 走单色 tint 时，
     * 外框吃满主色、猫自动淡成一层灰，层次与原稿一致，深浅主题都成立。
     */
    val Logo = ImageVector.Builder(
        defaultWidth = 30.dp, defaultHeight = 30.dp, viewportWidth = 120f, viewportHeight = 120f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(LOGO_ANTENNA).toNodes(),
            fill = SolidColor(Color.Black),
        )
        addPath(
            pathData = PathParser().parsePathString(LOGO_CAT).toNodes(),
            fill = SolidColor(Color.Black), fillAlpha = 0.56f,
        )
        addPath(
            pathData = PathParser().parsePathString(LOGO_FRAME).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
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
