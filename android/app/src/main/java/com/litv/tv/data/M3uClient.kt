package com.litv.tv.data

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 客户端自己解析频道。
 *
 * 服务端只下发「源 m3u 地址 + 显示规则」的**加密信封**（绑定本设备、60 秒有效），
 * 客户端解开后自己拉 m3u、解析、按规则过滤，并把结果连同版本号缓存到磁盘。
 * 版本没变就不重下 —— 服务端换源时 bump 了 channels_version，这才触发更新。
 *
 * 解析规则必须与 edge/scripts/build.mjs 一致：dedupeKey = id:tvgId 或 name:规范化名。
 * 否则同一个台在管理台和客户端是两个 stable_key，屏蔽/改名就对不上。
 */
object M3uClient {

    private const val TAG = "M3uClient"

    /** 缓存格式版本：解析逻辑改了（如补 epgId）就 +1，强制重解析一次 */
    private const val CACHE_FORMAT = 5

    /** 临时诊断：打印前几条没取到 tvg-id 的 EXTINF 原文 */
    private var dbgLines = 0
    private var dbgBuild = 0

    data class Parsed(
        val version: String,
        val channels: List<Channel>,
        /** 频道 id → 直连地址（客户端模式下不再走 /tv/play） */
        val urlById: Map<Long, String>,
        /** 频道 id → 播放需要的头（UA / Referer） */
        val headersById: Map<Long, Map<String, String>>,
    )

    /** 内存副本：播放时按 id 取直连地址 */
    @Volatile
    var current: Parsed? = null
        private set

    fun urlOf(channelId: Long): String? = current?.urlById?.get(channelId)

    fun headersOf(channelId: Long): Map<String, String> =
        current?.headersById?.get(channelId) ?: emptyMap()

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "channels_client.json")

    /** 把服务端下发的信封解成明文 JSON（v=0 时本来就是明文）。 */
    fun decrypt(env: JSONObject, dlKeyB64: String): JSONObject? {
        if (env.optInt("v", 0) != 1) return env
        val iv = Base64.decode(env.getString("iv"), Base64.NO_WRAP)
        val enc = Base64.decode(env.getString("enc"), Base64.NO_WRAP)
        val key = Base64.decode(dlKeyB64, Base64.NO_WRAP)
        if (key.size != 32 || iv.size != 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return JSONObject(String(cipher.doFinal(enc), Charsets.UTF_8))
    }

    /** 读磁盘缓存；没有/损坏返回 null。 */
    fun readCache(ctx: Context): Parsed? = runCatching {
        val f = cacheFile(ctx)
        if (!f.exists()) return null
        val root = JSONObject(f.readText())
        // 缓存格式变了就丢弃：老版本写的缓存里 epgId 是空的，
        // 而频道版本号没变 → 会一直复用那份数据，节目单永远匹配不上。
        if (root.optInt("v", 0) != CACHE_FORMAT) return null
        val arr = root.optJSONArray("channels") ?: return null
        val list = ArrayList<Channel>(arr.length())
        val urlById = HashMap<Long, String>()
        val headersById = HashMap<Long, Map<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            list.add(
                Channel(
                    id = id,
                    number = o.optInt("n"),
                    name = o.optString("name"),
                    group = o.optString("g", "未分组"),
                    logo = o.optString("logo"),
                    tvgId = o.optString("tvg"),
                    epgId = o.optString("epg").ifBlank { o.optString("tvg") },
                    enabled = true,
                ),
            )
            val url = o.optString("url")
            if (url.isNotEmpty()) urlById[id] = url
            val h = o.optJSONObject("h")
            if (h != null) {
                val m = HashMap<String, String>()
                for (k in h.keys()) m[k] = h.optString(k)
                headersById[id] = m
            }
        }
        Log.i(TAG, "读缓存：${list.size} 个，epgId 非空 ${list.count { it.epgId.isNotBlank() }} 个")
        if (list.isEmpty()) null
        else Parsed(root.optString("version", "0"), list, urlById, headersById)
    }.getOrNull()

    /** 写磁盘缓存。 */
    fun writeCache(ctx: Context, p: Parsed) {
        runCatching {
            val arr = JSONArray()
            for (ch in p.channels) {
                val o = JSONObject()
                o.put("id", ch.id)
                o.put("n", ch.number)
                o.put("name", ch.name)
                o.put("g", ch.group)
                o.put("logo", ch.logo)
                o.put("tvg", ch.tvgId)
                o.put("epg", ch.epgId.ifBlank { ch.tvgId })
                o.put("url", p.urlById[ch.id] ?: "")
                val h = p.headersById[ch.id]
                if (h != null) o.put("h", JSONObject(h as Map<*, *>))
                arr.put(o)
            }
            val root = JSONObject()
            root.put("version", p.version)
            root.put("v", CACHE_FORMAT)
            root.put("channels", arr)
            cacheFile(ctx).writeText(root.toString())
            Log.i(TAG, "写缓存：${p.channels.size} 个，其中 epgId 非空 ${p.channels.count { it.epgId.isNotBlank() }} 个")
        }.onFailure { Log.w(TAG, "写缓存失败: ${it.javaClass.simpleName}") }
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 加载频道：版本一致直接用磁盘缓存，否则重新拉源解析。
     * 任何一步失败都回调 null，由调用方回落到服务端下发的频道。
     */
    @Volatile
    private var loading = false

    /** 补 epgId 时要用到节目单的 名称→id 映射 */
    private var ctxRef: Context? = null
    private var epgRef: XmltvEpg? = null

    fun load(
        ctx: Context,
        api: Api,
        dlKey: String,
        epg: XmltvEpg? = null,
        onDone: (Parsed?) -> Unit,
    ) {
        this.ctxRef = ctx.applicationContext
        this.epgRef = epg
        // 幂等：bootstrap 可能被调用两次（缓存 + 网络、或界面重建），
        // 不去重就会重复下载 m3u、并且回调打到已经销毁的 Activity 上。
        if (loading) {
            Log.i(TAG, "已在加载中，跳过重复请求")
            current?.let { onDone(it) }
            return
        }
        loading = true

        // ★ 先用磁盘缓存顶上：起播不该等网络。
        //   以前是"先问服务端要信封 → 再拉 m3u"，冷启动凭空多几秒。
        //   缓存在就立刻可用，网络那次只做版本校验与更新。
        readCache(ctx)?.let { cached ->
            current = cached
            onDone(cached)
        }

        api.channelSrc(
            onOk = { env ->
                Thread {
                    val result = runCatching { build(ctx, api, env, dlKey) }.getOrNull()
                    if (result == null) Log.w(TAG, "客户端解析失败，回落服务端频道")
                    else Log.i(TAG, "客户端频道就绪：${result.channels.size} 个 / 版本 ${result.version}")
                    loading = false
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(result) }
                }.start()
            },
            onErr = { msg ->
                loading = false
                Log.w(TAG, "channel-src 失败（$msg），回落服务端频道")
                onDone(null)
            },
        )
    }

    /** 后台线程里跑：解密 → 版本比对 → 拉源 → 解析 → 过滤 → 缓存。 */
    private fun build(ctx: Context, api: Api, env: JSONObject, dlKey: String): Parsed? {
        val payload = decrypt(env, dlKey) ?: return readCache(ctx)
        val version = payload.optString("version", "0")

        readCache(ctx)?.let { cached ->
            if (cached.version == version) return adopt(cached)
        }

        val urls = payload.optJSONArray("urls") ?: return null
        val policy = payload.optJSONObject("policy")
        val hiddenGroups = policy?.optJSONArray("groups_hidden").strList()
        val hiddenChannels = policy?.optJSONArray("channels_hidden").strList()

        val merged = LinkedHashMap<String, Channel>()
        val urlById = HashMap<Long, String>()
        val headersById = HashMap<Long, Map<String, String>>()

        for (i in 0 until urls.length()) {
            val u = urls.optJSONObject(i) ?: continue
            val text = api.fetchText(u.optString("url"), u.optString("ua"), u.optString("referer"))
            if (text.isNullOrBlank()) {
                Log.w(TAG, "源拉取失败：" + u.optString("name"))
                continue
            }
            mergeInto(
                text, merged, urlById, headersById,
                defaultUa = u.optString("ua"),
                defaultReferer = u.optString("referer"),
            )
        }

        val kept = merged.values.filter { it.group !in hiddenGroups && it.name !in hiddenChannels }
        if (kept.isEmpty()) return readCache(ctx)

        val keepIds = kept.map { it.id }.toSet()
        urlById.keys.retainAll(keepIds)
        headersById.keys.retainAll(keepIds)

        // 没有 tvg-id 的频道：用显示名去 xmltv 的 <display-name> 里找回 epg_id。
        // 服务端入库时就是这么对的（build.mjs），客户端跳过后节目单会全空。
        val epg = epgRef
        val fixed = if (epg == null) kept else kept.map { ch ->
            if (ch.epgId.isNotBlank()) ch
            else {
                val id = epg.idByName(ch.name)
                if (id.isBlank()) ch else ch.copy(epgId = id)
            }
        }
        Log.i(TAG, "epgId 补齐：" + fixed.count { it.epgId.isNotBlank() } + "/" + fixed.size)

        val parsed = Parsed(version, fixed, urlById, headersById)
        writeCache(ctx, parsed)
        return adopt(parsed)
    }

    // ---------------------------------------------------------------- m3u 解析

    /** 与 build.mjs 的 normalizeName 等价（不用正则，避免多层转义被吃掉）。 */
    private fun normalizeName(name: String): String {
        val sb = StringBuilder()
        for (ch in name.lowercase()) {
            val c = ch.code
            if (c <= 32 || c == 92 || c == 47 || c == 124 || c == 183) continue
            if (ch == '-' || ch == '_' || ch == '.') continue
            sb.append(ch)
        }
        return sb.toString()
    }

    /** #EXTINF 的属性段与名字之间用逗号分隔，但属性值里也可能有逗号。 */
    private fun commaOutsideQuotes(line: String): Int {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return i
        }
        return -1
    }

    private val ATTR_RE = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    private fun attr(head: String, key: String): String {
        for (m in ATTR_RE.findAll(head)) {
            if (m.groupValues[1].lowercase() == key) return m.groupValues[2]
        }
        return ""
    }

    private fun JSONArray?.strList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) if (!isNull(i)) out.add(optString(i))
        return out
    }

    /** 把一个源的文件内容合并进总表；同一频道的备用线路只保留第一条。 */
    fun mergeInto(
        text: String,
        merged: MutableMap<String, Channel>,
        urlById: MutableMap<Long, String>,
        headersById: MutableMap<Long, Map<String, String>>,
        defaultUa: String = "",
        defaultReferer: String = "",
    ) {
        var name = ""
        var tvgId = ""
        var logo = ""
        var group = ""
        var ua = ""
        var referer = ""
        var pending = false

        // lines() 按任意换行符切分，省掉转义
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#EXTM3U")) continue
            if (line.startsWith("#EXTINF")) {
                val comma = commaOutsideQuotes(line)
                val head = if (comma > 0) line.substring(0, comma) else line
                name = if (comma > 0) line.substring(comma + 1).trim() else ""
                tvgId = attr(head, "tvg-id")
                if (tvgId.isEmpty() && dbgLines < 3) {
                    dbgLines++
                    Log.w(TAG, "EXTINF 没取到 tvg-id，原文=" + head.take(160))
                }
                logo = attr(head, "tvg-logo")
                group = attr(head, "group-title")
                ua = ""
                referer = ""
                pending = true
                continue
            }
            if (line.startsWith("#EXTGRP:")) {
                if (pending && group.isEmpty()) group = line.removePrefix("#EXTGRP:").trim()
                continue
            }
            if (line.startsWith("#EXTVLCOPT:")) {
                val opt = line.removePrefix("#EXTVLCOPT:")
                val eq = opt.indexOf('=')
                if (pending && eq > 0) {
                    val k = opt.substring(0, eq).trim().lowercase()
                    val v = opt.substring(eq + 1).trim()
                    if (k == "http-user-agent") ua = v
                    else if (k == "http-referrer" || k == "http-referer") referer = v
                }
                continue
            }
            if (line.startsWith("#")) continue
            if (!pending) continue

            val key = if (tvgId.isNotEmpty()) "id:$tvgId" else "name:" + normalizeName(name)
            val id = key.hashCode().toLong() and 0x7FFFFFFFL
            if (!merged.containsKey(key)) {
                merged[key] = Channel(
                    id = id,
                    number = merged.size + 1,
                    name = name,
                    group = group.ifEmpty { "未分组" },
                    logo = logo,
                    tvgId = tvgId,
                    epgId = tvgId,
                    enabled = true,
                )
                if (dbgBuild < 3) {
                    dbgBuild++
                    Log.i(TAG, "构造 tvgId='${merged[key]?.tvgId}' epgId='${merged[key]?.epgId}' name='$name'")
                }
                urlById[id] = line
                // 频道自己没写 #EXTVLCOPT 时，用**源级别**的 UA/Referer 兜底。
                // 源的 UA 配在管理台的 sources 里，只用在"拉 m3u"上一次是不够的：
                // 播放同样要带，否则源站校验不通过 —— 用户看到的就是"手动选台 UA 没了"。
                val effUa = ua.ifEmpty { defaultUa }
                val effReferer = referer.ifEmpty { defaultReferer }
                if (effUa.isNotEmpty() || effReferer.isNotEmpty()) {
                    val h = HashMap<String, String>()
                    if (effUa.isNotEmpty()) h["User-Agent"] = effUa
                    if (effReferer.isNotEmpty()) h["Referer"] = effReferer
                    headersById[id] = h
                }
            }
            pending = false
        }
    }

    /** 记到内存（播放时按 id 取地址）。 */
    fun adopt(p: Parsed): Parsed {
        current = p
        return p
    }
}
