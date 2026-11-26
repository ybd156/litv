package com.litv.tv.player

/**
 * 「屏幕状态变了，播放器要配合做点什么」的最小接口。
 *
 * 抽成接口是为了让 [LivePlayers] 的记账能在纯 JVM 单测里验：
 * [PlayerManager] 依赖 Context，纯单测里造不出来。
 *
 * 语义（2026-11-26 定）：息屏**不停播**，只静音；亮屏恢复音量。
 * 停播只发生在「应用整体退到后台」那条路径上（[LivePlayers.pauseAll]）。
 */
interface ScreenControllable {
    fun pause()
    fun resume()

    /** 静音：播放继续、画面继续解码，只是不出声。 */
    fun mute() = Unit

    /** 恢复静音前的音量。 */
    fun unmute() = Unit
}
