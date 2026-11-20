package com.litv.tv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * 极简 HTTP 数据源：**只发我们明确要求的头**。
 *
 * 为什么不用 `DefaultHttpDataSource`（真机事故 2026-11-21，央视频）：
 * 同一个 m3u8 URL、同一个 App 进程内，
 *   - 裸 `HttpURLConnection`（只带 User-Agent）        → 200
 *   - `DefaultHttpDataSource`（同样只配了 UA）         → 200
 *   - 但 `HlsMediaSource` 走同一个 factory              → 403
 * 排查里 DataSpec 是 `method=GET pos=0 len=-1 flags=FLAG_ALLOW_GZIP`，
 * 一切正常，说明差异来自 Media3 数据源内部追加的东西（gzip 路径会包一层
 * `GzipDataSource`，也可能追加其它头）。这里用最原始的方式发请求，
 * 把"默认行为"这个变量彻底去掉。
 *
 * 只支持 http/https，够 IPTV 用；不支持 gzip（本来也不需要，m3u8/ts 都是明文）。
 */
@OptIn(UnstableApi::class)
class RawHttpDataSource(
    private val userAgent: String,
    private val extraHeaders: Map<String, String>,
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 15000,
) : DataSource {

    private var conn: HttpURLConnection? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        // 不需要：上层不依赖传输统计
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val c = URL(dataSpec.uri.toString()).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = true
        // 只发 User-Agent 与调用方显式要求的头；不设 Accept-Encoding，
        // 让它走最朴素的 identity 传输。
        c.setRequestProperty("User-Agent", userAgent)
        for ((k, v) in extraHeaders) {
            if (k.equals("User-Agent", ignoreCase = true)) continue
            if (v.isNotBlank()) c.setRequestProperty(k, v)
        }
        // m3u8 是整份重拉；分片也可从头拉。position>0 时才带 Range。
        if (dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else
                (dataSpec.position + dataSpec.length - 1).toString()
            c.setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
        }
        conn = c
        val code = c.responseCode
        if (code < 200 || code > 299) {
            runCatching { c.disconnect() }
            conn = null
            throw java.io.IOException("HTTP $code")
        }
        input = c.inputStream
        opened = true
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            c.contentLengthLong.takeIf { it > 0 } ?: C.LENGTH_UNSET.toLong()
        } else {
            dataSpec.length
        }
        return if (bytesRemaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else minOf(bytesRemaining, length.toLong()).toInt()
        val n = input?.read(buffer, offset, want) ?: C.RESULT_END_OF_INPUT
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { input?.close() }
        runCatching { conn?.disconnect() }
        input = null
        conn = null
        uri = null
        opened = false
    }

    /** 工厂：每次请求新建一个连接，参数从引擎当前状态现读。 */
    class Factory(
        private val uaProvider: () -> String,
        private val headersProvider: () -> Map<String, String>,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RawHttpDataSource(uaProvider(), headersProvider())
    }
}
