package com.litv.tv.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
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

        tabs[TAB_PLAY]?.setOnClickListener { backToHome(activity, TAB_PLAY) }
        tabs[TAB_CHANNEL]?.setOnClickListener { backToHome(activity, TAB_CHANNEL) }
        tabs[TAB_REMOTE]?.setOnClickListener {
            if (current == TAB_REMOTE) return@setOnClickListener
            activity.startActivity(Intent(activity, RemoteActivity::class.java))
            activity.noTransition()
            activity.finish()
        }
        tabs[TAB_SETTINGS]?.setOnClickListener {
            if (current == TAB_SETTINGS) return@setOnClickListener
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
            activity.noTransition()
            activity.finish()
        }
    }

    /**
     * 回首页，并指定要落在哪个 Tab。
     *
     * 只 finish 是不够的：首页按"上次停留的 Tab"恢复，于是用户在设置页
     * 点「频道」，回到首页看到的却是上次那个 Tab（比如播放），
     * 看起来就像"点了频道却跳到别的页"。
     *
     * 这里把目标 Tab 写进 prefs，首页 onResume 读它来决定显示哪个面板。
     */
    private fun backToHome(activity: Activity, tab: String) {
        (activity.application as? com.litv.tv.LitvApp)?.prefs?.lastTab = tab
        activity.finish()
        activity.noTransition()
    }
}

/**
 * 关闭 Activity 切换动画。
 *
 * `overridePendingTransition` 从 API 34 起**已被忽略**——也就是说
 * 之前写的"关闭动画"在 Android 14+ 上根本没生效，切换时照样有系统动画。
 * API 33+ 改用 overrideActivityTransition，低版本回退到旧方法。
 */
@Suppress("DEPRECATION")
fun Activity.noTransition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    } else {
        overridePendingTransition(0, 0)
    }
}
