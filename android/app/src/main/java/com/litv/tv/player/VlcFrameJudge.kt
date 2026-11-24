package com.litv.tv.player

/**
 * VLC「起播/重建后到底有没有出画」的判定。
 *
 * 为什么单独拉出来：这段判定的三个输入（屏幕是否亮着、内核是否还在、采样窗口内的帧数增量）
 * 之前揉在 `PlayerManager.vlcFrameRecheck` 的 lambda 里，只有真机才能验，
 * 而它恰恰是最容易误判、误判代价最大（白重建一轮播放器）的一段。
 *
 * 真机事故 2026-11-25：电视息屏后显示管线停摆，`displayedPictures` 恒为 0，
 * 于是「起播 12 秒 → 复检 0 帧 → 重建一次 → 再复检 0 帧 → 不再重试」，
 * 日志留下 `VLC 起播后无画面（0 -> 0）`，而实际上只是屏幕关着。
 * 息屏不是故障，是用户主动关的屏：**此时不判、不重建、不报错**。
 */
enum class VlcFrameDecision {
    /** 屏幕已熄灭：没有显示管线就没有帧，任何「没画面」的结论都不成立。 */
    SkipAsleep,

    /** 内核已经不是 VLC（换台/换内核的残留回调）：无从判断。 */
    SkipNoEngine,

    /** 采样窗口内出画了，正常。 */
    Normal,

    /** 没出画，但还没重建过：重建一次。 */
    Rebuild,

    /** 没出画，已经重建过：放弃，等用户换台。 */
    GiveUp,
}

object VlcFrameJudge {

    /**
     * 采样窗口内至少要有这么多帧才算「在出画」。
     *
     * 2 而不是 5：起播阶段帧率本来就低（缓冲刚结束、关键帧稀疏），
     * 阈值定高了会把正常起播判成定屏，重建后又进缓冲，形成卡顿循环。
     */
    const val MIN_FRAMES = 2

    fun judge(
        screenInteractive: Boolean,
        enginePresent: Boolean,
        framesDelta: Int,
        alreadyRetried: Boolean,
    ): VlcFrameDecision = when {
        !screenInteractive -> VlcFrameDecision.SkipAsleep
        !enginePresent -> VlcFrameDecision.SkipNoEngine
        framesDelta >= MIN_FRAMES -> VlcFrameDecision.Normal
        alreadyRetried -> VlcFrameDecision.GiveUp
        else -> VlcFrameDecision.Rebuild
    }
}
