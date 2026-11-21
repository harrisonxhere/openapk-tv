package app.tvhub

/**
 * Runtime rewriting for the two hosts used by catalog.json:
 *   github.com ................. release APKs (4180 URLs)
 *   raw.githubusercontent.com . artwork      (3621 URLs)
 *
 * Only the transport path changes. The APK bytes, catalog.json and the
 * certSha256 check in Installer.checkSignature() are left untouched, so a
 * hostile or broken mirror cannot turn into a silently installed package.
 *
 * Measured 2026-11-14 from a China network without a proxy:
 *   gh-proxy.com .... 200 on raw, 206 on release APK, ~174 KB/s for 1 MB
 *   ghproxy.net ..... 200 on raw, 206 on release APK,  ~96 KB/s for 1 MB
 *   cdn.jsdelivr.net / raw.gitmirror.com ... unreachable
 * Public proxies come and go, so keep more than one and ALWAYS put the
 * original URL last as a fallback. Never make a single mirror mandatory.
 */
object Mirror {
    private const val GITHUB = "https://github.com/"
    private const val RAW = "https://raw.githubusercontent.com/"

    private val proxies = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    private fun githubHosted(url: String) = url.startsWith(GITHUB) || url.startsWith(RAW)

    /** Ordered list to try: mirrors first (when enabled), original URL last. */
    fun candidates(url: String): List<String> =
        if (!Prefs.mirrorAuto || !githubHosted(url)) listOf(url)
        else proxies.map { it + url } + url
}
