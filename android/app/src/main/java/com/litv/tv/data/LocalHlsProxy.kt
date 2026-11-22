package com.litv.tv.data

import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 极简本地 HLS 代理，只服务内置源（央视频）。
 *
 * 为什么需要它（2026-11-21 实测）：
 * 该 CDN 对播放器发出的请求一律 403（ExoPlayer 的自定义/极简数据源、VLC 的 ffmpeg 都试过），
 * 但**同一个 App 进程内**用 `HttpURLConnection` 拉 m3u8 是 200、拉分片是 200（2135KB/个）。
 * 所以把取流这件事收回到 App 自己手里：播放器只连 127.0.0.1，
 * 真实请求由这里用 `HttpURLConnection` 代发。
 *
 * 工作方式：
 *   /ch/{channelId}.m3u8  → 现场向央视频取流，把 playlist 里的分片地址改写成
 *                           /seg/{base64url(原始地址)}，返回给播放器
 *   /seg/{base64url}      → 解出原始分片地址，代拉字节并原样转发
 *
 * playlist 缓存 15 秒：直播 m3u8 每 5 秒左右推进一次，缓存太短会疯狂取流，
 * 太长会让播放器拿到过期分片（分片地址约 30 秒失效）。
 */
object LocalHlsProxy {

    private const val TAG = "HlsProxy"

    /** 央视 CDN 认这个 UA（和直播接口一致）。 */
    private const val UA_PROXY = "qqlive"
    /**
     * playlist 缓存时长。
     *
     * ★ 不能设长（真机事故 2026-11-21）：直播 m3u8 每 5 秒左右推进一次，
     * 播放器按 `#EXT-X-MEDIA-SEQUENCE` 判断有没有新分片。缓存 15 秒时，
     * 播放器每 8 秒来问一次，两次都拿到同一份 sequence → 它认为没有新内容
     * → 不再请求分片 → 16 秒后 `PlayerManager` 判定卡死、反复自动恢复。
     * 实际表现就是「播一会儿一卡一卡」。
     *
     * 取一次流约 400ms（bkliveinfo + playlist），播放器 8 秒才问一次，够用。
     */
    private const val M3U8_TTL_MS = 4_000L

    /** 线路（playurl）缓存：央视的 playurl 约 30~40 秒失效，这里取 25 秒保守值。
     *  m3u8 **内容**仍然每次都拉（保证 MEDIA-SEQUENCE 推进），只是不再每次重取线路。 */
    private const val LINE_TTL_MS = 15_000L

    private data class CachedM3u8(val text: String, val at: Long, val realBase: String)

    /** 线路地址缓存：bkliveinfo 不能每 2 秒调一次，会被限流。 */
    private data class CachedLine(val url: String, val at: Long)

    private val cache = ConcurrentHashMap<Long, CachedM3u8>()
    private val lineCache = ConcurrentHashMap<Long, CachedLine>()
    private val pool = Executors.newFixedThreadPool(6)

    /**
     * 全代理共用**一个** OkHttpClient —— 这是分片流畅的前提。
     *
     * 原来每个请求都 `URL(url).openConnection()` 新建 HttpURLConnection，
     * 等于每个 2~3MB 的分片都要重做一次 TCP + TLS 握手。手机 CPU 快看不出来，
     * 电视（Amlogi 盒子 + 移动宽带）上光握手就吃掉大半时间，缓冲一欠就重缓冲。
     *
     * 依据：ysp-live 的文档把「连接复用」写成了分片流畅的前提（YSP_KEEPALIVE）。
     * OkHttp 自带连接池 + keep-alive，复用同一个 client 就能做到。
     */
    private val httpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            // ★ 2026-11-22 回退：30 秒是错的。
            //   起播总预算只有 36 秒（PlayerManager 的 START_BUDGET_MS），
            //   一次请求占掉 30 秒，就等于只给了一次机会、而且必然超预算 ——
            //   真机实测「起播总预算 36000ms 用尽，放弃」连出两次，完全起不来。
            //   宁可 20 秒超时让播放器早点重试，也不要一次耗死整个预算。
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 连接池：默认 5 个空闲连接 / 5 分钟，这里放宽一点，够 27 个台 + 主备线路
            .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }

    /** Application context：Cronet 建 Engine 需要。 */
    @Volatile private var appCtx: android.content.Context? = null

    /** 必须在起播前调一次（PlayerActivity/PhoneHomeActivity 里）。 */
    fun attach(ctx: android.content.Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var port = 0
    @Volatile private var running = false

    /** 启动代理（幂等）。返回监听端口；失败返回 0。 */
    fun ensureStarted(): Int {
        if (running && port > 0) return port
        synchronized(this) {
            if (running && port > 0) return port
            return runCatching {
                val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                port = ss.localPort
                running = true
                Thread({
                    while (running) {
                        val s = runCatching { ss.accept() }.getOrNull() ?: break
                        pool.execute { handle(s) }
                    }
                }, "hls-proxy-accept").apply { isDaemon = true }.start()
                Log.i(TAG, "本地 HLS 代理已启动，端口 $port")
                port
            }.getOrElse { e ->
                Log.w(TAG, "启动失败: ${e.message}")
                0
            }
        }
    }

    /** 某个频道给播放器用的地址；代理起不来时返回 null，调用方回落到直连。 */
    fun urlFor(channelId: Long): String? {
        val p = ensureStarted()
        if (p <= 0) return null
        return "http://127.0.0.1:$p/ch/$channelId.m3u8"
    }

    /**
     * 回看地址。`startSec`/`endSec` 是 Unix 秒。
     *
     * 央视频回看的 playlist 是**按窗口**给的（实测一次只回 5 个分片 ≈ 20 秒，
     * 不管 endtime 给多大）。要连续播放，只能靠播放器定期重拉 playlist、
     * 我们在每次重拉时把窗口往前推一格（见 serveReplay 里的游标）。
     */
    fun replayUrlFor(channelId: Long, startSec: Long, endSec: Long): String? {
        val p = ensureStarted()
        if (p <= 0) return null
        return "http://127.0.0.1:$p/rp/$channelId/$startSec/$endSec.m3u8"
    }

    /** 回看游标：每个 (频道, 起播时刻) 记"已经发到哪一窗"。 */
    private data class ReplayCursor(var nextStart: Long, var issued: Int)

    private val replayCursors = ConcurrentHashMap<String, ReplayCursor>()

    /**
     * 该换线路了吗。
     *
     * 央视频一次返回两条线路（`playurl` + `backurl_list[0]`）。之前永远只用第一条，
     * 一旦这条线在某个出口 IP 上被 CDN 拒（分片 403），重新取流拿回来的还是同一条线
     * 的分片地址，于是 403 → 502 → 播放器重试 5 次 → **黑屏**。
     * 真机日志里这种「IO_BAD_HTTP 502」出现了 112 次，全部集中在播内置源的时段。
     *
     * 这里在分片 403 时置位，下次取流就换下一条线路试。
     * 同一时刻只播一个台，所以一个全局标记够用。
     */
    @Volatile private var needRotateLine = false

    /**
     * 连续 403 达到阈值时的回调（参数：频道 id）。
     *
     * 用途：央视频分片 CDN 会成片拒绝某些出口 IP（实测电视走广东移动时 100% 403，
     * 而同一套代码在电信线路上分片 200/1.8MB）。客户端改不了这个限制，
     * 但可以**自动降级到自建源的同频道**——用户点的是「CCTV-1」，
     * 走哪条链路是他的事，画面不能断。
     *
     * 在 UI 层注册；没注册时行为与之前一致。
     */
    @Volatile var onTooMany403: ((Long) -> Unit)? = null

    /** 当前正在服务的频道 id（分片 URL 里没有频道号，只能按「最近取过清单的那个台」算）。 */
    @Volatile private var curChannelId: Long = 0L

    /** 连续 403 计数，成功拉到一个分片就清零。 */
    private val fail403 = java.util.concurrent.atomic.AtomicInteger(0)

    /** 当前用第几条线路（0 起）。 */
    @Volatile private var lineIndex = 0

    /**
     * 最近几个分片的字节缓存（LRU）。
     *
     * 为什么需要：分片 URL 带 `svrtime` 签名、时效很短。而 playurl 每 15 秒换一次，
     * 新 playlist 的分片地址和旧的完全不同 —— 播放器缓冲里那些**旧地址**再去拉就 403，
     * 每 403 一次画面就停一下（真机 120 秒里出现 2 次）。
     *
     * 这里把最近拉过的分片留一份，命中就直接给，不再打 CDN，403 自然消失。
     * 只留 3 个：一个分片 2~3MB，3 个约 6~8MB；够覆盖播放器的重试窗口，又不会撑爆内存。
     */
    private val segCache = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > 8
    }

    /** /rp/{id}/{start}/{end}.m3u8 —— 代取回看 playlist 并改写分片地址。 */
    private fun serveReplay(path: String, out: OutputStream) {
        val parts = path.removePrefix("/rp/").substringBefore(".m3u8").split('/')
        if (parts.size < 3) {
            writeStatus(out, 400, "bad replay path")
            return
        }
        val id = parts[0].toLongOrNull()
        val startSec = parts[1].toLongOrNull()
        val endSec = parts[2].toLongOrNull()
        if (id == null || startSec == null || endSec == null) {
            writeStatus(out, 400, "bad replay args")
            return
        }
        val ch = YspClient.channelOf(id)
        if (ch == null) {
            writeStatus(out, 404, "unknown channel")
            return
        }
        val span = (endSec - startSec).coerceAtLeast(300L)
        val key = "$id/$startSec"
        val cur = replayCursors.getOrPut(key) { ReplayCursor(startSec, 0) }
        // 第二次以后的请求才推进窗口；第一次一定要从用户点的那档节目开头起播
        val from = if (cur.issued == 0) startSec else cur.nextStart

        var realUrl: String? = null
        for ((i, wait) in longArrayOf(0L, 300L, 800L).withIndex()) {
            if (wait > 0) Thread.sleep(wait)
            realUrl = runCatching { YspClient.firstReplayLine(ch, from, from + span) }.getOrNull()
            if (!realUrl.isNullOrBlank()) break
            Log.w(TAG, "回看取流第 ${i + 1} 次失败（ch=$id from=$from）")
        }
        if (realUrl.isNullOrBlank()) {
            Log.w(TAG, "回看取流彻底失败 ch=$id from=$from")
            writeStatus(out, 502, "replay-failed")
            return
        }
        val text = try {
            fetchTextSmart(realUrl!!)
        } catch (e: Throwable) {
            Log.w(TAG, "回看 playlist 拉取失败 from=$from：" + e.javaClass.simpleName + ": " + e.message)
            writeStatus(out, 502, "replay-playlist-failed")
            return
        }

        val base = runCatching { java.net.URI(realUrl!!) }.getOrNull()
        val rewritten = buildString {
            for (raw in text!!.lineSequence()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> append('\n')
                    line.startsWith("#") -> append(line).append('\n')
                    else -> {
                        val abs = if (line.startsWith("http")) line
                        else base?.resolve(line)?.toString() ?: line
                        val enc = Base64.encodeToString(
                            abs.toByteArray(Charsets.UTF_8),
                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                        )
                        append("/seg/").append(enc).append('\n')
                    }
                }
            }
        }
        val windowSec = runCatching {
            Regex("#EXTINF:([0-9.]+)").findAll(text!!).sumOf { it.groupValues[1].toDouble() }.toLong()
        }.getOrDefault(20L).coerceAtLeast(5L)

        cur.nextStart = from + windowSec
        cur.issued += 1
        Log.i(TAG, "回看 ch=$id from=$from window=${windowSec}s 第${cur.issued}次")
        writeBody(out, rewritten.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
        cache.clear()
    }

    // ---------------------------------------------------------------- 处理

    private fun handle(client: Socket) {
        runCatching {
            client.use { c ->
                c.soTimeout = 20000
                val input = c.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return@use
                // 读请求头并记下 UA/Referer —— 通用代理（自建源）要原样带给源站，
                // 很多源站按 UA 返回不同清单甚至 403。
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    when {
                        lower.startsWith("user-agent:") ->
                            reqUa = line.substringAfter(':').trim().ifBlank { "qqlive" }
                        lower.startsWith("referer:") ->
                            reqReferer = line.substringAfter(':').trim()
                    }
                }
                val path = requestLine.split(' ').getOrNull(1) ?: return@use
                Log.i(TAG, "REQ " + requestLine.take(80))
                val out = BufferedOutputStream(c.getOutputStream())
                when {
                    path.startsWith("/ch/") -> servePlaylist(path, out)
                    path.startsWith("/rp/") -> serveReplay(path, out)
                    path.startsWith("/seg/") -> serveSegment(path, out)
                    // 通用 HLS 代理：自建源也走本地，才能预取分片 + 缓存 + 复用连接。
                    path.startsWith("/raw/") -> serveRaw(path, out)
                    path.startsWith("/rawseg/") -> serveRawSeg(path, out)
                    else -> writeStatus(out, 404, "not found")
                }
                runCatching { out.flush() }
            }
        }.onFailure { e -> Log.w(TAG, "请求处理失败: ${e.message}") }
    }

    // ---------------------------------------------------------------- 通用 HLS 代理（自建源）

    /**
     * 为什么自建源也要走代理：
     *
     * 实测（2026-11-22，PC 并发测 65 个源）：mursor 源站拉一个 1~2KB 的 m3u8
     * **中位要 3.1 秒**，最慢 10 秒。电视上更差（同一个 cctv1-MCP 在 PC 1.36 秒、
     * 电视起播 15 秒）。上游改不了，客户端唯一能做的就是：
     *   · 拿到 m3u8 后**立刻后台预取前几个分片**，播放器来要时直接命中
     *   · 分片留一份缓存，重连/重缓冲时不必再打源站
     *   · 所有请求共用 OkHttp 连接池（少一次 TLS 握手）
     *
     * 只处理 HLS（m3u8）；其它格式调用方回退直连。
     */
    @Volatile private var reqUa: String = "qqlive"
    @Volatile private var reqReferer: String? = null

    private val rawCache = ConcurrentHashMap<String, CachedM3u8>()
    private val rawSegCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ByteArray>) = size > 12
    }

    private fun b64enc(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64dec(s: String): String? = runCatching {
        String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }.getOrNull()

    /** 对外暴露：把任意 m3u8 地址包装成本地代理地址。 */
    fun rawUrlFor(m3u8Url: String): String? {
        val port = ensureStarted()
        if (port <= 0) return null
        return "http://127.0.0.1:$port/raw/" + b64enc(m3u8Url) + ".m3u8"
    }

    private fun serveRaw(path: String, out: OutputStream) {
        val enc = path.removePrefix("/raw/").substringBefore('?').substringBeforeLast('.')
        val realUrl = b64dec(enc)
        if (realUrl.isNullOrBlank()) {
            writeStatus(out, 400, "bad raw url")
            return
        }
        // 清单缓存 5 秒：源站慢的时候，播放器 2~4 秒一次的刷新不该每次都打源站。
        val cached = rawCache[realUrl]
        if (cached != null && System.currentTimeMillis() - cached.at < 5_000L) {
            writeBody(out, cached.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
            return
        }
        // ★ 超时必须给足：实测源站拉一个 1~2KB 的 m3u8 **中位 3.1 秒、p90 4.5 秒、最慢 10 秒**，
        //   而 open() 的默认超时是 3 秒（那是为内置源的 2KB 清单定的）。
        //   用默认值的结果就是真机日志里成片的「加载失败 502，第 1/5…第 5/5 次重试」。
        val text = runCatching { fetchText(realUrl, reqUa, timeoutMs = 10_000) }.getOrNull()
        if (text.isNullOrBlank()) {
            // 源站这一次没给：有旧清单就先用旧的顶着，播放器不至于立刻断
            if (cached != null) {
                writeBody(out, cached.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
            } else {
                writeStatus(out, 502, "raw playlist failed")
            }
            return
        }
        // 改写分片地址 → /rawseg/{b64}
        val base = realUrl.substringBeforeLast('/') + "/"
        val sb = StringBuilder()
        val segs = ArrayList<String>(8)
        for (line in text.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("#")) {
                sb.append(s).append('\n')
            } else {
                val abs = if (s.startsWith("http")) s else base + s.removePrefix("./")
                segs.add(abs)
                sb.append("/rawseg/").append(b64enc(abs)).append('\n')
            }
        }
        val rewritten = sb.toString()
        rawCache[realUrl] = CachedM3u8(rewritten, System.currentTimeMillis(), realUrl)
        writeBody(out, rewritten.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
        // ★ 预取前 2 个分片：源站慢，等播放器来要就晚了。
        //   拿到清单时立刻并行拉，播放器请求时多半已经命中缓存。
        prefetchRawSegments(segs.take(2))
    }

    private val prefetchPool = Executors.newFixedThreadPool(2)

    private fun prefetchRawSegments(segs: List<String>) {
        val todo = segs.filter { s -> synchronized(rawSegCache) { rawSegCache[s] } == null }
        if (todo.isEmpty()) return
        runCatching {
            prefetchPool.execute {
                todo.forEach { url ->
                    runCatching {
                        val bytes = fetchBytesSmart(url)
                        synchronized(rawSegCache) { rawSegCache[url] = bytes }
                        Log.i(TAG, "预取分片 " + bytes.size / 1024 + "KB")
                    }
                }
            }
        }
    }

    private fun serveRawSeg(path: String, out: OutputStream) {
        val enc = path.removePrefix("/rawseg/").substringBefore('?')
        val real = b64dec(enc)
        if (real.isNullOrBlank()) {
            writeStatus(out, 400, "bad raw seg")
            return
        }
        synchronized(rawSegCache) { rawSegCache[real] }?.let { hit ->
            writeBody(out, hit, "video/mp2t")
            return
        }
        val t0 = System.currentTimeMillis()
        val bytes = try {
            fetchBytes(real, reqUa)
        } catch (e: Throwable) {
            // ★ 之前这里静默返回 502，线上出现 ERROR_CODE_IO_UNEXPECified 时完全查不到原因
            //   （不是 403，所以旧日志一个字都没有）。把异常类别和耗时落盘。
            val cost = System.currentTimeMillis() - t0
            Log.w(TAG, "自建源分片失败（${cost}ms）：${e.javaClass.simpleName}: ${e.message}")
            PlayLog.w(TAG, "自建源分片失败 ${cost}ms ${e.javaClass.simpleName}")
            writeStatus(out, 502, "raw segment failed")
            return
        }
        synchronized(rawSegCache) { rawSegCache[real] = bytes }
        writeBody(out, bytes, "video/mp2t")
    }

    private fun servePlaylist(path: String, out: OutputStream) {
        val id = path.removePrefix("/ch/").substringBefore('.').toLongOrNull()
        if (id == null) {
            writeStatus(out, 400, "bad channel")
            return
        }
        val ch = YspClient.channelOf(id)
        if (ch == null) {
            writeStatus(out, 404, "unknown channel")
            return
        }

        // 记录当前正在直播的频道：分片 URL 里没有频道号，403 降级只能按这个算。
        // ★ 必须放在缓存检查**之前** —— 缓存命中分支会直接 return，放在后面就永远设不上。
        curChannelId = id

        val cached = cache[id]
        if (cached != null && System.currentTimeMillis() - cached.at < M3U8_TTL_MS) {
            writeBody(out, cached.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
            return
        }

        // 现场取流（这一步是 App 自己的 HttpURLConnection，能过 CDN）。
        // 取流偶发失败（iretcode 抖动/版权接口超时），重试一次再放弃，减少播放器侧的 502。
        // 线路地址有缓存：bkliveinfo 不能每 2 秒调一次（会被限流，表现为满屏 502）。
        // 缓存 25 秒，过期或拉 m3u8 失败时才重新取流。
        val now = System.currentTimeMillis()
        var realUrl: String? = lineCache[id]
            ?.takeIf { now - it.at < LINE_TTL_MS }
            ?.url
        if (realUrl.isNullOrBlank()) {
            for ((i, wait) in longArrayOf(0L, 300L, 700L, 1500L).withIndex()) {
                if (wait > 0) Thread.sleep(wait)
                realUrl = runCatching {
                    val list = YspClient.lines(ch)
                    if (list.isEmpty()) {
                        null
                    } else {
                        // 上一条线路刚 403 过就换下一条；否则用当前这条
                        if (needRotateLine) {
                            lineIndex = (lineIndex + 1) % list.size
                            needRotateLine = false
                            if (list.size > 1) {
                                Log.i(TAG, "分片 403 后切线路 → 第 ${lineIndex + 1}/${list.size} 条")
                                PlayLog.w(TAG, "切线路 → 第 ${lineIndex + 1}/${list.size} 条")
                            }
                        }
                        list[lineIndex.coerceIn(0, list.size - 1)].url
                    }
                }.getOrNull()
                if (!realUrl.isNullOrBlank()) break
                Log.w(TAG, "取流第 ${i + 1} 次失败，继续重试")
            }
            if (!realUrl.isNullOrBlank()) lineCache[id] = CachedLine(realUrl!!, System.currentTimeMillis())
        }
        if (realUrl.isNullOrBlank()) {
            // 已有缓存就先用旧的顶着，别让播放器直接断
            if (cached != null) {
                writeBody(out, cached.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
            } else {
                writeStatus(out, 502, "no-line")
            }
            return
        }
        // ★ 只有第一次给足超时；失败时若有缓存就直接用缓存，不再重试。
        //   原来最多重试 3 次、每次都可能等满超时 —— 播放器那边早就判死了
        //   （真机日志「加载慢 6088ms / 2KB」「加载慢 16367ms / 2KB」就是这么来的）。
        //   直播 playlist 只是「有哪些分片」的清单，用上一份旧的顶一下完全能播。
        var text: String? = runCatching { fetchText(realUrl!!) }.getOrNull()
        if (text.isNullOrBlank()) {
            // ★ 这一句必须有：playurl 会过期（央视约 30~40 秒），
            //   不清 lineCache 的话下次还从缓存里取同一个死链，
            //   表现是「一直用旧 playlist 顶着、分片全是过期地址」→ 播不动。
            //   （上面「用缓存顶」只是为了让播放器这次别干等，不是让线路永远不换。）
            lineCache.remove(id)
            if (cached != null) {
                Log.w(TAG, "拉 playlist 失败，用缓存顶（ch=$id，缓存 ${System.currentTimeMillis() - cached.at}ms 前）；已清线路缓存")
                writeBody(out, cached.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
                return
            }
        }
        if (text.isNullOrBlank()) {
            text = runCatching { fetchText(realUrl!!) }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            // ★ 线路（playurl）失效时必须**同时**清掉 lineCache。
            //   原来只清了 playlist 缓存，3 次重试全打在同一个失效地址上，
            //   每次撞满 fetchText 的超时 → 一次卡顿 16 秒（真机实测：
            //   「加载慢 16367ms / 2KB」→ 判死 → 自动恢复）。
            lineCache.remove(id)
            Log.w(TAG, "playlist 三次都失败，已清线路缓存（ch=$id）")
        }
        if (text.isNullOrBlank()) {
            if (cached != null) {
                writeBody(out, cached.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
            } else {
                writeStatus(out, 502, "fetch-failed")
            }
            return
        }

        // 把分片地址改写成 /seg/{base64url}
        val base = runCatching { java.net.URI(realUrl) }.getOrNull()
        val rewritten = buildString {
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> append('\n')
                    line.startsWith("#") -> append(line).append('\n')
                    else -> {
                        val abs = if (line.startsWith("http")) line
                        else base?.resolve(line)?.toString() ?: line
                        val enc = Base64.encodeToString(
                            abs.toByteArray(Charsets.UTF_8),
                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                        )
                        append("/seg/").append(enc).append('\n')
                    }
                }
            }
        }
        cache[id] = CachedM3u8(rewritten, System.currentTimeMillis(), realUrl)
        Log.i(TAG, "M3U8 ch=$id bytes=" + rewritten.length + " segs=" + rewritten.count { it == '/' } )
        PlayLog.w(TAG, "M3U8 ch=$id bytes=" + rewritten.length)
        writeBody(out, rewritten.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
    }

    private fun serveSegment(path: String, out: OutputStream) {
        val enc = path.removePrefix("/seg/").substringBefore('?')
        val real = runCatching {
            String(
                Base64.decode(enc, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
        }.getOrNull()
        if (real.isNullOrBlank()) {
            writeStatus(out, 400, "bad segment")
            return
        }
        synchronized(segCache) { segCache[real] }?.let { hit ->
            writeBody(out, hit, "video/mp2t")
            return
        }
        val t0 = System.currentTimeMillis()
        val bytes = try {
            fetchBytesSmart(real)
        } catch (e: Throwable) {
            // 把真实原因暴露出来：这段原来用 runCatching 吞掉了异常，
            // 只留下一个 "segment failed"，根本不知道是 403、超时还是解码问题。
            Log.w(TAG, "分片拉取失败（${real.take(90)}…）：${e.javaClass.simpleName}: ${e.message}")
            // ★ 403 几乎都是「分片地址过期」：playlist 里带的是签名 URL，有时效，
            //   而我们缓存了 m3u8。播放器重试时会拿着旧 playlist 里的旧地址反复打 403。
            //   这里直接清掉全部 playlist 缓存，下次请求就会重新取流、拿到新地址。
            //   （缓存很小，清全部的代价远低于精确追踪「这个分片属于哪个频道」）
            val msg = e.message ?: ""
            if (msg.contains("403")) {
                // 403 时清 playlist + 线路，强制重新 ticket + 取流。
                //
                // 曾经试过「什么都不清，等 playlist 自然轮换」——电视上更糟：
                // 90 秒内 502 报 76 次、403 报 54 次，连 VLC 都判「直播流结束」。
                // 说明这个 CDN 在电视那条链路上，旧 playurl 取回来的分片地址
                // 基本全是打不开的，必须换线路才行。
                //
                // segCache 仍然保留：它兜的是「同一个分片之前拉成功过」的情况。
                val n = cache.size
                cache.clear()
                lineCache.clear()
                // ★ 关键：置位，下次取流换另一条线路。
                //   只清缓存不换线的话，重新取流拿回来还是同一条线，
                //   分片地址照样 403 → 播放器重试 5 次 → 黑屏（真机 112 次）。
                needRotateLine = true
                val cnt = fail403.incrementAndGet()
                Log.i(TAG, "分片 403（清 playlist=$n、线路；下次换线路）连续第 $cnt 次")
                PlayLog.w(TAG, "分片 403，连续第 $cnt 次")
                // 连续 3 次 403 就降级。
                //
                // 为什么是 3：真机日志显示 ExoPlayer 一轮重试最多产生 **4 次**分片 403，
                // 第 5 次是整体失败、随后 PlayerManager 重建播放器又能拉到分片，
                // 计数器就被清零了 —— 阈值设 6 永远够不到（实测卡在 4）。
                if (cnt == 3) {
                    val target = curChannelId
                    Log.w(TAG, "连续 3 次分片 403，通知上层降级（ch=$target）")
                    PlayLog.w(TAG, "连续 3 次 403 → 请求降级到其他源")
                    onTooMany403?.invoke(target)
                }
            }
            writeStatus(out, 502, "segment failed: " + (msg.ifBlank { e.javaClass.simpleName }))
            return
        }
        val cost = System.currentTimeMillis() - t0
        Log.i(TAG, "SEG bytes=" + bytes.size)
        // 只在慢的时候落盘：正常分片 1~2 秒，超过 3 秒就是 CDN 抖动，正是要抓的东西
        if (cost > 3000) PlayLog.w(TAG, "分片慢 ${cost}ms / ${bytes.size / 1024}KB")
        synchronized(segCache) { segCache[real] = bytes }
        if (fail403.get() != 0) fail403.set(0)   // 能拉到分片说明链路恢复，计数归零
        writeBody(out, bytes, "video/mp2t")
    }

    // ---------------------------------------------------------------- HTTP

    private fun open(url: String, timeoutMs: Int = 3000, ua: String = UA_PROXY): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        // 回看的 CDN（tlivecloud-playback-cdn）会主动 `Connection reset`，
        // 加上这两条让它走最朴素的一问一答：不复用连接、不要压缩。
        c.setRequestProperty("Connection", "close")
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    private fun fetchText(url: String, ua: String = UA_PROXY, timeoutMs: Int = 3000): String {
        val c = open(url, timeoutMs, ua)
        return try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.readBytes().toString(Charsets.UTF_8)
        } finally {
            runCatching { c.disconnect() }
        }
    }

    /**
     * 回看的 CDN 用 Cronet（Chromium TLS 栈），其余用 HttpURLConnection。
     *
     * 为什么分开：`tlivecloud-playback-cdn.ysp.cctv.cn` 对 TLS 栈敏感，
     * HttpURLConnection 一律 Connection reset；而直播 CDN 用 HttpURLConnection 没问题，
     * 没必要让直播也承担 Cronet 的初始化开销。
     */
    private fun fetchBytesSmart(url: String): ByteArray {
        val isReplay = url.contains("playback-cdn", ignoreCase = true) ||
            url.contains("tlivecloud", ignoreCase = true)
        if (!isReplay) return fetchBytes(url)
        // 回看 CDN 对 TLS 栈敏感，实测结论：
        //   HttpURLConnection（Conscrypt）   → 连 m3u8 都 Connection reset
        //   Cronet（Chromium BoringSSL）     → m3u8 能过，分片 ERR_CONNECTION_RESET
        //   **OkHttp（自带 TLS + 强制 h1.1）→ m3u8 和分片都 200**
        // 所以回看统一走 OkHttp，Cronet 已移除（省下每 ABI 约 3MB）。
        return okHttpFetch(url)
    }

    /** 用 OkHttp 取字节。强制 HTTP/1.1（回看 CDN 在 h2 上会 RST）。 */
    private fun okHttpFetch(url: String): ByteArray {
        // 同样走共享 client（回看 CDN 对 TLS 栈敏感，必须 OkHttp）
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA_PROXY).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("OkHttp HTTP " + resp.code)
            return resp.body?.bytes() ?: throw java.io.IOException("OkHttp 空响应")
        }
    }

    private fun fetchTextSmart(url: String): String {
        return fetchBytesSmart(url).toString(Charsets.UTF_8)
    }

    private fun fetchBytes(url: String, ua: String = UA_PROXY): ByteArray {
        // 走共享 OkHttpClient，复用 TCP/TLS 连接。
        //
        // ★ ua 必须**按调用方传入**，不能读全局 reqUa：
        //   全局字段是 /raw/ 请求时写的，会污染内置源和回看 —— 上一版就是这么回归的
        //   （自建源的 UA 被带到央视频请求上，两个源都起不来）。
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", ua.ifBlank { UA_PROXY })
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP " + resp.code)
            return resp.body?.bytes() ?: throw java.io.IOException("空响应")
        }
    }

    @Suppress("unused")
    private fun fetchBytesLegacy(url: String): ByteArray {
        val c = open(url, 12000)
        return try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.readBytes()
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun writeBody(out: OutputStream, body: ByteArray, contentType: String) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $msg\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(body)
        }
    }
}
