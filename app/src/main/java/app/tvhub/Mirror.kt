package app.tvhub

import android.content.res.Resources
import java.util.Locale

/**
 * 下载链路的选择：封面图（raw.githubusercontent.com）与安装包（github.com/…/releases），
 * 以及应用自己的更新索引，三处都从这里取候选地址。
 *
 * 三种模式（设置 → 下载源，见 [Prefs.source]）：
 * - **自动**（默认）：系统语言是简体中文才走国内镜像，其它语言直连 GitHub；
 * - **直连**：永远先试 GitHub 原链，哪怕超时；
 * - **国内镜像**：永远先试镜像，失败再回退原链。
 *
 * 「自动」的理由：中文电视盒子多半在国内网络里，直连 GitHub 经常超时或只有几十 KB/s；
 * 而英文系统多半在墙外，绕一层公共代理只会更慢。判断看**系统语言**（[systemLocale]），
 * 不看 App 内语言 —— 界面被设成中文不代表这台机器在国内。
 *
 * 只有传输路径变。APK 字节、catalog.json 与 [Installer.checkSignature] 的证书指纹都不动，
 * 所以一个坏心眼的镜像无法变成一次静默安装。
 *
 * 2026-11-14 在国内网络无代理实测：
 *   gh-proxy.com .... 200 on raw, 206 on release APK, ~174 KB/s for 1 MB
 *   ghproxy.net ..... 200 on raw, 206 on release APK,  ~96 KB/s for 1 MB
 *   cdn.jsdelivr.net / raw.gitmirror.com ... unreachable
 * 公共代理来去无常，所以留不止一个，并且永远把原链放最后兜底。
 */
object Mirror {
    private const val GITHUB = "https://github.com/"
    private const val RAW = "https://raw.githubusercontent.com/"

    /** 镜像站点，按国内实测速度排序；新增站点追加在末尾即可 */
    private val proxies = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    private fun githubHosted(url: String) = url.startsWith(GITHUB) || url.startsWith(RAW)

    /**
     * 系统语言。**不能用 `Locale.getDefault()`**：`L.apply()` 会把进程默认 Locale 改成 App 选择的
     * 语言，之后 `getDefault()` 返回的是界面语言而不是系统语言；而且语言变化时 Activity 会重建，
     * 进程通常还活着，读到的仍是旧值。`Resources.getSystem()` 的 configuration 才是系统全局的那一份。
     */
    fun systemLocale(): Locale = runCatching {
        Resources.getSystem().configuration.locales[0]
    }.getOrNull() ?: Locale.getDefault()

    /** 简体中文：语言是 zh，且不是港澳台（zh-HK/TW/MO 用繁体，它们的网络位置与语言无关，保持直连） */
    fun isSimplifiedChinese(locale: Locale): Boolean =
        locale.language.equals("zh", ignoreCase = true) &&
            locale.country.uppercase() !in setOf("TW", "HK", "MO")

    fun systemIsSimplifiedChinese(): Boolean = isSimplifiedChinese(systemLocale())

    /** 当前是否真的在走镜像。自动档要看系统语言，另外两档就是用户钉死的结果 */
    fun usingMirror(): Boolean = when (Prefs.source) {
        Prefs.SOURCE_GITHUB -> false
        Prefs.SOURCE_MIRROR -> true
        else -> systemIsSimplifiedChinese()
    }

    /** Ordered list to try: mirrors first (when in use), original URL last. */
    fun candidates(url: String): List<String> =
        if (!githubHosted(url) || !usingMirror()) listOf(url)
        else proxies.map { it + url } + url
}
