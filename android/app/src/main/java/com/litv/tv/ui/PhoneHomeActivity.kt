package com.litv.tv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import com.litv.tv.data.EpgProgram
import com.litv.tv.player.EngineKind
import com.litv.tv.player.PlayerManager

/**
 * 手机端主界面 —— 单页四 Tab，不开新页面。
 *
 *   播放   内嵌播放器 + 控制条 + 节目单
 *   频道   搜索 + 分组 + 列表（点一下就地播放，切到播放页）
 *   遥控器  跳 RemoteActivity（带同样的底部栏，像切页）
 *   设置   跳 SettingsActivity（同上）
 *
 * 之前点频道是 startActivity 到 PhonePlayerActivity，用户看到的是"又开了一个页面"。
 * 现在直接在播放页里播，切台也是原地换。
 */
class PhoneHomeActivity : AppCompatActivity(), PlayerManager.Callback {

    private lateinit var app: LitvApp

    // 播放页
    private lateinit var panelPlay: View
    private lateinit var panelChannel: View
    private lateinit var surface: SurfaceView
    private lateinit var loading: ProgressBar
    private lateinit var playName: TextView
    private lateinit var playEngine: TextView
    private lateinit var playNow: TextView
    private lateinit var playRoute: TextView
    private lateinit var playHint: TextView
    private lateinit var epgList: LinearLayout
    private lateinit var gestureTip: TextView
    private lateinit var btnPlayPause: TextView
    private lateinit var btnMute: TextView

    // 频道页
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var groupChips: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var adapter: PhoneChannelAdapter

    private lateinit var player: PlayerManager
    private val main = Handler(Looper.getMainLooper())

    private var all: List<Channel> = emptyList()
    private val favorites = mutableSetOf<Long>()
    private val nowTitles = mutableMapOf<Long, String>()
    private var groups: List<String> = emptyList()
    private var selectedGroup: String? = null
    private var onlyFav = false
    private var currentTab = TAB_PLAY

    private var currentChannel: Channel? = null
    private var programs: List<EpgProgram> = emptyList()
    private var paused = false
    private var muted = false
    private var landscape = false

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

        bindViews()
        setupPlayer()
        setupTabs()
        setupControls()
        setupList()
        setupSearch()

        switchTab(TAB_PLAY)
        load()
    }

    private fun bindViews() {
        panelPlay = findViewById(R.id.panelPlay)
        panelChannel = findViewById(R.id.panelChannel)
        surface = findViewById(R.id.surface)
        loading = findViewById(R.id.loading)
        playName = findViewById(R.id.playName)
        playEngine = findViewById(R.id.playEngine)
        playNow = findViewById(R.id.playNow)
        playRoute = findViewById(R.id.playRoute)
        playHint = findViewById(R.id.playHint)
        epgList = findViewById(R.id.epgList)
        gestureTip = findViewById(R.id.gestureTip)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnMute = findViewById(R.id.btnMute)

        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        groupChips = findViewById(R.id.groupChips)
        searchInput = findViewById(R.id.searchInput)

        // 视频按 16:9 固定比例，竖屏铺满全高会把画面压成中间一条
        val box = findViewById<View>(R.id.videoBox)
        box.post {
            if (box.width > 0) {
                box.layoutParams = box.layoutParams.apply { height = box.width * 9 / 16 }
            }
        }
    }

    private fun setupPlayer() {
        player = PlayerManager(this, app.prefs).also { it.callback = this }
        player.attach(surface, findViewById(android.R.id.content))
    }

    private fun setupTabs() {
        findViewById<TextView>(R.id.tabPlay).setOnClickListener { switchTab(TAB_PLAY) }
        findViewById<TextView>(R.id.tabChannel).setOnClickListener { switchTab(TAB_CHANNEL) }
        findViewById<TextView>(R.id.tabRemote).setOnClickListener {
            startActivity(Intent(this, RemoteActivity::class.java))
            overridePendingTransition(0, 0)
        }
        findViewById<TextView>(R.id.tabSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(0, 0)
        }
    }

    private fun setupControls() {
        findViewById<TextView>(R.id.btnPrev).setOnClickListener { switchBy(-1) }
        findViewById<TextView>(R.id.btnNext).setOnClickListener { switchBy(+1) }

        btnPlayPause.setOnClickListener {
            if (currentChannel == null) { switchTab(TAB_CHANNEL); return@setOnClickListener }
            if (paused) {
                player.resume(); paused = false; btnPlayPause.text = "⏸"
            } else {
                player.pause(); paused = true; btnPlayPause.text = "▶"
            }
        }

        btnMute.setOnClickListener {
            muted = !muted
            player.setVolume(if (muted) 0f else 1f)
            btnMute.text = if (muted) "🔇" else "🔊"
        }

        findViewById<TextView>(R.id.btnFullscreen).setOnClickListener { toggleOrientation() }

        // 点画面：暂停/继续（和主流播放器一致）
        surface.setOnClickListener { btnPlayPause.performClick() }
    }

    /** 全屏 = 切横屏；再点切回竖屏。 */
    private fun toggleOrientation() {
        landscape = !landscape
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun setupList() {
        adapter = PhoneChannelAdapter(
            onClick = { ch ->
                // 就地播放并切到播放页——不新开 Activity
                switchTab(TAB_PLAY)
                playChannel(ch)
            },
            onStarClick = { ch -> toggleFavorite(ch) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (::adapter.isInitialized) render()
            }
        })
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        panelPlay.visibility = if (tab == TAB_PLAY) View.VISIBLE else View.GONE
        panelChannel.visibility = if (tab == TAB_CHANNEL) View.VISIBLE else View.GONE
        highlightTabs()
    }

    private fun highlightTabs() {
        val on = getColor(R.color.accent)
        val off = getColor(R.color.text_secondary)
        findViewById<TextView>(R.id.tabPlay).setTextColor(if (currentTab == TAB_PLAY) on else off)
        findViewById<TextView>(R.id.tabChannel).setTextColor(if (currentTab == TAB_CHANNEL) on else off)
    }

    // ---------------------------------------------------------------- 数据

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
        status.text = if (fromCache) "缓存 ${all.size} 个频道" else "${all.size} 个频道"
        renderChips()
        render()

        // 首次进来：自动播上次看的台，或第一个
        if (currentChannel == null && all.isNotEmpty()) {
            val last = all.firstOrNull { it.id == app.prefs.lastChannelId } ?: all.first()
            playChannel(last)
        }
    }

    private fun renderChips() {
        groupChips.removeAllViews()
        addChip("全部") { selectedGroup = null; onlyFav = false; render() }
        if (favorites.isNotEmpty()) addChip("★ 收藏") { onlyFav = true; render() }
        groups.forEach { g -> addChip(g) { selectedGroup = g; onlyFav = false; render() } }
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

    private fun render() {
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

    private fun toggleFavorite(ch: Channel) {
        val on = !favorites.contains(ch.id)
        app.api.favorite(ch.id, on, onOk = {
            if (on) favorites.add(ch.id) else favorites.remove(ch.id)
            renderChips()
            render()
        }, onErr = { status.text = "收藏失败：$it" })
    }

    // ---------------------------------------------------------------- 播放

    private fun playChannel(ch: Channel) {
        currentChannel = ch
        app.prefs.lastChannelId = ch.id
        playName.text = ch.name
        playHint.visibility = View.GONE
        loading.visibility = View.VISIBLE
        paused = false
        btnPlayPause.text = "⏸"

        app.api.play(
            ch.id,
            direct = app.prefs.directMode != "relay",
            onOk = { info ->
                // 源配置里带了 UA 就用它，否则用默认
                val headers = mapOf("User-Agent" to info.ua.ifBlank { "LITV/1.0 (Android)" })
                // 把链路和实际用的 UA 显示出来，方便确认源站校验的 UA 到底带没带
                val modeText = if (info.mode == "direct") "直连源站" else "服务端中转"
                val uaText = if (info.ua.isBlank()) "默认 UA" else "UA: " + info.ua.take(30)
                playRoute.text = modeText + " · " + uaText
                player.play(ch.id, app.api.absolute(info.url), headers, app.prefs.engine)
                app.api.heartbeat(ch.id, "playing")
            },
            onErr = { msg ->
                loading.visibility = View.GONE
                playHint.visibility = View.VISIBLE
                playHint.text = "播放失败：$msg"
            })

        app.api.epg(ch.id, 0, onOk = { p ->
            programs = p
            renderNow()
        }, onErr = {})
    }

    private fun switchBy(delta: Int) {
        if (all.isEmpty()) return
        val cur = currentChannel ?: return
        val i = all.indexOfFirst { it.id == cur.id }
        if (i < 0) return
        val n = ((i + delta) % all.size + all.size) % all.size
        playChannel(all[n])
    }

    private fun renderNow() {
        val now = System.currentTimeMillis() / 1000
        val cur = programs.firstOrNull { it.startAt <= now && it.endAt > now }
        playNow.text = cur?.let { "正在播：${it.title}" } ?: "暂无节目单"
        renderEpg()
    }

    /** 节目单：当前节目高亮。 */
    private fun renderEpg() {
        epgList.removeAllViews()
        if (programs.isEmpty()) {
            val tv = TextView(this)
            tv.text = getString(R.string.no_epg)
            tv.setTextColor(getColor(R.color.text_secondary))
            tv.textSize = 13f
            tv.setPadding(0, dp(8), 0, dp(8))
            epgList.addView(tv)
            return
        }
        val now = System.currentTimeMillis() / 1000
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        programs.forEach { p ->
            val row = TextView(this)
            val playing = p.startAt <= now && p.endAt > now
            row.text = fmt.format(java.util.Date(p.startAt * 1000)) + "  " + p.title
            row.textSize = 13f
            row.setPadding(dp(10), dp(9), dp(10), dp(9))
            row.setTextColor(getColor(if (playing) R.color.accent else R.color.text_secondary))
            if (playing) row.setTypeface(null, android.graphics.Typeface.BOLD)
            epgList.addView(row)
        }
    }

    // ---------------------------------------------------------------- PlayerManager.Callback

    override fun onEngineChanged(kind: EngineKind) {
        playEngine.text = kind.label
    }

    override fun onPrepared(width: Int, height: Int) = Unit

    override fun onPlaying() {
        loading.visibility = View.GONE
        playHint.visibility = View.GONE
    }

    override fun onError(message: String) {
        loading.visibility = View.GONE
        playHint.visibility = View.VISIBLE
        playHint.text = message
    }

    override fun onVideoSizeChanged(width: Int, height: Int) = Unit

    // ---------------------------------------------------------------- 生命周期

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && all.isNotEmpty()) load()
    }

    override fun onPause() {
        super.onPause()
        player.pause()
        paused = true
        if (::btnPlayPause.isInitialized) btnPlayPause.text = "▶"
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 避开状态栏和底部手势条。 */
    private fun applySystemInsets() {
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }
}
