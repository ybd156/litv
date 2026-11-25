package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回看 ENDED 的判定：真播完 vs 中途断。
 *
 * 这两条真的分错过一次——VLC 一个分片 Connection reset 就报 ENDED，
 * 当时我们无条件当"播完了"，于是回看自己跳回直播（2026-11-25 模拟器）。
 */
class ReplayEndJudgeTest {

    @Test
    fun `播到窗口末尾算正常结束`() {
        assertEquals(
            ReplayEnd.Finished,
            ReplayEndJudge.decide(positionMs = 3_600_000L, durationMs = 3_600_000L, retriesUsed = 0),
        )
    }

    @Test
    fun `差几秒也算正常结束`() {
        // 最后一小段没播到不值得报"中断"，直接回直播更自然。
        assertEquals(
            ReplayEnd.Finished,
            ReplayEndJudge.decide(positionMs = 3_596_000L, durationMs = 3_600_000L, retriesUsed = 0),
        )
    }

    @Test
    fun `位置超过窗口（时钟被推过头）也算正常结束`() {
        assertEquals(
            ReplayEnd.Finished,
            ReplayEndJudge.decide(positionMs = 3_700_000L, durationMs = 3_600_000L, retriesUsed = 0),
        )
    }

    @Test
    fun `刚开播就 ENDED 是中途断，还有额度就重开`() {
        assertEquals(
            ReplayEnd.Restart,
            ReplayEndJudge.decide(positionMs = 16_000L, durationMs = 3_600_000L, retriesUsed = 0),
        )
    }

    @Test
    fun `差得远就是中途断，同样重开`() {
        assertEquals(
            ReplayEnd.Restart,
            ReplayEndJudge.decide(positionMs = 1_800_000L, durationMs = 3_600_000L, retriesUsed = 1),
        )
    }

    @Test
    fun `额度用尽就放弃，不要无限重连`() {
        assertEquals(
            ReplayEnd.GiveUp,
            ReplayEndJudge.decide(
                positionMs = 16_000L,
                durationMs = 3_600_000L,
                retriesUsed = ReplayEndJudge.DEFAULT_MAX_RETRIES,
            ),
        )
    }

    @Test
    fun `时长未知时先重开一次，额度用尽才放弃`() {
        assertEquals(
            ReplayEnd.Restart,
            ReplayEndJudge.decide(positionMs = 60_000L, durationMs = -1L, retriesUsed = 0),
        )
        assertEquals(
            ReplayEnd.GiveUp,
            ReplayEndJudge.decide(positionMs = 60_000L, durationMs = 0L, retriesUsed = 2),
        )
    }

    @Test
    fun `位置为负（刚起播还没走）按中途断处理`() {
        assertEquals(
            ReplayEnd.Restart,
            ReplayEndJudge.decide(positionMs = -1L, durationMs = 3_600_000L, retriesUsed = 0),
        )
    }
}
