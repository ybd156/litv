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
 *
 * 上报节奏（2026-11-24 改）
 * ------------------------
 * 旧版按固定 10 秒重复上报同一段停帧，结果**一次卡死被写成 13 行**
 * （真机：21:44:51 起 6s→16s→26s…→126s，帧数恒为 209）。这样日志里的
 * 「停帧次数」根本不是事件数，拿它做改前改后的对比会被自己骗。
 *
 * 现在的约定，一条停帧事件最多产生这几行：
 *   · `STALLED(firstReport=true)`  刚满 [stallMs] 时报一次 —— 事件开始
 *   · `STALLED(firstReport=false)` 持续到 [milestonesMs] 里的节点时补一行 —— 说明它没恢复
 *   · `RECOVERED`                  画面重新推进时报一次，带**总停帧时长**
 * 于是「数事件」只数 firstReport 的那些，时长看 RECOVERED 那行。
 */
class FrameStallDetector(
    /** 帧数多久不增长算停帧。 */
    private val stallMs: Long = 5_000,
    /** 一段停帧持续到这些时长时补报一次（里程碑，不是轮询）。 */
    private val milestonesMs: LongArray = longArrayOf(30_000, 60_000, 120_000, 300_000, 600_000),
) {

    enum class State {
        /** 不判定：没起播、已暂停、息屏，或该内核还不能给出有效帧数。 */
        IDLE,

        /** 画面在推进，或本次采样不需要上报。 */
        OK,

        /** 判定停帧。[Verdict.firstReport] 为真表示这是本次停帧的第一行。 */
        STALLED,

        /** 之前判过停帧，这次帧数重新增长 —— 本次停帧结束。 */
        RECOVERED,
    }

    data class Verdict(
        val state: State,
        val stalledMs: Long = 0,
        val frames: Long = -1,
        /** 仅 STALLED 有意义：是否本次停帧的第一行。 */
        val firstReport: Boolean = false,
    )

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

    /** 本次停帧已经补报过到哪一档里程碑；-1 表示还没补报过。 */
    private var reportedMilestone = -1L

    /** 换台/重连时清空，避免拿上一个内核的基线去判新内核。 */
    fun reset() {
        baselineFrames = -1L
        lastChangeAt = 0L
        stalled = false
        reportedMilestone = -1L
    }

    /**
     * 采样一次。
     *
     * @param nowMs 单调时钟（`SystemClock.elapsedRealtime()`），不要传墙上时间。
     * @param frames 内核累计已显示帧数；负数和 0 都表示「还没有信号」。
     * @param playing 是否处于「应当出画」的状态（已起播、未暂停）。
     * @param interactive 屏幕是否亮着且解锁——息屏时不出帧是正常的，不能判卡。
     */
    fun sample(nowMs: Long, frames: Long, playing: Boolean, interactive: Boolean): Verdict {
        // frames <= 0 一律当作「还没有信号」，不建立基线。
        //
        // ★ 2026-11-24 真机实测：切台/重建播放器后帧计数从 0 重新累计，
        //   旧判据把「0 帧」当成「帧数不增长」，于是每次换台都打一条
        //   「画面停帧 6s（0帧）」。那不是卡，是计数还没开始。
        if (!playing || !interactive || frames <= 0) {
            baselineFrames = -1L
            lastChangeAt = 0L
            stalled = false
            reportedMilestone = -1L
            return Verdict(State.IDLE, frames = frames)
        }
        if (baselineFrames < 0) {
            baselineFrames = frames
            lastChangeAt = nowMs
            return Verdict(State.OK, frames = frames)
        }
        if (frames != baselineFrames) {
            val wasStalled = stalled
            val stalledFor = if (wasStalled) nowMs - lastChangeAt else 0L
            baselineFrames = frames
            lastChangeAt = nowMs
            stalled = false
            reportedMilestone = -1L
            return if (wasStalled) Verdict(State.RECOVERED, stalledFor, frames)
            else Verdict(State.OK, frames = frames)
        }
        val still = nowMs - lastChangeAt
        if (still < stallMs) return Verdict(State.OK, frames = frames)
        if (!stalled) {
            stalled = true
            reportedMilestone = -1L
            return Verdict(State.STALLED, still, frames, firstReport = true)
        }
        // 已判过停帧：只在跨过下一个里程碑时补一行，不再每 10 秒刷。
        val crossed = milestonesMs.lastOrNull { it <= still && it > reportedMilestone }
        return if (crossed != null) {
            reportedMilestone = crossed
            Verdict(State.STALLED, still, frames, firstReport = false)
        } else {
            Verdict(State.OK, frames = frames)
        }
    }
}
