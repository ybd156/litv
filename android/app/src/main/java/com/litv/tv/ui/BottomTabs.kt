package com.litv.tv.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.litv.tv.R

/**
 * 底部菜单栏的统一接线。
 *
 * 首页、遥控器页、设置页都用同一套行为：
 *   播放 / 频道 -> 回首页（首页自己决定落在哪个 Tab）
 *   遥控器     -> 已在遥控器页就不动，否则跳过去
 *   设置       -> 已在设置页就不动，否则跳过去
 *
 * 这样三个页面看起来是同一个 App 的不同 Tab，而不是互相跳转的独立页面。
 */
object BottomTabs {

    const val TAB_PLAY = "play"
    const val TAB_CHANNEL = "channel"
    const val TAB_REMOTE = "remote"
    const val TAB_SETTINGS = "settings"

    fun wire(activity: Activity, current: String) {
        val accent = activity.getColor(R.color.accent)
        val dim = activity.getColor(R.color.text_secondary)

        val tabs = mapOf(
            TAB_PLAY to activity.findViewById<TextView>(R.id.tabPlay),
            TAB_CHANNEL to activity.findViewById<TextView>(R.id.tabChannel),
            TAB_REMOTE to activity.findViewById<TextView>(R.id.tabRemote),
            TAB_SETTINGS to activity.findViewById<TextView>(R.id.tabSettings),
        )
        tabs[current]?.setTextColor(accent)
        tabs.filterKeys { it != current }.values.forEach { it?.setTextColor(dim) }

        tabs[TAB_PLAY]?.setOnClickListener { backToHome(activity) }
        tabs[TAB_CHANNEL]?.setOnClickListener { backToHome(activity) }
        tabs[TAB_REMOTE]?.setOnClickListener {
            if (current == TAB_REMOTE) return@setOnClickListener
            activity.startActivity(Intent(activity, RemoteActivity::class.java))
            activity.overridePendingTransition(0, 0)
            activity.finish()
        }
        tabs[TAB_SETTINGS]?.setOnClickListener {
            if (current == TAB_SETTINGS) return@setOnClickListener
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
            activity.overridePendingTransition(0, 0)
            activity.finish()
        }
    }

    /**
     * 回首页。
     *
     * 首页可能是 HomeActivity（电视）或 PhoneHomeActivity（手机），
     * 直接 finish 回到栈里的那个即可——不要重新 startActivity，
     * 否则会多开一层。
     */
    private fun backToHome(activity: Activity) {
        activity.finish()
        activity.overridePendingTransition(0, 0)
    }
}
