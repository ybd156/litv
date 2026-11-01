package com.litv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.litv.tv.LitvApp
import com.litv.tv.R
import com.litv.tv.data.Bootstrap
import com.litv.tv.data.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页：频道网格。
 *
 * 冷启动体验关键点：
 *   - 先用本地缓存渲染（0 网络等待），再后台刷新，避免"打开就是转圈"
 *   - 刷新失败不清空已有列表，只在状态栏提示
 *   - 点频道直接进播放页；返回时焦点回到原来那一格
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var app: LitvApp
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var groupLabel: TextView

    private lateinit var adapter: ChannelAdapter
    private val main = Handler(Looper.getMainLooper())
    private var lastFocusedPos = 0
    private val favorites = mutableSetOf<Long>()
    private val nowTitles = mutableMapOf<Long, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        app = application as LitvApp

        // 没绑过就先走绑定流程
        if (app.prefs.token.isBlank()) {
            startActivity(Intent(this, BindActivity::class.java))
            finish()
            return
        }

        list = findViewById(R.id.channelList)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        groupLabel = findViewById(R.id.groupLabel)

        adapter = ChannelAdapter(app.tier.logoMaxPx, onClick = { ch -> openPlayer(ch) }, onLongClick = { ch ->
            toggleFavorite(ch)
        })
        val span = if (app.tier == Tier.LOW) 4 else 6
        list.layoutManager = GridLayoutManager(this, span)
        list.adapter = adapter
        list.itemAnimator = null // 低端机上动画反而添乱

        // 先渲染缓存
        renderCache()
        refresh()
    }

    private fun renderCache() {
        val cached = app.prefs.cachedBootstrap
        if (cached.isBlank()) return
        runCatching {
            val b = Gson().fromJson(cached, Bootstrap::class.java)
            applyData(b, fromCache = true)
        }
    }

    private fun refresh() {
        status.text = getString(R.string.loading)
        app.api.bootstrap(
            onOk = { b ->
                app.prefs.cachedBootstrap = Gson().toJson(b)
                applyData(b, fromCache = false)
            },
            onErr = { msg ->
                if (adapter.itemCount == 0) {
                    status.text = "加载失败：$msg"
                    // token 失效（服务端被吊销）→ 回绑定页
                    if (msg.contains("未绑定") || msg.contains("token")) {
                        app.prefs.clearSession()
                        startActivity(Intent(this, BindActivity::class.java))
                        finish()
                    }
                } else {
                    status.text = "刷新失败（显示的是缓存）：$msg"
                }
            })
    }

    private fun applyData(b: Bootstrap, fromCache: Boolean) {
        // 服务端下发的远程配置优先于本地设置——这样在管理台改一次，
        // 家里所有电视下次刷新就生效，不用挨个跑去按遥控器。
        b.device?.config?.let { applyRemoteConfig(it) }

        val nowMap = b.now.associate { it.channelId to it.title }
        nowTitles.clear()
        nowTitles.putAll(nowMap)
        favorites.clear()
        favorites.addAll(b.favorites)
        adapter.submit(b.channels, nowMap, favorites)

        empty.visibility = if (b.channels.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (b.channels.isEmpty()) View.GONE else View.VISIBLE

        val total = b.channels.size
        groupLabel.text = "${b.groups.size} 个分组 · $total 个频道"
        status.text = if (fromCache) "已加载缓存，正在刷新…" else "更新于 ${timeNow()}"

        // 焦点落在上次看的那台
        if (!fromCache && total > 0) {
            val idx = b.channels.indexOfFirst { it.id == app.prefs.lastChannelId }
            lastFocusedPos = if (idx >= 0) idx else 0
            list.post { list.scrollToPosition(lastFocusedPos) }
        }
    }

    private fun openPlayer(ch: Channel) {
        app.prefs.lastChannelId = ch.id
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, ch.id)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, ch.name)
        )
    }

    /** MENU 键进设置——TV 上没有"我的"页面，菜单键是最符合直觉的入口。 */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_MENU,
                android.view.KeyEvent.KEYCODE_INFO -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 长按频道 = 收藏/取消收藏。
     * 为什么用长按而不是加个星标按钮：TV 上没有鼠标，多一个可聚焦元素
     * 就多一次方向键跳动，破坏"上下走、OK 进"的直觉。
     */
    private fun toggleFavorite(ch: Channel) {
        val on = !favorites.contains(ch.id)
        app.api.favorite(ch.id, on,
            onOk = {
                if (on) favorites.add(ch.id) else favorites.remove(ch.id)
                adapter.submit(adapter.items(), nowTitles, favorites)
                status.text = if (on) "已收藏「${ch.name}」" else "已取消收藏「${ch.name}」"
            },
            onErr = { status.text = "收藏失败：$it" })
    }

    /**
     * 应用服务端下发的播放参数。
     * 只覆盖"服务端明确给了值"的字段，没给的保持本地设置，
     * 避免服务端字段缺失时把用户自己调好的参数冲掉。
     */
    private fun applyRemoteConfig(cfg: com.litv.tv.data.DeviceConfig) {
        if (cfg.engine.isNotBlank() && cfg.engine != "auto") app.prefs.engine = cfg.engine
        if (cfg.decode.isNotBlank()) app.prefs.decode = cfg.decode
        if (cfg.bufferMs > 0) app.prefs.bufferMs = cfg.bufferMs
        if (cfg.aspect.isNotBlank()) app.prefs.aspect = cfg.aspect
    }

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    override fun onResume() {
        super.onResume()
        // 从播放页返回时轻量刷新一下"正在播"
        if (::adapter.isInitialized && adapter.itemCount > 0) refresh()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
