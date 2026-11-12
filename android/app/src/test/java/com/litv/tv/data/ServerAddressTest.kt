package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 服务端地址解析（绑定页 / 设置页的输入口）。
 *
 * 为什么值得单测：这是用户唯一手输的东西，也是唯一能把 token 发到意外目标的地方。
 * 拼错或校验漏了，表现是"连不上"或更糟——把 bearer token 发给了别的 origin。
 * 这些断言全部是 java.net.URI 的纯计算，不需要 Android 框架。
 */
class ServerAddressTest {

    @Test
    fun `只输主机名时补默认端口 28027`() {
        assertEquals("http://192.168.1.10:28027", ServerAddress.normalize("192.168.1.10"))
    }

    @Test
    fun `https 与显式端口原样保留`() {
        assertEquals(
            "https://tv.example.com:8443",
            ServerAddress.normalize("https://tv.example.com:8443"),
        )
        // https 默认端口不该被改写成 28027
        assertEquals("https://tv.example.com", ServerAddress.normalize("https://tv.example.com"))
    }

    @Test
    fun `拒绝内嵌凭据`() {
        // 地址里带 user:pass 会把凭据写进日志/缓存，也会让 origin 判断失真
        assertNull(ServerAddress.parse("http://user:pass@nas.local"))
        assertNull(ServerAddress.parse("nas.local@evil.example"))
    }

    @Test
    fun `拒绝路径 查询串 与片段`() {
        assertNull(ServerAddress.parse("http://nas.local/app/litv"))
        assertNull(ServerAddress.parse("http://nas.local/?a=1"))
        assertNull(ServerAddress.parse("http://nas.local/#frag"))
    }

    @Test
    fun `拒绝非 http 协议 空白 与超长输入`() {
        assertNull(ServerAddress.parse("ftp://nas.local"))
        assertNull(ServerAddress.parse("file:///etc/hosts"))
        assertNull(ServerAddress.parse("   "))
        assertNull(ServerAddress.parse("http://nas.local:28027" + "a".repeat(2100)))
        assertNull(ServerAddress.parse("nas local"))
    }

    @Test
    fun `拒绝越界端口`() {
        assertNull(ServerAddress.parse("http://nas.local:70000"))
        assertNull(ServerAddress.parse("http://nas.local:0"))
    }
}
