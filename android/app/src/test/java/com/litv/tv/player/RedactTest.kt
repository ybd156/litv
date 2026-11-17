package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏契约。
 *
 * 事故背景：`VlcEngine` 曾用 `url.take(60)` 打日志。短域名下 60 字符正好覆盖
 * `/live/12/stream.ts?sig=…` 的前段，等于把签名凭据写进 logcat；
 * direct 模式下更直接暴露用户私有源站的 host/IP。
 * 这类问题**单元测试测不到日志语句本身**，所以真正的门禁是
 * `tools/checks/check-playback-security.py` 的 grep；这里的测试保证
 * 脱敏函数本身不漏（否则门禁会变成"用了 Redact 但仍然漏"）。
 */
class RedactTest {

    @Test
    fun `签名 URL 不泄漏 query`() {
        val out = Redact.url("http://192.0.2.10:8080/live/12/stream.ts?exp=1730000000&sig=DEADBEEF")
        assertEquals("http://192.0.2.10:8080?<redacted>", out)
        assertFalse(out.contains("sig"))
        assertFalse(out.contains("DEADBEEF"))
        assertFalse(out.contains("exp="))
    }

    @Test
    fun `path 里的账号也不保留`() {
        // 源站路径常带账号：/iptv/13800138000/live.m3u8
        val out = Redact.url("http://src.example/iptv/13800138000/live.m3u8")
        assertFalse("不能保留 path：$out", out.contains("13800138000"))
        assertEquals("http://src.example", out)
    }

    @Test
    fun `无 query 时不假装有 query`() {
        val out = Redact.url("https://a.example/x.ts")
        assertEquals("https://a.example", out)
        assertFalse(out.contains("<redacted>"))
    }

    @Test
    fun `解析不了的内容只给长度 不给原文`() {
        val out = Redact.url("not a url at all, but contains secret-token-abc")
        assertFalse(out.contains("secret-token-abc"))
        assertTrue(out.startsWith("<url "))
        assertEquals("<empty url>", Redact.url(""))
    }

    @Test
    fun `非法字符的 URL 也不抛异常`() {
        // 上游错误信息里出现的 URL 常带空格/中文，脱敏本身不能崩
        val out = Redact.url("http://host/路径 带空格?x=1")
        assertFalse(out.contains("x=1"))
    }
}
