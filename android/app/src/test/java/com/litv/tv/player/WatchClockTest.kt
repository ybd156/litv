package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `watch_ms` 的单位契约。
 *
 * 背景（真机/线上事故）：这个字段曾经发的是**秒**，而字段名与所有消费方
 * 都按**毫秒**理解 —— 服务端 `Σstall_ms / Σwatch_ms` 算 rebuffer ratio，
 * 分母小 1000 倍 → 60 秒观看卡 3.2 秒，真实 5.3%，报表显示 **5333%**。
 * 服务端夹取（`maxDurationMs = 86_400_000`）拦不住它：秒的数值更小，永不越界，
 * 所以错误完全静默 —— 只能靠这条测试钉住。
 */
class WatchClockTest {

    @Test
    fun `一分钟观看返回 60000 毫秒而不是 60`() {
        // ★ 这条在旧实现上会失败：旧代码 `watchedSec()` 返回 60。
        assertEquals(60_000, WatchClock.watchedMs(startedAtWall = 1_000L, nowWall = 61_000L))
    }

    @Test
    fun `未起播时返回 0 而不是从 1970 年算起`() {
        assertEquals(0, WatchClock.watchedMs(startedAtWall = 0L, nowWall = 1_700_000_000_000L))
    }

    @Test
    fun `时钟回拨或同一毫秒返回 0 而不是负数`() {
        // rebuffer_ratio 的分母变负会让整个统计失真，比截断难查得多
        assertEquals(0, WatchClock.watchedMs(startedAtWall = 5_000L, nowWall = 4_000L))
        assertEquals(0, WatchClock.watchedMs(startedAtWall = 5_000L, nowWall = 5_000L))
    }

    @Test
    fun `超长观看夹到 Int 上限而不是溢出成负数`() {
        val huge = Int.MAX_VALUE.toLong() + 10_000L
        assertEquals(Int.MAX_VALUE, WatchClock.watchedMs(startedAtWall = 1L, nowWall = huge))
    }

    @Test
    fun `与秒级口径相差正好 1000 倍`() {
        // 这条是给"以为自己改对了、其实又除以 1000"的人看的
        val ms = WatchClock.watchedMs(startedAtWall = 0L.plus(1_000L), nowWall = 31_000L)
        assertEquals(30_000, ms)
        assertEquals(30, ms / 1000)
    }
}
