package com.litv.tv.data

import android.content.Context
import android.util.Log
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 用 Cronet（Chromium 的 HTTP/TLS 栈）取流的极简封装，**只同步**。
 *
 * 为什么必须另起一套：央视频**回看**的 CDN（`tlivecloud-playback-cdn.ysp.cctv.cn`）
 * 对发起请求的 TLS 栈敏感 —— 同一个 URL，
 *   · Python urllib（OpenSSL）            → 200
 *   · Android HttpURLConnection（Conscrypt）→ `SocketException: Connection reset`
 * 加 `Connection: close` / `Accept-Encoding: identity` / 换 UA 都无效。
 * 直播 CDN 没这个问题，所以只有回看路径用这里。
 *
 * 只用一次建 Engine（很重，几百毫秒），之后复用。
 */
object CronetFetcher {

    private const val TAG = "Cronet"

    @Volatile private var engine: CronetEngine? = null
    private val executor = Executors.newFixedThreadPool(4)

    /** 懒建 Engine。失败返回 null（调用方回落 HttpURLConnection）。 */
    private fun engineOrNull(ctx: Context): CronetEngine? {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            return runCatching {
                CronetEngine.Builder(ctx.applicationContext)
                    .enableQuic(false)   // 央视 CDN 不走 QUIC，关掉省事
                    // ★ 必须关 HTTP/2：回看的 CDN 在 h2 连接上会主动 RST
                    //   （net::ERR_CONNECTION_RESET），而同一个 URL 用 h1.1 是 200。
                    //   直播 CDN 没这问题，但这里统一走 h1.1 更稳。
                    .enableHttp2(false)
                    .build()
                    .also {
                        engine = it
                        Log.i(TAG, "Cronet 已初始化")
                    }
            }.getOrElse { e ->
                Log.w(TAG, "Cronet 初始化失败：" + e.message)
                null
            }
        }
    }

    /**
     * 同步取字节。失败抛异常，调用方决定怎么回落。
     *
     * @param userAgent 有些 CDN 靠 UA 分流，默认给 qqlive（和直播一致）
     */
    @Throws(Exception::class)
    fun fetch(ctx: Context, url: String, userAgent: String = "qqlive", timeoutMs: Long = 20000): ByteArray {
        val eng = engineOrNull(ctx) ?: throw IllegalStateException("Cronet 不可用")
        val out = ByteArrayOutputStream()
        val err = AtomicReference<Throwable?>(null)
        val status = AtomicReference(-1)
        val latch = CountDownLatch(1)

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String,
            ) {
                // 跟随重定向
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                status.set(info.httpStatusCode)
                Log.i(TAG, "响应开始 code=" + info.httpStatusCode +
                    " neg=" + info.negotiatedProtocol +
                    " len=" + info.allHeaders["content-length"] +
                    " url尾=" + info.url.takeLast(50))
                request.read(ByteBuffer.allocateDirect(64 * 1024))
            }

            override fun onReadCompleted(
                request: UrlRequest,
                info: UrlResponseInfo,
                buffer: ByteBuffer,
            ) {
                buffer.flip()
                if (buffer.hasRemaining()) {
                    val chunk = ByteArray(buffer.remaining())
                    buffer.get(chunk)
                    out.write(chunk)
                }
                buffer.clear()
                request.read(buffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                Log.i(TAG, "完成 code=" + info.httpStatusCode + " 字节=" + -1)
                latch.countDown()
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
                Log.w(TAG, "失败 code=" + (info?.httpStatusCode ?: -1) +
                    " 已读=" + -1 + " : " + error.message)
                err.set(error)
                latch.countDown()
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                err.set(java.io.IOException("cancelled"))
                latch.countDown()
            }
        }

        val req = eng.newUrlRequestBuilder(url, callback, executor)
            .setHttpMethod("GET")
            .addHeader("User-Agent", userAgent)
            .build()
        req.start()
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            runCatching { req.cancel() }
            throw java.io.IOException("Cronet 超时 ${timeoutMs}ms")
        }
        err.get()?.let { throw java.io.IOException("Cronet: " + (it.message ?: it.javaClass.simpleName), it) }
        val code = status.get()
        if (code !in 200..299) throw java.io.IOException("Cronet HTTP $code")
        return out.toByteArray()
    }
}
