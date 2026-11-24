package com.litv.tv.player

/**
 * 心跳去重闸门。
 *
 * 评审 P0-10 的事实：`/api/v1/tv/heartbeat` 只挂在服务端 `/tv/play` 成功分支上，
 * 缓存命中、内置源、本地解析、时移四条起播路径**一次都不报**——于是管理台的
 * 「终端设备」看到的在线时间与「正在播放」长期停留在只有走中转时才更新。
 *
 * 现在心跳改由会话状态触发：会话进入 `Playing` 时问一次本闸门要不要发。
 * 需要闸门是因为「进入 Playing」会在同一次起播里出现多次（换内核重试、
 * 暂停后恢复、位置重置后重建都会再进一次），不加去重就是每个动作一次心跳。
 *
 * 判据只有一条：**同一次起播（[attempt]）只发一次**，换台或重试会让
 * `SessionMachine` 的 attempt 递增，于是自然再发一次。
 */
class HeartbeatGate {

    private var lastAttempt = -1

    /**
     * @param attempt 会话状态机里本次起播的序号（换台/重试会变）
     * @param needsHeartbeat 这个来源是否需要心跳（见 `PlaySource.needsHeartbeat`）
     * @return true 表示这一次该发
     */
    fun shouldSend(attempt: Int, needsHeartbeat: Boolean): Boolean {
        if (!needsHeartbeat) return false
        if (attempt == lastAttempt) return false
        lastAttempt = attempt
        return true
    }

    /** 会话释放时清空：下次进页面是新的观看，不该被上一次的 attempt 挡住。 */
    fun reset() {
        lastAttempt = -1
    }
}
