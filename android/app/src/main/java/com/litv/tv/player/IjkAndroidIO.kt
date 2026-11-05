package com.litv.tv.player

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.BufferedSource
import tv.danmaku.ijk.media.player.misc.IAndroidIO
import java.util.concurrent.TimeUnit

/**
 * 让 ijkplayer 走 Android 系统的 TLS 栈取流。
 *
 * 背景
 * ----
 * ijkplayer 官方 AAR 里的 ffmpeg **没有编 TLS**（.so 里查不到任何
 * OpenSSL/GnuTLS 符号），所以 https 源一律失败：
 *
 *     IjkEngine: ijk 播放失败 what=-10000 extra=0   （120ms 就报）
 *
 * 重编 ijkplayer 代价极大：它基于 ffmpeg 3.4，构建脚本是 2018 年的，
 * 配 NDK 27 要改一堆东西，还得单独编一版 OpenSSL 1.1。
 *
 * 更好的办法：ijkplayer 支持 `ijkio:` 协议 —— 把 IO 完全回调给 Java：
 *
 *     IjkMediaPlayer.setAndroidIOCallback(IAndroidIO)
 *     数据源写成 "ijkio:" + 真实 URL
 *
 * 这样网络请求由我们自己做（OkHttp → Android 系统 TLS），
 * ijk 只负责解封装和解码。**不用重编任何 native 库**。
 *
 * 注意
 * ----
 * - `seek` 直接返回 -1：直播流不可回退，ijk 拿到失败会按直播处理。
 * - `read` 返回 0 表示 EOF，-1 表示错误。直播不会 EOF。
 * - 请求头（UA / Referer）在这里统一加，因为 ijk 的 ijkio 协议
 *   不传 headers 参数。
 */
class IjkAndroidIO(
    private val headers: Map<String, String>,
) : IAndroidIO {

    companion object {
        private const val TAG = "IjkAndroidIO"
        private const val DEFAULT_UA = "LITV/1.0 (Android)"

        // ijk 的 seek whence 常量（对应 ijkavutil 里的 IJKAVSEEK_SIZE）
        private const val IJKAVSEEK_SIZE = 0x10000
        private const val SEEK_SET = 0
        private const val SEEK_CUR = 1
        private const val SEEK_END = 2

        /** 复用一个 client：连接池和 TLS 会话能复用，换台时省一次握手。 */
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    private var body: ResponseBody? = null
    private var source: BufferedSource? = null

    // ---- 读缓冲 ----
    //
    // ijk 的 HLS 解复用器读完 m3u8 后**会 seek 回 0 再读一遍**。
    // 之前 seek 直接返回 -1，它就一直卡在那里，最后被看门狗判超时。
    // 所以把读过的数据缓存下来，seek 在缓冲范围内直接定位。
    //
    // 每个分片是独立的 open()，缓冲随之重置，不会无限增长。
    private var buf = ByteArray(1 shl 16)
    private var bufLen = 0
    private var pos = 0
    private var eof = false

    private fun ensureCap(need: Int) {
        if (need <= buf.size) return
        var cap = buf.size
        while (cap < need) cap = cap shl 1
        buf = buf.copyOf(cap)
    }

    override fun open(url: String): Int {
        close()
        return try {
            val builder = Request.Builder().url(url)
            var hasUa = false
            for ((k, v) in headers) {
                if (k.isBlank() || v.isBlank()) continue
                if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                runCatching { builder.header(k, v) }
            }
            if (!hasUa) builder.header("User-Agent", DEFAULT_UA)

            val resp = client.newCall(builder.build()).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "open 失败 HTTP ${resp.code}: $url")
                resp.close()
                return -1
            }
            body = resp.body
            source = body?.source()
            Log.i(TAG, "open ok ${resp.code} ${resp.header("Content-Type") ?: ""}")
            0
        } catch (t: Throwable) {
            Log.w(TAG, "open 异常: ${t.message}")
            -1
        }
    }

    override fun read(buffer: ByteArray, size: Int): Int {
        // 缓冲区里还有没消费的就先给出去
        if (pos < bufLen) {
            val n = minOf(bufLen - pos, size)
            System.arraycopy(buf, pos, buffer, 0, n)
            pos += n
            return n
        }
        if (eof) return 0

        val src = source ?: return -1
        return try {
            val n = src.read(buffer, 0, size)
            if (n < 0) {
                eof = true
                0
            } else {
                ensureCap(bufLen + n)
                System.arraycopy(buffer, 0, buf, bufLen, n)
                bufLen += n
                pos += n
                n
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read 异常: ${t.message}")
            -1
        }
    }

    /**
     * 在已缓冲的范围内支持 seek。
     *
     * ijk 的 HLS 解复用器会 seek 回 0 重读 m3u8，不支持就卡住。
     * 超出缓冲范围（还没读到的位置）返回 -1——直播没法往前跳。
     */
    override fun seek(offset: Long, whence: Int): Long {
        val newPos = when (whence) {
            SEEK_SET -> offset
            SEEK_CUR -> pos + offset
            SEEK_END -> return -1L
            IJKAVSEEK_SIZE -> return bufLen.toLong()
            else -> return -1L
        }
        if (newPos < 0 || newPos > bufLen) return -1L
        pos = newPos.toInt()
        return newPos
    }

    override fun close(): Int {
        runCatching { source?.close() }
        runCatching { body?.close() }
        source = null
        body = null
        bufLen = 0
        pos = 0
        eof = false
        return 0
    }
}
