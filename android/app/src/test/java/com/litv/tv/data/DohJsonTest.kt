package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoH JSON 解析（阿里/腾讯/Cloudflare 三家同形状）——纯函数，进 CI。
 *
 * 这些断言直接对应"用户电视上连不上源站"的那类根因：解析器如果对某家的响应
 * 形状判断错，就会把"能解析"误判成"需要回落"，DoH 等于白开；
 * 反过来把 NXDOMAIN 当成有答案，会把播放器指向一个不存在的地址。
 */
class DohJsonTest {

    /** 阿里 dns.alidns.com 的真实响应形状（A 记录） */
    private val ali = """
        {"Status":0,"TC":false,"RD":true,"RA":true,"AD":false,"CD":false,
         "Question":[{"name":"cdn.example.com.","type":1}],
         "Answer":[{"name":"cdn.example.com.","TTL":600,"type":1,"data":"93.184.216.34"}]}
    """.trimIndent()

    /** 腾讯 doh.pub 的真实响应形状（多答案 + 含 AAAA，必须只取 A） */
    private val tencent = """
        {"Status":0,"TC":false,"RD":true,"RA":true,"AD":true,"CD":false,
         "Question":[{"name":"cdn.example.com.","type":1}],
         "Answer":[
           {"name":"cdn.example.com.","TTL":120,"type":1,"data":"1.2.3.4"},
           {"name":"cdn.example.com.","TTL":30,"type":28,"data":"2408:8000::1"}
         ]}
    """.trimIndent()

    /** Cloudflare 会多带 Comment 等字段，解析器必须忽略未知键 */
    private val cloudflare = """
        {"Status":0,"TC":false,"RD":true,"RA":true,"AD":true,"CD":false,
         "Question":[{"name":"cdn.example.com.","type":1}],
         "Answer":[{"name":"cdn.example.com.","TTL":300,"type":1,"data":"203.0.113.7"}],
         "Comment":"unknown keys must be ignored"}
    """.trimIndent()

    @Test
    fun `阿里形状解析出 A 记录与 TTL`() {
        val r = DohJson.parse(ali)
        assertTrue(r is DohJson.Parsed.Ok)
        val ok = r as DohJson.Parsed.Ok
        assertEquals(listOf("93.184.216.34"), ok.ips)
        assertEquals(600, ok.ttlSec)
    }

    @Test
    fun `腾讯形状只取 A 记录 丢掉 AAAA`() {
        val ok = DohJson.parse(tencent) as DohJson.Parsed.Ok
        assertEquals(listOf("1.2.3.4"), ok.ips)
        // TTL 取所有**被采纳**答案里的最小值；AAAA 被丢弃，所以不是 30
        assertEquals(120, ok.ttlSec)
    }

    @Test
    fun `Cloudflare 形状忽略未知字段`() {
        val ok = DohJson.parse(cloudflare) as DohJson.Parsed.Ok
        assertEquals(listOf("203.0.113.7"), ok.ips)
        assertEquals(300, ok.ttlSec)
    }

    @Test
    fun `NXDOMAIN 的 Status 非 0 必须回落`() {
        val nxdomain = """
            {"Status":3,"TC":false,"RD":true,"RA":true,
             "Question":[{"name":"nope.example.com.","type":1}]}
        """.trimIndent()
        val r = DohJson.parse(nxdomain)
        assertTrue(r is DohJson.Parsed.NeedFallback)
        assertTrue((r as DohJson.Parsed.NeedFallback).reason.startsWith("status-"))
    }

    @Test
    fun `空响应与畸形 JSON 必须回落`() {
        assertTrue(DohJson.parse(null) is DohJson.Parsed.NeedFallback)
        assertTrue(DohJson.parse("") is DohJson.Parsed.NeedFallback)
        assertTrue(DohJson.parse("not json at all") is DohJson.Parsed.NeedFallback)
        // 没有 Status 的 JSON：不是 DoH 响应，不能盲信
        assertTrue(DohJson.parse("""{"Answer":[]}""") is DohJson.Parsed.NeedFallback)
    }

    @Test
    fun `没有 A 记录时回落 而不是返回空列表`() {
        val onlyAaaa = """
            {"Status":0,"Answer":[{"name":"x.","TTL":60,"type":28,"data":"2408:8000::1"}]}
        """.trimIndent()
        assertTrue(DohJson.parse(onlyAaaa) is DohJson.Parsed.NeedFallback)
        // data 不是 IP（被投毒的响应可能是主机名）也要丢掉
        val poison = """
            {"Status":0,"Answer":[{"name":"x.","TTL":60,"type":1,"data":"evil.example"}]}
        """.trimIndent()
        assertTrue(DohJson.parse(poison) is DohJson.Parsed.NeedFallback)
    }

    @Test
    fun `TTL 缺失取 60 秒 超长截到 10 分钟`() {
        assertEquals(60, DohJson.ttlSec(null))
        assertEquals(60, DohJson.ttlSec(0))
        assertEquals(60, DohJson.ttlSec(-5))
        assertEquals(600, DohJson.ttlSec(86_400))
        assertEquals(300, DohJson.ttlSec(300))
        // 缺 TTL 的答案也用 60
        val noTtl = """{"Status":0,"Answer":[{"type":1,"data":"1.2.3.4"}]}"""
        val ok = DohJson.parse(noTtl) as DohJson.Parsed.Ok
        assertEquals(60, ok.ttlSec)
    }

    @Test
    fun `内网名与字面 IP 不走 DoH`() {
        // 这是"不要改变局域网行为"的硬要求：NAS 地址不能拿去问公网 DNS
        assertTrue(DohPolicy.preferSystemDns("192.168.1.10"))
        assertTrue(DohPolicy.preferSystemDns("10.0.0.5"))
        assertTrue(DohPolicy.preferSystemDns("nas"))
        assertTrue(DohPolicy.preferSystemDns("fnos.local"))
        assertTrue(DohPolicy.preferSystemDns("localhost"))
        assertTrue(DohPolicy.preferSystemDns("2408:8000::1"))
        assertTrue(DohPolicy.preferSystemDns(""))
        // 公网域名才走 DoH
        assertFalse(DohPolicy.preferSystemDns("cdn.example.com"))
        assertFalse(DohPolicy.preferSystemDns("iptv.683868.xyz"))
    }
}
