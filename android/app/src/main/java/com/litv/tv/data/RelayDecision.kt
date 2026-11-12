package com.litv.tv.data

/**
 * 「直连失败了该怎么办」的策略（**纯函数**，可 JVM 单测）。
 *
 * 用户口径（本轮需求）：**中转默认不用**，只在用户手动点时用。
 * 以前是"直连第一个内核失败 → 立即 forceRelayOnce → 重播"，用户每次都要先看到
 * 一条"改走服务端中转"的提示，而且 NAS 上行被悄悄用掉。现在默认变成：
 * 报错 + 把选择权交给用户。
 *
 * 保留一个开关（`autoRelay`，默认 false）给"不想每次都手动"的人，
 * 同时让线上行为可以一键回退到旧的自动降级。
 */
object RelayDecision {

    enum class Action {
        /** 再试一次直连（用户点了重试，或还没失败过）。 */
        RETRY_DIRECT,

        /** 改用 NAS 中转（用户手动点了，或用户开了自动中转开关）。 */
        USE_RELAY,

        /** 既不重试也不中转：把错误与两个选项摆给用户。 */
        SHOW_ERROR,
    }

    /**
     * @param autoRelay      设置里的「直连失败时自动改用中转」开关（默认 false）
     * @param directFailed   本次播放是否已经确认直连不通（directBrokenChannels 命中 / 本内核层直连失败）
     * @param userTappedRelay 用户是不是刚点了「用 NAS 中转试试」
     */
    fun decide(
        autoRelay: Boolean,
        directFailed: Boolean,
        userTappedRelay: Boolean = false,
    ): Action = when {
        // 用户明确点了，一律照办（最高优先级）
        userTappedRelay -> Action.USE_RELAY
        // 没直连失败：正常重试一次直连
        !directFailed -> Action.RETRY_DIRECT
        // 直连失败 + 用户开了自动中转：保持旧行为
        autoRelay -> Action.USE_RELAY
        // 默认：不自动切，把选择交给用户
        else -> Action.SHOW_ERROR
    }
}
