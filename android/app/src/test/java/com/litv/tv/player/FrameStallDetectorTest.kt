package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FrameStallDetector] 的行为约束。
 *
 * 这些用例守的是「判据本身」——它一旦松了，跨内核比较就重新变成猜：
 *   · 帧数在涨就不许报停帧（否则正常播放会被当成卡）；
 *   · 停帧只报一次，之后按固定间隔重复（否则 2 秒采样会把日志刷满）；
 *   · 息屏/暂停/内核不提供帧数时不判定，且**不保留基线**（否则恢复后立刻误报）。
 */
class FrameStallDetectorTest {

    private fun detector() = FrameStallDetector(stallMs = 5_000, repeatReportMs = 10_000)

    @Test
    fun `帧数持续增长不报停帧`() {
        val d = detector()
        var frames = 0L
        var now = 0L
        repeat(20) {
            now += 2_000
            frames += 50
            assertEquals(FrameStallDetector.State.OK, d.sample(now, frames, playing = true, interactive = true).state)
        }
    }

    @Test
    fun `帧数静止满阈值报一次然后按间隔重复`() {
        val d = detector()
        // 建立基线
        assertEquals(FrameStallDetector.State.OK, d.sample(1_000, 100, true, true).state)
        // 2s / 4s：还没到 5s
        assertEquals(FrameStallDetector.State.OK, d.sample(3_000, 100, true, true).state)
        assertEquals(FrameStallDetector.State.OK, d.sample(5_000, 100, true, true).state)
        // 6s：满 5s → 新判定，报
        val first = d.sample(6_000, 100, true, true)
        assertEquals(FrameStallDetector.State.STALLED, first.state)
        assertEquals(5_000, first.stalledMs)
        // 8s：同一段停帧，距离上次上报不足 10s → 不重复
        assertEquals(FrameStallDetector.State.OK, d.sample(8_000, 100, true, true).state)
        // 16s：够 10s → 重复上报，stalledMs 反映真实停了多久
        val again = d.sample(16_000, 100, true, true)
        assertEquals(FrameStallDetector.State.STALLED, again.state)
        assertEquals(15_000, again.stalledMs)
    }

    @Test
    fun `恢复推进后再次静止能重新判定`() {
        val d = detector()
        d.sample(0, 100, true, true)
        assertEquals(FrameStallDetector.State.STALLED, d.sample(5_000, 100, true, true).state)
        // 恢复：帧数变化 → OK，基线重置
        assertEquals(FrameStallDetector.State.OK, d.sample(7_000, 160, true, true).state)
        // 再静止 5s → 又能报（不是"报过就哑了"）
        assertEquals(FrameStallDetector.State.STALLED, d.sample(12_000, 160, true, true).state)
    }

    @Test
    fun `暂停或息屏时不判定且不保留基线`() {
        val d = detector()
        d.sample(0, 100, true, true)
        // 息屏：不判定
        repeat(5) { i ->
            assertEquals(FrameStallDetector.State.IDLE, d.sample(1_000L + i * 2_000, 100, true, false).state)
        }
        // 亮屏后重新建立基线：即使帧数与息屏前相同，也不该立刻判停帧
        assertEquals(FrameStallDetector.State.OK, d.sample(12_000, 100, true, true).state)
        assertEquals(FrameStallDetector.State.OK, d.sample(14_000, 100, true, true).state)
        assertEquals(FrameStallDetector.State.STALLED, d.sample(17_000, 100, true, true).state)
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
