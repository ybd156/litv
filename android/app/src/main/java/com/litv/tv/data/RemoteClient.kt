package com.litv.tv.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 遥控通道（电视端）。
 *
 * 电视启动后连一条 WebSocket 到服务端待命，手机发来的按键从这里进来。
 *
 * 为什么用 WebSocket 而不是让电视开 HTTP 端口：
 *  - 电视不用暴露端口、不用做局域网发现、不用处理 IP 变化
 *  - 手机和电视只要都能连到服务端就行（跨网段也能用）
 *  - 鉴权天然统一（双方都是 device token）
 */
class RemoteClient(private val prefs: Prefs) {

    /** 收到指令的回调。参数是原始 JSON。 */
    var onCommand: ((JSONObject) -> Unit)? = null

    /** 连接状态变化（电视端可以显示"遥控已就绪"）。 */
    var onState: ((connected: Boolean) -> Unit)? = null

    private var ws: WebSocket? = null
    private var closedByUs = false
    private var retry = 0

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect() {
        if (prefs.token.isBlank() || prefs.serverBase.isBlank()) return
        if (ws != null) return
        closedByUs = false

        val wsBase = prefs.serverBase
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val url = wsBase.trimEnd('/') + "/api/v1/tv/ws"

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${prefs.token}")
            .build()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "遥控通道已连接")
                retry = 0
                onState?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = JSONObject(text)
                    onCommand?.invoke(obj)
                }.onFailure { Log.w(TAG, "指令解析失败: $text", it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "遥控通道断开: ${t.message}")
                ws = null
                onState?.invoke(false)
                scheduleRetry()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                onState?.invoke(false)
                if (!closedByUs) scheduleRetry()
            }
        })
    }

    /** 断线重连：1s → 2s → 4s → 最长 30s，别把服务端打爆。 */
    private fun scheduleRetry() {
        if (closedByUs) return
        val delay = (1000L shl retry.coerceAtMost(5)).coerceAtMost(30_000L)
        retry++
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!closedByUs) connect()
        }, delay)
    }

    fun disconnect() {
        closedByUs = true
        ws?.close(1000, "bye")
        ws = null
        onState?.invoke(false)
    }

    private companion object {
        const val TAG = "RemoteClient"
    }
}
