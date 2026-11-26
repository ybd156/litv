package com.litv.tv.player

/**
 * 息屏时对播放做什么。
 *
 * 抽成纯函数是为了两件事：
 * 1. 只有一处决定「默认静音」——默认值散在接收器里最容易漏改，
 *    升级后老用户就会变成「息屏继续响」；
 * 2. 能在 JVM 单测里验，不需要设备。
 *
 * 2026-11-26 改口径：默认从「停播」改成「静音」。用户原话
 * 「我觉得都不用停播，息屏你就把这个应用静音啊」——直播继续往前走，
 * 亮屏就是现场，也不用再判断「暂停久了要不要重连」。
 */
enum class ScreenOffAction {
    /** 息屏就静音，播放继续（默认）；亮屏恢复音量。 */
    Mute,

    /** 什么都不做，声音继续（给「息屏当背景音」用）。 */
    None,
}

object ScreenOffPlan {

    /** 偏好里「静音」的取值。`Prefs.screenOffAction` 的默认值必须与它一致。 */
    const val VALUE_MUTE = "mute"

    /** 偏好里「无动作」的取值。 */
    const val VALUE_NONE = "none"

    /**
     * 旧版本（2026-11-26 之前）存的 `"pause"` 现在也映射成 [ScreenOffAction.Mute]：
     * 那时的手段是停播，现在的诉求是别出声，静音是最接近的行为。
     */
    const val VALUE_LEGACY_PAUSE = "pause"

    /**
     * 把偏好里的字符串映射成动作。
     *
     * 只有 `none` 才表示「不处理」，其余一概按 [ScreenOffAction.Mute]：
     * 认不出的值（手工改过 prefs、降级、写坏了、大小写不对）宁可多静音一次，
     * 也不要半夜息屏后客厅一直响。大小写**故意**不容错——写入路径只有
     * [valueOf]，读到 `None` 说明有人手改过，此时按最安全的一档处理。
     */
    fun of(raw: String?): ScreenOffAction =
        if (raw?.trim() == VALUE_NONE) ScreenOffAction.None else ScreenOffAction.Mute

    /** 偏好值 → 设置页显示的中文。 */
    fun label(action: ScreenOffAction): String = when (action) {
        ScreenOffAction.Mute -> "静音"
        ScreenOffAction.None -> "无动作"
    }

    /** 设置页显示的中文 → 偏好值。 */
    fun valueOf(label: String): String = when (label.trim()) {
        "无动作" -> VALUE_NONE
        else -> VALUE_MUTE
    }

    /**
     * 暂停多久之后就不该「接着播」，而应回到直播边缘。
     *
     * 只对「应用退到后台」这类真暂停有意义（息屏现在只静音，不暂停）。
     *
     * 30 秒的依据：滑窗 HLS 的缓冲与签名余量都在这个量级——
     * 再短会把「按个暂停喝口水」也变成重连；再长就会先播一段旧内容再跳，
     * 观感上像卡了一下（电视实测：熄屏几分钟后恢复必然触发
     * `ERROR_CODE_BEHIND_LIVE_WINDOW`，那还不如直接重连）。
     */
    const val LIVE_REJOIN_MS = 30_000L

    /** 继续时该「续播」还是「重回直播边缘」。 */
    enum class ResumeAction {
        Resume,

        RejoinLive,
    }

    /**
     * 暂停后怎么继续。
     *
     * 只对直播有意义：回看是有限时长内容，暂停多久都该从暂停处接着看，
     * 调用方必须先判断「当前是不是直播」再问这里。
     */
    fun onScreenOn(pausedMs: Long, thresholdMs: Long = LIVE_REJOIN_MS): ResumeAction =
        if (pausedMs >= thresholdMs) ResumeAction.RejoinLive else ResumeAction.Resume
}
