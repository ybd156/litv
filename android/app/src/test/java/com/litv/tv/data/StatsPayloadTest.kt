package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/api/v1/tv/stats` 请求体形状（QoE 埋点的兼容性契约）。
 *
 * 背景：服务端 `decode()` 开了 `DisallowUnknownFields`，多发一个不认识的键就是 400；
 * 而埋点又是**静默失败**的（不能因为统计打断播放）——所以"到底发了哪些键"
 * 只能靠测试钉死，人肉读代码不可靠。
 *
 * 更重要的背景：在这个改动之前，客户端**只报过 start**，`stall` 字符串零命中，
 * 于是服务端 StallRate 上线以来恒为 0、管理台显示"卡顿率 0%"主动误导。
 * 这里断言 stall/stop 真的带上了度量字段。
 */
class StatsPayloadTest {

    @Test
    fun `stall 事件带齐 stall_ms watch_ms path engine`() {
        val body = StatsPayload.build(
            channelId = 42, event = "stall", detail = "frozen=16000ms",
            stallMs = 16_000, watchMs = 300,
            path = "hls", engine = "exo",
        )
        // 数值统一按字符串断言：避免 Int 装箱后 assertEquals 的重载歧义
        assertEquals("16000", body["stall_ms"].toString())
        assertEquals("300", body["watch_ms"].toString())
        assertEquals("hls", body["path"])
        assertEquals("exo", body["engine"])
        assertEquals("stall", body["event"])
        assertEquals("42", body["channel_id"].toString())
    }

    @Test
    fun `stop 事件也带 watch_ms 与 path`() {
        val body = StatsPayload.build(
            channelId = 7, event = "stop", watchMs = 120, path = "remux", engine = "vlc",
        )
        assertEquals("120", body["watch_ms"].toString())
        assertEquals("remux", body["path"])
        assertEquals("vlc", body["engine"])
    }

    @Test
    fun `start 与 error 保持历史四键 不多发未知字段`() {
        // 老服务端对 start/error 是认识的：加了新键就会 400，等于把能用的埋点弄坏
        for (event in listOf("start", "error")) {
            val body = StatsPayload.build(
                channelId = 1, event = event, startupMs = 2345, detail = "x",
                stallMs = 999, watchMs = 999, path = "hls", engine = "exo",
            )
            assertEquals(setOf("channel_id", "event", "startup_ms", "detail"), body.keys)
            assertFalse(body.containsKey("stall_ms"))
            assertFalse(body.containsKey("watch_ms"))
        }
    }

    @Test
    fun `path 与 engine 为空时不下发空字符串键`() {
        val body = StatsPayload.build(channelId = 1, event = "stall", stallMs = 1000, watchMs = 10)
        assertTrue(body.containsKey("stall_ms"))
        assertFalse(body.containsKey("path"))
        assertFalse(body.containsKey("engine"))
    }
}
