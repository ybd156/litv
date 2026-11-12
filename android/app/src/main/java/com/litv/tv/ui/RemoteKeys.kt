package com.litv.tv.ui

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import com.litv.tv.LitvApp
import org.json.JSONObject

/**
 * 把手机发来的遥控指令翻译成 KeyEvent，直接喂给当前 Activity。
 *
 * 为什么这么做：播放页和首页本来就有完整的按键处理逻辑（换台/呼出面板/数字跳台…），
 * 再写一套"遥控专用的指令处理"会变成两份逻辑、迟早不一致。
 * 伪造 KeyEvent 让遥控和真遥控器走**完全同一条代码路径**，行为天然一致。
 */
object RemoteKeys {

    /** 返回 true 表示已处理。 */
    fun handle(activity: Activity, cmd: JSONObject): Boolean {
        val app = activity.application as LitvApp
        when (cmd.optString("type", "key")) {
            "key" -> {
                val code = keyCodeOf(cmd.optString("key")) ?: return false
                // 长按语义：有些指令需要 DOWN/UP 分离（比如长按 OK 呼出线路）
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return true
            }
            "play" -> {
                val chId = cmd.optLong("channel_id", 0)
                if (chId <= 0) return false
                app.prefs.lastChannelId = chId
                val target = PhoneHomeActivity::class.java
                // 电视端直接进播放页；手机端先进首页再点（保持返回栈正常）
                val intent = if (DeviceType.isTV(activity)) {
                    Intent(activity, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, chId)
                } else {
                    Intent(activity, target)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                return true
            }
        }
        return false
    }

    private fun keyCodeOf(name: String): Int? = when (name.lowercase()) {
        "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "ok", "enter", "center" -> KeyEvent.KEYCODE_DPAD_CENTER
        "back" -> KeyEvent.KEYCODE_BACK
        "menu" -> KeyEvent.KEYCODE_MENU
        "info" -> KeyEvent.KEYCODE_INFO
        "home" -> KeyEvent.KEYCODE_HOME
        "volume_up" -> KeyEvent.KEYCODE_VOLUME_UP
        "volume_down" -> KeyEvent.KEYCODE_VOLUME_DOWN
        "channel_up" -> KeyEvent.KEYCODE_CHANNEL_UP
        "channel_down" -> KeyEvent.KEYCODE_CHANNEL_DOWN
        "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        else -> {
            // 数字键：手机遥控面板发 "0".."9"
            val d = name.toIntOrNull() ?: return null
            if (d in 0..9) KeyEvent.KEYCODE_0 + d else null
        }
    }
}
