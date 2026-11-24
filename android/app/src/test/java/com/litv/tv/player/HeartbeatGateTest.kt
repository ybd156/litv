package com.litv.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心跳闸门的行为验收。
 *
 * 旧代码里根本没有「起播发生了」这个统一事实可挂：心跳只写在
 * `/tv/play` 成功的那个分支里（[PlaybackCoordinator] 之前），所以
 * 缓存命中 / 内置源 / 本地解析 / 时移四条路径一次都不报心跳。
 * 这些用例在新代码上是绿的；把 [HeartbeatGate.shouldSend] 退回
 * 「永远 return true」会让「同一次起播里再次进入播放不重复发」变红，
 * 退回「永远 return false」会让第 1、3、5 条变红。
 */
class HeartbeatGateTest {

    @Test
    fun `第一次进入播放要发心跳`() {
        val g = HeartbeatGate()
        assertTrue(g.shouldSend(attempt = 1, needsHeartbeat = true))
    }

    @Test
    fun `同一次起播里再次进入播放不重复发`() {
        val g = HeartbeatGate()
        assertTrue(g.shouldSend(1, true))
        // 暂停恢复、位置重置后重建、换内核重试都会再进一次 Playing
        assertFalse(g.shouldSend(1, true))
        assertFalse(g.shouldSend(1, true))
    }

    @Test
    fun `换台或重试后 attempt 变了要再发一次`() {
        val g = HeartbeatGate()
        assertTrue(g.shouldSend(1, true))
        assertTrue(g.shouldSend(2, true))
        assertTrue(g.shouldSend(7, true))
    }

    @Test
    fun `来源不需要心跳时永远不发`() {
        val g = HeartbeatGate()
        assertFalse(g.shouldSend(1, false))
        assertFalse(g.shouldSend(2, false))
        // 关掉的那次不能吃掉额度：随后需要心跳的那次仍然要发
        assertTrue(g.shouldSend(3, true))
    }

    @Test
    fun `释放后重建页面可以重新发`() {
        val g = HeartbeatGate()
        assertTrue(g.shouldSend(1, true))
        g.reset()
        assertTrue(g.shouldSend(1, true))
    }
}
