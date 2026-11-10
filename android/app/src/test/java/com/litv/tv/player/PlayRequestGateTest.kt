package com.litv.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换台代际闸门（PlayRequestGate）的语义。
 *
 * 它锚定的是 C2 的修复机理：连按换台时并发发出的多个 /tv/play 响应
 * **不保证按序返回**，只有"最后一次换台"的代际允许生效。
 *
 * 注意边界（不要高估这条测试）：这里只证明闸门本身的判定正确，
 * **不证明** PlayerActivity 把它接对了——接线是否正确只能靠代码审查 + 真机
 * 连按换台验证（见报告里的手工验证清单）。
 */
class PlayRequestGateTest {

    @Test
    fun `连按三次只有最后一次是当前请求`() {
        val gate = PlayRequestGate()
        val first = gate.begin()
        val second = gate.begin()
        val third = gate.begin()

        // 晚到的旧响应必须被判过期，否则会把播放器切回旧台
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(second))
        assertTrue(gate.isCurrent(third))
    }

    @Test
    fun `离开页面后所有在途请求都作废`() {
        val gate = PlayRequestGate()
        val seq = gate.begin()
        assertTrue(gate.isCurrent(seq))

        gate.invalidate()
        assertFalse(gate.isCurrent(seq))
    }

    @Test
    fun `代际号单调递增且不重复`() {
        val gate = PlayRequestGate()
        val a = gate.begin()
        val b = gate.begin()
        assertNotEquals(a, b)
        assertTrue(b > a)
        // 取号不影响"当前"语义：只有最后取的那个生效
        assertTrue(gate.isCurrent(b))
        assertFalse(gate.isCurrent(a))
    }
}
