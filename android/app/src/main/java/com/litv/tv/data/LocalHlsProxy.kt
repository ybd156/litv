package com.litv.tv.data

import android.util.Base64
import android.util.Log
import com.litv.tv.player.Redact
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
 *   /rawfile/{base64url}  → 密钥 / fMP4 init 段这类小文件的透传（不进分片缓存、不预取）
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

    /**
     * 播放器来要某片、而这片正在被后台预取时，最多等它多久。
     *
     * 等的好处：不重复打源站（源站每多一次请求就更慢一轮）。
     * 不能等太久：播放器那边也是有超时的，等过它就没意义了。
     */
    private const val WAIT_INFLIGHT_MS = 1_200L

    /** 失败后重试的门槛：只有在这个时间内就失败的才值得再试一次。 */
    private const val FAST_FAIL_MS = 3_000L

    /**
     * 自建源 playlist 的「保活」间隔。
     *
     * 真机实测（2026-11-24，新包）：分片预取生效后，播放器的每一次「加载慢」
     * 都是 **1KB、trackType=-1** —— 不是分片，是 m3u8 清单本身，1.7~4.7 秒。
     * 源站拉清单就是这么慢，但它只是「现在有哪些分片」，完全可以提前拉好。
     */
    private const val RAW_KEEPALIVE_MS = 3_000L

    /** 多久没有播放器来要，就停掉保活（换台/退出后别一直打源站）。 */
    private const val RAW_IDLE_MS = 30_000L

    /**
     * 分片慢到这个程度就标记「下次换线路」。
     *
     * 4 秒的依据：正常分片 1~2 秒；一片 4 秒以上，播放器的缓冲就会被它吃掉一格，
     * 连续两片就进重缓冲。这时换线路的收益大于「再等等看」。
     */
    private const val SEGMENT_SLOW_SWITCH_MS = 4_000L

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

        // 清单改写统一走 PlaylistRewrite（三处调用点共用一份规则）：
        //   · `/` 开头的分片地址按 playlist 的 origin 解析，不再拼成 `base//path`；
        //   · #EXT-X-MAP / #EXT-X-KEY / #EXT-X-MEDIA 的 URI 一起改写 —— 不改的话
        //     fMP4/CMAF 拿不到 init 段、AES-128 拿不到密钥，经代理整条播不了。
        val rw = PlaylistRewrite.rewrite(
            text = text,
            playlistUrl = realUrl!!,
            segPrefix = "/seg/",
            tagUriPrefix = "/rawfile/",
            encode = ::b64enc,
        )
        val windowSec = runCatching {
            Regex("#EXTINF:([0-9.]+)").findAll(text).sumOf { it.groupValues[1].toDouble() }.toLong()
        }.getOrDefault(20L).coerceAtLeast(5L)

        cur.nextStart = from + windowSec
        cur.issued += 1
        Log.i(TAG, "回看 ch=$id from=$from window=${windowSec}s 第${cur.issued}次")
        writeBody(out, rw.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
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
                // ★ 只打「方法 + 路由类别 + 长度」。
                //   原来打 `requestLine.take(80)`：`/raw/<base64>` 的前 80 字符里
                //   就藏着源站地址前缀（base64 可逆），等于把用户私有源站写进 logcat。
                Log.i(TAG, "REQ " + requestMethod(requestLine) + " " + routeKind(path) + " len=" + requestLine.length)
                val out = BufferedOutputStream(c.getOutputStream())
                when {
                    path.startsWith("/ch/") -> servePlaylist(path, out)
                    path.startsWith("/rp/") -> serveReplay(path, out)
                    path.startsWith("/seg/") -> serveSegment(path, out)
                    // 密钥 / fMP4 init 段：只透传，不进分片缓存、不预取。
                    path.startsWith("/rawfile/") -> serveRawFile(path, out)
                    // 通用 HLS 代理：自建源也走本地，才能预取分片 + 缓存 + 复用连接。
                    path.startsWith("/raw/") -> serveRaw(path, out)
                    path.startsWith("/rawseg/") -> serveRawSeg(path, out)
                    else -> writeStatus(out, 404, "not found")
                }
                runCatching { out.flush() }
            }
        }.onFailure { e -> Log.w(TAG, "请求处理失败: ${e.message}") }
    }

    /** 请求行里的方法（GET/POST…）；只取空格前那一小段，请求行本身不进日志。 */
    private fun requestMethod(requestLine: String): String =
        requestLine.substringBefore(' ').take(8).ifBlank { "?" }

    /**
     * 路由类别（**不含** base64 段）。
     *
     * ★ 为什么不能打完整路径：`/raw/<base64>` 里的 base64 解出来就是源站地址，
     *   打前 80 个字符已经够还原出 host + 路径前缀。只留类别，长度另打。
     */
    private fun routeKind(path: String): String = when {
        path.startsWith("/seg/") -> "seg"
        path.startsWith("/rawseg/") -> "rawseg"
        path.startsWith("/rawfile/") -> "rawfile"
        path.startsWith("/raw/") -> "raw"
        path.startsWith("/ch/") -> "ch"
        path.startsWith("/rp/") -> "rp"
        else -> "other"
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
    private val rawSegCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        // ★ 2026-11-24：12 → 24。
        //   预取窗口是 6 片，缓存只留 12 会让「刚预取好、还没播到就被挤掉」反复发生。
        //   分片约 2~3MB，24 片上限约 60MB，对电视（2GB）和手机都可接受。
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ByteArray>) = size > 24
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
        // 有播放器在要这条清单 → 保活线程开始提前刷新它
        markRawActive(realUrl)

        val cached = rawCache[realUrl]
        // ★ 2026-11-24 第二轮：**有缓存就立刻给，永不阻塞**。
        //   实测（23:11–23:15）源站清单会整段挂住 20 秒（「清单拉取失败 20022ms」），
        //   而这一版之前「缓存超过 8 秒就阻塞重拉」——播放器于是跟着等 20 秒，
        //   缓冲区 22 秒被抽干，画面停 50 秒以上。
        //   清单只是「现在有哪些分片」，晚几秒的清单照样能播；真正要新鲜的是分片。
        if (cached != null) {
            refreshRawAsync(realUrl)
            writeBody(out, cached.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
            return
        }
        val fresh = fetchAndRewriteRaw(realUrl)
        if (fresh != null) {
            writeBody(out, fresh.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
            return
        }
        writeStatus(out, 502, "raw playlist failed")
    }

    /** 同一条清单同时只允许一个刷新在跑（播放器和保活线程会抢）。 */
    private val rawRefreshInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 后台刷新：有缓存时走这里，绝不让调用方等源站。 */
    private fun refreshRawAsync(realUrl: String) {
        val c = rawCache[realUrl]
        val age = if (c == null) Long.MAX_VALUE else System.currentTimeMillis() - c.at
        if (age < RAW_KEEPALIVE_MS) return
        if (!rawRefreshInFlight.add(realUrl)) return
        val submitted = runCatching {
            prefetchPool.execute {
                try {
                    fetchAndRewriteRaw(realUrl)
                } finally {
                    rawRefreshInFlight.remove(realUrl)
                }
            }
        }.isSuccess
        if (!submitted) rawRefreshInFlight.remove(realUrl)
    }


    /**
     * 拉一次清单、改写成 /rawseg/{b64}、写进缓存，并把窗口尾部交给预取。
     *
     * 单独抽出来是为了让「阻塞路径」和「保活线程」用同一份逻辑，
     * 不出现两套改写规则（以前内置源/自建源/回看各写一遍，已经吃过亏）。
     */
    private fun fetchAndRewriteRaw(realUrl: String): String? {
        val t0 = System.currentTimeMillis()
        // ★ 超时给足但**别给成 10+10=20 秒**：实测源站会整段挂住，
        //   20 秒的连接+读取叠加直接把播放器缓冲抽干（真机 23:11 出现
        //   「清单拉取失败 20022ms」，同期画面停 56 秒）。
        //   连接 4 秒、读取 6 秒：单次最多 10 秒，失败还有下一次（保活每 3 秒一次）。
        val text = runCatching {
            fetchText(realUrl, reqUa, timeoutMs = 4_000, readMs = 6_000)
        }.getOrNull()
        if (text.isNullOrBlank()) {
            PlayLog.w(TAG, "清单拉取失败 ${System.currentTimeMillis() - t0}ms")
            return null
        }
        // 与内置源/回看共用同一份改写规则：媒体行 → /rawseg/，
        // KEY / MAP 的 URI → /rawfile/（不经这一步，加密源与 fMP4 源全都播不了）。
        val rw = PlaylistRewrite.rewrite(
            text = text,
            playlistUrl = realUrl,
            segPrefix = "/rawseg/",
            tagUriPrefix = "/rawfile/",
            encode = ::b64enc,
        )
        rawCache[realUrl] = CachedM3u8(rw.text, System.currentTimeMillis(), realUrl)
        // ★ 预取窗口**尾部**：直播是滑动窗口，播放器从边缘往前播。
        //   旧实现写的是 segs.take(2)（最旧的两片），等于预取了它早就不用的东西。
        prefetchWindow(rw.mediaUrls)
        return rw.text
    }

    // ---------------------------------------------------------------- 清单保活

    @Volatile private var rawActiveUrl: String? = null
    @Volatile private var rawActiveAt = 0L
    @Volatile private var rawKeeperStarted = false

    /**
     * 记录「现在这条清单有人在播」，并按需启动保活线程。
     *
     * 为什么要保活：源站清单慢是改不了的，但**什么时候拉**可以改。
     * 播放器来要时才拉 → 它必须等；后台每 3 秒拉一次 → 它每次都命中缓存。
     */
    private fun markRawActive(url: String) {
        rawActiveUrl = url
        rawActiveAt = System.currentTimeMillis()
        if (rawKeeperStarted) return
        synchronized(this) {
            if (rawKeeperStarted) return
            rawKeeperStarted = true
            Thread({
                while (true) {
                    try {
                        Thread.sleep(1_000)
                        val u = rawActiveUrl ?: continue
                        // 已经没人看了：别再打源站
                        if (System.currentTimeMillis() - rawActiveAt > RAW_IDLE_MS) continue
                        val c = rawCache[u]
                        val age = if (c == null) Long.MAX_VALUE else System.currentTimeMillis() - c.at
                        if (age >= RAW_KEEPALIVE_MS) {
                            refreshRawAsync(u)
                            rawKeepCount++
                        }
                    } catch (e: InterruptedException) {
                        // 进程退出，线程跟着结束
                    } catch (e: Throwable) {
                        Log.w(TAG, "清单保活失败 " + e.javaClass.simpleName)
                    }
                }
            }, "litv-raw-keeper").apply { isDaemon = true }.start()
        }
    }

    /** 保活刷新次数（只用于判断它是否真的在跑）。 */
    @Volatile private var rawKeepCount = 0L


    private val prefetchPool = Executors.newFixedThreadPool(SegmentPrefetch.MAX_PARALLEL)

    /** 正在拉的地址：避免「后台预取」和「播放器来要」重复打源站。 */
    private val segInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 由预取提前拉好的地址（只用来算预取命中率）。 */
    private val segPrefetched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val segHealth = SegmentHealth()

    /**
     * 拿到 playlist 就把窗口尾部若干片**并发**预取。
     *
     * 为什么要并发：源站单片 p50 约 1.9 秒、尾部 5~11.5 秒（真机 2026-11-24）。
     * 播放器是串行要分片的，串行就等于吞吐 = 1/单片耗时，缓冲永远填不满；
     * 并发 6 路后同样的源能跑出数倍吞吐，缓冲才有机会堆到 minBuffer。
     */
    private fun prefetchWindow(segs: List<String>) {
        val cached = synchronized(rawSegCache) { rawSegCache.keys.toSet() }
        val todo = SegmentPrefetch.plan(segs, cached, segInFlight)
        if (todo.isEmpty()) return
        for (url in todo) {
            if (!segInFlight.add(url)) continue
            val submitted = runCatching {
                prefetchPool.execute {
                    val t0 = System.currentTimeMillis()
                    try {
                        val bytes = fetchBytesSmart(url)
                        val cost = System.currentTimeMillis() - t0
                        synchronized(rawSegCache) { rawSegCache[url] = bytes }
                        segPrefetched.add(url)
                        segHealth.onFetch(cost, ok = true)
                        Log.i(TAG, "预取分片 " + bytes.size / 1024 + "KB " + cost + "ms")
                    } catch (e: Throwable) {
                        segHealth.onFetch(System.currentTimeMillis() - t0, ok = false)
                        Log.w(TAG, "预取失败 " + e.javaClass.simpleName)
                    } finally {
                        segInFlight.remove(url)
                        segHealth.takeSummaryIfDue()?.let { PlayLog.w(TAG, it) }
                    }
                }
            }.isSuccess
            if (!submitted) segInFlight.remove(url)
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
            segHealth.onHit(fromPrefetch = segPrefetched.contains(real))
            writeBody(out, hit, "video/mp2t")
            return
        }
        // 预取正在拉这一片：短等一下它，别让两边各打一次源站。
        val waitUntil = System.currentTimeMillis() + WAIT_INFLIGHT_MS
        while (segInFlight.contains(real) && System.currentTimeMillis() < waitUntil) {
            Thread.sleep(50)
            synchronized(rawSegCache) { rawSegCache[real] }?.let { hit ->
                segHealth.onHit(fromPrefetch = true)
                writeBody(out, hit, "video/mp2t")
                return
            }
        }
        val t0 = System.currentTimeMillis()
        var bytes: ByteArray? = null
        var lastErr: Throwable? = null
        try {
            bytes = fetchBytes(real, reqUa)
        } catch (e: Throwable) {
            lastErr = e
        }
        // 只有**很快**失败才重试一次（多半是瞬时 5xx/连接重置）。
        // 慢慢失败（撞满超时）再重试一次只会把播放器的等待翻倍。
        if (bytes == null && System.currentTimeMillis() - t0 <= FAST_FAIL_MS) {
            try {
                bytes = fetchBytes(real, reqUa)
            } catch (e: Throwable) {
                lastErr = e
            }
        }
        val cost = System.currentTimeMillis() - t0
        val body = bytes
        if (body == null) {
            // ★ 之前这里静默返回 502，线上出现 ERROR_CODE_IO_UNEXPECified 时完全查不到原因
            //   （不是 403，所以旧日志一个字都没有）。把异常类别和耗时落盘。
            Log.w(TAG, "自建源分片失败（${cost}ms）：${lastErr?.javaClass?.simpleName}: ${lastErr?.message}")
            PlayLog.w(TAG, "自建源分片失败 ${cost}ms ${lastErr?.javaClass?.simpleName}")
            segHealth.onFetch(cost, ok = false)
            writeStatus(out, 502, "raw segment failed")
            return
        }
        segHealth.onFetch(cost, ok = true)
        synchronized(rawSegCache) { rawSegCache[real] = body }
        writeBody(out, body, "video/mp2t")
    }

    /**
     * `/rawfile/{base64url}` —— `#EXT-X-MAP` 的 init 段、`#EXT-X-KEY` 的 AES 密钥这类小文件。
     *
     * 为什么不复用 `/rawseg/`：
     *   · 那些走分片缓存（LRU 24 片、按 MB 计），密钥只有 16 字节、init 段也不是 TS，
     *     塞进去只会把真正的分片挤出去；
     *   · 分片缓存路径会按窗口预取，密钥/init 段没有「窗口」概念。
     * 所以这里**只透传**：不缓存、不预取、不记账进分片统计，Content-Type 用 octet-stream。
     */
    private fun serveRawFile(path: String, out: OutputStream) {
        val enc = path.removePrefix("/rawfile/").substringBefore('?')
        val real = b64dec(enc)
        if (real.isNullOrBlank()) {
            writeStatus(out, 400, "bad raw file")
            return
        }
        val t0 = System.currentTimeMillis()
        // UA 顺序：央视频的 CDN 认 `qqlive`（内置源的分片就是用它取的），自建源认请求带来的 UA。
        // 这里两类都会经过，所以先按内置源试；失败再按请求 UA 试一次。
        // 这两类文件都很小（密钥 16B、init 段几十 KB），两次上限各 6s / 4s。
        val bytes = runCatching { fetchBytes(real, UA_PROXY, timeoutMs = 6_000L) }.getOrNull()
            ?: if (reqUa != UA_PROXY) {
                runCatching { fetchBytes(real, reqUa, timeoutMs = 4_000L) }.getOrNull()
            } else {
                null
            }
        if (bytes == null) {
            Log.w(TAG, "附带文件拉取失败（${Redact.url(real)}）${System.currentTimeMillis() - t0}ms")
            writeStatus(out, 502, "raw file failed")
            return
        }
        writeBody(out, bytes, "application/octet-stream")
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

        // 把分片地址改写成 /seg/{base64url}（规则见 PlaylistRewrite）
        val playlistUrl = realUrl ?: return
        val playlistText = text ?: return
        val rw = PlaylistRewrite.rewrite(
            text = playlistText,
            playlistUrl = playlistUrl,
            segPrefix = "/seg/",
            tagUriPrefix = "/rawfile/",
            encode = ::b64enc,
        )
        cache[id] = CachedM3u8(rw.text, System.currentTimeMillis(), playlistUrl)
        Log.i(TAG, "M3U8 ch=$id bytes=" + rw.text.length + " segs=" + rw.mediaUrls.size)
        PlayLog.w(TAG, "M3U8 ch=$id bytes=" + rw.text.length)
        writeBody(out, rw.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
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
            // ★ 但地址必须脱敏：real 是带签名的源站分片地址，`take(90)` 正好吃到
            //   host + 路径前缀 + 签名参数（Redact 只留 scheme+host+port 与「有没有 query」）。
            Log.w(TAG, "分片拉取失败（${Redact.url(real)}）：${e.javaClass.simpleName}: ${e.message}")
            // ★ 403 几乎都是「分片地址过期」：playlist 里带的是签名 URL，有时效，
            //   而我们缓存了 m3u8。播放器重试时会拿着旧 playlist 里的旧地址反复打 403。
            //   这里直接清掉全部 playlist 缓存，下次请求就会重新取流、拿到新地址。
            //   （缓存很小，清全部的代价远低于精确追踪「这个分片属于哪个频道」）
            val msg = e.message ?: ""
            // ★★ 序号必须在清缓存**之前**取（见 SegmentFallback 的类注释）。
            //    旧顺序是「先 cache.clear() 再让 alternateLineSegment 去 cache 里数序号」，
            //    缓存已空 → 序号恒 null → 换线路一次都没真正发生过，403 依旧原样 502。
            //    这里用清缓存前的清单把序号定下来，后面换线直接按它去备线清单取同一位。
            val failIndex = SegmentFallback.indexOf(
                playlist = cache[curChannelId]?.text,
                served = real,
                decode = ::b64dec,
            )
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
                Log.i(TAG, "分片 403（清 playlist=$n、线路；对齐序号=${failIndex ?: "-"}）连续第 $cnt 次")
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
            // ★ 2026-11-24：分片级换线路。
            //   这一片在当前线路上拿不到（或 403 之后仍然拿不到），
            //   立刻去备用线路取**同一位置**的片子顶上，而不是把 502 甩给播放器
            //   （播放器拿到 502 只会重试同一条死路，直到判死换内核）。
            //   failIndex 是清缓存之前算好的序号 —— 传进去，别再让被调方自己去缓存里找。
            val alt = runCatching { alternateLineSegment(failIndex) }.getOrNull()
            // 换线记账落盘：内置源不走预取、片计数一直是 0，靠这一句让换线次数看得见。
            segHealth.takeSummaryIfDue()?.let { PlayLog.w(TAG, it) }
            if (alt != null) {
                synchronized(segCache) { segCache[real] = alt }
                if (fail403.get() != 0) fail403.set(0)
                writeBody(out, alt, "video/mp2t")
                return
            }
            writeStatus(out, 502, "segment failed: " + (msg.ifBlank { e.javaClass.simpleName }))
            return
        }
        val cost = System.currentTimeMillis() - t0
        Log.i(TAG, "SEG bytes=" + bytes.size)
        // 只在慢的时候落盘：正常分片 1~2 秒，超过 3 秒就是 CDN 抖动，正是要抓的东西
        if (cost > 3000) PlayLog.w(TAG, "分片慢 ${cost}ms / ${bytes.size / 1024}KB")
        // 这一片虽然拿到了，但慢到会直接把播放器拖进重缓冲：标记下次换线路。
        // 只在「还有别的线路」时才有意义，内置源才有；自建源单线时它是个空动作。
        if (cost > SEGMENT_SLOW_SWITCH_MS) {
            needRotateLine = true
            PlayLog.w(TAG, "分片慢 ${cost}ms → 下次取流换线路")
        }
        synchronized(segCache) { segCache[real] = bytes }
        if (fail403.get() != 0) fail403.set(0)   // 能拉到分片说明链路恢复，计数归零
        writeBody(out, bytes, "video/mp2t")
    }

    /**
     * 分片级换线路：用**另一条线路**取同一位置的分片。
     *
     * 为什么需要：内置源（央视频）一次会返回 playurl + backurl_list 多条线路，
     * 但它们的分片地址各自带签名、互不通用。旧实现只会在 403 时「下次取流换条线」，
     * 属于**整条链路重来**；而真机上更常见的是「某一片特别慢/偶发 5xx」——
     * 这时候换整条线路代价太大（要重新取流、重新拉清单），把这一片从别的线补上就够。
     *
     * 怎么对齐：分片地址里没有序号，只能按它在自己清单里的**位置**去备线清单里取同一位。
     * 直播窗口大约同长，位差一般 0~1 片，画面最多跳一下，比黑屏/判死好得多。
     *
     * ★ 两条硬约束（2026-11-24 修）：
     *   ① [index] 由调用方在**清缓存之前**算好传进来（见 [SegmentFallback.indexOf]）。
     *      旧实现让本函数自己去 `cache[channelId]` 里数序号，而调用点已经清空缓存，
     *      于是序号恒 null —— 换线路一次都没真正发生过。
     *   ② 整条路径有总预算 [SegmentFallback.TOTAL_BUDGET_MS]。旧实现
     *      `lines(5s)` + 每条备线清单（4+5s）+ 分片（OkHttp 默认 20s）叠起来最坏 34 秒，
     *      全程占着 handle 线程，播放器那边早就判死换内核了。
     */
    private fun alternateLineSegment(index: Int?): ByteArray? {
        val id = curChannelId
        if (id <= 0) return null
        val idx = index ?: return null
        val ch = runCatching { YspClient.channelOf(id) }.getOrNull() ?: return null
        val deadline = System.currentTimeMillis() + SegmentFallback.TOTAL_BUDGET_MS
        val lines = runCatching {
            YspClient.lines(ch, timeoutMs = SegmentFallback.linesTimeoutMs(SegmentFallback.TOTAL_BUDGET_MS))
        }.getOrNull().orEmpty()
        val targets = SegmentFallback.targets(lines.size, lineIndex)
        if (targets.isEmpty()) return null

        // 记账：到这里才是「真的要去备线上取一片」，成功与否都要能看见。
        segHealth.onLineSwitchTry()
        var pending = targets.size
        for (i in targets) {
            // 剩余时间平摊给还没试的备线；不够一片的最低耗时就直接停手，
            // 不把一个必然超时的请求硬塞给源站。
            val per = SegmentFallback.perLineMs(deadline - System.currentTimeMillis(), pending) ?: break
            pending--
            // 清单 1~2KB、分片 2~3MB：时间对半分，清单那边一般用不满。
            val half = (per / 2).coerceAtLeast(1L).toInt()
            val url = runCatching { segmentUrlAt(lines[i].url, idx, half, half) }.getOrNull() ?: continue
            val bytes = runCatching { fetchBytesSmart(url, timeoutMs = (per - half).coerceAtLeast(1L)) }
                .getOrNull() ?: continue
            lineIndex = i
            needRotateLine = false
            segHealth.onLineSwitchOk()
            Log.i(TAG, "分片换线路成功 → 第 " + (i + 1) + "/" + lines.size + " 条")
            PlayLog.w(TAG, "分片换线路成功 → 第 " + (i + 1) + "/" + lines.size + " 条")
            return bytes
        }
        Log.w(
            TAG,
            "分片换线路也失败（共 ${lines.size} 条，剩余预算 " +
                (deadline - System.currentTimeMillis()).coerceAtLeast(0L) + "ms）",
        )
        return null
    }

    /**
     * 取某条线路 playlist 里第 [index] 个分片地址。
     *
     * 序号超出该线窗口时**不 clamp**（旧实现 `coerceIn` 会取到最后一片，
     * 等于把已经播过的内容塞给播放器）：直接返回 null，让调用方跳过这条备线。
     */
    private fun segmentUrlAt(playlistUrl: String, index: Int, timeoutMs: Int, readMs: Int): String? {
        val text = fetchText(playlistUrl, UA_PROXY, timeoutMs = timeoutMs, readMs = readMs)
        val segs = PlaylistRewrite.mediaUrls(text, playlistUrl)
        val at = SegmentFallback.pickIndex(index, segs.size) ?: return null
        return segs[at]
    }

    // ---------------------------------------------------------------- HTTP

    private fun open(
        url: String,
        timeoutMs: Int = 3000,
        ua: String = UA_PROXY,
        readMs: Int = timeoutMs,
    ): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = readMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        // 回看的 CDN（tlivecloud-playback-cdn）会主动 `Connection reset`，
        // 加上这两条让它走最朴素的一问一答：不复用连接、不要压缩。
        c.setRequestProperty("Connection", "close")
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    private fun fetchText(
        url: String,
        ua: String = UA_PROXY,
        timeoutMs: Int = 3000,
        readMs: Int = timeoutMs,
    ): String {
        val c = open(url, timeoutMs, ua, readMs)
        return try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.readBytes().toString(Charsets.UTF_8)
        } finally {
            runCatching { c.disconnect() }
        }
    }

    /**
     * 取字节：回看 CDN 对 TLS 栈敏感，统一走 OkHttp（原因见 [okHttpFetch] 里的实测结论）。
     *
     * @param timeoutMs > 0 时给**这一次**调用一个硬上限（覆盖 client 的 20 秒 readTimeout）。
     *        换线路属于「这一片已经失败」之后的补救，必须能被总预算掐断，
     *        否则一次补救就能把 handle 线程占满 20 秒。
     */
    private fun fetchBytesSmart(url: String, timeoutMs: Long = 0L): ByteArray {
        val isReplay = url.contains("playback-cdn", ignoreCase = true) ||
            url.contains("tlivecloud", ignoreCase = true)
        if (!isReplay) return fetchBytes(url, timeoutMs = timeoutMs)
        // 回看 CDN 对 TLS 栈敏感，实测结论：
        //   HttpURLConnection（Conscrypt）   → 连 m3u8 都 Connection reset
        //   Cronet（Chromium BoringSSL）     → m3u8 能过，分片 ERR_CONNECTION_RESET
        //   **OkHttp（自带 TLS + 强制 h1.1）→ m3u8 和分片都 200**
        // 所以回看统一走 OkHttp，Cronet 已移除（省下每 ABI 约 3MB）。
        return okHttpFetch(url, timeoutMs)
    }

    /** 用 OkHttp 取字节。强制 HTTP/1.1（回看 CDN 在 h2 上会 RST）。 */
    private fun okHttpFetch(url: String, timeoutMs: Long = 0L): ByteArray {
        // 同样走共享 client（回看 CDN 对 TLS 栈敏感，必须 OkHttp）
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA_PROXY).build()
        val call = httpClient.newCall(req)
        if (timeoutMs > 0L) {
            call.timeout().timeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("OkHttp HTTP " + resp.code)
            return resp.body?.bytes() ?: throw java.io.IOException("OkHttp 空响应")
        }
    }

    private fun fetchTextSmart(url: String): String {
        return fetchBytesSmart(url).toString(Charsets.UTF_8)
    }

    private fun fetchBytes(url: String, ua: String = UA_PROXY, timeoutMs: Long = 0L): ByteArray {
        // 走共享 OkHttpClient，复用 TCP/TLS 连接。
        //
        // ★ ua 必须**按调用方传入**，不能读全局 reqUa：
        //   全局字段是 /raw/ 请求时写的，会污染内置源和回看 —— 上一版就是这么回归的
        //   （自建源的 UA 被带到央视频请求上，两个源都起不来）。
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", ua.ifBlank { UA_PROXY })
            .build()
        val call = httpClient.newCall(req)
        // 换线补救路径要用到这次调用的硬上限（见 fetchBytesSmart 的注释）。
        if (timeoutMs > 0L) {
            call.timeout().timeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        call.execute().use { resp ->
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
