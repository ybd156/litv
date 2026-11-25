package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 回看失败记忆：只影响「要不要画回看角标」，不参与播放行为。 */
class ReplayFailureMemoTest {

    @Before
    fun setUp() {
        ReplayFailureMemo.reset()
    }

    @Test
    fun `没失败过就不在冷却`() {
        assertFalse(ReplayFailureMemo.isCoolingDown(-1001L, 1_000L))
    }

    @Test
    fun `刚失败过在冷却`() {
        ReplayFailureMemo.markFailed(-1001L, 5_000L)
        assertTrue(ReplayFailureMemo.isCoolingDown(-1001L, 5_001L))
    }

    @Test
    fun `冷却到期后自动解除`() {
        ReplayFailureMemo.markFailed(-1001L, 0L)
        assertTrue(ReplayFailureMemo.isCoolingDown(-1001L, ReplayFailureMemo.COOLDOWN_MS - 1))
        assertFalse(ReplayFailureMemo.isCoolingDown(-1001L, ReplayFailureMemo.COOLDOWN_MS))
    }

    @Test
    fun `失败是分频道的，不牵连别的台`() {
        ReplayFailureMemo.markFailed(-1001L, 1_000L)
        assertTrue(ReplayFailureMemo.isCoolingDown(-1001L, 2_000L))
        assertFalse(ReplayFailureMemo.isCoolingDown(-1002L, 2_000L))
    }

    @Test
    fun `播成功一次就清掉冷却`() {
        ReplayFailureMemo.markFailed(-1001L, 1_000L)
        ReplayFailureMemo.clear(-1001L)
        assertFalse(ReplayFailureMemo.isCoolingDown(-1001L, 2_000L))
    }

    @Test
    fun `只有 id 0 不记账，负数（内置源）照记`() {
        ReplayFailureMemo.markFailed(0L, 1_000L)
        assertEquals(0, ReplayFailureMemo.size())

        // 内置源频道 id 是负数（-1001 这类），必须能记上，否则内置源的失败冷却等于不存在
        ReplayFailureMemo.markFailed(-5L, 1_000L)
        assertEquals(1, ReplayFailureMemo.size())
        assertTrue(ReplayFailureMemo.isCoolingDown(-5L, 2_000L))
    }

    @Test
    fun `时钟回拨按不在冷却处理`() {
        ReplayFailureMemo.markFailed(-1001L, 10_000L)
        assertFalse(ReplayFailureMemo.isCoolingDown(-1001L, 9_000L))
    }
}
