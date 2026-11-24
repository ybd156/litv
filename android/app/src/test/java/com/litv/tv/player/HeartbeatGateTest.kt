package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心跳闸门的行为验收。
 *
 * 旧代码里根本没有「起播发生了」这个统一事实可挂：心跳只写在
 * `/tv/play` 成功的那个分支里（[PlaybackCoordinator] 之前），所以
 * 缓存命中 / 内置源 / 本地解析 / 时移四条路径一次都不报心跳。
 *
 * 这些用例在新代码上是绿的；把 [HeartbeatGate.shouldSend] 退回
 * 「永远 return true」会让「同一次起播里再次进入播放不重复发」变红，
 * 退回「永远 return false」会让第 1、3、4 条变红。
 */
class HeartbeatGateTest {

    @Test
    fun `第一次进入播放要发心跳`() {
        val g = HeartbeatGate()
        assertTrue(g.shouldSend(attempt = 1))
    }

    @Test
    fun `同一次起播里再次进入播放不重复发`() {
        val g = HeartbeatGate()
        assertTrue(g.shouldSend(1))
        // 暂停恢复、位置重置后重建、换内核重试都会再进一次 Playing
        assertFalse(g.shouldSend(1))
        assertFalse(g.shouldSend(1))
    }

    @Test
    fun `换台或重试后 attempt 变了要再发一次`() {
        val g = HeartbeatGate()
        assertTrue(g.shouldSend(1))
        assertTrue(g.shouldSend(2))
        assertTrue(g.shouldSend(7))
    }

    @Test
    fun `释放后重建页面可以重新发`() {
        val g = HeartbeatGate()
        assertTrue(g.shouldSend(1))
        g.reset()
        assertTrue(g.shouldSend(1))
    }

    @Test
    fun `内置源的合成频道号报 0，其余报真实 id`() {
        // 服务端 tvHeartbeat 不校验 playing_channel，它只是「设备还活着」的信号。
        // 内置源用负数合成 id（实测 -1018），直接报上去没有意义，报 0 表示
        // 「在播，但没有服务端认识的频道」。
        assertEquals(8829L, PlaySource.Cached(8829, "u", emptyMap(), "exo").heartbeatChannelId)
        assertEquals(1L, PlaySource.Remote(1, "u", emptyMap(), "exo").heartbeatChannelId)
        assertEquals(0L, PlaySource.Builtin(-1018, "u", emptyMap(), "exo").heartbeatChannelId)
        assertEquals(0L, PlaySource.Local(0, "u", emptyMap(), "exo").heartbeatChannelId)
    }
}
