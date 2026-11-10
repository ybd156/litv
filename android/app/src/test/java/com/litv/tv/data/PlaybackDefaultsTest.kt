package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放参数默认值的一致性（PlaybackDefaults）。
 *
 * 这条测试的存在理由就是"防漂移"：曾经出现过
 * Prefs 默认 15000ms、PlayerManager 里有个 5000 的死常量、文件头注释写 6s、
 * 设置页选项只有 3000~12000 四个值。用户在设置里看到"15 秒"，
 * 点一下那一行就掉到 5 秒（索引静默回落），播放随即被拖成"3 秒判超时"。
 *
 * 所以这里断言的是**约束**，不是某个数字：
 *   1. 默认值必须出现在可选列表里；
 *   2. 列表必须是升序且不重复（设置页的左右循环依赖顺序）。
 */
class PlaybackDefaultsTest {

    @Test
    fun `起播超时默认值必须出现在选项里`() {
        assertTrue(
            "默认 ${PlaybackDefaults.START_TIMEOUT_MS}ms 不在选项 ${PlaybackDefaults.START_TIMEOUT_OPTIONS_MS} 里，" +
                "用户在设置页一点就会掉到别的值",
            PlaybackDefaults.START_TIMEOUT_OPTIONS_MS.contains(PlaybackDefaults.START_TIMEOUT_MS),
        )
    }

    @Test
    fun `重试次数默认值必须出现在选项里`() {
        assertTrue(
            "默认 ${PlaybackDefaults.RETRY_COUNT} 次不在选项 ${PlaybackDefaults.RETRY_OPTIONS} 里",
            PlaybackDefaults.RETRY_OPTIONS.contains(PlaybackDefaults.RETRY_COUNT),
        )
    }

    @Test
    fun `选项列表严格升序且不重复`() {
        val timeouts = PlaybackDefaults.START_TIMEOUT_OPTIONS_MS
        assertEquals(timeouts.distinct().sorted(), timeouts)
        val retries = PlaybackDefaults.RETRY_OPTIONS
        assertEquals(retries.distinct().sorted(), retries)
    }
}
