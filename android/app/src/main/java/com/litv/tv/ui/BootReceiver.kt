package com.litv.tv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.litv.tv.data.Prefs

/**
 * 开机自启。
 *
 * 为什么需要：老人机场景，电视断电重开后如果还要用遥控器找 App，
 * 这个产品就没法给家人用。开机直接进直播是刚需。
 *
 * 注意：Android 10+ 对后台启动 Activity 有限制，但 BOOT_COMPLETED
 * 属于允许的例外场景；部分国产 ROM 仍需用户在系统里手动允许自启。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val prefs = Prefs(context)
        // 没绑定过就不打扰用户，让他自己去点图标走绑定流程
        if (prefs.token.isBlank()) {
            Log.i(TAG, "未绑定，跳过开机自启")
            return
        }
        runCatching {
            val i = Intent(context, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }.onFailure { Log.w(TAG, "开机自启失败", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
