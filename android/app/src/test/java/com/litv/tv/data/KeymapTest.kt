package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键位表（T1：一个键只做一件事）。
 *
 * 这些断言的意义：键位表是给用户看的说明书，一旦代码里某个键被绑了两件事，
 * 用户按下去就会"有时候不灵"。冲突检查进 CI 比人工核对可靠。
 */
class KeymapTest {

    @Test
    fun `同一状态下没有重复键位`() {
        val c = Keymap.conflicts()
        assertTrue("键位冲突：${c.joinToString("；")}", c.isEmpty())
    }

    @Test
    fun `四个状态都有键位说明`() {
        Keymap.Context.entries.forEach { ctx ->
            assertTrue("状态 ${ctx.label} 没有键位说明", Keymap.forContext(ctx).isNotEmpty())
        }
    }

    @Test
    fun `播放中必须写清 OK 与长按 OK 的区别`() {
        val keys = Keymap.forContext(Keymap.Context.PLAYING).map { it.key }
        assertTrue(keys.contains("OK"))
        assertTrue(keys.contains("长按 OK"))
        // 数字键跳台要写进表里（用户不知道有这个功能就等于没有）
        assertTrue(keys.any { it.contains("数字") })
    }

    @Test
    fun `BACK 在播放中与频道列表里的语义分别写清`() {
        val playingBack = Keymap.forContext(Keymap.Context.PLAYING).first { it.key == "BACK" }
        val panelBack = Keymap.forContext(Keymap.Context.PANEL).first { it.key == "BACK" }
        // 同一个键在不同状态做不同事 —— 这是允许的，但两条说明必须不同
        assertTrue(playingBack.action != panelBack.action)
    }

    @Test
    fun `渲染文本包含每个状态标题`() {
        val text = Keymap.asText()
        Keymap.Context.entries.forEach { ctx ->
            assertTrue("缺状态标题：${ctx.label}", text.contains("【${ctx.label}】"))
        }
    }
}
