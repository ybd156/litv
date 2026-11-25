package com.litv.tv.player

/**
 * 息屏时对播放做什么。
 *
 * 抽成纯函数是为了两件事：
 * 1. 只有一处决定「默认停播」——以前这个默认值散在接收器里，改成偏好时最容易
 *    漏改默认值，结果升级后老用户变成「息屏继续响」；
 * 2. 能在 JVM 单测里验，不需要设备。
 */
enum class ScreenOffAction {
    /** 息屏就停播（默认）；亮屏接着播。 */
    Pause,

    /** 什么都不做，声音继续（给「息屏当背景音」用）。 */
    None,
}

object ScreenOffPlan {

    /** 偏好里「停播」的取值。`Prefs.screenOffAction` 的默认值必须与它一致。 */
    const val VALUE_PAUSE = "pause"

    /** 偏好里「无动作」的取值。 */
    const val VALUE_NONE = "none"

    /**
     * 把偏好里的字符串映射成动作。
     *
     * 认不出的值（手工改过 prefs、降级、写坏了）一律按 [ScreenOffAction.Pause]：
     * 宁可多停一次，也不要半夜息屏后客厅一直响。
     */
    fun of(raw: String?): ScreenOffAction =
        if (raw?.trim() == VALUE_NONE) ScreenOffAction.None else ScreenOffAction.Pause

    /** 偏好值 → 设置页显示的中文。 */
    fun label(action: ScreenOffAction): String = when (action) {
        ScreenOffAction.Pause -> "停播"
        ScreenOffAction.None -> "无动作"
    }

    /** 设置页显示的中文 → 偏好值。 */
    fun valueOf(label: String): String = when (label.trim()) {
        "无动作" -> VALUE_NONE
        else -> VALUE_PAUSE
    }

    /**
     * 暂停多久之后就不该「接着播」，而应回到直播边缘。
     *
     * 30 秒的依据：滑窗 HLS 的缓冲与签名余量都在这个量级——
     * 再短会把「按个暂停喝口水」也变成重连；再长就会先播一段旧内容再跳，
     * 观感上像卡了一下（电视实测：熄屏几分钟后恢复必然触发
     * `ERROR_CODE_BEHIND_LIVE_WINDOW`，那还不如直接重连）。
     */
    const val LIVE_REJOIN_MS = 30_000L

    /** 亮屏/继续时该「续播」还是「重回直播边缘」。 */
    enum class ResumeAction {
        Resume,

        RejoinLive,
    }

    /**
     * 亮屏后怎么继续。
     *
     * 只对直播有意义：回看是有限时长内容，暂停多久都该从暂停处接着看，
     * 调用方必须先判断「当前是不是直播」再问这里。
     */
    fun onScreenOn(pausedMs: Long, thresholdMs: Long = LIVE_REJOIN_MS): ResumeAction =
        if (pausedMs >= thresholdMs) ResumeAction.RejoinLive else ResumeAction.Resume
}
