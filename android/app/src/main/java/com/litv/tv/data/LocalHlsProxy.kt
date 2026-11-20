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
            size > 6
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
                // 吃掉剩余的请求头
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(' ').getOrNull(1) ?: return@use
                Log.i(TAG, "REQ " + requestLine.take(80))
                val out = BufferedOutputStream(c.getOutputStream())
                when {
                    path.startsWith("/ch/") -> servePlaylist(path, out)
                    path.startsWith("/rp/") -> serveReplay(path, out)
                    path.startsWith("/seg/") -> serveSegment(path, out)
                    else -> writeStatus(out, 404, "not found")
                }
                runCatching { out.flush() }
            }
        }.onFailure { e -> Log.w(TAG, "请求处理失败: ${e.message}") }
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
                realUrl = runCatching { YspClient.firstLine(ch) }.getOrNull()
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
                // ★ 关键：过期的是**线路（playurl）**，不是 playlist。
                //   playlist 的 TTL 只有 4 秒，通常早就空了 —— 真机上日志是
                //   「已清 0 个 playlist 缓存」然后继续 403，因为下次仍然拿
                //   同一个旧 playurl 去取流，取回来的还是同一批过期分片地址。
                //   所以这里必须把 lineCache 一起清掉，强制重新 ticket+取流。
                val n = cache.size
                cache.clear()
                val m = lineCache.size
                lineCache.clear()
                // ★ 千万别清 segCache：403 说明「这个地址现在打不开了」，
                //   但之前已经拉到的字节仍在缓存里，播放器重试时正好能从这里拿到。
                //   第一版把缓存一起清了，等于把唯一的救命稻草扔掉 —— 403 之后还是 403。
                Log.i(TAG, "分片 403（地址已过期），清 playlist=$n、线路=$m，分片缓存保留")
            }
            writeStatus(out, 502, "segment failed: " + (msg.ifBlank { e.javaClass.simpleName }))
            return
        }
        Log.i(TAG, "SEG bytes=" + bytes.size)
        synchronized(segCache) { segCache[real] = bytes }
        writeBody(out, bytes, "video/mp2t")
    }

    // ---------------------------------------------------------------- HTTP

    private fun open(url: String, timeoutMs: Int = 3000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "qqlive")
        // 回看的 CDN（tlivecloud-playback-cdn）会主动 `Connection reset`，
        // 加上这两条让它走最朴素的一问一答：不复用连接、不要压缩。
        c.setRequestProperty("Connection", "close")
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    private fun fetchText(url: String): String {
        val c = open(url)
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
        val client = okhttp3.OkHttpClient.Builder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "qqlive")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("OkHttp HTTP " + resp.code)
            return resp.body?.bytes() ?: throw java.io.IOException("OkHttp 空响应")
        }
    }

    private fun fetchTextSmart(url: String): String {
        return fetchBytesSmart(url).toString(Charsets.UTF_8)
    }

    private fun fetchBytes(url: String): ByteArray {
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
