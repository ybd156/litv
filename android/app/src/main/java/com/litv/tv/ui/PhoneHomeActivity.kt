package com.litv.tv.ui

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        // 播放中保持屏幕常亮：看直播时屏幕自己灭掉是很烦的事。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        // 恢复上次停留的 Tab，而不是写死回「播放」。
        // 从遥控器页/设置页返回时首页可能已被系统重建，
        // 写死 TAB_PLAY 会让用户觉得"点了设置又给我弹回播放"。
        switchTab(app.prefs.lastTab)
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

        // 返回二次确认：直接退太容易误触，参考实现都是「再按一次返回退出」。
        // 用 OnBackPressedDispatcher 注册，override onBackPressed() 在
        // androidx.activity 上不一定拦得住。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 全屏状态下返回 = 退出全屏，而不是退出 App。
                // 之前不管在什么状态，返回都是"再按一次退出"，很容易误退。
                if (landscape) {
                    toggleOrientation()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                lastBackAt = now
                android.widget.Toast.makeText(this@PhoneHomeActivity, "再按一次返回退出",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        })
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
        player.attach(surface.holder.surface, findViewById(android.R.id.content))
    }

    private fun setupTabs() {
        findViewById<TextView>(R.id.tabPlay).setOnClickListener { switchTab(TAB_PLAY) }
        findViewById<TextView>(R.id.tabChannel).setOnClickListener { switchTab(TAB_CHANNEL) }
        findViewById<TextView>(R.id.tabRemote).setOnClickListener {
            startActivity(Intent(this, RemoteActivity::class.java))
            noTransition()
        }
        findViewById<TextView>(R.id.tabSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            noTransition()
        }
    }

    private fun setupControls() {
        findViewById<TextView>(R.id.btnPrev).setOnClickListener { switchBy(-1) }
        findViewById<TextView>(R.id.btnNext).setOnClickListener { switchBy(+1) }

        btnPlayPause.setOnClickListener {
            if (currentChannel == null) { switchTab(TAB_CHANNEL); return@setOnClickListener }
            if (paused) {
                player.resume(); paused = false; btnPlayPause.text = "⏸\n暂停"
            } else {
                player.pause(); paused = true; btnPlayPause.text = "▶\n播放"
            }
        }

        btnMute.setOnClickListener {
            muted = !muted
            player.setVolume(if (muted) 0f else 1f)
            btnMute.text = if (muted) "🔇" else "🔊"
        }

        findViewById<TextView>(R.id.btnFullscreen).setOnClickListener { toggleOrientation() }

        // 投屏：发现由服务端做（iOS 拿不到多播权限，安卓也统一走服务端）
        findViewById<TextView>(R.id.btnCast).setOnClickListener { openCastPicker() }

        // 点画面：呼出/收起控件浮层（现代播放器的通用交互）。
        // 原来点一下就直接暂停，用户完全看不到有哪些控件。
        surface.setOnClickListener { toggleControls() }
    }

    /**
     * 全屏 = 切横屏并让画面铺满整屏；再点切回竖屏。
     *
     * 只切方向是不够的：竖屏布局是「播放器 + 频道列表 + 底部栏」三段，
     * 转到横屏后这三段还在，画面只占上面一小块——用户看到的就是
     * 「点了全屏但没全屏」。所以横屏时必须把频道列表和底部栏藏掉，
     * 并把播放器容器撑满。
     */
    // ---------------------------------------------------------------- 控件浮层

    private val controlsHideRunnable = Runnable { hideControls() }

    /** 呼出控件，4 秒后自动收起（播放中不遮挡画面）。 */
    private fun showControls() {
        findViewById<View>(R.id.playerControls)?.visibility = View.VISIBLE
        Handler(Looper.getMainLooper()).removeCallbacks(controlsHideRunnable)
        Handler(Looper.getMainLooper()).postDelayed(controlsHideRunnable, 4000)
    }

    private fun hideControls() {
        findViewById<View>(R.id.playerControls)?.visibility = View.GONE
    }

    private fun toggleControls() {
        val v = findViewById<View>(R.id.playerControls) ?: return
        if (v.visibility == View.VISIBLE) hideControls() else showControls()
    }

    /** 隐藏/显示状态栏与导航栏。 */
    private fun setSystemBarsHidden(hidden: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * 投屏选择器：列出局域网里能投的设备，点一个就让电视播当前频道。
     *
     * 发现放在服务端：手机端不需要多播权限，安卓和 iOS 也能共用同一套逻辑。
     */
    private fun openCastPicker() {
        val ch = currentChannel
        if (ch == null) {
            android.widget.Toast.makeText(this, "先选一个频道", android.widget.Toast.LENGTH_SHORT).show()
            switchTab(TAB_CHANNEL)
            return
        }
        android.widget.Toast.makeText(this, "正在搜索可投屏设备…", android.widget.Toast.LENGTH_SHORT).show()
        app.api.dlnaRenderers(
            onOk = { list ->
                runOnUiThread {
                    if (list.isEmpty()) {
                        android.widget.Toast.makeText(this, "局域网里没找到可投屏设备",
                            android.widget.Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val labels = list.map { r ->
                        if (r.model.isBlank()) r.name else r.name + "  ·  " + r.model
                    }.toTypedArray()
                    android.app.AlertDialog.Builder(this)
                        .setTitle("投屏到")
                        .setItems(labels) { _, i ->
                            val r = list[i]
                            app.api.dlnaCast(ch.id, r.controlUrl, r.name,
                                onOk = {
                                    runOnUiThread {
                                        android.widget.Toast.makeText(this, "已投屏到 " + r.name,
                                            android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onErr = { msg ->
                                    runOnUiThread {
                                        android.widget.Toast.makeText(this, "投屏失败：" + msg,
                                            android.widget.Toast.LENGTH_LONG).show()
                                    }
                                })
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            },
            onErr = { msg ->
                runOnUiThread {
                    android.widget.Toast.makeText(this, "搜索失败：" + msg,
                        android.widget.Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun toggleOrientation() {
        landscape = !landscape
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        applyFullscreen(landscape)
    }

    /** 按是否全屏调整可见性与播放器高度。 */
    private fun applyFullscreen(on: Boolean) {
        findViewById<View>(R.id.bottomTabs)?.visibility = if (on) View.GONE else View.VISIBLE
        // 全屏时连频道名标题也藏掉——用户要的是"只剩画面"
        findViewById<View>(R.id.playHeader)?.visibility = if (on) View.GONE else View.VISIBLE
        // 沉浸式：隐藏状态栏与导航栏，下滑可临时唤出
        setSystemBarsHidden(on)
        findViewById<View>(R.id.panelChannel)?.visibility =
            if (on) View.GONE else if (currentTab == TAB_CHANNEL) View.VISIBLE else View.GONE

        val box = findViewById<View>(R.id.videoBox) ?: return
        val lp = box.layoutParams ?: return
        // 横屏铺满：高度改成 MATCH_PARENT；竖屏恢复原来的自适应（16:9）
        lp.height = if (on) ViewGroup.LayoutParams.MATCH_PARENT
        else ViewGroup.LayoutParams.WRAP_CONTENT
        box.layoutParams = lp
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
        app.prefs.lastTab = tab
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
        btnPlayPause.text = "⏸\n暂停"

        app.api.play(
            ch.id,
            direct = app.prefs.directMode != "relay",
            onOk = { info ->
                // R09：带整份线路头，不再只带 UA
                val headers = info.playHeaders("LITV/1.0 (Android)")
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
        // 出画面时闪一下控件，让用户知道点画面能呼出操作条
        showControls()
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
        // 从遥控器/设置返回时重新应用一次当前 Tab，
        // 避免两个面板的可见性因为重建而错乱
        // 以 prefs 为准：别的页面（遥控器/设置）可能改过目标 Tab
        if (::panelPlay.isInitialized) switchTab(app.prefs.lastTab)
        if (::adapter.isInitialized && all.isNotEmpty()) load()
    }

    override fun onPause() {
        super.onPause()
        player.pause()
        paused = true
        if (::btnPlayPause.isInitialized) btnPlayPause.text = "▶\n播放"
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

    // ---------------------------------------------------------------- 返回

    private var lastBackAt = 0L

}
