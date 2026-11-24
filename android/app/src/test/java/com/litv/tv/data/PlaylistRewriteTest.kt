package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaylistRewrite] 的行为约束。
 *
 * 守的是 2026-11-24 评审在代码里确认的两个 bug：
 *   · `/` 开头的分片地址被拼成 `base + //path`（部分 CDN 直接 404）；
 *   · `#EXT-X-MAP` / `#EXT-X-KEY` / `#EXT-X-MEDIA` 的 `URI="…"` 没改写，
 *     fMP4/CMAF 拿不到 init 段、AES-128 拿不到密钥 → 经代理整条播不了。
 *
 * 编码器用假实现（`E[地址]`）：真实实现是 base64url，但这里要断言的是
 * **结构**（哪一行变成什么前缀、URI 属性有没有被换掉），不是编码算法本身。
 */
class PlaylistRewriteTest {

    private val enc: (String) -> String = { "E[$it]" }

    private val tsUrl = "https://cdn.example/live/ch1/index.m3u8"

    private fun rewrite(
        text: String,
        url: String = tsUrl,
        seg: String = "/seg/",
        tag: String = "/rawfile/",
    ) = PlaylistRewrite.rewrite(text, url, seg, tag, enc)

    @Test
    fun `斜杠开头的分片按 origin 解析，不再拼出双斜杠`() {
        val text = "#EXTM3U\n#EXTINF:4.0,\n/live/ch1/s1.ts\n"
        val rw = rewrite(text, tsUrl)
        assertEquals(listOf("https://cdn.example/live/ch1/s1.ts"), rw.mediaUrls)
        assertTrue("不能出现 base//path 这种双斜杠", !rw.text.contains("cdn.example//"))
        assertEquals(
            "#EXTM3U\n#EXTINF:4.0,\n/seg/E[https://cdn.example/live/ch1/s1.ts]\n",
            rw.text,
        )
    }

    @Test
    fun `EXT-X-MAP 的 init 段要改写，EXTINF 行逐字不变`() {
        val text = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4.0,\nf1.m4s\n"
        val rw = rewrite(text, "https://cdn.example/vod/a/index.m3u8")
        assertEquals(
            "#EXTM3U\n" +
                "#EXT-X-MAP:URI=\"/rawfile/E[https://cdn.example/vod/a/init.mp4]\"\n" +
                "#EXTINF:4.0,\n" +
                "/seg/E[https://cdn.example/vod/a/f1.m4s]\n",
            rw.text,
        )
    }

    @Test
    fun `EXT-X-KEY 只改 URI，METHOD 与 IV 原样保留`() {
        val text = "#EXTM3U\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x11223344556677889900AABBCCDDEEFF\n" +
            "#EXTINF:6.0,\nseg0.ts\n"
        val rw = rewrite(text, "https://cdn.example/hls/index.m3u8")
        assertTrue(rw.text.contains("METHOD=AES-128"))
        assertTrue(rw.text.contains("IV=0x11223344556677889900AABBCCDDEEFF"))
        assertTrue(rw.text.contains("URI=\"/rawfile/E[https://cdn.example/hls/key.bin]\""))
        assertEquals(listOf("https://cdn.example/hls/seg0.ts"), rw.mediaUrls)
    }

    @Test
    fun `普通 TS 清单只换分片行，其余逐字节不变（含末行无换行）`() {
        val text = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n" +
            "#EXTINF:4.0,\nseg1.ts\n#EXTINF:4.0,\nseg2.ts\n#EXT-X-ENDLIST"
        val rw = rewrite(text, "https://cdn.example/live/ch1/index.m3u8?sign=abc&exp=123")
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n" +
                "#EXTINF:4.0,\n/seg/E[https://cdn.example/live/ch1/seg1.ts]\n" +
                "#EXTINF:4.0,\n/seg/E[https://cdn.example/live/ch1/seg2.ts]\n" +
                "#EXT-X-ENDLIST",
            rw.text,
        )
        // 基准地址的 query（签名）不能被带进分片地址
        assertEquals(
            listOf(
                "https://cdn.example/live/ch1/seg1.ts",
                "https://cdn.example/live/ch1/seg2.ts",
            ),
            rw.mediaUrls,
        )
    }

    @Test
    fun `带 query 与无末斜杠的清单地址，相对路径按目录拼接`() {
        assertEquals(
            "https://cdn.example/a/b/seg9.ts",
            PlaylistRewrite.resolve("seg9.ts", "https://cdn.example/a/b/index.m3u8?token=xyz"),
        )
        assertEquals(
            "https://cdn.example/a/b/seg9.ts",
            PlaylistRewrite.resolve("./seg9.ts", "https://cdn.example/a/b/index.m3u8"),
        )
        // `../` 必须被真正解析掉：字符串拼接会得到 `.../a/b/../x.ts`（源站 404）
        assertEquals(
            "https://cdn.example/a/x.ts",
            PlaylistRewrite.resolve("../x.ts", "https://cdn.example/a/b/index.m3u8"),
        )
        assertEquals(
            "https://cdn2.example/x.ts",
            PlaylistRewrite.resolve("https://cdn2.example/x.ts", "https://cdn.example/a/b/index.m3u8"),
        )
    }

    @Test
    fun `EXT-X-MEDIA 的 URI 也改写，前缀可换成 rawseg`() {
        val text = "#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"声1\",URI=\"audio/index.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO=\"aud\"\n" +
            "video/index.m3u8\n"
        val rw = rewrite(text, "https://cdn.example/tv/master.m3u8", seg = "/rawseg/")
        assertTrue(rw.text.contains("URI=\"/rawfile/E[https://cdn.example/tv/audio/index.m3u8]\""))
        assertEquals(listOf("https://cdn.example/tv/video/index.m3u8"), rw.mediaUrls)
        assertTrue(rw.text.contains("/rawseg/E[https://cdn.example/tv/video/index.m3u8]"))
    }

    @Test
    fun `CRLF 清单保持 CRLF，不被顺手改成 LF`() {
        val text = "#EXTM3U\r\n#EXTINF:4.0,\r\ns1.ts\r\n"
        val rw = rewrite(text, tsUrl)
        assertEquals(
            "#EXTM3U\r\n#EXTINF:4.0,\r\n/seg/E[https://cdn.example/live/ch1/s1.ts]\r\n",
            rw.text,
        )
    }

    @Test
    fun `空清单与纯标签清单不产生媒体地址`() {
        assertTrue(PlaylistRewrite.mediaUrls("", tsUrl).isEmpty())
        assertEquals("#EXTM3U\n#EXT-X-ENDLIST", rewrite("#EXTM3U\n#EXT-X-ENDLIST").text)
        // 空文本保持空：旧语义（每行都补一个 '\n'）会把它变成 "\n"
        assertEquals("", rewrite("").text)
    }
}
