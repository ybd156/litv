package com.litv.tv.player

/**
 * 屏幕电源状态每 5 秒轮询一次时要做什么。
 *
 * 为什么不能只监听 `ACTION_SCREEN_OFF`：2026-11-26 电视现场，面板已经黑了
 * （`dumpsys power` = `mWakefulness=Asleep` / `Display Power: state=OFF`），
 * 而应用注册的接收器一行日志都没打，说明这条关屏**没有投递广播**。
 * 于是停播不会发生，VLC 的「12 秒没送帧 → 轻推 pause/play」还会把已经停掉的
 * 播放重新拉起来 —— 表现就是「屏幕黑着、声音还在放」。
 *
 * 判断逻辑放在这里（纯函数、不碰 Android 类）是为了能单测：
 * `PlayerManager`/`LitvApp` 都要 Context，纯 JVM 测试里造不出来。
 */
enum class ScreenPowerEvent { None, Pause, Resume }

object ScreenPowerWatch {

    /**
     * @param last 上一次观察到的 isInteractive；null 表示还没有基线（第一次只看不动作）
     * @param now 这一次观察到的 isInteractive
     */
    fun decide(last: Boolean?, now: Boolean): ScreenPowerEvent = when {
        last == null -> ScreenPowerEvent.None
        last == now -> ScreenPowerEvent.None
        now -> ScreenPowerEvent.Resume
        else -> ScreenPowerEvent.Pause
    }
}
