package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放地址归一的边界（UrlJoin 是从 Api.absolute 抽出来的纯函数）。
 *
 * 为什么值得钉死：这段拼错的表现是"全黑屏 + 日志里只有一串 URI"，
 * 在真机上几乎无法反推。三条真实形态各一条断言：
 *   1. 站内相对路径要拼上 base；
 *   2. 直连模式下服务端给的是上游绝对地址，原样返回（拼了就废）；
 *   3. 空 base / 空 url 的退化行为保持抽取前的语义（不顺手"优化"）。
 */
class UrlJoinTest {

    @Test
    fun `站内相对路径拼上服务端根地址`() {
        assertEquals(
            "http://nas.local:28027/app/litv/live/1/stream.ts",
            UrlJoin.absolute("http://nas.local:28027", "/app/litv/live/1/stream.ts"),
        )
    }

    @Test
    fun `base 带尾斜杠且路径不带前斜杠时不产生双斜杠`() {
        assertEquals(
            "http://nas.local:28027/app/litv/live/1/stream.ts",
            UrlJoin.absolute("http://nas.local:28027/", "app/litv/live/1/stream.ts"),
        )
    }

    @Test
    fun `直连模式的上游绝对地址原样返回`() {
        val upstream = "http://upstream.example.net:8080/live/1.m3u8?token=redacted"
        // 绝对地址不能再拼 base，否则会拼成 http://nas/http://upstream/...
        assertEquals(upstream, UrlJoin.absolute("http://nas.local:28027", upstream))
        assertEquals(
            "https://upstream.example.net/hls/1.m3u8",
            UrlJoin.absolute("http://nas.local:28027", "https://upstream.example.net/hls/1.m3u8"),
        )
    }

    @Test
    fun `空 base 与空 url 保持抽取前的退化语义`() {
        // base 为空时旧实现就是"base + /url"，这里锁定它，避免被顺手改成别的行为
        assertEquals("/a.ts", UrlJoin.absolute("", "/a.ts"))
        assertEquals("/a.ts", UrlJoin.absolute("", "a.ts"))
        assertEquals("http://nas.local:28027/", UrlJoin.absolute("http://nas.local:28027", ""))
    }
}
