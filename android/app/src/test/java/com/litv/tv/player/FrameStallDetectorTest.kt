package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FrameStallDetector] 的行为约束。
 *
 * 这些用例守的是「判据本身」——它一旦松了，跨内核比较就重新变成猜：
 *   · 帧数在涨就不许报停帧（否则正常播放会被当成卡）；
 *   · **一段停帧只报一次首行**，之后只在里程碑补报（旧版每 10 秒一行，
 *     一次 126 秒的卡死在日志里变成 13 条「画面停帧」，事件数直接失真）；
 *   · 恢复时必须给一行 RECOVERED 并带上总时长；
 *   · 息屏/暂停/内核不提供帧数/**帧数还是 0** 时不判定，且不保留基线
 *     （2026-11-24 真机：切台后帧计数从 0 重来，旧判据每次都打一条「停帧 6s（0帧）」）。
 */
class FrameStallDetectorTest {

    private fun detector() = FrameStallDetector(
        stallMs = 5_000,
        milestonesMs = longArrayOf(30_000, 60_000, 120_000),
    )

    @Test
    fun `帧数持续增长不报停帧`() {
        val d = detector()
        var frames = 10L
        var now = 0L
        repeat(20) {
            now += 2_000
            frames += 50
            assertEquals(
                FrameStallDetector.State.OK,
                d.sample(now, frames, playing = true, interactive = true).state,
            )
        }
    }

    @Test
    fun `一段停帧只报一次首行 之后只在里程碑补报`() {
        val d = detector()
        d.sample(1_000, 100, true, true) // 基线
        assertEquals(FrameStallDetector.State.OK, d.sample(3_000, 100, true, true).state)
        assertEquals(FrameStallDetector.State.OK, d.sample(5_000, 100, true, true).state)

        // 满 5 秒：首行
        val first = d.sample(6_000, 100, true, true)
        assertEquals(FrameStallDetector.State.STALLED, first.state)
        assertTrue(first.firstReport)
        assertEquals(5_000, first.stalledMs)

        // 之后每 2 秒采样都不再重复（旧版这里会每 10 秒刷一行）
        for (t in longArrayOf(8_000, 10_000, 12_000, 14_000, 16_000, 18_000, 20_000)) {
            assertEquals(
                "t=$t 不应重复上报",
                FrameStallDetector.State.OK,
                d.sample(t, 100, true, true).state,
            )
        }

        // 跨过 30 秒里程碑：补一行，且不是首行
        val milestone = d.sample(35_000, 100, true, true)
        assertEquals(FrameStallDetector.State.STALLED, milestone.state)
        assertTrue(!milestone.firstReport)

        // 60 秒里程碑再来一行
        val next = d.sample(61_000, 100, true, true)
        assertEquals(FrameStallDetector.State.STALLED, next.state)
        assertTrue(!next.firstReport)

        // 恢复：给 RECOVERED 并带总时长（1_000 → 70_000）
        val rec = d.sample(70_000, 180, true, true)
        assertEquals(FrameStallDetector.State.RECOVERED, rec.state)
        assertEquals(69_000, rec.stalledMs)
    }

    @Test
    fun `恢复推进后再次静止能重新判定`() {
        val d = detector()
        d.sample(0, 100, true, true)
        assertEquals(FrameStallDetector.State.STALLED, d.sample(5_000, 100, true, true).state)
        val rec = d.sample(7_000, 160, true, true)
        assertEquals(FrameStallDetector.State.RECOVERED, rec.state)
        // 再静止 5s → 又能报（不是"报过就哑了"）
        val again = d.sample(12_000, 160, true, true)
        assertEquals(FrameStallDetector.State.STALLED, again.state)
        assertTrue(again.firstReport)
    }

    @Test
    fun `暂停或息屏时不判定且不保留基线`() {
        val d = detector()
        d.sample(0, 100, true, true)
        repeat(5) { i ->
            assertEquals(
                FrameStallDetector.State.IDLE,
                d.sample(1_000L + i * 2_000, 100, true, false).state,
            )
        }
        // 亮屏后重新建立基线：即使帧数与息屏前相同，也不该立刻判停帧
        assertEquals(FrameStallDetector.State.OK, d.sample(12_000, 100, true, true).state)
        assertEquals(FrameStallDetector.State.OK, d.sample(14_000, 100, true, true).state)
        assertEquals(FrameStallDetector.State.STALLED, d.sample(17_000, 100, true, true).state)
    }

    @Test
    fun `帧数为零时不当成停帧`() {
        // 切台/重建后帧计数从 0 重新累计，这时候「不增长」不代表卡
        val d = detector()
        repeat(10) { i ->
            assertEquals(
                FrameStallDetector.State.IDLE,
                d.sample(i * 2_000L, 0, true, true).state,
            )
        }
        // 真正出帧后才开始判定
        assertEquals(FrameStallDetector.State.OK, d.sample(30_000, 12, true, true).state)
        assertEquals(FrameStallDetector.State.STALLED, d.sample(36_000, 12, true, true).state)
    }

    @Test
    fun `内核不提供帧数时不判定`() {
        val d = detector()
        repeat(5) { i ->
            assertEquals(FrameStallDetector.State.IDLE, d.sample(i * 2_000L, -1, true, true).state)
        }
        // 之后拿到了帧数（例如 VLC 的 stats 首次可用）→ 正常建立基线
        assertEquals(FrameStallDetector.State.OK, d.sample(20_000, 42, true, true).state)
    }

    @Test
    fun `换台重置后不继承上一个内核的基线`() {
        val d = detector()
        d.sample(0, 100, true, true)
        assertEquals(FrameStallDetector.State.STALLED, d.sample(5_000, 100, true, true).state)
        d.reset()
        // 新台起播：即使帧数恰好相同，也不该马上判停
        assertEquals(FrameStallDetector.State.OK, d.sample(6_000, 100, true, true).state)
        assertEquals(FrameStallDetector.State.STALLED, d.sample(11_000, 100, true, true).state)
    }

    // ---------------------------------------------------------------- 三内核的推进信号

    @Test
    fun `推进信号按内核能力选取并带单位`() {
        // Exo：帧数与位置都有 → 用帧数
        assertEquals(
            FrameStallDetector.Progress(900, "帧"),
            FrameStallDetector.progressOf(EngineStats(positionMs = 12_345, displayedFrames = 900)),
        )
        // VLC：位置不可靠（-1），只有帧数 → 用帧数
        assertEquals(
            FrameStallDetector.Progress(777, "帧"),
            FrameStallDetector.progressOf(EngineStats(positionMs = -1, displayedFrames = 777)),
        )
        // mpv：没有累计帧数口径，但 time-pos 可靠 → 用位置，单位必须是 ms
        assertEquals(
            FrameStallDetector.Progress(4_200, "ms"),
            FrameStallDetector.progressOf(EngineStats(positionMs = 4_200, displayedFrames = -1)),
        )
        // 什么都拿不到 → 不判定（不许拿 0 当信号，否则会立刻误判停帧）
        assertEquals(null, FrameStallDetector.progressOf(EngineStats(positionMs = -1, displayedFrames = -1)))
        assertEquals(null, FrameStallDetector.progressOf(null))
    }
}
