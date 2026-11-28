package app.tvhub

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.os.Build
import android.os.StatFs
import android.util.JsonReader
import android.util.JsonToken
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

data class Apk(val name: String, val abi: String, val url: String, val size: Long)
data class Release(val tag: String, val date: String)

data class AppArt(val banner: String?, val feature: String?, val icon: String?, val shots: List<String>) {
    val wall: String? get() = shots.firstOrNull() ?: feature ?: banner
    val tile: String? get() = banner ?: feature ?: shots.firstOrNull()
    val gallery: List<String> get() = (shots + listOfNotNull(feature, banner)).distinct()
}

data class App(
    val id: String,
    val repo: String,
    val name: String,
    val pkg: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    /** 上游原始标签（18 个，英文），用于「同房间」这类细粒度场景 */
    val room: String,
    /** 一级分类 key（12 个，ASCII），分类页磁贴用它分组；显示名走 groupName() */
    val group: String,
    /** 二级分类 key（房间页 chips 用），默认等于上游 room；显示名走 roomName() */
    val sub: String,
    val note: String,
    val desc: String,
    val maker: String,
    val stars: Int,
    val licence: String?,
    val archived: Boolean,
    val streams: Boolean,
    val version: String,
    val date: String,
    val certSha256: String?,
    val art: AppArt,
    val apks: List<Apk>,
    val releases: List<Release>,
    val forkOf: String?,
    val parentStars: Int,
) {
    val runsHere: Boolean get() = (minSdk ?: 0) <= Build.VERSION.SDK_INT
}

object Catalog {
    var apps by mutableStateOf<List<App>>(emptyList())
        private set
    var generated: String = ""
        private set

    /** 加载进度 0..1，Splash 用来画进度条（按已读字节估算） */
    var progress by mutableStateOf(0f)
        private set

    private var idIndex: Map<String, App> = emptyMap()
    private var repoIndex: Map<String, App> = emptyMap()
    private var forkIndex: Map<String, List<App>> = emptyMap()

    /**
     * 流式解析 catalog.json：JsonReader 边读边建 App，
     * 不再先 readText() 成 3.3MB 字符串再建整棵 JSONObject 树（峰值内存约为原来的 1/3，低端电视明显更快）。
     */
    suspend fun load(ctx: Context) {
        if (apps.isNotEmpty()) return
        /* 先试二进制缓存：命中时跳过 JSON 解析，二次启动近乎秒开 */
        val key = cacheKey(ctx)
        val cached = withContext(Dispatchers.IO) { BinCache.read(ctx, key) }
        if (cached != null) {
            generated = cached.first
            publish(cached.second)
            return
        }
        val parsed = withContext(Dispatchers.Default) {
            val total = runCatching { ctx.assets.openFd("catalog.json").use { it.length } }.getOrDefault(-1L)
            val counting = CountingStream(ctx.assets.open("catalog.json", android.content.res.AssetManager.ACCESS_STREAMING))
            val out = ArrayList<App>(3000)
            JsonReader(InputStreamReader(BufferedInputStream(counting, 64 * 1024), Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "generated" -> generated = r.nextString()
                    "apps" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            out += readApp(r)
                            if (total > 0 && out.size % 100 == 0) {
                                val f = (counting.count.toFloat() / total).coerceIn(0f, 1f)
                                withContext(Dispatchers.Main) { progress = f }
                            }
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
                r.endObject()
            }
            out
        }
        publish(parsed)
        /* 缓存写盘放后台，不挡首屏；失败只是下次再解析一遍 */
        val gen = generated
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { BinCache.write(ctx, key, gen, parsed) }
    }

    /** APK 更新（assets 可能换了）或格式升级时缓存自动失效 */
    private fun cacheKey(ctx: Context): String {
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val size = runCatching { ctx.assets.openFd("catalog.json").use { it.length } }.getOrDefault(-1L)
        return "v${BinCache.FORMAT}|${pi?.lastUpdateTime}|${pi?.longVersionCode}|$size"
    }

    private fun publish(parsed: List<App>) {
        /* 与原 firstOrNull 语义一致：重复键保留第一条 */
        idIndex = HashMap<String, App>(parsed.size * 2).also { m -> parsed.forEach { m.putIfAbsent(it.id, it) } }
        repoIndex = HashMap<String, App>(parsed.size * 2).also { m -> parsed.forEach { m.putIfAbsent(it.repo, it) } }
        forkIndex = parsed.filter { it.forkOf != null }.groupBy { it.forkOf!! }
        progress = 1f
        apps = parsed
    }

    private class CountingStream(inner: InputStream) : FilterInputStream(inner) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private fun JsonReader.strOrNull(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    private fun JsonReader.intOrNull(): Int? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextInt()

    private fun JsonReader.strings(): List<String> {
        if (peek() == JsonToken.NULL) { nextNull(); return emptyList() }
        val l = ArrayList<String>(4); beginArray(); while (hasNext()) strOrNull()?.let { l += it }; endArray(); return l
    }

    private fun readApp(r: JsonReader): App {
        var id = ""; var repo = ""; var name = ""; var pkg = ""; var vc = 0L; var vn: String? = null
        var minSdk: Int? = null; var room = ""; var group = ""; var sub = ""; var note = ""; var desc = ""
        var maker = ""; var stars = 0; var licence: String? = null; var archived = false
        var version = ""; var date = ""; var cert: String? = null; var forkOf: String? = null; var parentStars = 0
        var topics: List<String> = emptyList()
        var art = AppArt(null, null, null, emptyList())
        val apks = ArrayList<Apk>(4); val releases = ArrayList<Release>(4)
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "id" -> id = r.nextString()
            "repo" -> repo = r.nextString()
            "name" -> name = r.nextString()
            "package" -> pkg = r.nextString()
            "versionCode" -> vc = r.nextLong()
            "versionName" -> vn = r.strOrNull()
            "minSdk" -> minSdk = r.intOrNull()
            "room" -> room = r.nextString()
            "group" -> group = r.strOrNull().orEmpty()
            "sub" -> sub = r.strOrNull().orEmpty()
            "note" -> note = r.strOrNull().orEmpty()
            "desc" -> desc = r.strOrNull().orEmpty()
            "topics" -> topics = r.strings()
            "maker" -> maker = r.nextString()
            "stars" -> stars = r.nextInt()
            "licence" -> licence = r.strOrNull()
            "archived" -> archived = if (r.peek() == JsonToken.NULL) { r.nextNull(); false } else r.nextBoolean()
            "version" -> version = r.nextString()
            "date" -> date = r.nextString()
            "certSha256" -> cert = r.strOrNull()
            "forkOf" -> forkOf = r.strOrNull()
            "parentStars" -> parentStars = r.intOrNull() ?: 0
            "art" -> art = readArt(r)
            "apks" -> { r.beginArray(); while (r.hasNext()) apks += readApk(r); r.endArray() }
            "releases" -> { r.beginArray(); while (r.hasNext()) releases += readRelease(r); r.endArray() }
            else -> r.skipValue()
        }
        r.endObject()
        return App(
            id = id, repo = repo, name = name, pkg = pkg, versionCode = vc, versionName = vn, minSdk = minSdk,
            room = room,
            /* 数据里已按 12 组重新分配；万一拿到没有 group 的旧文件，按 room 现算一份兜底 */
            group = group.ifBlank { groupOfRoom(room) },
            /* 二级 key：旧文件没有这个字段时退回 room */
            sub = sub.ifBlank { room },
            note = note, desc = desc, maker = maker, stars = stars, licence = licence, archived = archived,
            streams = STREAMS.containsMatchIn("$name $note $desc $repo $pkg ${topics.joinToString(" ")}"),
            version = version, date = date, certSha256 = cert, art = art, apks = apks, releases = releases,
            forkOf = forkOf, parentStars = parentStars,
        )
    }

    private fun readArt(r: JsonReader): AppArt {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return AppArt(null, null, null, emptyList()) }
        var banner: String? = null; var feature: String? = null; var icon: String? = null; var shots: List<String> = emptyList()
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "banner" -> banner = r.strOrNull()
            "feature" -> feature = r.strOrNull()
            "icon" -> icon = r.strOrNull()
            "shots" -> shots = r.strings()
            else -> r.skipValue()
        }
        r.endObject()
        return AppArt(banner, feature, icon, shots)
    }

    private fun readApk(r: JsonReader): Apk {
        var name = ""; var abi = ""; var url = ""; var size = 0L
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "name" -> name = r.nextString(); "abi" -> abi = r.nextString()
            "url" -> url = r.nextString(); "size" -> size = r.nextLong()
            else -> r.skipValue()
        }
        r.endObject()
        return Apk(name, abi, url, size)
    }

    private fun readRelease(r: JsonReader): Release {
        var tag = ""; var date = ""
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "tag" -> tag = r.nextString(); "date" -> date = r.nextString()
            else -> r.skipValue()
        }
        r.endObject()
        return Release(tag, date)
    }

    const val MIN_STARS = 10

    private val STREAMS = Regex("iptv|\\bm3u8?\\b|xtream|debrid|torbox|torrserv|stremio|cloudstream|live ?tv\\b|电视直播|直播源|tvbox", RegexOption.IGNORE_CASE)

    fun shown(a: App) = !(Prefs.hideStreams && a.streams)

    val visible: List<App> get() = apps.filter { (it.stars >= MIN_STARS || it.parentStars >= MIN_STARS) && shown(it) }

    fun forksOf(a: App): List<App> = forkIndex[a.repo].orEmpty()

    fun byRepo(repo: String): App? = repoIndex[repo]

    fun sibling(a: App, i: Installed): App? =
        apps.firstOrNull { it.pkg == a.pkg && it.id != a.id && it.certSha256 != null && it.certSha256 in i.certs }

    fun byId(id: String): App? = idIndex[id]
}

/**
 * 目录的二进制缓存：filesDir/catalog.bin。
 * 格式：魔数 + key + generated + 条数 + 每条 App 的定长字段序列（字符串 = int 字节长度 + UTF-8，-1 表示 null）。
 * 任何读错（版本不符、截断、损坏）都返回 null，调用方回落到 JSON 解析并重写缓存。
 * 改了 App / AppArt / Apk / Release 的字段 → 必须把 FORMAT +1。
 */
private object BinCache {
    const val FORMAT = 1
    private const val MAGIC = 0x54564842 // "TVHB"
    private fun file(ctx: Context) = File(ctx.filesDir, "catalog.bin")

    fun read(ctx: Context, key: String): Pair<String, List<App>>? = runCatching {
        val f = file(ctx)
        if (!f.isFile) return null
        DataInputStream(BufferedInputStream(f.inputStream(), 128 * 1024)).use { d ->
            if (d.readInt() != MAGIC) return null
            if (d.s() != key) return null
            val gen = d.s().orEmpty()
            val n = d.readInt()
            val out = ArrayList<App>(n)
            repeat(n) { out += d.app() }
            gen to out
        }
    }.getOrElse { file(ctx).delete(); null }

    fun write(ctx: Context, key: String, gen: String, apps: List<App>) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "catalog.bin.tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 128 * 1024)).use { d ->
                d.writeInt(MAGIC); d.s(key); d.s(gen); d.writeInt(apps.size)
                apps.forEach { d.app(it) }
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { tmp.delete() }
    }

    private fun DataOutputStream.s(v: String?) {
        if (v == null) { writeInt(-1); return }
        val b = v.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b)
    }
    private fun DataInputStream.s(): String? {
        val n = readInt(); if (n < 0) return null
        val b = ByteArray(n); readFully(b); return String(b, Charsets.UTF_8)
    }
    private fun DataOutputStream.ss(l: List<String>) { writeInt(l.size); l.forEach { s(it) } }
    private fun DataInputStream.ss(): List<String> { val n = readInt(); return List(n) { s().orEmpty() } }

    private fun DataOutputStream.app(a: App) {
        s(a.id); s(a.repo); s(a.name); s(a.pkg); writeLong(a.versionCode); s(a.versionName)
        writeInt(a.minSdk ?: Int.MIN_VALUE)
        s(a.room); s(a.group); s(a.sub); s(a.note); s(a.desc); s(a.maker); writeInt(a.stars); s(a.licence)
        writeBoolean(a.archived); writeBoolean(a.streams); s(a.version); s(a.date); s(a.certSha256)
        s(a.art.banner); s(a.art.feature); s(a.art.icon); ss(a.art.shots)
        writeInt(a.apks.size); a.apks.forEach { s(it.name); s(it.abi); s(it.url); writeLong(it.size) }
        writeInt(a.releases.size); a.releases.forEach { s(it.tag); s(it.date) }
        s(a.forkOf); writeInt(a.parentStars)
    }

    private fun DataInputStream.app(): App {
        val id = s()!!; val repo = s()!!; val name = s()!!; val pkg = s()!!; val vc = readLong(); val vn = s()
        val min = readInt().let { if (it == Int.MIN_VALUE) null else it }
        val room = s()!!; val group = s()!!; val sub = s()!!; val note = s()!!; val desc = s()!!; val maker = s()!!
        val stars = readInt(); val licence = s()
        val archived = readBoolean(); val streams = readBoolean(); val version = s()!!; val date = s()!!; val cert = s()
        val art = AppArt(s(), s(), s(), ss())
        val apks = List(readInt()) { Apk(s()!!, s()!!, s()!!, readLong()) }
        val rels = List(readInt()) { Release(s()!!, s()!!) }
        val forkOf = s(); val ps = readInt()
        return App(id, repo, name, pkg, vc, vn, min, room, group, sub, note, desc, maker, stars, licence,
            archived, streams, version, date, cert, art, apks, rels, forkOf, ps)
    }
}

/**
 * 首页主推位的数据源。
 *
 * 现在只有「热门榜」一种来源：可见 → 有横幅图 → 能在本机装 → 未归档 → 图取得到，
 * 再按「星标 × 新鲜度」排序，一个房间最多占一个位，最后按当天的种子打散。
 *
 * 以后加「精选」时，只要在 [featured] 里读 assets/featured.json 返回人工顺序，
 * [playlist] 会自动优先用它，位子不够再回落到热门榜。
 */
object Hero {
    private const val SLOTS = 6

    fun playlist(): List<App> = featured().ifEmpty { hot() }

    /** 同一天里顺序固定（重启、切语言都不跳），跨天才换一批 */
    private fun seed(): Int = (LocalDate.now().toEpochDay() and 0x7FFFFFFFL).toInt()

    /** 起点：同一天接着上次的下一张，跨天从第一张开始 */
    fun start(size: Int): Int {
        if (size <= 0) return 0
        if (Prefs.heroDay != LocalDate.now().toEpochDay()) return 0
        return Math.floorMod(Prefs.heroCursor + 1, size)
    }

    /** 预留：精选位。加策划表后在这里读 assets/featured.json 并按人工顺序解析 */
    private fun featured(): List<App> = emptyList()

    private fun hot(): List<App> {
        val pool = Catalog.visible.filter { a ->
            val b = a.art.banner
            b != null && a.runsHere && !a.archived && !Images.knownBad(b)
        }
        val ranked = pool.sortedWith(
            compareByDescending<App> { weight(it) }.thenByDescending { it.stars }
        )
        val out = ArrayList<App>(SLOTS)
        val rooms = HashSet<String>(SLOTS)
        for (a in ranked) {
            if (out.size >= SLOTS) break
            if (rooms.add(a.group)) out += a
        }
        /* 房间不够 6 个时放宽：同房间可以补位，别让主推位空着 */
        if (out.size < SLOTS) for (a in ranked) {
            if (out.size >= SLOTS) break
            if (out.none { it.id == a.id }) out += a
        }
        return out.shuffled(Random(seed()))
    }

    /** 近期有发布的往上抬，避免主推常年挂着不更新的项目 */
    private fun weight(a: App): Double {
        val days = runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(a.date.take(10)), LocalDate.now())
        }.getOrDefault(9_999L)
        val boost = when {
            days in 0..7 -> 1.4
            days in 8..30 -> 1.2
            else -> 1.0
        }
        return a.stars * boost
    }
}

fun pickApk(app: App): Apk {
    for (abi in Build.SUPPORTED_ABIS) app.apks.firstOrNull { it.abi == abi }?.let { return it }
    return app.apks.firstOrNull { it.abi == "universal" }
        ?: app.apks.firstOrNull { it.abi == "any" }
        ?: app.apks.firstOrNull()
        ?: Apk(name = app.name, abi = "any", url = "", size = 0L)
}

object L {
    /** 当前语言下的 Resources。语言一换就整个重建，所以别在别处缓存它 */
    lateinit var res: Resources
        private set

    /**
     * 语言版本号。str() 会读它，于是所有在组合里调用 str() 的地方都会在切语言时失效重绘。
     * 只在 [applyLang] 里自增，绝不要放到组合过程中。
     */
    var rev by mutableIntStateOf(0)
        private set

    private lateinit var app: Context
    private var systemLocale: Locale = Locale.getDefault()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // 进程刚起来时的 getDefault() 就是系统语言，留作「跟随系统」的还原值
        systemLocale = Locale.getDefault()
        apply()
    }

    /** 设置页切语言后调用：重建 Resources、复位 Locale.getDefault()，再自增 rev 通知重绘 */
    fun applyLang() = apply()

    private fun apply() {
        val loc = Langs.locale(Prefs.lang) ?: systemLocale
        Locale.setDefault(loc) // Fmt 里的日期/数字格式化也跟着走
        val cfg = Configuration(app.resources.configuration)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        res = app.createConfigurationContext(cfg).resources
        rev++
    }
}

/**
 * 支持的语言表。下标 0 固定是「跟随系统」，其余就是 [Prefs.lang] 能取到的 1..n。
 * 加一门语言：这里追加一行（BCP-47 tag + 对应的 Locale + 母语名），再补一份
 * values-xx/strings.xml，设置页的语言选择会自动多一项，UI 不用改。
 */
data class Lang(val tag: String, val locale: Locale?, val endonym: String)

object Langs {
    val all: List<Lang> = listOf(
        Lang("system", null, ""),
        Lang("zh-CN", Locale.SIMPLIFIED_CHINESE, "简体中文"),
        Lang("en", Locale.ENGLISH, "English"),
    )

    fun locale(i: Int): Locale? = all.getOrNull(i)?.locale

    /** 列表里显示的名字：「跟随系统」跟着当前语言翻译，其余用母语名，任何界面语言下都认得出 */
    fun label(i: Int): String =
        all.getOrNull(i)?.takeIf { it.locale != null }?.endonym ?: str(R.string.lang_system)
}

fun str(id: Int, vararg args: Any): String {
    L.rev // 订阅语言版本号，切语言时整棵文案重算
    return L.res.getString(id, *args)
}

fun strArray(id: Int): List<String> {
    L.rev
    return L.res.getStringArray(id).toList()
}

fun plural(id: Int, n: Int, vararg args: Any): String {
    L.rev
    return L.res.getQuantityString(id, n, *args)
}

private val roomNames = mapOf(
    "Launchers" to R.string.room_launchers, "Screensavers" to R.string.room_screensavers, "VPN & network" to R.string.room_vpn,
    "Streaming" to R.string.room_streaming, "App stores" to R.string.room_stores, "Games" to R.string.room_games,
    "Music" to R.string.room_music, "Video" to R.string.room_video, "Smart home" to R.string.room_home,
    "Video players" to R.string.room_local_players,
    "Photos" to R.string.room_photos, "Reading" to R.string.room_reading, "Learning" to R.string.room_learning,
    "Chat & social" to R.string.room_chat, "AI" to R.string.room_ai, "Browsers" to R.string.room_browsers,
    "Files & downloads" to R.string.room_files, "Tools" to R.string.room_tools, "Other" to R.string.room_other,
)

fun roomName(room: String) = roomNames[room]?.let { str(it) } ?: room

/* ---------------- 一级分类 ----------------
   key 用 ASCII，跟 catalog.json 的 group 字段一一对应；显示名只存在于字符串资源里，
   所以以后加语言只要加一套 values-xx，数据、代码都不用动。 */
val GROUP_KEYS = listOf(
    "video", "photos", "music", "games", "reading", "social",
    "browser", "home", "desktop", "stores", "ai", "system",
)

/** 兜底容器，不做一级磁贴，单独一条入口排在分类页底部 */
const val GROUP_OTHER = "other"

private val groupNames = mapOf(
    "video" to R.string.group_video, "photos" to R.string.group_photos,
    "music" to R.string.group_music, "games" to R.string.group_games,
    "reading" to R.string.group_reading, "social" to R.string.group_social,
    "browser" to R.string.group_browser, "home" to R.string.group_home,
    "desktop" to R.string.group_desktop, "stores" to R.string.group_stores,
    "ai" to R.string.group_ai, "system" to R.string.group_system,
    GROUP_OTHER to R.string.group_other,
)

fun groupName(key: String) = groupNames[key]?.let { str(it) } ?: key

/** 旧数据没有 group 字段时按 room 现算，规则与 tools/reclassify.py 保持一致 */
internal fun groupOfRoom(room: String): String = when (room) {
    "Streaming", "Video" -> "video"
    "Photos" -> "photos"
    "Music" -> "music"
    "Games" -> "games"
    "Reading", "Learning" -> "reading"
    "Chat & social" -> "social"
    "Browsers" -> "browser"
    "Smart home" -> "home"
    "Launchers", "Screensavers" -> "desktop"
    "App stores" -> "stores"
    "AI" -> "ai"
    "Tools", "Files & downloads", "VPN & network" -> "system"
    else -> GROUP_OTHER
}

fun abiLabel(abi: String): String = when (abi) {
    "arm64-v8a" -> str(R.string.abi_arm64)
    "armeabi-v7a" -> str(R.string.abi_arm32)
    "x86_64" -> str(R.string.abi_x86_64)
    "x86" -> "x86"
    else -> abi
}

private val androidNames = mapOf(
    21 to "5", 22 to "5.1", 23 to "6", 24 to "7", 25 to "7.1", 26 to "8", 27 to "8.1", 28 to "9",
    29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16",
)

fun androidName(sdk: Int) = "Android ${androidNames[sdk] ?: "API $sdk"}"

object Fmt {
    private fun fmt(pattern: Int, d: String) =
        LocalDate.parse(d.take(10)).format(DateTimeFormatter.ofPattern(str(pattern), Locale.getDefault()))
    fun date(d: String) = fmt(R.string.fmt_date, d)
    fun shortDate(d: String) = fmt(R.string.fmt_short_date, d)
    fun monthYear(d: String) = fmt(R.string.fmt_month_year, d)
    fun mb(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    fun gb(bytes: Long): String = String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
    fun gbNum(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / 1_000_000_000.0)
    fun count(n: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)
    fun stars(n: Int): String = if (n >= 1000) String.format(Locale.getDefault(), "%.1fk", n / 1000.0).replace(Regex("[.,]0k$"), "k") else "$n"
}

object Images {
    private val memory = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val locks = HashMap<String, Mutex>()
    private lateinit var dir: File

    /* UI 正在等网络的请求数。预热协程据此让路：用户翻页时先紧着当前这一屏，
       别让后台预取把代理的带宽和连接数占满 */
    private val inflight = java.util.concurrent.atomic.AtomicInteger(0)
    val busy: Int get() = inflight.get()

    /* 取不到的图（镜像全挂 / 代理返回错误页）。主推位据此把这一项踢掉，别让纯色块占着轮播位 */
    private val bad = HashSet<String>()

    /** 坏图集合变动时 +1，给主推列表当 remember key 用 */
    var epoch by mutableIntStateOf(0)
        private set

    fun knownBad(url: String): Boolean = bad.contains(url)

    private fun mark(url: String, failed: Boolean) {
        synchronized(bad) {
            val changed = if (failed) bad.add(url) else bad.remove(url)
            if (changed) epoch++
        }
    }

    fun init(ctx: Context) { dir = File(ctx.cacheDir, "img").apply { mkdirs() } }

    fun cached(url: String, maxWidth: Int): ImageBitmap? = memory.get("$maxWidth|$url")

    suspend fun load(url: String, maxWidth: Int): ImageBitmap? {
        val key = "$maxWidth|$url"
        memory.get(key)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(url) { Mutex() } }
        val bmp = lock.withLock {
            memory.get(key) ?: withContext(Dispatchers.IO) {
                inflight.incrementAndGet()
                try {
                    runCatching {
                        val file = File(dir, hash(url))
                        // A cached file is keyed by the ORIGINAL url, so switching
                        // download sources never invalidates the image cache.
                        val bitmap = if (file.exists()) decode(file, maxWidth) else download(url, file, maxWidth)
                        bitmap?.also { memory.put(key, it) }
                    }.getOrNull()
                } finally {
                    inflight.decrementAndGet()
                }
            }
        }
        mark(url, failed = bmp == null)
        return bmp
    }

    /** 图已经在磁盘上（预热过 / 之前打开过），UI 只需要本地解码 */
    fun onDisk(url: String): Boolean = runCatching { File(dir, hash(url)).exists() }.getOrDefault(false)

    fun bytesOnDisk(url: String): Long = runCatching { File(dir, hash(url)).length() }.getOrDefault(0L)

    /**
     * 预热：只把图落到磁盘，**不解码**。
     *
     * 解码后的位图留在 [memory] 里会挤掉用户正在看的这一屏，而本地解码本身只要几十毫秒，
     * 所以预热做到「文件在盘上」就够了。取不到时**不**标 bad——预热失败多半是临时网络抖动，
     * 标了就会把这张图永久降级成文字，让真正去取的那次再判定。
     */
    suspend fun warm(url: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(dir, hash(url))
        if (file.exists()) return@withContext true
        val lock = synchronized(locks) { locks.getOrPut(url) { Mutex() } }
        var ok = false
        lock.withLock {
            ok = file.exists()
            if (!ok) {
                /* 预热用短超时：catalog 里总有取不到的图，别让一个坏 URL 把整条通道堵上十几秒 */
                for (candidate in Mirror.candidates(url)) {
                    if (!fetch(candidate, file, connectMs = 4_000, readMs = 8_000)) continue
                    if (probe(file)) { ok = true; break }
                    file.delete()
                }
                if (!ok) File(file.path + ".part").delete()
            }
        }
        ok
    }

    /** Try each candidate until one yields an actual decodable image. */
    private fun download(url: String, file: File, maxWidth: Int): ImageBitmap? {
        for (candidate in Mirror.candidates(url)) {
            if (!fetch(candidate, file)) continue
            decode(file, maxWidth)?.let { return it }
            // 200 OK but not an image (proxy error page): discard and try the next source.
            file.delete()
        }
        File(file.path + ".part").delete()
        return null
    }

    /** Returns false on any failure so the next candidate can take over. */
    private fun fetch(
        url: String,
        file: File,
        connectMs: Int = 10_000,
        readMs: Int = 20_000,
    ): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectMs
            readTimeout = readMs
            setRequestProperty("User-Agent", "TVHub/${BuildConfig.VERSION_NAME} (Android TV)")
        }
        try {
            if (conn.responseCode !in 200..299) {
                false
            } else {
                val part = File(file.path + ".part")
                conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                part.renameTo(file)
            }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    /** 只读图片头里的尺寸，用来判断「代理返回的 200 是不是一张真图」，不占内存 */
    private fun probe(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun decode(file: File, maxWidth: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

data class Installed(val versionCode: Long, val versionName: String?, val certs: Set<String>)

object Device {
    var tick by mutableIntStateOf(0)

    private var snapshotTick = -1
    private var snapshot: Map<String, Installed> = emptyMap()

    fun all(ctx: Context): Map<String, Installed> {
        if (snapshotTick != tick) {
            snapshot = ctx.packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
                .associate { it.packageName to Installed(it.longVersionCode, it.versionName, certs(it)) }
            snapshotTick = tick
        }
        return snapshot
    }

    fun installed(ctx: Context, pkg: String): Installed? = all(ctx)[pkg]

    fun installedAs(ctx: Context, a: App): Installed? = installed(ctx, a.pkg)?.takeIf { i ->
        a.certSha256 == null || i.certs.isEmpty() || a.certSha256 in i.certs || Catalog.sibling(a, i) == null
    }

    fun archiveCerts(ctx: Context, file: File): Set<String> {
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val viaSigning = pi?.let { certs(it) }.orEmpty()
        if (viaSigning.isNotEmpty()) return viaSigning
        @Suppress("DEPRECATION")
        val old = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        return old?.signatures?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
    }

    private fun certs(pi: PackageInfo): Set<String> {
        val si = pi.signingInfo ?: return emptySet()
        val all = (si.apkContentsSigners.orEmpty().toList() + si.signingCertificateHistory.orEmpty().toList())
        return all.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun freeBytes(ctx: Context): Long = StatFs(ctx.filesDir.path).availableBytes
    fun totalBytes(ctx: Context): Long = StatFs(ctx.filesDir.path).totalBytes

    fun cacheDir(ctx: Context) = File(ctx.cacheDir, "apks")
    fun cacheBytes(ctx: Context): Long = cacheDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L
    fun clearCache(ctx: Context) { cacheDir(ctx).listFiles()?.forEach { it.delete() } }
}

object Prefs {
    val accents = listOf(
        Triple(R.string.accent_sage, 0xFF3F6F55, 0xFF7DB592),
        Triple(R.string.accent_red, 0xFFC23B2E, 0xFFE8735F),
        Triple(R.string.accent_ochre, 0xFF9A6412, 0xFFE0A84A),
        Triple(R.string.accent_plum, 0xFF5B4B8A, 0xFFA597E0),
        Triple(R.string.accent_none, 0xFF8E8E93, 0xFFAEAEB2),
    )
    var accent by mutableIntStateOf(0)
        private set

    var hideStreams by mutableStateOf(true)
        private set

    fun toggleStreams() {
        hideStreams = !hideStreams
        file.edit().putBoolean("hideStreams", hideStreams).apply()
    }

    /* ---------- 下载源：0 自动 / 1 直连 GitHub / 2 国内镜像（见 Mirror） ---------- */
    const val SOURCE_AUTO = 0
    const val SOURCE_GITHUB = 1
    const val SOURCE_MIRROR = 2

    /** 默认「自动」：非中文系统直连 GitHub，简中系统才走国内镜像 */
    var source by mutableIntStateOf(SOURCE_AUTO)
        private set

    /** 系统语言刚切成简体中文、而当前并没走镜像时置真，由 UI 弹一次询问框 */
    var askMirror by mutableStateOf(false)

    fun useSource(i: Int) {
        val next = i.coerceIn(SOURCE_AUTO, SOURCE_MIRROR)
        if (next == source) return
        source = next
        file.edit().putInt("source", next).apply()
        askMirror = false
    }

    /**
     * 系统语言变更检测，只在 `onCreate` 调一次。
     *
     * manifest 的 `configChanges` 不含 `locale`，所以系统语言一改 Activity 必然重建 —— 不需要
     * 常驻监听，重建路径上比一次「上次见到的语言」就够了。只问「刚切成简中」那一趟：
     * 首次安装没有上一条记录（不弹）；已经走镜像时也不弹（自动档对简中已经生效，手动选了镜像更不必问）。
     */
    fun noteSystemLanguage() {
        val now = Mirror.systemLocale().toLanguageTag()
        val last = file.getString("sysLang", null)
        if (now != last) file.edit().putString("sysLang", now).apply()
        if (last == null || last == now) return
        val wasZh = runCatching { Mirror.isSimplifiedChinese(Locale.forLanguageTag(last)) }.getOrDefault(false)
        if (!wasZh && Mirror.systemIsSimplifiedChinese() && !Mirror.usingMirror()) askMirror = true
    }

    val themes = listOf(R.string.theme_match, R.string.theme_light, R.string.theme_dark)
    var theme by mutableIntStateOf(0)
        private set

    /** 界面语言下标，取值见 [Langs.all]：0 跟随系统，1..n 是具体语言 */
    var lang by mutableIntStateOf(0)
        private set

    fun useLang(i: Int) {
        val next = i.coerceIn(Langs.all.indices)
        if (next == lang) return
        lang = next
        file.edit().putInt("lang", lang).apply()
        L.applyLang()
    }

    var systemDark by mutableStateOf(false)
    val dark: Boolean get() = when (theme) { 1 -> false; 2 -> true; else -> systemDark }
    private lateinit var file: android.content.SharedPreferences

    fun init(ctx: Context) {
        file = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        accent = file.getInt("accent", 0).coerceIn(accents.indices)
        theme = file.getInt("theme", 0).coerceIn(themes.indices)
        lang = file.getInt("lang", 0).coerceIn(Langs.all.indices)
        hideStreams = file.getBoolean("hideStreams", true)
        /* 旧版的 mirrorAuto 布尔迁到三档：当时手动关掉的算「直连」，其余交给「自动」按语言重判 */
        source = when {
            file.contains("source") -> file.getInt("source", SOURCE_AUTO)
            file.getBoolean("mirrorAuto", true) -> SOURCE_AUTO
            else -> SOURCE_GITHUB
        }.coerceIn(SOURCE_AUTO, SOURCE_MIRROR)
        heroCursor = file.getInt("heroCursor", 0)
        heroDay = file.getLong("heroDay", 0L)
    }

    fun nextTheme() {
        theme = (theme + 1) % themes.size
        file.edit().putInt("theme", theme).apply()
    }

    /** 设置页的分段控件直接指定深浅色（1 = 浅色，2 = 深色） */
    fun useTheme(i: Int) {
        theme = i.coerceIn(themes.indices)
        file.edit().putInt("theme", theme).apply()
    }

    fun nextAccent() {
        accent = (accent + 1) % accents.size
        file.edit().putInt("accent", accent).apply()
    }

    /* ---------- 首页主推位的游标：电视盒子一天开关机好几次，每次都从第 1 张开始的话
       后几张永远没有曝光 ---------- */
    var heroCursor by mutableIntStateOf(0)
        private set
    var heroDay by mutableStateOf(0L)
        private set

    fun useHero(i: Int) {
        heroCursor = i
        heroDay = LocalDate.now().toEpochDay()
        file.edit().putInt("heroCursor", heroCursor).putLong("heroDay", heroDay).apply()
    }
}

/**
 * 图片预热。
 *
 * 慢的地方不在渲染而在网络：一张 GitHub raw 的图经代理单次要 300–800ms，进一个分类页要同时取
 * 十几张，于是有 2–4 秒的空白。这里在目录读完后按「最可能先看到的顺序」把图先落到磁盘，
 * 真正显示时只差一次本地解码（几十毫秒）。
 *
 * 三条约束：
 * - **顺序**：主推位 → 首页热门/最近 → 各分类首屏，越可能先看到越靠前；
 * - **让路**：[Images.busy] > 0 说明用户正在等当前这一屏，预热暂停，不抢带宽和连接数；
 * - **预算**：单次启动最多下 [BUDGET] 字节，超了就停，剩下的等真要用时再取。
 *
 * 只写磁盘不解码（见 [Images.warm]），所以不会把内存缓存里用户正在看的图挤掉。
 */
object Warm {
    private const val BUDGET = 48L * 1024 * 1024

    /** 5 条通道：代理单张就要 1–3 秒，3 条太慢；再多会挤到 UI 的请求（靠 [Images.busy] 让路兜底） */
    private const val LANES = 5

    /** 分类页一屏 6×3；首页每个分类栏只露 4 张，但用户会往下滚，按一屏备着 */
    private const val SCREEN = 18

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { drain(queue()) }
    }

    /** 预热队列：按「先看到的先下」排，同一张图只排一次（磁盘缓存只按 url 存，与宽度无关） */
    private fun queue(): List<String> {
        val pool = Catalog.visible
        if (pool.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()

        /* 卡片主图：有横幅就横幅（显示的就是它），没有才退到图标 */
        fun card(a: App) {
            (a.art.banner ?: a.art.icon)?.let { out += it }
        }

        /* 首页的横幅和图标都要：横幅取不到（或纯色假图）时卡片会退到 NameCard，那时要图标 */
        fun tile(a: App) {
            a.art.banner?.let { out += it }
            a.art.icon?.let { out += it }
        }

        val hero = Hero.playlist()
        hero.forEach { tile(it) }
        val heroIds = hero.map { it.id }.toSet()
        /* 与 HomeShelf 里的 hot / recent 保持同一套排序，否则预热的是另一批 */
        pool.filter { it.id !in heroIds }.sortedByDescending { it.stars }.take(4).forEach { tile(it) }
        pool.sortedByDescending { it.date }.take(5).forEach { tile(it) }

        /* 分类页：按一级分类预热（other 垫底）。
           分两批铺：先把每个分类的首行（6 张）都备好，这样不管点哪个分类，第一行立刻有图；
           再回头补后两行。按分类整块下的话，排在后面的分类要等好几分钟才轮到。 */
        val groups = pool.groupBy { it.group }
            .entries
            .sortedWith(compareBy({ it.key == GROUP_OTHER }, { -it.value.size }))
            .map { (_, all) -> all.sortedByDescending { it.stars }.take(SCREEN) }

        groups.forEach { g -> g.take(6).forEach { card(it) } }
        groups.forEach { g -> g.drop(6).forEach { card(it) } }

        return out.toList()
    }

    private suspend fun drain(urls: List<String>) {
        if (urls.isEmpty()) return
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val spent = java.util.concurrent.atomic.AtomicLong(0L)
        coroutineScope {
            repeat(LANES) {
                launch {
                    while (true) {
                        /* 用户正在翻页：先让当前这一屏取完 */
                        while (Images.busy > 0) delay(260)
                        if (spent.get() >= BUDGET) break
                        val i = next.getAndIncrement()
                        if (i >= urls.size) break
                        val url = urls[i]
                        if (Images.onDisk(url)) continue
                        if (Images.warm(url)) spent.addAndGet(Images.bytesOnDisk(url))
                        /* 每张之间留缝，避免把代理的连接池打满 */
                        delay(60)
                    }
                }
            }
        }
    }
}
