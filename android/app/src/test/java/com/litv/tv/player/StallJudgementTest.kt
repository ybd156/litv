package com.litv.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡死判定（isStalled）。
 *
 * 锚定的是真机上「播一会就卡、日志里一条错误都没有、只能换台」的根因：
 * 直播流卡死后播放器会把位置变成 -1，而旧实现看到 `pos < 0` 就直接跳过判定，
 * 于是看门狗永远不触发。
 *
 * 注意边界（不要高估这条测试）：这里只证明**判定函数**的取舍正确，
 * **不证明**看门狗真的跑起来了——后者依赖 startStallWatchdog 的调用时机
 * （只在 onPlaying 之后启动），只能靠真机验证。
 */
class StallJudgementTest {

    private val threshold = 16_000L

    @Test
    fun `正常播放不判定`() {
        // 位置在前进，frozenMs 很小
        assertFalse(isStalled(pos = 5_000, lastPosMs = 4_000, frozenMs = 4_000, thresholdMs = threshold))
    }

    @Test
    fun `位置不变超过阈值判定卡死`() {
        assertTrue(isStalled(pos = 5_000, lastPosMs = 5_000, frozenMs = 20_000, thresholdMs = threshold))
    }

    @Test
    fun `从来没拿到过位置时不判定`() {
        // 有些内核本来就不给位置。这种情况无从判断，不能误判成卡死。
        assertFalse(isStalled(pos = -1, lastPosMs = -1, frozenMs = 60_000, thresholdMs = threshold))
    }

    @Test
    fun `拿到过位置之后变成 -1 仍然判定卡死`() {
        // ★ 这条在旧实现上会失败（旧代码 `if (pos < 0) return`）。
        // 直播流卡死后位置会变成 -1，这恰恰是最需要判定的场景。
        assertTrue(isStalled(pos = -1, lastPosMs = 8_000, frozenMs = 20_000, thresholdMs = threshold))
    }

    @Test
    fun `变成 -1 但还没到阈值时不判定`() {
        // 短暂的 -1（seek、切分片）不能触发重建
        assertFalse(isStalled(pos = -1, lastPosMs = 8_000, frozenMs = 4_000, thresholdMs = threshold))
    }

    @Test
    fun `阈值关闭时不判定`() {
        assertFalse(isStalled(pos = 5_000, lastPosMs = 5_000, frozenMs = 99_999, thresholdMs = 0))
    }
}
