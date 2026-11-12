package com.litv.tv.player

/**
 * 换台请求的「代际闸门」。
 *
 * 要解决的问题（真机可见）：连续按 ↑/↓ 换台时，每按一次都会发一个 `/tv/play`。
 * 这些请求是并发发出的（OkHttp enqueue），**返回顺序没有保证**。
 * 没有闸门时，晚到的旧响应会把播放器切回它自己的频道——用户看到的是
 * 「台名已经是 C，画面还在放 A」。同理，旧请求的失败提示会盖掉新台的信息。
 *
 * 用法：
 * ```
 * val seq = gate.begin()            // 每次换台取一个新号
 * api.play(..., onOk = { info ->
 *     if (!gate.isCurrent(seq)) return@play   // 过期回调直接丢弃
 *     ...
 * })
 * ```
 *
 * 线程约定：只在主线程使用。Api 的回调已经统一 post 到主线程，
 * 所以这里不需要加锁（加锁反而会掩盖"在后台线程碰 UI 状态"的问题）。
 */
class PlayRequestGate {

    private var current = 0

    /** 开始一次新的播放请求，返回它的代际号。 */
    fun begin(): Int {
        current += 1
        return current
    }

    /** 这次回调的代际是否仍然是当前请求；false 表示已过期，必须整段丢弃。 */
    fun isCurrent(seq: Int): Boolean = seq == current

    /** 作废所有在途请求（例如离开播放页），之后到达的回调全部会被判过期。 */
    fun invalidate() {
        current += 1
    }
}
