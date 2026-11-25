package com.litv.tv.player

/**
 * 回看里收到 ENDED 时该怎么办。
 *
 * 回看的 ENDED 有两种完全不同的含义，内核分不出来：
 *
 * 1. **真播完了** —— 我们自己算的时间轴已经走到窗口末尾；
 * 2. **中途断了** —— VLC 遇到一个分片取流失败就直接报流结束。
 *    2026-11-25 模拟器实测：`SSLException: Connection reset by peer` 一次，
 *    VLC 立刻 ENDED，而那时候才播了十几秒（窗口 1 小时）。
 *
 * 以前把两者都当成 (1)，于是"一次上游抖动"被升级成"偷偷退出回看"，
 * 用户看到的是画面自己跳回直播，没有任何提示。
 *
 * 判据只有一条：**播放头到没到窗口末尾**。没到就是断了，按当前位置重开，
 * 但要限次——上游真挂了的时候不能无限重连。
 */
enum class ReplayEnd {
    /** 真播完了：回直播，提示「回看结束」。 */
    Finished,

    /** 中途断了：按当前位置重开这段回看。 */
    Restart,

    /** 断了且重试额度用尽：回直播，并告诉用户是中断。 */
    GiveUp,
}

object ReplayEndJudge {
    /** 差这么多就算播完了——最后一小段不值得为它报「中断」。 */
    const val NEAR_END_MS = 5_000L

    /** 默认允许重开两次：偶发的一次抖动不该让用户丢掉回看。 */
    const val DEFAULT_MAX_RETRIES = 2

    /**
     * @param positionMs 我们自己的时间轴（[ReplayClock.positionMs]），不是内核的。
     * @param durationMs 窗口总长；`<= 0` 表示不知道（时移、或节目时长缺失）。
     * @param retriesUsed 已经重开过几次。
     */
    fun decide(
        positionMs: Long,
        durationMs: Long,
        retriesUsed: Int,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
    ): ReplayEnd {
        if (durationMs > 0 && positionMs >= durationMs - NEAR_END_MS) return ReplayEnd.Finished
        // 时长未知时判不了"是不是播完了"，倾向于认为断了——
        // 真播完的那种情况会由 refreshReplayBar 的末尾判定兜住。
        val budgetLeft = retriesUsed < maxRetries
        return if (budgetLeft) ReplayEnd.Restart else ReplayEnd.GiveUp
    }
}
