package com.litv.tv.data

import android.content.Context
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 客户端自己下载并解析 xmltv 节目单。
 *
 * ## 为什么放客户端
 *
 * 服务端（edge）跑在 D1 免费额度上，**按行计费**。一份全量节目单几千行，
 * 每天刷一次就是几千行写入 —— 而节目单本质上是「客户端要看的参考数据」，
 * 不是「服务端要算的状态」。所以：
 *
 *   · 服务端只下发 xmltv 的地址（bootstrap 的 `epg_url`）
 *   · 客户端下载一次、解析一次、缓存在本地，之后切台查内存
 *   · 服务端 0 行写入、0 行读取
 *
 * 副作用是节目单永远是最新的：不用等重建，客户端自己决定多久刷一次。
 *
 * ## 体积
 *
 * 典型 xmltv 约 7 MB、3~4 万条节目。解析后常驻内存约 10 MB ——
 * 对电视盒子（1.5 GB 起）可以接受，但**不要**把它塞进 ViewPager 那种
 * 会被反复重建的组件里，这个类是进程级单例。
 *
 * ## 失败策略
 *
 * 任何一步失败都返回 null / 空列表，绝不抛给调用方：
 * 节目单是锦上添花，不能因为它让播放页崩掉。调用方拿到 null 时会回落到
 * 服务端接口（`/api/v1/tv/epg/{id}`），那条路在 [LITV_EPG_IN_D1] 模式下仍然有效。
 */
class XmltvEpg(private val context: Context) {

    data class Program(
        val start: Long,
        val end: Long,
        val title: String,
        val description: String,
        val category: String,
    )

    /** epg_id（= m3u 的 tvg-id / xmltv 的 channel id）→ 该频道的节目，按开始时间升序 */
    private val byChannel = HashMap<String, MutableList<Program>>()
    private var loaded = false
    private var loading = false
    private var loadedAtMs = 0L
    private var loadedFrom = ""

    /** 已解析到多少条节目（调试/统计用） */
    var programCount = 0
        private set

    fun isReady(): Boolean = loaded

    /**
     * 取某个频道的节目。返回 null 表示「还没准备好」，调用方该回落到服务端。
     * 返回空列表表示「确实没有这个频道的节目」。
     */
    fun programsFor(epgId: String): List<Program>? {
        if (!loaded) return null
        if (epgId.isBlank()) return emptyList()
        return byChannel[epgId] ?: emptyList()
    }

    /**
     * 确保数据就绪，然后回调（主线程之外调用）。
     *
     * @param url bootstrap 下发的 xmltv 地址
     * @param onReady 成功与否都会回调；失败时 loaded 仍是 false，调用方回落到服务端
     */
    /** 强制重新下载（跨天时调用）。 */
    fun forceRefresh(url: String, onReady: (Boolean) -> Unit) {
        synchronized(this) {
            loaded = false
            loadedFrom = ""
            loadedAtMs = 0
            cacheFile().delete()
        }
        ensure(url, onReady)
    }

    fun ensure(url: String, onReady: (Boolean) -> Unit) {
        if (url.isBlank()) {
            onReady(false)
            return
        }
        synchronized(this) {
            // 同一个地址、缓存未过期：直接复用
            if (loaded && loadedFrom == url && !expired()) {
                onReady(true)
                return
            }
            if (loading) {
                onReady(loaded)
                return
            }
            loading = true
        }

        Thread({
            val ok = try {
                val file = cacheFile()
                if (!file.exists() || file.length() < 1024 || expired()) {
                    download(url, file)
                }
                parse(file)
                loadedFrom = url
                loadedAtMs = System.currentTimeMillis()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "节目单解析失败：${t.javaClass.simpleName}")
                false
            }
            synchronized(this) { loading = false }
            onReady(ok)
        }, "litv-xmltv").start()
    }

    /**
     * 缓存是否过期。
     *
     * ★ 用**文件修改时间**判断，不是内存里的 loadedAtMs。
     * 原来只看内存时间戳：进程重启后 loadedAtMs 会被设成"现在"，
     * 于是磁盘上那份哪怕是一周前的 xmltv 也被当成刚下过，永远不刷新——
     * 电视盒子常年不关机、但会因升级/断电重启，这个坑一定会踩到。
     */
    private fun expired(): Boolean {
        val f = cacheFile()
        if (!f.exists()) return true
        val age = System.currentTimeMillis() - f.lastModified()
        return age > REFRESH_MS
    }

    private fun cacheFile(): File = File(context.filesDir, "epg.xml")

    private fun download(url: String, target: File) {
        val tmp = File(target.absolutePath + ".tmp")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
        } finally {
            conn.disconnect()
        }
        // 下完再原子改名：半截文件被当成缓存会导致解析出半份节目单
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun parse(file: File) {
        val map = HashMap<String, MutableList<Program>>()
        var count = 0
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(file.inputStream().buffered(64 * 1024), "UTF-8")
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val ch = parser.getAttributeValue(null, "channel") ?: ""
                val start = parseTime(parser.getAttributeValue(null, "start"))
                val stop = parseTime(parser.getAttributeValue(null, "stop"))

                // 进到 programme 内部，把它的子标签一次读完再出来。
                // 用 depth 收口：`nextText()` 自己会跳到对应的 END_TAG，
                // 但 title/desc/category 之外的子标签（episode-num 之类）要跳过。
                var title = ""
                var desc = ""
                var cat = ""
                val depth = parser.depth
                while (true) {
                    val ev = parser.next()
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    if (ev == XmlPullParser.END_TAG && parser.depth == depth) break
                    if (ev != XmlPullParser.START_TAG) continue
                    when (parser.name) {
                        "title" -> title = parser.nextText()
                        "desc" -> desc = parser.nextText()
                        "category" -> cat = parser.nextText()
                    }
                }

                if (ch.isNotBlank() && start > 0) {
                    // 没有 stop 的用 +30 分钟兜底，否则进度条会除以零
                    val end = if (stop > start) stop else start + 1800
                    map.getOrPut(ch) { mutableListOf() }.add(Program(start, end, title, desc, cat))
                    count++
                }
                event = parser.eventType
                continue
            }
            event = parser.next()
        }

        byChannel.clear()
        for ((k, v) in map) {
            v.sortBy { it.start }
            byChannel[k] = v
        }
        programCount = count
        loaded = byChannel.isNotEmpty()
        Log.i(TAG, "节目单就绪：${byChannel.size} 个频道 / $count 条")
    }

    /**
     * xmltv 时间：`20261112155000 +0800`（本地时间是带时区的墙上时间）。
     * 解析成 Unix 秒，客户端一律用秒比较。
     */
    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0
        val s = raw.trim()
        if (s.length < 14) return 0
        return try {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            (fmt.parse(s.substring(0, 14))?.time ?: 0L) / 1000
        } catch (t: Throwable) {
            0
        }
    }

    private companion object {
        const val TAG = "LITV"
        /** 缓存有效期：节目单一天一变，24 小时足够 */
        const val REFRESH_MS = 24 * 60 * 60 * 1000L
    }
}
