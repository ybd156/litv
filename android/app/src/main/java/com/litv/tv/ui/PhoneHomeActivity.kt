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

    // 「正在热播」大卡片 + 搜索框
    private lateinit var searchInput: android.widget.EditText
    private lateinit var heroCard: LinearLayout
    private lateinit var heroLogo: android.widget.ImageView
    private lateinit var heroName: TextView
    private lateinit var heroNow: TextView
    private lateinit var heroPlay: TextView

    private var all: List<Channel> = emptyList()
    private val favorites = mutableSetOf<Long>()
    private val nowTitles = mutableMapOf<Long, String>()
    private var groups: List<String> = emptyList()
    private var selectedGroup: String? = null   // null = 全部
    private var onlyFav = false
    private var currentTab = TAB_PLAY

    private companion object {
        const val TAB_PLAY = "play"
        const val TAB_CHANNEL = "channel"
    }

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

        searchInput = findViewById(R.id.searchInput)
        heroCard = findViewById(R.id.heroCard)
        heroLogo = findViewById(R.id.heroLogo)
        heroName = findViewById(R.id.heroName)
        heroNow = findViewById(R.id.heroNow)
        heroPlay = findViewById(R.id.heroPlay)

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (::adapter.isInitialized) render()
            }
        })

        adapter = PhoneChannelAdapter(
            onClick = { ch -> openPlayer(ch) },
            onStarClick = { ch -> toggleFavorite(ch) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<TextView>(R.id.tabPlay).setOnClickListener { switchTab(TAB_PLAY) }
        findViewById<TextView>(R.id.tabChannel).setOnClickListener { switchTab(TAB_CHANNEL) }
        findViewById<TextView>(R.id.tabRemote).setOnClickListener {
            startActivity(Intent(this, RemoteActivity::class.java))
        }
        findViewById<TextView>(R.id.tabSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 默认落在「播放」页：手机竖屏第一屏最该看到的是"接着上次看"
        switchTab(TAB_PLAY)
        load()
    }

    /**
     * 切页。
     *
     * 播放页：突出「正在热播」大卡片，下面接收藏的台（没收藏就显示全部）。
     * 频道页：搜索 + 分组 + 全量列表。
     *
     * 两个页共用同一个 RecyclerView，只是喂不同的数据和显隐，省一套列表。
     */
    private fun switchTab(tab: String) {
        currentTab = tab
        val isPlay = tab == TAB_PLAY
        heroCard.visibility = if (isPlay && all.isNotEmpty()) View.VISIBLE else View.GONE
        searchInput.visibility = if (isPlay) View.GONE else View.VISIBLE
        findViewById<View>(R.id.groupBar).visibility = if (isPlay) View.GONE else View.VISIBLE
        if (::adapter.isInitialized) render()
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
        // 播放页：收藏优先；没收藏就显示全部，别让用户对着空白页
        if (currentTab == TAB_PLAY) {
            val favList = all.filter { favorites.contains(it.id) }
            val data = if (favList.isNotEmpty()) favList else all
            adapter.submit(data, nowTitles, favorites)
            empty.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
            renderHero()
            return
        }

        // 频道页：搜索 + 分组过滤
        val kw = searchInput.text.toString().trim()
        val filtered = all.filter { ch ->
            val okFav = !onlyFav || favorites.contains(ch.id)
            val okGroup = selectedGroup == null || ch.group == selectedGroup
            val okKw = kw.isBlank() ||
                ch.name.contains(kw, ignoreCase = true) ||
                ch.group.contains(kw, ignoreCase = true)
            okFav && okGroup && okKw
        }
        adapter.submit(filtered, nowTitles, favorites)
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * 「正在热播」大卡片：优先上次看的台，没有就挑第一个有节目信息的。
     *
     * 手机竖屏第一屏只放得下两三行列表，用户最想干的是「接着上次那个台看」，
     * 所以把它单独做成一张大卡片顶在最上面。
     */
    private fun renderHero() {
        val last = all.firstOrNull { it.id == app.prefs.lastChannelId }
        val hero = last
            ?: all.firstOrNull { nowTitles[it.id].orEmpty().isNotBlank() }
            ?: all.firstOrNull()
        if (hero == null) {
            heroCard.visibility = View.GONE
            return
        }
        heroCard.visibility = View.VISIBLE
        heroName.text = hero.name
        val now = nowTitles[hero.id].orEmpty()
        heroNow.text = if (now.isNotBlank()) "正在播：$now" else "点这里开始观看"
        heroPlay.text = if (last != null) "▶ 继续" else "▶ 观看"

        // 复用列表的台标缓存，别为一张图再开一套加载逻辑
        adapter.loadLogo(heroLogo, hero.logo)
        heroCard.setOnClickListener { openPlayer(hero) }
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
