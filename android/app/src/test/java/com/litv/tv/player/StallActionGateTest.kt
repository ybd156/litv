package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StallActionGate] 的行为约束。
 *
 * 锚定的是「一次长停帧被记成多次卡死」的根因：`FrameStallDetector` 为了不刷屏，
 * 只在首行与 30/60/120s 里程碑返回 STALLED；旧接线直接按
 * `stalledMs >= 10s` 动作，于是同一段 120 秒的停帧会在每个里程碑各重建一次，
 * 三次之后撞上 `MAX_STALL_RECOVER` 判死，还共用 `stallRecovered` 把
 * 解码/渲染问题记成「直连线路差」。
 *
 * 注意边界（不要高估这些用例）：这里只证明**闸门的判定**正确。信号怎么来
 * （FrameStallDetector 的采样节奏）、动作怎么执行（startCurrent / tryNext /
 * 放弃重试）都依赖 PlayerManager 与真机，只能靠代码审查 + 真机验证。
 */
class StallActionGateTest {

    private fun gate() = StallActionGate(actionAfterMs = 10_000L, minIntervalMs = 20_000L)

    @Test
    fun `120 秒停帧每 2 秒采样跨多里程碑只动作一次`() {
        val g = gate()
        val actions = mutableListOf<StallAction>()

        // 检测器给出「这一段停帧」的首行（此时画面已经停了 5 秒）
        g.onStalled(0L, 5_000L, firstReport = true).let { if (it != StallAction.NONE) actions += it }

        // 之后每 2 秒采样一次：检测器只在 30/60/120s 里程碑再报 STALLED，
        // 其余采样是 OK（停帧还在继续，只是这一拍没有新报告）。
        val milestones = longArrayOf(30_000L, 60_000L, 120_000L)
        var t = 0L
        while (t < 120_000L) {
            t += 2_000L
            val a = if (t in milestones) {
                g.onStalled(t, t + 5_000L, firstReport = false)
            } else {
                g.onTick(t)
            }
            if (a != StallAction.NONE) actions += a
        }

        // 旧实现：10s / 30s / 60s / 120s 各重建一次（4 次）。
        assertEquals(listOf(StallAction.REBUILD_SAME), actions)
        assertEquals(1, g.actionCount)
    }

    @Test
    fun `恢复后再停升级为换内核`() {
        val g = gate()
        // 第 1 段停帧：重建同内核
        assertEquals(StallAction.REBUILD_SAME, g.onStalled(0L, 10_000L, true))
        g.endEpisode()

        // 画面好了 30 秒又停：阶梯升到换内核（不再是原地重建）
        assertEquals(StallAction.NEXT_ENGINE, g.onStalled(30_000L, 10_000L, true))
        assertEquals(2, g.actionCount)
    }

    @Test
    fun `两次动作最小间隔 20 秒`() {
        val g = gate()
        assertEquals(StallAction.REBUILD_SAME, g.onStalled(0L, 10_000L, true))
        g.endEpisode()

        // 2 秒后又停：虽然又过了阈值，但离上次动作只有 2 秒 —— 不许再动
        assertEquals(StallAction.NONE, g.onStalled(2_000L, 10_000L, true))
        assertEquals(StallAction.NONE, g.onTick(15_000L)) // 15s，仍不足 20s

        // 22 秒：间隔够了，补上换内核（这段停帧还没恢复，只是被推迟了）
        assertEquals(StallAction.NEXT_ENGINE, g.onStalled(22_000L, 20_000L, false))
    }

    @Test
    fun `第三次动作是放弃 之后不再动作`() {
        val g = gate()
        assertEquals(StallAction.REBUILD_SAME, g.onStalled(0L, 10_000L, true))
        g.endEpisode()
        assertEquals(StallAction.NEXT_ENGINE, g.onStalled(25_000L, 10_000L, true))
        g.endEpisode()
        assertEquals(StallAction.GIVE_UP, g.onStalled(50_000L, 10_000L, true))

        assertTrue(g.isGivenUp)
        g.endEpisode()
        // 已经放弃：再停多少次都不许动作（等 retryNow 的 reset）
        assertEquals(StallAction.NONE, g.onStalled(80_000L, 10_000L, true))
        assertEquals(StallAction.NONE, g.onTick(120_000L))
        assertEquals(3, g.actionCount)
    }

    @Test
    fun `位置看门狗的判据不受闸门影响`() {
        val g = gate()
        // 帧闸门自己的阈值是 10 秒：9 秒时不该动
        assertEquals(StallAction.NONE, g.onStalled(0L, 9_000L, true))
        assertEquals(StallAction.NONE, g.onTick(9_000L))

        // 位置看门狗仍是 16 秒由 isStalled 单独判定（两条路径各算各的账）
        assertFalse(isStalled(pos = 5_000, lastPosMs = 5_000, frozenMs = 15_000, thresholdMs = 16_000))
        assertTrue(isStalled(pos = 5_000, lastPosMs = 5_000, frozenMs = 16_000, thresholdMs = 16_000))

        // 帧闸门到 10 秒动作之后，位置判据的阈值语义原样不变
        assertEquals(StallAction.REBUILD_SAME, g.onTick(10_000L))
        assertFalse(isStalled(pos = 5_000, lastPosMs = 5_000, frozenMs = 10_000, thresholdMs = 16_000))
    }

    @Test
    fun `换台复位后阶梯从头开始`() {
        val g = gate()
        assertEquals(StallAction.REBUILD_SAME, g.onStalled(0L, 10_000L, true))
        g.endEpisode()
        assertEquals(StallAction.NEXT_ENGINE, g.onStalled(25_000L, 10_000L, true))
        g.endEpisode()
        assertEquals(StallAction.GIVE_UP, g.onStalled(50_000L, 10_000L, true))

        // 换台 / 用户重试：整轮重新开始
        g.reset()
        assertFalse(g.isGivenUp)
        assertEquals(0, g.actionCount)
        assertEquals(StallAction.REBUILD_SAME, g.onStalled(60_000L, 10_000L, true))
    }

    @Test
    fun `episode 关闭后不会拿旧时长动作`() {
        val g = gate()
        // 刚停 5 秒就被暂停/换内核关掉（还没到动作阈值）
        assertEquals(StallAction.NONE, g.onStalled(0L, 5_000L, true))
        g.endEpisode()

        // 100 秒后仍在采样，但这 100 秒不属于任何停帧 —— 不许动作
        assertEquals(StallAction.NONE, g.onTick(100_000L))

        // 真正的新停帧从它自己的首行重新计时
        assertEquals(StallAction.NONE, g.onStalled(100_000L, 5_000L, true))
        assertEquals(StallAction.NONE, g.onTick(105_000L))
        assertEquals(StallAction.REBUILD_SAME, g.onTick(110_000L))
    }
}
