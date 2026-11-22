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
    fun `单次内核尝试的最坏路径必须落在预算内`() {
        // ⚠ 这条断言的是**单次内核尝试**（startEngine 之间的那一段），不是整段起播。
        // 原因是实现口径：PlayerManager.startEngine() 每次起内核都会重设 deadline，
        // 所以真正生效的是「每次尝试各一份预算」。原版测试把无字节、无帧、一次超时
        // 三段相加当成"总预算最坏路径"，那三段其实分属不同尝试，等于把漏项固化成绿灯。
        // 口径本身待定（docs/refactor §4「起播预算」行）；这里先把**单次**能证明的
        // 关系钉住，任何一条不成立都说明某个阶段会被预算先杀、永远不生效。
        val noByteChain = PlaybackDefaults.START_NOBYTE_MS * (PlaybackDefaults.RETRY_COUNT + 1)
        assertTrue(
            "无字节重试链 ${PlaybackDefaults.START_NOBYTE_MS}×(${PlaybackDefaults.RETRY_COUNT}+1)" +
                "=$noByteChain 不小于预算 ${PlaybackDefaults.START_BUDGET_MS}：" +
                "重试或换内核还没走完就会被预算掐掉",
            noByteChain < PlaybackDefaults.START_BUDGET_MS,
        )
        val noFramePath = PlaybackDefaults.START_NOBYTE_MS + PlaybackDefaults.START_FRAME_MS
        assertTrue(
            "无字节 + 无帧 $noFramePath 不小于预算 ${PlaybackDefaults.START_BUDGET_MS}：" +
                "「有数据但不出帧→换软解」这一步永远不会生效",
            noFramePath < PlaybackDefaults.START_BUDGET_MS,
        )
    }

    @Test
    fun `单次预算必须远小于旧的两分钟`() {
        // 老代码 3 内核 ×(15s 超时 + 15s 重试) ≈ 105s。这条防止有人把预算又调回去。
        //
        // 上界从 30s 放到 40s：START_NOBYTE_MS 提到 12s 之后，单次尝试的无字节链
        // 已经是 24s（12×2），预算必须明显高于它（上面那条断言），所以 <30s 与它冲突。
        // 40s 仍然远小于旧代码的每台 105s，"别调回两分钟"的意图不变。
        assertTrue(
            "START_BUDGET_MS=${PlaybackDefaults.START_BUDGET_MS} 应 < 40000",
            PlaybackDefaults.START_BUDGET_MS < 40_000,
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
