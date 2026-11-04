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
import com.litv.tv.data.PlayCache
import com.litv.tv.data.PlayCacheStore
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
    /** 第一次拿到 Surface 了吗（用来区分「首次创建」和「重建」） */
    private var surfaceReadyOnce = false

    /**
     * **用户意图**：true 表示用户主动暂停了。
     * 只由播放/暂停按钮改；生命周期暂停播放时不动它，
     * 否则「切个设置页回来画面就停了」。
     */
    private var paused = false
    private var muted = false
    private var landscape = false

    /** 竖屏下视频区的 16:9 高度；全屏/退出全屏来回切时靠它恢复 */
    private var videoHeight169 = 0

    private companion object {
        const val TAG = "PhoneHome"
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

        // 视频区高度统一走 applyVideoRatio()。
        // SurfaceView 没有固有尺寸，父容器用 WRAP_CONTENT 时它会把
        // 剩余高度全吃掉——画面被拉伸、下面的节目单被挤没。
        val box = findViewById<View>(R.id.videoBox)
        box.post { applyVideoRatio() }
    }

    private fun setupPlayer() {
        // 手机端不用 tunneled 播放：它会和 Surface 强绑定，
        // 切 Tab 回来 Surface 重建后画面会卡死（全黑）。
        com.litv.tv.player.exoEnableTunneling = false

        // 播放器是 Application 级单例 + 前台服务保活：
        // Activity 重建、退到后台再回来都不重建播放器，缓冲也还在。
        com.litv.tv.player.PlaybackService.ensureRunning(this)
        player = app.player.also { it.callback = this }

        // 必须监听 Surface 生命周期，不能只在 onCreate 里 attach 一次。
        // SurfaceView 被设为 GONE（切 Tab）或 Activity 被覆盖（去设置页）时，
        // Surface 会被销毁；回来时系统会给一个**全新的** Surface，
        // 播放器还指着旧的 —— 表现就是画面全黑但台名还在。
        surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                // 首次创建：正常 attach，等上层 playChannel
                if (!surfaceReadyOnce) {
                    surfaceReadyOnce = true
                    player.attach(h.surface, findViewById(android.R.id.content))
                    return
                }
                // 重建（切 Tab / 从设置页回来 / 退后台再进）：
                // 旧 Surface 已销毁，解码器还指着它，只换 Surface 换不过来。
                // 按当前频道重新拉流最可靠（有缓存，通常 1-2 秒）。
                android.util.Log.i(TAG, "Surface 重建，重新拉流")
                recoverPlayback()
            }

            override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                player.attach(h.surface, findViewById(android.R.id.content))
            }

            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                // 不能在这里碰播放器：此刻 Surface 已被系统释放，
                // 任何 setVideoSurface 调用都会抛 "The surface has been released"。
                // 真正的恢复放在下一次 surfaceCreated 里（重新拉流）。
                android.util.Log.i(TAG, "Surface 销毁")
            }
        })

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

        applyVideoRatio()
    }

    /**
     * 按当前方向和宽度重算视频区高度。
     *
     * 横屏：铺满剩余空间（MATCH_PARENT）。
     * 竖屏：固定 16:9，**绝不**回退到 WRAP_CONTENT——
     * 那个值会让 SurfaceView 撑满剩余高度，画面被拉伸变形，
     * 而且每次控件显隐都会重新量一次，界面就会"飘"。
     */
    private fun applyVideoRatio() {
        val box = findViewById<View>(R.id.videoBox) ?: return
        val lp = box.layoutParams ?: return
        if (landscape) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            if (box.width > 0) videoHeight169 = box.width * 9 / 16
            lp.height = if (videoHeight169 > 0) videoHeight169
            else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        box.layoutParams = lp
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后宽度变了，16:9 高度要跟着重算
        findViewById<View>(R.id.videoBox)?.post { applyVideoRatio() }
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
        // 播放面板**始终保持可见**：把它设成 GONE 会让 SurfaceView 的 Surface
        // 被销毁，回来时解码器卡在旧 Surface 上（dequeueBuffer error -19）。
        // 频道面板是不透明的全屏面板，直接盖在上面即可。
        panelPlay.visibility = View.VISIBLE
        panelChannel.visibility = if (tab == TAB_CHANNEL) View.VISIBLE else View.GONE
        highlightTabs()
        // 切回播放页必须重新接画面。
        // SurfaceView 一旦被设为 GONE，它的 Surface 就被系统销毁了，
        // 回来时是一个全新的 Surface，播放器还指着旧的——表现为画面全黑、
        // 但台名和引擎标签都在。之前就是这个原因。
        if (tab == TAB_PLAY) reattachVideo()
    }

    /**
     * Surface 重建后恢复播放。
     *
     * 为什么不只是 attach：SurfaceView 销毁时解码器会卡在旧 Surface 的
     * 缓冲队列上（dequeueBuffer error -19），再 setVideoSurface 也换不过来。
     * 直接按当前频道重新拉流，是唯一稳定的恢复方式。
     */
    private fun recoverPlayback() {
        val ch = currentChannel ?: return
        if (paused) return
        android.util.Log.i(TAG, "恢复播放：${ch.name}")
        playChannel(ch)
    }

    /** 把播放器重新接到当前的 Surface 上，并按用户意图恢复播放。 */
    private fun reattachVideo() {
        if (!::surface.isInitialized) return
        surface.post {
            val h = surface.holder
            val sv = h.surface
            if (sv != null && sv.isValid) {
                player.attach(sv, findViewById(android.R.id.content))
                // resume() 才是「继续播」；play() 是「换台播放」，签名不同
                if (!paused) player.resume()
            }
        }
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

        // ---- 先用缓存地址起播，把 /tv/play 的网络往返从关键路径上摘掉 ----
        //
        // 启动耗时实测：应用启动 → 首帧 9.5 秒，其中约 2.8 秒是在等
        // /tv/play 的往返。而返回的地址本身带签名有效期（expires），
        // 只要还没过期就能直接用——网络请求挪到后台，起播不用等它。
        val nowSec = System.currentTimeMillis() / 1000
        val cached = PlayCacheStore.get(app.prefs, ch.id, nowSec)
        val cacheUsable = cached != null   // 留 60 秒余量，避免刚好卡在过期边界

        if (cacheUsable) {
            android.util.Log.i(TAG, "缓存起播 ${ch.name}，签名还剩 ${cached!!.expires - nowSec}s")
            playRoute.text = (if (cached.mode == "direct") "直连源站" else "服务端中转") +
                " · " + (if (cached.ua.isBlank()) "默认 UA" else "UA: " + cached.ua.take(30))
            player.play(ch.id, app.api.absolute(cached.url), cached.headers, app.prefs.engine)
        }

        app.api.play(
            ch.id,
            direct = app.prefs.directMode != "relay",
            onOk = { info ->
                // R09：带整份线路头，不再只带 UA
                val headers = info.playHeaders("LITV/1.0 (Android)")
                val absUrl = app.api.absolute(info.url)
                // 把链路和实际用的 UA 显示出来，方便确认源站校验的 UA 到底带没带
                val modeText = if (info.mode == "direct") "直连源站" else "服务端中转"
                val uaText = if (info.ua.isBlank()) "默认 UA" else "UA: " + info.ua.take(30)
                playRoute.text = modeText + " · " + uaText

                // 刷新缓存供下次启动/换台直接命中
                PlayCacheStore.put(app.prefs, PlayCache(
                    ch.id, absUrl, headers, info.mode, info.ua, info.expires))

                if (!cacheUsable) {
                    // 没有可用缓存：现在才起播
                    player.play(ch.id, absUrl, headers, app.prefs.engine)
                }
                app.api.heartbeat(ch.id, "playing")
            },
            onErr = { msg ->
                // 缓存已经起播时不要把错误糊上去打断用户
                if (!cacheUsable) {
                    loading.visibility = View.GONE
                    playHint.visibility = View.VISIBLE
                    playHint.text = "播放失败：$msg"
                }
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
        // 没有节目单时这里不占一行——下面的节目单区域已经会显示「暂无节目单」，
        // 两处都显示同一句话是重复的。
        if (programs.isEmpty()) {
            playNow.visibility = View.GONE
        } else {
            playNow.visibility = View.VISIBLE
            playNow.text = cur?.let { "正在播：${it.title}" } ?: "正在播：—"
        }
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
        // 从设置页/遥控器页回来：Surface 是重建的，必须重接并恢复播放
        if (::panelPlay.isInitialized && currentTab == TAB_PLAY) reattachVideo()
        if (::adapter.isInitialized && all.isNotEmpty()) load()
    }

    override fun onPause() {
        super.onPause()
        // 只暂停，不改 paused —— 那是用户的意图，回来还要按它恢复。
        //
        // 这里**不能**解绑 Surface：解绑后回来时新 Surface 还没就绪，
        // 播放器会因为拿不到渲染目标而「起播超时」，进而降级到 VLC，反而更慢。
        // Surface 生命周期交给 SurfaceHolder.Callback。
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不释放播放器：它归前台服务管，退到后台还要继续播。
        // 只断开回调和 Surface，避免持有已销毁的 Activity / Surface。
        if (::player.isInitialized) {
            player.callback = null
            player.attach(null, null)
        }
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
