package com.litv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.litv.tv.LitvApp
import com.litv.tv.R
import com.litv.tv.data.Bootstrap
import com.litv.tv.data.Channel

/**
 * 手机端首页。
 *
 * 与 TV 端的差别（不是简单换皮）：
 *  - 单列行式列表（拇指可及、点击热区大）
 *  - 顶部横向滚动的分组 chip（TV 上是用左右键切，手机上必须能直接点）
 *  - 收藏用右侧星标（TV 上是长按，手机上星标更直观）
 *  - 底部 Tab（手机有导航习惯，TV 没有）
 */
class PhoneHomeActivity : AppCompatActivity() {

    private lateinit var app: LitvApp
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var groupChips: LinearLayout
    private lateinit var adapter: PhoneChannelAdapter

    private var all: List<Channel> = emptyList()
    private val favorites = mutableSetOf<Long>()
    private val nowTitles = mutableMapOf<Long, String>()
    private var groups: List<String> = emptyList()
    private var selectedGroup: String? = null   // null = 全部
    private var onlyFav = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_home)
        applySystemInsets()
        app = application as LitvApp

        if (app.prefs.token.isBlank()) {
            startActivity(Intent(this, BindActivity::class.java)); finish(); return
        }

        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        groupChips = findViewById(R.id.groupChips)

        adapter = PhoneChannelAdapter(
            onClick = { ch -> openPlayer(ch) },
            onStarClick = { ch -> toggleFavorite(ch) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<TextView>(R.id.tabLive).setOnClickListener {
            onlyFav = false; selectedGroup = null; render()
        }
        findViewById<TextView>(R.id.tabFav).setOnClickListener {
            onlyFav = true; render()
        }
        findViewById<TextView>(R.id.tabRemote).setOnClickListener {
            startActivity(Intent(this, RemoteActivity::class.java))
        }
        findViewById<TextView>(R.id.tabSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        load()
    }

    private fun load() {
        val cached = app.prefs.cachedBootstrap
        if (cached.isNotBlank()) {
            runCatching { apply(Gson().fromJson(cached, Bootstrap::class.java), true) }
        }
        app.api.bootstrap(
            onOk = { b ->
                app.prefs.cachedBootstrap = Gson().toJson(b)
                apply(b, false)
            },
            onErr = { msg ->
                if (all.isEmpty()) {
                    status.text = "加载失败：$msg"
                    if (msg.contains("未绑定") || msg.contains("token")) {
                        app.prefs.clearSession()
                        startActivity(Intent(this, BindActivity::class.java)); finish()
                    }
                } else {
                    status.text = "刷新失败（显示缓存）"
                }
            })
    }

    private fun apply(b: Bootstrap, fromCache: Boolean) {
        all = b.channels
        favorites.clear(); favorites.addAll(b.favorites)
        nowTitles.clear(); nowTitles.putAll(b.now.associate { it.channelId to it.title })
        groups = all.map { it.group }.filter { it.isNotBlank() }.distinct()
        status.text = if (fromCache) "缓存" else "${all.size} 个频道"
        renderChips()
        render()
    }

    private fun renderChips() {
        groupChips.removeAllViews()
        addChip("全部") { selectedGroup = null; onlyFav = false; render() }
        if (favorites.isNotEmpty()) {
            addChip("★ 收藏") { onlyFav = true; render() }
        }
        groups.forEach { g ->
            addChip(g) { selectedGroup = g; onlyFav = false; render() }
        }
    }

    private fun addChip(label: String, onClick: () -> Unit) {
        val tv = TextView(this)
        tv.text = label
        tv.setTextAppearance(R.style.GroupChip)
        tv.setPadding(dp(14), dp(7), dp(14), dp(7))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) }
        tv.layoutParams = lp
        tv.gravity = Gravity.CENTER
        tv.background = getDrawable(R.drawable.bg_card)
        tv.setOnClickListener { onClick() }
        groupChips.addView(tv)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val filtered = when {
            onlyFav -> all.filter { favorites.contains(it.id) }
            selectedGroup != null -> all.filter { it.group == selectedGroup }
            else -> all
        }
        adapter.submit(filtered, nowTitles, favorites)
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggleFavorite(ch: Channel) {
        val on = !favorites.contains(ch.id)
        app.api.favorite(ch.id, on, onOk = {
            if (on) favorites.add(ch.id) else favorites.remove(ch.id)
            renderChips()
            render()
        }, onErr = { status.text = "收藏失败：$it" })
    }

    /**
     * 让内容避开状态栏和底部手势条。
     *
     * 布局里的 20dp 顶部内边距只是视觉留白，**不能代替系统栏避让**——
     * 全面屏手机上状态栏会直接压在标题上，底部 Tab 会被手势条盖住一半。
     * 这里把 systemBars 的 inset 加到根容器上，标题和 Tab 就都躲开了。
     */
    private fun applySystemInsets() {
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun openPlayer(ch: Channel) {
        app.prefs.lastChannelId = ch.id
        startActivity(Intent(this, PhonePlayerActivity::class.java)
            .putExtra(PhonePlayerActivity.EXTRA_CHANNEL_ID, ch.id))
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && all.isNotEmpty()) load()
    }
}
