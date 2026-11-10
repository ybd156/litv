package com.litv.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放地址缓存的过期判定（PlayCache.isUsable，从 PlayCacheStore.get 里抽出来的纯函数）。
 *
 * 为什么值得钉边界：判定松了会拿一条"还剩 3 秒"的签名地址去起播，
 * 结果是解码器刚初始完服务端就返回 403，三个内核全失败——
 * 日志里只会看到"所有播放内核都无法播放这个频道"，极难定位。
 */
class PlayCacheExpiryTest {

    private val now = 1_700_000_000L

    @Test
    fun `剩余时间超过一分钟后仍可用`() {
        val c = PlayCache(channelId = 1, url = "/app/litv/live/1.ts", expires = now + 61)
        assertTrue(c.isUsable(now))
    }

    @Test
    fun `剩余时间不足一分钟一律不用`() {
        // 正好 60 秒也不算够：起播本身就要花掉几秒
        assertFalse(PlayCache(url = "/x.ts", expires = now + 60).isUsable(now))
        assertFalse(PlayCache(url = "/x.ts", expires = now + 1).isUsable(now))
        // 已经过期
        assertFalse(PlayCache(url = "/x.ts", expires = now - 1).isUsable(now))
        // 服务端没给过期时间（0）
        assertFalse(PlayCache(url = "/x.ts", expires = 0).isUsable(now))
    }

    @Test
    fun `没有地址的缓存不算可用`() {
        assertFalse(PlayCache(url = "", expires = now + 3600).isUsable(now))
        assertFalse(PlayCache(url = "   ", expires = now + 3600).isUsable(now))
    }

    @Test
    fun `余量阈值可以显式放松`() {
        val c = PlayCache(url = "/x.ts", expires = now + 10)
        assertFalse(c.isUsable(now))
        assertTrue(c.isUsable(now, minRemainSec = 0))
    }
}
