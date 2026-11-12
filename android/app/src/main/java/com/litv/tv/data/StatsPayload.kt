package com.litv.tv.data

/**
 * `/api/v1/tv/stats` 的请求体构造（纯函数，可在 JVM 单测里断言）。
 *
 * 为什么要单独抽出来：这段 JSON 形状直接受服务端 `decode()` 的
 * `DisallowUnknownFields` 约束——多发一个不认识的键就是 400。埋点又必须静默失败，
 * 所以"到底发了哪些键"必须能被测试钉死，而不是靠人肉读代码。
 *
 * 兼容性约定（写死在这里，别改）：
 *   - `start` / `error` 事件的键集合**必须**保持历史四个：
 *     channel_id / event / startup_ms / detail —— 老服务端能收。
 *   - `stall_ms` / `watch_ms` / `path` / `engine` 只在 `stall` 与 `stop` 事件里出现。
 *     原因：这两类是"新事件/新语义"，对应的服务端列是本轮才加的（A3）。
 *     `stop` 以前客户端从来没发过，所以老服务端即使拒收也不会丢历史数据；
 *     代价只是「新 APK + 旧 NAS」时这两类上报被 400 静默丢弃，播放完全不受影响。
 */
object StatsPayload {

    /**
     * @param path   播放链路：ts | hls | remux（取服务端 [PlayInfo.kind]，**不是** direct/relay）
     * @param engine 内核 key：exo | vlc | mpv | avs3（取 [com.litv.tv.player.EngineKind.key]）
     */
    fun build(
        channelId: Long,
        event: String,
        startupMs: Int = 0,
        detail: String = "",
        stallMs: Int = 0,
        watchMs: Int = 0,
        path: String = "",
        engine: String = "",
    ): Map<String, Any?> {
        val body = LinkedHashMap<String, Any?>()
        body["channel_id"] = channelId
        body["event"] = event
        body["startup_ms"] = startupMs
        body["detail"] = detail
        if (event == "stall" || event == "stop") {
            body["stall_ms"] = stallMs
            body["watch_ms"] = watchMs
            if (path.isNotBlank()) body["path"] = path
            if (engine.isNotBlank()) body["engine"] = engine
        }
        return body
    }
}
