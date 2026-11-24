package app.tvhub

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 更新索引：一张「应用 → 最新 Release」的小表，由 `.github/workflows/updates.yml`
 * 每天跑一次生成，客户端只读这一个文件。
 *
 * 为什么不在设备上直接问 GitHub API：
 *   匿名调用只有 60 次/小时，一个用户翻两屏就把额度烧光；api.github.com 在国内也不稳。
 *   索引文件 gzip 后几十 KB，一次拉全库，还带 ETag，没更新时 304 直接结束。
 *
 * 为什么索引里没有 versionCode：
 *   versionCode 必须下 APK 才能拿到，而判断「有没有新版」只要 tag。
 *   所以这里按 tag 的数字段比较；真正安装时 Android 会拿本地已装包比对，
 *   签名校验也走 PackageManager，不需要远端提供 certSha256。
 */
object UpdateIndex {
    data class Latest(val tag: String, val date: String, val apks: List<Apk>)

    /** 索引里的 `self` 段：OpenAPK TV 自己的最新 Release。apk 可能为空（发布时忘了传附件） */
    data class SelfRelease(val tag: String, val date: String, val apk: Apk?)

    /** id → 最新版本。只包含「和内置 catalog 版本不同」的条目，没有条 = 已是最新 */
    var latest by mutableStateOf<Map<String, Latest>>(emptyMap())
        private set

    /** 自己的最新版；null = 索引没带 self 段（旧格式 / 抓取失败） */
    var self by mutableStateOf<SelfRelease?>(null)
        private set

    /** 每次同步成功 +1。UI 的 remember 以它为 key，索引到了自动重算「可更新」 */
    var epoch by mutableIntStateOf(0)
        private set

    var checkedAt by mutableLongStateOf(0L)
        private set

    private lateinit var dir: File

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "updates").apply { mkdirs() }
    }

    private val dataFile get() = File(dir, "updates.json")
    private val etagFile get() = File(dir, "etag")

    /** 构建期配置的索引地址；空字符串 = 这个功能整体关闭 */
    private val url: String get() = BuildConfig.UPDATE_INDEX_URL.trim()

    val enabled: Boolean get() = url.isNotEmpty()

    /** 先读上次的缓存：冷启动就能显示「可更新」，不用等网络 */
    suspend fun loadCache() {
        if (!enabled || !dataFile.exists()) return
        withContext(Dispatchers.IO) {
            runCatching { parse(dataFile.readBytes()) }.getOrNull()?.let { apply(it) }
        }
    }

    private fun apply(p: Parsed) {
        latest = p.apps
        self = p.self
        epoch++
    }

    /**
     * 首帧之后再联网刷新。失败保持安静：电视端没有合适的报错位置，
     * 而且索引是「锦上添花」——拿不到就继续用上一次的结果 / 内置数据。
     */
    suspend fun refresh(ctx: Context, delayMs: Long = 2_500) {
        if (!enabled) return
        loadCache()
        delay(delayMs)
        withContext(Dispatchers.IO) {
            runCatching { sync() }.onFailure { /* 静默：下一轮再试 */ }
        }
    }

    private fun sync() {
        val etag = runCatching { etagFile.readText().trim() }.getOrNull().orEmpty()
        for (candidate in Mirror.candidates(url)) {
            val conn = (URL(candidate).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TVHub/${BuildConfig.VERSION_NAME} (Android TV)")
                setRequestProperty("Accept-Encoding", "identity")   // 直接拿 .gz 的字节，自己解压
                if (etag.isNotEmpty()) setRequestProperty("If-None-Match", etag)
            }
            try {
                if (conn.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) return
                if (conn.responseCode !in 200..299) continue
                val raw = conn.inputStream.use { it.readBytes() }
                val json = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
                    GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
                val parsed = parse(json)
                dataFile.writeBytes(json)
                conn.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { etagFile.writeText(it) }
                apply(parsed)
                checkedAt = System.currentTimeMillis()
                return
            } catch (_: Exception) {
                // 换下一个候选（镜像 / 原链）
            } finally {
                conn.disconnect()
            }
        }
    }

    private class Parsed(val apps: Map<String, Latest>, val self: SelfRelease?)

    private fun parse(bytes: ByteArray): Parsed {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val apps = root.optJSONObject("apps")
        val out = HashMap<String, Latest>(apps?.length() ?: 0)
        if (apps != null) {
            val keys = apps.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = apps.optJSONObject(id) ?: continue
                val tag = o.optString("tag").trim()
                if (tag.isEmpty()) continue
                val arr = o.optJSONArray("apks")
                val apks = buildList {
                    if (arr != null) for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        val u = a.optString("url")
                        if (u.isBlank()) continue
                        add(Apk(a.optString("name"), a.optString("abi"), u, a.optLong("size")))
                    }
                }
                out[id] = Latest(tag, o.optString("date"), apks)
            }
        }
        return Parsed(out, parseSelf(root))
    }

    /** `self` 段存的是远端最新版（可能就等于本机这版），要不要升级看 [selfNewer] */
    private fun parseSelf(root: JSONObject): SelfRelease? {
        val o = root.optJSONObject("self") ?: return null
        val tag = o.optString("tag").trim()
        if (tag.isEmpty()) return null
        val a = o.optJSONObject("apk")
        val apk = a?.let {
            val u = it.optString("url")
            if (u.isBlank()) null
            else Apk(it.optString("name"), it.optString("abi"), u, it.optLong("size"))
        }
        return SelfRelease(tag, o.optString("date"), apk)
    }

    /** OpenAPK TV 自己有没有新版：按 versionName 的数字段比（发版必须同时改 versionName） */
    fun selfNewer(): Boolean {
        val s = self ?: return false
        return newer(s.tag, BuildConfig.VERSION_NAME)
    }

    /** 这个应用有没有比内置版本更新的 Release */
    fun isNewer(a: App): Boolean {
        val l = latest[a.id] ?: return false
        return newer(l.tag, a.version)
    }

    /** 详情页要展示「更新到哪个版本」时用它 */
    fun latestTag(a: App): String? = latest[a.id]?.takeIf { newer(it.tag, a.version) }?.tag

    /** 把最新版本 / APK 列表叠到 App 上；没有更新时原样返回，调用方不用判空 */
    fun effective(a: App): App {
        val l = latest[a.id] ?: return a
        if (!newer(l.tag, a.version)) return a
        val date = l.date.ifBlank { a.date }
        return a.copy(
            version = l.tag,
            versionName = l.tag,
            date = date,
            apks = if (l.apks.isEmpty()) a.apks else l.apks,
            /* 版本脉络也跟着往前挪一格，否则详情页最上面那条还是打包时的旧版本 */
            releases = if (a.releases.any { it.tag == l.tag }) a.releases
            else listOf(Release(l.tag, date)) + a.releases,
        )
    }

    /**
     * 只比数字段：v1.18.2 / 1.18.2 / release-1.18.2 视为同一版；
     * `1.18.2-beta` 和 `1.18.2` 数字段相同，也算同一版（不催用户上 beta）。
     * 任一边没有数字（nightly、latest）时返回 false —— 宁可漏报，也不要天天误报。
     */
    private fun newer(remote: String, local: String): Boolean {
        val r = key(remote)
        val l = key(local)
        if (r.isEmpty() || l.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun key(tag: String): List<Int> {
        val head = tag.substringBefore('+')          // 丢掉 +build 元数据
        val out = ArrayList<Int>(4)
        var i = 0
        while (i < head.length && out.size < 4) {
            if (head[i].isDigit()) {
                var j = i
                while (j < head.length && head[j].isDigit()) j++
                out.add(head.substring(i, j).toIntOrNull() ?: 0)
                i = j
            } else i++
        }
        return out
    }
}
