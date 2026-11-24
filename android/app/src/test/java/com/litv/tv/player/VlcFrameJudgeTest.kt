package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 真机事故 2026-11-25（电视 MiTV-ASTP0 息屏）：
 * `displayedPictures` 恒 0 → 「起播 12 秒复检无画面 → 重建一次 → 再复检无画面 → 放弃」，
 * 日志写着 `VLC 起播后无画面（0 -> 0）`，而实际上播放正常、只是屏幕关着。
 */
class VlcFrameJudgeTest {

    @Test
    fun 息屏时即使零帧也不下结论() {
        assertEquals(
            VlcFrameDecision.SkipAsleep,
            VlcFrameJudge.judge(
                screenInteractive = false,
                enginePresent = true,
                framesDelta = 0,
                alreadyRetried = false,
            ),
        )
    }

    @Test
    fun 息屏优先于已重建过_避免误报放弃() {
        assertEquals(
            VlcFrameDecision.SkipAsleep,
            VlcFrameJudge.judge(
                screenInteractive = false,
                enginePresent = true,
                framesDelta = 0,
                alreadyRetried = true,
            ),
        )
    }

    @Test
    fun 内核已被换掉时不判() {
        assertEquals(
            VlcFrameDecision.SkipNoEngine,
            VlcFrameJudge.judge(
                screenInteractive = true,
                enginePresent = false,
                framesDelta = 0,
                alreadyRetried = false,
            ),
        )
    }

    @Test
    fun 亮屏零帧首次判重建() {
        assertEquals(
            VlcFrameDecision.Rebuild,
            VlcFrameJudge.judge(
                screenInteractive = true,
                enginePresent = true,
                framesDelta = 0,
                alreadyRetried = false,
            ),
        )
    }

    @Test
    fun 亮屏零帧且已重建过则放弃() {
        assertEquals(
            VlcFrameDecision.GiveUp,
            VlcFrameJudge.judge(
                screenInteractive = true,
                enginePresent = true,
                framesDelta = 0,
                alreadyRetried = true,
            ),
        )
    }

    @Test
    fun 达到阈值即判正常_不受是否重建过影响() {
        for (retried in listOf(false, true)) {
            assertEquals(
                VlcFrameDecision.Normal,
                VlcFrameJudge.judge(
                    screenInteractive = true,
                    enginePresent = true,
                    framesDelta = VlcFrameJudge.MIN_FRAMES,
                    alreadyRetried = retried,
                ),
            )
        }
    }

    @Test
    fun 差一帧仍算没出画() {
        assertEquals(
            VlcFrameDecision.Rebuild,
            VlcFrameJudge.judge(
                screenInteractive = true,
                enginePresent = true,
                framesDelta = VlcFrameJudge.MIN_FRAMES - 1,
                alreadyRetried = false,
            ),
        )
    }
}
