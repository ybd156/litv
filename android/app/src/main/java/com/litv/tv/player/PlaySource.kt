package com.litv.tv.player

/**
 * 一次起播的**来源**。评审 P0-5 的事实是：心跳/QoE 只挂在服务端 `/tv/play` 成功分支上，
 * 缓存、内置源、本地解析、时移四条路径全漏；根因是四条路径各自调 `player.play(...)`，
 * 没有任何一处「起播发生了」的统一事实可供挂载。
 *
 * 这个类型就是把那条事实显式化：四种来源共用同一组播放参数，差异只在**语义**，
 * 于是心跳/QoE/日志可以在唯一入口按 [needsHeartbeat] 分流，而不是散在四个分支里。
 */
sealed class PlaySource(
    open val channelId: Long,
    open val url: String,
    open val headers: Map<String, String>,
    open val engine: String,
    open val bustCache: Boolean,
) {
    /** 服务端 `/tv/play` 签发的直播地址（唯一会向服务端报心跳/QoE 的路径）。 */
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

    /** 是否需要向服务端报心跳与 QoE：只有服务端签发的地址才有服务端那一侧的记账。 */
    val needsHeartbeat: Boolean get() = this is Remote
}
