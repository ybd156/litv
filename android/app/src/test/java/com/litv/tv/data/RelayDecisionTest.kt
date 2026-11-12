package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 「直连失败后该怎么办」的策略（T2 的唯一自动化保护）。
 *
 * 用户口径：**中转默认不用，只在手动点时用**。这条策略最容易在后续重构里被
 * "顺手改回自动降级"（毕竟自动降级体验更顺），所以把它做成纯函数并钉死：
 * 默认配置下**任何**直连失败都不得返回"中转"。
 */
class RelayDecisionTest {

    @Test
    fun `默认配置下直连失败不自动中转`() {
        val action = RelayDecision.decide(autoRelay = false, directFailed = true)
        assertNotEquals(RelayDecision.Action.USE_RELAY, action)
        assertEquals(RelayDecision.Action.SHOW_ERROR, action)
    }

    @Test
    fun `用户手动点了中转就必须中转`() {
        assertEquals(
            RelayDecision.Action.USE_RELAY,
            RelayDecision.decide(autoRelay = false, directFailed = true, userTappedRelay = true),
        )
        // 即使没直连失败，用户点中转也照办（他可能就是想用 NAS 拉）
        assertEquals(
            RelayDecision.Action.USE_RELAY,
            RelayDecision.decide(autoRelay = false, directFailed = false, userTappedRelay = true),
        )
    }

    @Test
    fun `打开自动中转开关才回到旧行为`() {
        assertEquals(
            RelayDecision.Action.USE_RELAY,
            RelayDecision.decide(autoRelay = true, directFailed = true),
        )
    }

    @Test
    fun `没失败过就是重试直连`() {
        assertEquals(
            RelayDecision.Action.RETRY_DIRECT,
            RelayDecision.decide(autoRelay = false, directFailed = false),
        )
        assertEquals(
            RelayDecision.Action.RETRY_DIRECT,
            RelayDecision.decide(autoRelay = true, directFailed = false),
        )
    }
}
