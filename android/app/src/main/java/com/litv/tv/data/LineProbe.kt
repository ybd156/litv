package com.litv.tv.data

import java.net.HttpURLConnection
import java.net.URL

/**
 * 线路连通性测量（客户端自己测）。
 *
 * 服务端下发的 `health` 只有三种值：1 健康 / 0 失效 / -1 未探测。
 * 实测绝大多数线路都是 -1（服务端没跑过探测任务），
 * 界面上就显示成「未检测」——对用户没有任何信息量。
 *
 * 与其等管理员去服务端跑探测，不如客户端在打开线路选择时自己测一次：
 * 向签名地址发一个只取首字节的请求，量一下首字节耗时。
 * 这是**用户自己这台设备、这条网络**到源站的真实情况，
 * 比服务端那边的探测结果更有参考价值。
 *
 * 边界：
 * - 只取 Range: bytes=0-0，不下载整个分片
 * - 超时 4 秒，失败即判失效
 * - 结果由调用方缓存，不要每次聚焦都测
 */
object LineProbe {

    /** 测量结果。latencyMs 为 null 表示连不上。 */
    data class Result(val latencyMs: Int?) {
        val ok: Boolean get() = latencyMs != null
    }

    /**
     * 测一条线路的首字节耗时。
     *
     * @param url 带签名的播放地址（由 /api/v1/tv/play/{id} 下发）
     * @param ua  这条线路专属的 User-Agent，空则用默认
     * @return 毫秒；连不上返回 null
     */
    fun measure(url: String, ua: String, timeoutMs: Int = 4_000): Result {
        if (url.isBlank()) return Result(null)
        val started = System.currentTimeMillis()
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                // 只要首字节：不把整个分片拉下来
                setRequestProperty("Range", "bytes=0-0")
                if (ua.isNotBlank()) setRequestProperty("User-Agent", ua)
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
            }
            try {
                conn.inputStream.use { it.read() }
                Result((System.currentTimeMillis() - started).toInt())
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            // 打类名而不是完整 URL：地址里带签名参数
            android.util.Log.i("LITV", "线路探测失败：${t.javaClass.simpleName}")
            Result(null)
        }
    }
}
