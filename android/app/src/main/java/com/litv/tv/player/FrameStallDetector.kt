package com.litv.tv.player

/**
 * 画面是否还在推进（跨内核同尺度）。
 *
 * 为什么需要它
 * ------------
 * 两个内核在「怎么算卡住」这件事上**完全不一致**，所以「卡不卡」根本没法比较：
 *   · Exo：有真实 `positionMs`，看门狗用「位置 16 秒不动」判卡；但它一处在
 *     BUFFERING 就得豁免，否则「重缓冲 → 判死 → 重建 → 更久才出画」会放大。
 *   · VLC：`positionMs` 故意返回 -1（libVLC 播 HLS 直播时 `player.time` 常常
 *     不前进，用它会误判），`isBuffering` 也是 false（拿不到可靠状态）。
 *     于是 PlayerManager 的位置看门狗**对 VLC 全程跳过**，唯一信号是 VLC 内部
 *     「12 秒收不到 TimeChanged」——也就是说 VLC 侧 5 秒、10 秒的卡顿，日志里
 *     一个字都不会有。2026-11-23 的 A/B 就卡在这：Exo 12 分钟 9 次重缓冲，
 *     VLC「零事件」，但用户说两边都卡。
 *
 * 判据：**已显示帧数在 [stallMs] 内没有增长**。这个信号两内核都拿得到
 * （Exo 来自 AnalyticsListener 的累计 frameCount，VLC 来自
 * `MediaStats.displayedPictures`），而且与网络、缓冲、位置语义都无关。
 *
 * 本类**只做判定，不做动作**。把「判定」和「重启/换内核」分开，是为了先量出
 * 真实数据（各内核各卡多久、多久一次），再决定动作阈值——反过来先接重启，
 * 会把「慢」放大成「重建播放器」。
 */
class FrameStallDetector(
    /** 帧数多久不增长算停帧。 */
    private val stallMs: Long = 5_000,
    /** 同一段停帧里，重复上报的最小间隔（避免每 2 秒刷一条）。 */
    private val repeatReportMs: Long = 10_000,
) {

    enum class State {
        /** 不判定：没起播、已暂停、息屏，或该内核不提供帧数。 */
        IDLE,

        /** 画面在推进。 */
        OK,

        /** 判定停帧（本次采样新判定，或按 [repeatReportMs] 的重复上报）。 */
        STALLED,
    }

    data class Verdict(val state: State, val stalledMs: Long = 0, val frames: Long = -1)

    companion object {
        /**
         * 取「画面推进」信号：优先累计帧数，其次播放位置。
         *
         * 三个内核能给的东西不一样，但「有没有在推进」这件事必须统一：
         *   · Exo：帧数 + 位置都可靠 → 用帧数（帧数不受 seek/倍速影响）。
         *   · VLC：位置**不可靠**（HLS 直播时 `player.time` 经常不前进，
         *     用它会把正常播放判成卡死），但 `displayedPictures` 可靠 → 用帧数。
         *   · mpv：`time-pos` 可靠，但没有累计帧数口径 → 用位置（单位 ms）。
         *
         * 单位必须带出去，否则日志里「帧数 12000 未增长」会被当成 12000 帧。
         */
        fun progressOf(stats: EngineStats?): Progress? {
            if (stats == null) return null
            if (stats.displayedFrames >= 0) return Progress(stats.displayedFrames, "帧")
            if (stats.positionMs >= 0) return Progress(stats.positionMs, "ms")
            return null
        }
    }

    data class Progress(val value: Long, val unit: String)

    private var baselineFrames = -1L
    private var lastChangeAt = 0L
    private var stalled = false
    private var lastReportAt = 0L

    /** 换台/重连时清空，避免拿上一个内核的基线去判新内核。 */
    fun reset() {
        baselineFrames = -1L
        lastChangeAt = 0L
        stalled = false
        lastReportAt = 0L
    }

    /**
     * 采样一次。
     *
     * @param nowMs 单调时钟（`SystemClock.elapsedRealtime()`），不要传墙上时间。
     * @param frames 内核累计已显示帧数；负数表示该内核不提供。
     * @param playing 是否处于「应当出画」的状态（已起播、未暂停）。
     * @param interactive 屏幕是否亮着且解锁——息屏时不出帧是正常的，不能判卡。
     */
    fun sample(nowMs: Long, frames: Long, playing: Boolean, interactive: Boolean): Verdict {
        if (!playing || !interactive || frames < 0) {
            // 进入不判定状态：重置基线，避免恢复后拿旧时间戳算出一次假停帧。
            baselineFrames = -1L
            stalled = false
            lastChangeAt = 0L
            return Verdict(State.IDLE, frames = frames)
        }
        if (baselineFrames < 0) {
            baselineFrames = frames
            lastChangeAt = nowMs
            return Verdict(State.OK, frames = frames)
        }
        if (frames != baselineFrames) {
            baselineFrames = frames
            lastChangeAt = nowMs
            stalled = false
            return Verdict(State.OK, frames = frames)
        }
        val still = nowMs - lastChangeAt
        if (still < stallMs) return Verdict(State.OK, frames = frames)
        if (!stalled) {
            stalled = true
            lastReportAt = nowMs
            return Verdict(State.STALLED, still, frames)
        }
        if (nowMs - lastReportAt >= repeatReportMs) {
            lastReportAt = nowMs
            return Verdict(State.STALLED, still, frames)
        }
        return Verdict(State.OK, frames = frames)
    }
}
