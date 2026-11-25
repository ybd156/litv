package com.litv.tv.player

/**
 * 「能因屏幕熄灭而暂停、因屏幕点亮而恢复」的最小接口。
 *
 * 为什么单独抽一个接口：
 * 屏幕熄灭时电视不会把前台 Activity 推到后台（实测 `mWakefulness=Asleep` 而
 * `ResumedActivity` 仍是 `PlayerActivity`），所以只能靠系统广播停播。
 * 这条路径要能在纯 JVM 单测里验证（`LivePlayers` 的记账），
 * 而 [PlayerManager] 依赖 Android Context，单测里造不出来。
 */
interface ScreenPausable {
    fun pause()
    fun resume()
}
