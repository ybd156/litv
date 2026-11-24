package com.litv.tv.player

/**
 * 一次起播的**来源**。评审 P0-5 的事实是：心跳/QoE 只挂在服务端 `/tv/play` 成功分支上，
 * 缓存、内置源、本地解析、时移四条路径全漏；根因是四条路径各自调 `player.play(...)`，
 * 没有任何一处「起播发生了」的统一事实可供挂载。
 *
 * 这个类型就是把那条事实显式化：四种来源共用同一组播放参数，差异只在**语义**，
 * 于是心跳/QoE/日志可以在唯一入口挂一次，而不是散在四个分支里
 * （心跳要报的频道号见 [heartbeatChannelId]）。
 */
sealed class PlaySource(
    open val channelId: Long,
    open val url: String,
    open val headers: Map<String, String>,
    open val engine: String,
    open val bustCache: Boolean,
) {
    /** 服务端签发的直播地址（历史心跳只挂在这一条路径上）。 */
    class Remote(
        override val channelId: Long,
        override val url: String,
        override val headers: Map<String, String>,
        override val engine: String,
        override val bustCache: Boolean = true,
    ) : PlaySource(channelId, url, headers, engine, bustCache)

    /** 本地缓存的上一次签名地址（省掉一次 `/tv/play` 往返）。 */
    class Cached(
        override val channelId: Long,
        override val url: String,
        override val headers: Map<String, String>,
        override val engine: String,
        override val bustCache: Boolean = true,
    ) : PlaySource(channelId, url, headers, engine, bustCache)

    /** 客户端自己解析 M3U 得到的地址。 */
    class Local(
        override val channelId: Long,
        override val url: String,
        override val headers: Map<String, String>,
        override val engine: String,
        override val bustCache: Boolean = true,
    ) : PlaySource(channelId, url, headers, engine, bustCache)

    /**
     * 内置源（央视频）。按 URL 签名校验，**不能**加 `_t=` 缓存参数，
     * 所以 [bustCache] 恒为 false。
     */
    class Builtin(
        override val channelId: Long,
        override val url: String,
        override val headers: Map<String, String>,
        override val engine: String,
    ) : PlaySource(channelId, url, headers, engine, bustCache = false)

    /** 服务端时移/回看（`/tv/timeshift` 给的地址，本身带时间窗口）。 */
    class Timeshift(
        override val channelId: Long,
        override val url: String,
        override val headers: Map<String, String>,
        override val engine: String,
        override val bustCache: Boolean = true,
    ) : PlaySource(channelId, url, headers, engine, bustCache)

    val label: String
        get() = when (this) {
            is Remote -> "服务端"
            is Cached -> "缓存"
            is Local -> "本地解析"
            is Builtin -> "内置源"
            is Timeshift -> "时移"
        }

    /**
     * 心跳里要报的频道号。
     *
     * 心跳曾经只挂在服务端 `/tv/play` 成功分支上（评审 P0-10），于是缓存命中、
     * 内置源、本地解析、时移四条路径一次都不报，管理台「终端设备」的在线时间
     * 与「正在播放」只在偶尔走中转时才更新。
     *
     * 服务端 `tvHeartbeat`（`server/internal/api/handlers_client_device.go:161`）
     * 只更新设备的 IP/版本并回传 config/channels 版本号，`playing_channel`
     * 解码后既不入库也不校验——它的本质是「这台设备还活着」，与来源无关，
     * 所以四条路径都该报。
     *
     * 唯一要处理的是没有真实频道 id 的来源：内置源用合成 id（实测 -1018），
     * 那种就报 0，表示「在播，但没有服务端认识的频道」。
     */
    val heartbeatChannelId: Long get() = if (channelId > 0) channelId else 0L
}
