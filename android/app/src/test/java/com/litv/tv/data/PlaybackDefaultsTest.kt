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

    // ---------------------------------------------------------------- 起播预算（C3）

    @Test
    fun `总预算不小于显式最坏路径之和`() {
        // 最坏路径 = 无字节阶段 + 有字节无帧阶段 + 一次完整超时重试
        //          = 6000 + 8000 + 15000 × 1 = 29000
        // 预算若小于它，就意味着"某一层窗口还没走完就先被总预算掐掉"——
        // 那等于其中某个阶段是永远不会生效的死代码。
        val worstPath =
            PlaybackDefaults.START_NOBYTE_MS +
                PlaybackDefaults.START_FRAME_MS +
                PlaybackDefaults.START_TIMEOUT_MS * PlaybackDefaults.RETRY_COUNT
        assertTrue(
            "START_BUDGET_MS=${PlaybackDefaults.START_BUDGET_MS} 小于最坏路径 $worstPath，" +
                "某个阶段永远不会生效",
            PlaybackDefaults.START_BUDGET_MS >= worstPath,
        )
    }

    @Test
    fun `总预算必须远小于旧的两分钟`() {
        // 老代码 3 内核 ×(15s 超时 + 15s 重试) ≈ 105s。这条防止有人把预算又调回去。
        assertTrue(
            "START_BUDGET_MS=${PlaybackDefaults.START_BUDGET_MS} 应 < 30000",
            PlaybackDefaults.START_BUDGET_MS < 30_000,
        )
    }

    @Test
    fun `起播超时不得被调回小值`() {
        // 15s 是慢盒子（32 位 Amlogic）上实测出来的下限。当初压到 3~5 秒那次事故
        // 就是"本来能起播的流被判定为不可播"。这条是那道闸。
        assertTrue(
            "START_TIMEOUT_MS=${PlaybackDefaults.START_TIMEOUT_MS} 必须 >= 15000",
            PlaybackDefaults.START_TIMEOUT_MS >= 15_000,
        )
        // 两个阶段都必须小于整体超时，否则阶段窗口没有意义
        assertTrue(PlaybackDefaults.START_NOBYTE_MS < PlaybackDefaults.START_TIMEOUT_MS)
        assertTrue(PlaybackDefaults.START_FRAME_MS < PlaybackDefaults.START_TIMEOUT_MS)
    }
}
