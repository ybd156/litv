package com.litv.tv.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.ImageView
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

        val tabs = listOf(
            Tab(TAB_PLAY, R.id.tabPlay, R.id.iconPlay, R.id.itemPlay),
            Tab(TAB_CHANNEL, R.id.tabChannel, R.id.iconChannel, R.id.itemChannel),
            Tab(TAB_REMOTE, R.id.tabRemote, R.id.iconRemote, R.id.itemRemote),
            Tab(TAB_SETTINGS, R.id.tabSettings, R.id.iconSettings, R.id.itemSettings),
        )

        // 选中只靠颜色：文字与图标同色，未选中一律压到次级灰（扁平导航的常见做法）
        tabs.forEach { t ->
            val tint = if (t.key == current) accent else dim
            activity.findViewById<TextView>(t.textId)?.setTextColor(tint)
            activity.findViewById<ImageView>(t.iconId)?.setColorFilter(tint)
        }

        tabs.forEach { t ->
            // 点击挂在整个格子上（图标 + 文字都能点）。老布局没有容器，退化挂文字。
            val target = activity.findViewById<View>(t.itemId)
                ?: activity.findViewById<TextView>(t.textId)
                ?: return@forEach
            target.setOnClickListener {
                when (t.key) {
                    TAB_PLAY -> backToHome(activity, TAB_PLAY)
                    TAB_CHANNEL -> backToHome(activity, TAB_CHANNEL)
                    TAB_REMOTE -> if (current != TAB_REMOTE) {
                        activity.startActivity(Intent(activity, RemoteActivity::class.java))
                        activity.noTransition()
                        activity.finish()
                    }
                    TAB_SETTINGS -> if (current != TAB_SETTINGS) {
                        activity.startActivity(Intent(activity, SettingsActivity::class.java))
                        activity.noTransition()
                        activity.finish()
                    }
                }
            }
        }
    }

    /** 一格导航：文字、图标、可点区域三个 id 绑在一起，少写一份 map 就少一处对不上。 */
    private data class Tab(val key: String, val textId: Int, val iconId: Int, val itemId: Int)

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
