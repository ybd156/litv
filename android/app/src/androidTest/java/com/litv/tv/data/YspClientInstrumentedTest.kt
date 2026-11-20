package com.litv.tv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * 央视频取流的端到端验证（需要网络，跑在设备/模拟器上）。
 *
 * 验证链条：cKey 生成 → bkliveinfo 认票 → 拿到 m3u8 → 分片真的能下。
 * 这一段在 JVM 单测里跑不了（要用 android.util.Base64 且要真实网络）。
 */
@RunWith(AndroidJUnit4::class)
class YspClientInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cctv1_returns_playable_lines() {
        val ch = YspClient.CHANNELS.first { it.slug == "cctv1" }

        // 1) 取流：iretcode 非 0 会抛 YspException
        val lines = YspClient.lines(ch)
        assertTrue("CCTV-1 没有返回任何线路", lines.isNotEmpty())
        println("CCTV-1 线路数=${lines.size}")
        lines.forEach { println("  ${it.label}: ${it.url.take(120)}") }

        // 2) m3u8 能拉
        val url = lines.first().url
        val body = httpGet(url)
        assertTrue("m3u8 不含 #EXTM3U", body.contains("#EXTM3U"))
        val segs = body.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("m3u8 没有分片", segs.isNotEmpty())
        println("m3u8 分片数=${segs.size}")

        // 3) 分片能下（这是「列表活、内容死」的唯一判据）
        val seg = java.net.URI(url).resolve(segs.last()).toString()
        val bytes = httpBytes(seg)
        assertTrue("分片为空（${bytes.size} 字节）", bytes.size > 100 * 1024)
        println("分片 ${bytes.size / 1024} KB ✓")
    }

    private fun httpGet(u: String): String = String(httpBytes(u), Charsets.UTF_8)

    private fun httpBytes(u: String): ByteArray {
        val c = (URL(u).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 20000
            setRequestProperty("User-Agent", "qqlive")
        }
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.readBytes()
        } finally {
            c.disconnect()
        }
    }
}
