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
    private const val M3U8_TTL_MS = 15_000L

    private data class CachedM3u8(val text: String, val at: Long, val realBase: String)

    private val cache = ConcurrentHashMap<Long, CachedM3u8>()
    private val pool = Executors.newFixedThreadPool(6)

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
        var realUrl = runCatching { YspClient.firstLine(ch) }.getOrNull()
        if (realUrl.isNullOrBlank()) {
            Thread.sleep(300)
            realUrl = runCatching { YspClient.firstLine(ch) }.getOrNull()
        }
        if (realUrl.isNullOrBlank()) {
            // 已有缓存就先用旧的顶着，别让播放器直接断
            if (cached != null) {
                writeBody(out, cached.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
            } else {
                writeStatus(out, 502, "no line")
            }
            return
        }
        var text = runCatching { fetchText(realUrl) }.getOrNull()
        if (text.isNullOrBlank()) {
            Thread.sleep(200)
            text = runCatching { fetchText(realUrl) }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            if (cached != null) {
                writeBody(out, cached.text.toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
            } else {
                writeStatus(out, 502, "fetch failed")
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
        val bytes = runCatching { fetchBytes(real) }.getOrNull()
        if (bytes == null) {
            writeStatus(out, 502, "segment failed")
            return
        }
        Log.i(TAG, "SEG bytes=" + bytes.size)
        writeBody(out, bytes, "video/mp2t")
    }

    // ---------------------------------------------------------------- HTTP

    private fun open(url: String, timeoutMs: Int = 15000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "qqlive")
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

    private fun fetchBytes(url: String): ByteArray {
        val c = open(url, 25000)
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
