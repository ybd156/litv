package com.litv.tv.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 央视频（YSP）取流客户端。
 *
 * 流程：本地算 cKey → GET bkliveinfo.ysp.cctv.cn → 解析 playurl / backurl_list。
 * 返回的是标准 m3u8 直链，可直接交给 Exo/VLC 播放。
 *
 * Python 端已实测：CCTV-1 / CCTV-13 均 iretcode=0，m3u8 200，分片 1.6~2MB / 94~160ms。
 */
object YspClient {

    /** 一条可播的直播线路。 */
    data class Line(val url: String, val label: String)

    /**
     * 央视频内置源的 id。
     *
     * 用负数避开服务端分配的源 id（它们从 1 起），这样客户端可以安全地把
     * 央视频频道混进同一份频道表，靠 [Channel.sourceId] 区分「这个台怎么起播」。
     */
    const val SOURCE_ID = -1000L

    /** 频道 id 偏移：央视频第 i 个频道的 Channel.id = SOURCE_ID - 1 - i。 */
    private const val ID_BASE = SOURCE_ID - 1

    /**
     * 频道分组名。
     *
     * 两个约束：
     * 1. 要短 —— 分组列约 220 权重宽，长名会被省略号截断成「央视频…」，三组看起来一样；
     * 2. 不能与自建源的分组重名 —— 「全部源」视图下 `group == selGroup` 会把两边的台混在一起，
     *    所以统一加「内置」前缀。
     */
    private fun groupOf(slug: String): String = when {
        slug.startsWith("cgtn") -> "内置CGTN"
        slug.endsWith("4k") || slug.endsWith("8k") -> "内置4K"
        else -> "内置央视"
    }

    /**
     * 把内置频道表转成 App 的 [com.litv.tv.data.Channel]，供频道列表直接使用。
     *
     * 注意：这里**不产生流地址**。地址在每次起播时由 [lines] 现算，
     * 所以不会出现过期签名被缓存住的问题。
     */
    fun toChannels(): List<com.litv.tv.data.Channel> =
        CHANNELS.mapIndexed { i, c ->
            com.litv.tv.data.Channel(
                id = ID_BASE - i,
                number = i + 1,
                name = c.name,
                group = groupOf(c.slug),
                logo = "",
                tvgId = "",
                epgId = "",
                enabled = true,
                sort = i,
                streamCount = 1,
                sourceId = SOURCE_ID,
            )
        }

    /** 按 Channel.id 反查内置频道；不是央视频的台返回 null。 */
    fun channelOf(id: Long): Channel? {
        if (id > ID_BASE) return null
        val idx = (ID_BASE - id).toInt()
        return CHANNELS.getOrNull(idx)
    }

    /** 某个 id 是否属于央视频内置源。 */
    fun isYspChannel(id: Long): Boolean = id <= ID_BASE

    /** 取流失败的原因（区分「风控」与「网络」，便于上层重试策略）。 */
    class YspException(val code: Int, val detail: String) :
        Exception("YSP 取流失败 code=$code $detail")

    private const val ENDPOINT = "https://bkliveinfo.ysp.cctv.cn/?"
    private const val UA = "qqlive"

    /** 一个央视频频道。sid 是 cnlid，pid 是 livepid。 */
    data class Channel(val slug: String, val name: String, val sid: String, val pid: String, val defn: String = "fhd")

    /**
     * 央视频公开频道表（63 路中的央视部分）。
     *
     * 这些 sid/pid 是央视频的频道标识，不是流地址——流地址每次现算。
     * 上游改版时这张表也要跟着改，所以集中放在这里。
     */
    val CHANNELS: List<Channel> = listOf(
        Channel("cctv1", "CCTV-1 综合", "2024078201", "600001859"),
        Channel("cctv2", "CCTV-2 财经", "2024075401", "600001800"),
        Channel("cctv3", "CCTV-3 综艺", "2024068501", "600001801"),
        Channel("cctv4", "CCTV-4 中文国际", "2029797101", "600001814"),
        Channel("cctv5", "CCTV-5 体育", "2024078401", "600001818"),
        Channel("cctv5p", "CCTV-5+ 体育赛事", "2024078001", "600001817"),
        Channel("cctv6", "CCTV-6 电影", "2013693901", "600108442"),
        Channel("cctv7", "CCTV-7 国防军事", "2024072001", "600004092"),
        Channel("cctv8", "CCTV-8 电视剧", "2029793001", "600001803"),
        Channel("cctv9", "CCTV-9 纪录", "2024078601", "600004078"),
        Channel("cctv10", "CCTV-10 科教", "2024078701", "600001805"),
        Channel("cctv11", "CCTV-11 戏曲", "2027248701", "600001806"),
        Channel("cctv12", "CCTV-12 社会与法", "2027248801", "600001807"),
        Channel("cctv13", "CCTV-13 新闻", "2029797201", "600001811"),
        Channel("cctv14", "CCTV-14 少儿", "2027248901", "600001809"),
        Channel("cctv15", "CCTV-15 音乐", "2027249001", "600001815"),
        Channel("cctv16", "CCTV-16 奥林匹克", "2027249101", "600098637"),
        Channel("cctv164k", "CCTV-16 4K", "2027249301", "600099502"),
        Channel("cctv17", "CCTV-17 农业农村", "2027249401", "600001810"),
        Channel("cctv4k", "CCTV-4K 超高清", "2029810301", "600002264"),
        Channel("cctv8k", "CCTV-8K 超高清", "2026774101", "600156816"),
        Channel("cgtn", "CGTN", "2024181701", "600014550"),
        Channel("cgtnfr", "CGTN 法语", "2024181801", "600084704"),
        Channel("cgtnru", "CGTN 俄语", "2024181901", "600084758"),
        Channel("cgtnar", "CGTN 阿拉伯语", "2024182001", "600084782"),
        Channel("cgtnes", "CGTN 西班牙语", "2024182101", "600084744"),
        Channel("cgtndoc", "CGTN 纪录", "2024182301", "600084781"),
    )

    /** h264 能力声明（原实现里是 base64 的 "H(30:1080,60:1080|30:1080,60:1080)"）。 */
    private val SPVCODE: String =
        android.util.Base64.encodeToString(
            "H(30:1080,60:1080|30:1080,60:1080)".toByteArray(), android.util.Base64.NO_WRAP
        )

    /**
     * 取某频道的可用线路。
     *
     * @throws YspException iretcode 非 0（常见 32=权限验证失败，说明常量或算法已过期）
     */
    fun lines(ch: Channel, timeoutMs: Int = 15000): List<Line> {
        val t = YspCrypto.ticket(ch.sid)
        val q = buildString {
            fun p(k: String, v: String) {
                if (isNotEmpty()) append('&')
                append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8"))
            }
            p("atime", "120"); p("livepid", ch.pid); p("cnlid", ch.sid)
            p("appVer", YspCrypto.APP_VER); p("app_version", "300090")
            p("caplv", "1"); p("cmd", "2"); p("defn", ch.defn)
            p("device", "iPhone"); p("encryptVer", "4.2"); p("getpreviewinfo", "0")
            p("hevclv", "0"); p("lang", "zh-Hans_CN"); p("livequeue", "0")
            p("logintype", "1"); p("nettype", "1"); p("newnettype", "1")
            p("newplatform", YspCrypto.PLATFORM.toString())
            p("platform", YspCrypto.PLATFORM.toString())
            p("sdtfrom", "v3021"); p("spacode", "23"); p("spaudio", "1")
            p("spdemuxer", "6"); p("spdrm", "2"); p("spdynamicrange", "1")
            p("spflv", "1"); p("spflvaudio", "1"); p("sphdrfps", "60"); p("sphttps", "1")
            p("spvcode", SPVCODE); p("spvideo", "4"); p("stream", "1"); p("system", "1")
            p("sysver", "ios18.2.1"); p("uhd_flag", "0")
            p("cKey", t.cKey); p("guid", t.guid); p("fntick", t.ts.toString())
            p("flowid", t.flowId); p("playbacktime", "0")
        }

        val body = httpGet(ENDPOINT + q, timeoutMs)
        if (com.litv.tv.BuildConfig.DEBUG) {
            // 只显示参数形状，cKey 打码（AGENTS.md：Release 不带详细 HTTP 日志）
            android.util.Log.d("YspClient", "params=" + q.replace(t.cKey, "<CKEY>"))
        }
        val json = JSONObject(body)
        val code = json.optInt("iretcode", -1)
        if (code != 0) throw YspException(code, json.optString("errinfo", ""))

        val out = LinkedHashSet<String>()
        json.optString("playurl").takeIf { it.isNotBlank() }?.let { out.add(it) }
        val bu = json.opt("backurl_list") ?: json.opt("backurlList") ?: json.opt("backurl")
        when (bu) {
            is org.json.JSONArray -> for (i in 0 until bu.length()) {
                val it = bu.opt(i)
                val u = when (it) {
                    is String -> it
                    is JSONObject -> it.optString("url").ifBlank { it.optString("playurl") }
                    else -> ""
                }
                if (u.isNotBlank()) out.add(u)
            }
            is String -> bu.split(';', ',').map { it.trim() }.filter { it.isNotBlank() }.forEach { out.add(it) }
        }

        return out
            .filter { it.contains(".cctv.") }
            .filterNot { it.contains("liverecord.video.cloud.cctv.com") } // 已知死 CDN
            .mapIndexed { i, u -> Line(u, "线路 ${i + 1}") }
    }

    /** 第一条可用线路（失败抛 [YspException]）。 */
    fun firstLine(ch: Channel): String = lines(ch).firstOrNull()?.url
        ?: throw YspException(-1, "没有任何可用线路")

    // ---------------------------------------------------------------- HTTP

    private fun httpGet(url: String, timeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw YspException(code, "HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
