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
     * 是否需要向服务端报心跳。
     *
     * 曾经是 `this is Remote`（只有中转路径报），结果是缓存命中、内置源、
     * 本地解析、时移四条路径一次都不报——管理台「终端设备」的在线时间与
     * 「正在播放」只在偶尔走中转时才更新。
     *
     * 服务端 `tvHeartbeat` 的实现（`server/internal/api/handlers_client_device.go:161`）
     * 只做两件事：更新设备的 IP/版本、返回 config/channels 版本号；
     * `playing_channel` 只解码不校验。所以它**不依赖频道在服务端存在**，
     * 四条路径都可以报。唯一要挡的是没有真实频道 id 的情况（内置源的合成 id
     * 可能为 0 或负数），那种报上去只会污染设备记录。
     */
    val needsHeartbeat: Boolean get() = channelId > 0
}
