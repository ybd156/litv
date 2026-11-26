package com.litv.tv.player

import android.view.KeyEvent

/**
 * 儿童锁 / 老人模式：把界面锁在某一个台上。
 *
 * 要做的事只有三件：①不给换台；②不给退出播放页（面板/设置/节目单/返回全拦）；
 * ③回到前台继续播。解锁要输 4 位密码。
 *
 * **这不是安全边界**：密码明文存在本机 SharedPreferences 里，能装应用、
 * 能 `adb shell` 的人都能直接改。它挡的是「小孩乱按遥控器」「老人误触设置」，
 * 不是挡蓄意的对手 —— 所以这里不引 Keystore、不做哈希。
 *
 * 逻辑抽成纯函数是为了能单测：真正的按键拦截在 `PlayerActivity.dispatchKeyEvent`，
 * 那部分只能靠真机验。
 */
object KioskLock {

    /** 密码位数。 */
    const val PIN_LENGTH = 4

    /** 没设过密码时的默认密码（界面上会提示，改密码功能后续再加）。 */
    const val DEFAULT_PIN = "1234"

    /** 长按 OK 多久弹出解锁键盘。太短会和「短按出提示」抢事件。 */
    const val HOLD_MS = 1_500L

    /**
     * 锁定状态下要吞掉的按键。
     *
     * 放行的只有三类：
     * - 音量（`VOLUME_*`）与电源：这是电视的物理语义，拦了会让人以为遥控器坏了；
     * - OK / 确认键：它不改变任何状态，只是我们用它打开解锁键盘；
     * - 其余未列出的键（媒体键、输入源切换等）不主动拦，交给系统。
     */
    fun isBlockedKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_TV,
        -> true
        // 0-9 是连续的一段，遥控器有数字键的机型也一并拦掉
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> true
        else -> false
    }

    /**
     * 把用户输入的密码归一化：只留数字，超长取后 4 位，空值回落到默认密码。
     *
     * 为什么「空值回落默认」而不是「空值拒绝一切」：老版本没有这个键，
     * 用户第一次开锁时 prefs 里读出来是空串；这时若判「密码为空 → 永不匹配」，
     * 用户就被自己的电视锁在门外了。
     */
    fun sanitizePin(raw: String?): String {
        val digits = (raw ?: "").filter { it.isDigit() }
        return when {
            digits.isEmpty() -> DEFAULT_PIN
            digits.length <= PIN_LENGTH -> digits.padStart(PIN_LENGTH, '0')
            else -> digits.takeLast(PIN_LENGTH)
        }
    }

    /** 输入是否等于密码。两边都先归一化，所以 "12 34"、"12345" 这类都能对上。 */
    fun matches(input: String?, pin: String?): Boolean =
        sanitizePin(input) == sanitizePin(pin)

    /** 锁定时给用户看的那句话。 */
    fun hint(channelName: String): String {
        val name = channelName.ifBlank { "当前频道" }
        return "已锁定「$name」：长按 OK 输入密码解锁"
    }
}
