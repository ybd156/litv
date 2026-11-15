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
import com.litv.tv.data.LastChannelResolver
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
    private lateinit var btnPlayPause: android.widget.ImageView
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

    /** 上一次预热过相邻频道的台 id（onPlaying 会重复回调，用它去重） */
    private var lastPrefetchChannel: Long = 0

    /** 竖屏下视频区的 16:9 高度；全屏/退出全屏来回切时靠它恢复 */
    private var videoHeight169 = 0

    private companion object {
        const val TAG = "PhoneHome"
        const val TAB_PLAY = "play"
        const val TAB_CHANNEL = "channel"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 播放中保持屏幕常亮：看直播时屏幕自己灭掉是很烦的事。
        // 主页不需要常亮（只有播放页需要）
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_home)
        applySystemInsets()
        app = application as LitvApp
        app.api.onAuthExpired = { onAuthExpired() }

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
                // 重建（切 Tab / 从设置页回来 / 退后台再进）
                //
                // 新 Surface 会让解码器重新初始化，失败一次就会把"强制软解"标记置上，
                // 而软解在这类机器上同样起不来 —— 表现就是"切几次就播不了"。
                // 所以换 Surface 时清掉该标记，让重试仍从硬解开始。
                player.resetSoftDecodeFlag()
                val ch = currentChannel
                // 判据是"**播放器还持有这个台**"，不是"此刻正在播"。
                // 切走的瞬间经常正处在缓冲/重连状态，isPlaying 为 false，
                // 用它会误判成"播放器已停"→ 重新拉流，用户看到的就是"回来要重播"。
                val sameChannel = ch != null && player.playingChannelId == ch.id

                if (sameChannel) {
                    // 播放器一直活着（Service 保活），缓冲也在：
                    // 只把新 Surface 接上去并确保继续播，不重新拉流。
                    android.util.Log.i(TAG, "Surface 重建，播放器仍持有该台，只接 Surface")
                    player.attach(h.surface, findViewById(android.R.id.content))
                    runCatching { player.resume() }
                    return
                }

                // 播放器已经不在了（进程被杀/换了台）：重新拉流
                android.util.Log.i(TAG, "Surface 重建，播放器已停，重新拉流")
                recoverPlayback()
            }

            override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                player.attach(h.surface, findViewById(android.R.id.content))
            }

            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                // ★ 必须解绑。以前这里是空实现：Surface 被销毁了，解码器还挂在
                // 那张死 Surface 上；回来接一张新的时候，硬解直接
                // `ERROR_CODE_DECODING_FAILED: Decoder failed: c2.qti.avc.decoder`，
                // 然后降级软解、软解也起不来 —— 表现就是"切几次就播不了"。
                // 解绑后 ExoPlayer 会在新 Surface 到来时重新配置解码器。
                android.util.Log.i(TAG, "Surface 销毁，解绑播放器 Surface")
                runCatching { player.attach(null, null) }
            }
        })

        player.attach(surface.holder.surface, findViewById(android.R.id.content))
    }

    private fun setupTabs() {
        findViewById<TextView>(R.id.tabPlay).setOnClickListener { switchTab(TAB_PLAY) }
        findViewById<TextView>(R.id.tabChannel).setOnClickListener { switchTab(TAB_CHANNEL) }
        findViewById<TextView>(R.id.tabRemote).setOnClickListener {
            // 和设置页同理：遥控器是应用内页面，不该把播放停掉。
            // 停了就要重新拉流，用户看到的就是"点遥控器回来要重播"。
            suppressPauseForInternalPage = true
            startActivity(Intent(this, RemoteActivity::class.java))
            noTransition()
        }
        findViewById<TextView>(R.id.tabSettings).setOnClickListener {
            // 打开设置时不要停播：设置是覆盖在本页之上的同一应用页面，
            // 播到一半点设置就断流，用户会以为"点了设置就卡了"。
            suppressPauseForInternalPage = true
            startActivity(Intent(this, SettingsActivity::class.java))
            noTransition()
        }
    }

    private fun setupControls() {

        btnPlayPause.setOnClickListener {
            if (currentChannel == null) { switchTab(TAB_CHANNEL); return@setOnClickListener }
            if (paused) {
                player.resume(); paused = false; btnPlayPause.setImageResource(R.drawable.ic_pause)
            } else {
                player.pause(); paused = true; btnPlayPause.setImageResource(R.drawable.ic_play)
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
        // 全屏时画面按 16:9 居中，两侧/上下会有黑边。黑边属于 panelPlay 而不是
        // videoBox —— 只给 surface 挂点击的话，用户点黑边没有任何反应，
        // 就像"全屏里控件点不了/唤不出来"。全屏时整块 panelPlay 都能唤出控件。
        findViewById<View>(R.id.panelPlay)?.setOnClickListener {
            if (landscape) toggleControls()
        }
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

        findViewById<View>(R.id.fsTools)?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) syncFsTools()

        applyVideoRatio()
    }

    /** 把三个全屏工具按钮的文字同步成当前设置。 */
    private fun syncFsTools() {
        findViewById<TextView>(R.id.fsAspect)?.text = "比例 " + aspectLabel(app.prefs.aspect)
        findViewById<TextView>(R.id.fsEngine)?.text = "内核 " + engineLabelShort(app.prefs.engine)
        findViewById<TextView>(R.id.fsDecode)?.text = "解码 " + decodeLabelShort(app.prefs.decode)
    }

    private fun aspectLabel(v: String) = when (v) {
        "16:9" -> "16:9"; "4:3" -> "4:3"; "fill" -> "铺满"; else -> "自动"
    }

    private fun engineLabelShort(v: String) = when (v) {
        "exo" -> "Exo"; "vlc" -> "VLC"; "mpv" -> "mpv"; "avs3" -> "AV3S"; else -> "自动"
    }

    private fun decodeLabelShort(v: String) = when (v) {
        "hard" -> "硬解"; "soft" -> "软解"; else -> "自动"
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
        val parent = box.parent as? android.view.ViewGroup

        // 可用空间：父容器的宽高（横屏全屏时就是整屏）
        val availW = if (parent != null && parent.width > 0) parent.width
                     else resources.displayMetrics.widthPixels
        val availH = if (parent != null && parent.height > 0) parent.height
                     else resources.displayMetrics.heightPixels

        // 按 16:9 **同时受宽高约束**：
        // 只按宽度算的话，横屏 2712×9/16=1526 > 屏高 1220，画面会比屏幕还高，
        // 控件被顶到可视区外 —— 就是"全屏控件不见了、画面放大到看不见"。
        var w = availW
        var h = (availW * 9 / 16)
        if (h > availH) {
            h = availH
            w = (availH * 16 / 9)
        }

        lp.width = w
        lp.height = h
        box.layoutParams = lp

        (parent as? LinearLayout)?.let {
            it.gravity = when {
                landscape -> android.view.Gravity.CENTER
                else -> android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            }
        }
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
                android.util.Log.i(
                    TAG,
                    "bootstrap: clientParse=${b.clientParse} channels=${b.channels.size} " +
                        "version=${b.channelsVersion} epg=${b.epgUrl.isNotEmpty()}",
                )
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

    /** 用本地节目单缓存重算「正在播」，服务端 now 为空时完全靠它。 */
    private fun refreshFromEpg() {
        dayInit = false
        if (!app.epg.ready) return
        val nowSec = System.currentTimeMillis() / 1000
        val fresh = HashMap<Long, String>()
        for (ch in all) {
            val epgId = ch.epgId ?: continue
            val list = app.epg.programsFor(epgId) ?: continue
            val cur = list.firstOrNull { nowSec >= it.start && nowSec < it.end } ?: continue
            fresh[ch.id] = cur.title
        }
        if (fresh.isEmpty()) return
        nowTitles.clear()
        nowTitles.putAll(fresh)
        render()
    }

    private fun apply(b: Bootstrap, fromCache: Boolean) {
        // 网络回来的才可信；缓存里可能没有 client_parse 字段
        if (!fromCache) app.prefs.clientParseSeen = b.clientParse
        all = b.channels
        favorites.clear(); favorites.addAll(b.favorites)
        nowTitles.clear(); nowTitles.putAll(b.now.associate { it.channelId to it.title })
        // ★ 手机界面以前**从来没有调用过 app.epg.ensure()**，节目单根本没下载，
        //   而服务端 bootstrap 的 now 又是空的（重建后就没数据了），
        //   所以手机端一行节目名都没有。
        //   现在自己下载 xmltv 并按本地节目单算「正在播」，服务端 now 只作兜底。
        app.epg.ensure(b.epgUrl) { main.post { refreshFromEpg() } }
        if (b.clientParse || app.prefs.clientParseSeen || app.prefs.channelSource == "client") {
            com.litv.tv.data.M3uClient.load(this, app.api, app.prefs.dlKey) { parsed ->
                if (parsed != null && parsed.channels.isNotEmpty()) {
                    all = parsed.channels
                    groups = all.map { it.group }.filter { it.isNotBlank() }.distinct()
                    status.text = "客户端解析 ${all.size} 个频道"
                    renderChips()
                    render()
                }
            }
        }
        groups = all.map { it.group }.filter { it.isNotBlank() }.distinct()
        status.text = if (fromCache) "缓存 ${all.size} 个频道" else "${all.size} 个频道"
        renderChips()
        render()

        // 首次进来：自动播上次看的台，或第一个。
        // C2：与 TV 端共用 LastChannelResolver（id → tvg_id → name 三级兜底），
        // 上游刷新重建频道后 id 失效也能找回同一个台，不静默跳回第一个。
        if (currentChannel == null && all.isNotEmpty()) {
            val hit = LastChannelResolver.resolve(
                all, app.prefs.lastChannelId, app.prefs.lastChannelTvgId, app.prefs.lastChannelName,
            )
            val last = hit?.channel
                ?: LastChannelResolver.pickFirst(all)
                ?: all.first()
            android.util.Log.i(TAG, "恢复上次频道：${last.name}（依据=${hit?.via ?: "fallback"}）")
            // 关键：如果服务里的播放器**已经在播这个台**（退后台再回来、
            // 或者 Activity 被重建），只接上就行，不要重新拉流——
            // 否则 Service 保活省下的时间会全部赔回去。
            if (player.playingChannelId == last.id && player.isPlaying) {
                android.util.Log.i(TAG, "播放器已在播 ${last.name}，直接接上，不重新拉流")
                currentChannel = last
                playName.text = last.name
                loading.visibility = View.GONE
                paused = false
        btnPlayPause.setImageResource(R.drawable.ic_pause)
            } else {
                playChannel(last)
            }
        }
    }

    private fun renderChips() {
        groupChips.removeAllViews()
        addChip("全部") { selectedGroup = null; onlyFav = false; render() }
        // C1：收藏 chip 常显。以前是 if (favorites.isNotEmpty()) —— 新装用户
        // 连"收藏"这个入口都看不到，自然觉得"没有收藏"。
        addChip("★ 收藏") { onlyFav = true; render() }
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
        playingTimeshift = false
        renderLiveBadge()
        // 「同一台又播了一次」= 几乎一定是卡住/失败后的重播。
        // 这种情况必须重建播放器：否则缓存命中会跳过 prepare，重播等于
        // 什么都没做，画面一直卡着（和电视端是同一类 bug）。
        //
        // 换到**别的**台时仍走缓存起播——那是刻意的优化，实测能把「启动到首帧
        // 9.5 秒」里的约 2.8 秒（/tv/play 往返）省掉。
        val replayingSameChannel = currentChannel != null && currentChannel?.id == ch.id
        currentChannel = ch
        app.prefs.lastChannelId = ch.id
        // C2：id 会随上游重建失效，tvg_id 与名字一起存下来当兜底锚点
        app.prefs.lastChannelTvgId = ch.tvgId
        app.prefs.lastChannelName = ch.name
        playName.text = ch.name
        playHint.visibility = View.GONE
        loading.visibility = View.VISIBLE
        paused = false
        btnPlayPause.setImageResource(R.drawable.ic_pause)

        // ---- 客户端解析模式：地址就在本地，直接播，不请求 /tv/play ----
        val directUrl = com.litv.tv.data.M3uClient.urlOf(ch.id)
        if (directUrl != null) {
            playRoute.text = "直连源站 · 客户端解析"
            player.play(ch.id, directUrl, com.litv.tv.data.M3uClient.headersOf(ch.id), app.prefs.engine)
            return
        }

        // ---- 先用缓存地址起播，把 /tv/play 的网络往返从关键路径上摘掉 ----
        //
        // 启动耗时实测：应用启动 → 首帧 9.5 秒，其中约 2.8 秒是在等
        // /tv/play 的往返。而返回的地址本身带签名有效期（expires），
        // 只要还没过期就能直接用——网络请求挪到后台，起播不用等它。
        val nowSec = System.currentTimeMillis() / 1000
        val cached = PlayCacheStore.get(
            app.prefs, ch.id,
            PlayCacheStore.identity(app.prefs, 0, phoneCacheMode()),
            nowSec,
        )
        val cacheUsable = cached != null && !replayingSameChannel   // 留 60 秒余量，避免刚好卡在过期边界

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
                    identity = PlayCacheStore.identity(app.prefs, 0, phoneCacheMode()),
                    channelId = ch.id,
                    url = absUrl,
                    headers = headers,
                    mode = info.mode,
                    ua = info.ua,
                    expires = info.expires,
                ))

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

        // ★ 本地节目单优先。服务端的 epg_programs 在 edge 重建后是空的，
        //   只靠 app.api.epg 就永远显示「暂无节目单」。
        val localEpg = ch.epgId?.takeIf { it.isNotBlank() }?.let { app.epg.programsFor(it) }
        if (!localEpg.isNullOrEmpty()) {
            programs = localEpg.map {
                com.litv.tv.data.EpgProgram(
                    startAt = it.start,
                    endAt = it.end,
                    title = it.title,
                    description = it.description,
                    category = it.category,
                )
            }
            renderNow()
        } else {
            app.api.epg(ch.id, 0, onOk = { p ->
                programs = p
                renderNow()
            }, onErr = {})
        }
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

    /** 节目单当前查看的日期偏移（0=今天，1=明天，-1=昨天…）。 */
    private var epgDayOffset = 0
    /** 是否已按「今天」初始化过日期选择（只做一次，之后尊重用户点击）。 */
    private var dayInit = false

    /** 当前是否在回放（时移回看），false = 直播。 */
    private var playingTimeshift = false

    /** 刷新左上角「直播 / 回放」胶囊。 */
    private fun renderLiveBadge() {
        findViewById<TextView>(R.id.liveBadge)?.apply {
            text = if (playingTimeshift) "◀ 回放" else "● 直播"
            setTextColor(if (playingTimeshift) getColor(R.color.text_primary) else 0xFFFF4D4F.toInt())
        }
    }

    /** 已预约的节目起点：来自本地存储（ReminderStore），重启后仍在。 */
    private val reservedStarts = HashSet<Long>()

    /** 节目单：按日期分组 + 正在播/预约/回看。 */
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

        loadReminders()
        val now = System.currentTimeMillis() / 1000
        // 日期分组/标签/动作判定都走 EpgPrograms（电视端用同一份）
        val byDay = com.litv.tv.data.EpgPrograms.groupByDay(programs)
        val days = byDay.keys.toList()
        // 默认停在「今天」。节目单里常常前几天也有数据（回看用），
        // 排在前面；如果直接取 days[0]，看到的就是几天前 —— 用户会以为没更新。
        if (!dayInit) {
            val todayKey = com.litv.tv.data.EpgPrograms.dayKeyOf(now)
            val i = days.indexOf(todayKey)
            epgDayOffset = if (i >= 0) i else (days.size - 1).coerceAtLeast(0)
            dayInit = true
        }
        val selectedKey = days.getOrNull(epgDayOffset.coerceIn(0, (days.size - 1).coerceAtLeast(0)))
            ?: days.firstOrNull() ?: return

        // ---- 日期条 ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // 显式给尺寸：不给的话在某些设备上会被父容器撑开一大块空白
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(0, 0, 0, dp(4))
        }
        days.forEachIndexed { i, key ->
            val label = com.litv.tv.data.EpgPrograms.dayLabel(key)
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setTextColor(getColor(if (i == days.indexOf(selectedKey)) R.color.accent else R.color.text_secondary))
                isClickable = true
                setOnClickListener {
                    epgDayOffset = i
                    renderEpg()
                }
            }
            bar.addView(chip)
        }
        // 日期条要**固定在节目单上方**，不能塞进滚动内容里 ——
        // 塞进去的话一滚到「正在播」那一档，日期条自己也滚没了。
        val dateBarScrollView = ensureDateBar()
        val barHolder = dateBarScrollView.getChildAt(0) as LinearLayout
        barHolder.removeAllViews()
        while (bar.childCount > 0) {
            val v = bar.getChildAt(0)
            bar.removeViewAt(0)
            barHolder.addView(v)
        }
        // 把选中的那天（默认今天）滚到可见位置：节目单常带前几天，
        // 今天往往在右边屏外，不滚过去用户会以为没更新。
        val selIndex = days.indexOf(selectedKey)
        if (selIndex > 0) {
            dateBarScrollView.post {
                val chip = barHolder.getChildAt(selIndex) ?: return@post
                dateBarScrollView.scrollTo((chip.left - dp(24)).coerceAtLeast(0), 0)
            }
        }

        // ---- 节目行 ----
        var playingRow: View? = null
        byDay[selectedKey]?.forEach { p ->
            val action = com.litv.tv.data.EpgPrograms.actionOf(p, now)
            val playing = action == com.litv.tv.data.EpgPrograms.Action.PLAYING
            val finished = action == com.litv.tv.data.EpgPrograms.Action.REPLAY
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
            }
            val title = TextView(this).apply {
                text = com.litv.tv.data.EpgPrograms.timeLabel(p.startAt) + "  " + p.title
                textSize = 13f
                setTextColor(getColor(if (playing) R.color.accent else R.color.text_secondary))
                if (playing) setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(title)

            val actionView = TextView(this).apply {
                textSize = 12f
                setPadding(dp(10), dp(4), dp(10), dp(4))
                when {
                    playing -> { text = "正在播"; setTextColor(getColor(R.color.accent)) }
                    finished -> {
                        text = "回看"
                        setTextColor(getColor(R.color.accent))
                        isClickable = true
                        setOnClickListener {
                            playingTimeshift = true
                            renderLiveBadge()
                            EpgActions.replay(this@PhoneHomeActivity, app.api, player,
                                currentChannel ?: return@setOnClickListener, app.prefs.engine, p)
                        }
                    }
                    reservedStarts.contains(p.startAt) -> {
                        text = "已预约"
                        setTextColor(getColor(R.color.text_primary))
                    }
                    else -> {
                        text = "预约"
                        setTextColor(getColor(R.color.text_secondary))
                    }
                }
            }
            row.addView(actionView)
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            // 整行可点：动作按钮只有几个字，手指容易点偏；点整行等价于点动作。
            if (!playing) {
                row.isClickable = true
                row.setOnClickListener {
                    val ch = currentChannel ?: return@setOnClickListener
                    android.util.Log.i(TAG, "节目单点击：${p.title} finished=$finished")
                    if (finished) {
                        playingTimeshift = true
                        renderLiveBadge()
                        EpgActions.replay(this@PhoneHomeActivity, app.api, player, ch, app.prefs.engine, p)
                    } else {
                        // 本地记录即可：预约是"到点提醒我"，不需要服务端参与
                        com.litv.tv.data.ReminderStore.add(this@PhoneHomeActivity, p.startAt, ch.id, p.title)
                        reservedStarts.add(p.startAt)
                        android.widget.Toast.makeText(
                            this@PhoneHomeActivity, "已预约：" + p.title, android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        renderEpg()
                    }
                }
            }
            if (playing) playingRow = row
            epgList.addView(row)
        }

        // 滚到「正在播」那一档：节目单是一整天的列表，
        // 不滚的话默认停在 01:02，用户要往下翻很久才看到现在播的。
        // 滚到「正在播」那一档：等这一行真正布局完再算位置，
        // 用 main.post 会早于布局，row.top 还是 0，等于没滚。
        playingRow?.let { row ->
            row.post {
                val sc = findViewById<android.widget.ScrollView>(R.id.epgScroll)
                if (sc != null) {
                    val top = (row.top - dp(16)).coerceAtLeast(0)
                    sc.smoothScrollTo(0, top)
                }
            }
        }
    }

    private var dateBarScroll: android.widget.HorizontalScrollView? = null

    /**
     * 取得（必要时创建）固定在节目单上方的日期条容器。
     * 它插在 ScrollView 的**父容器**里、和 ScrollView 平级，
     * 所以滚动节目列表时日期条不会跟着消失。
     */
    private fun ensureDateBar(): android.widget.HorizontalScrollView {
        dateBarScroll?.let { return it }
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(dp(16), 0, dp(16), dp(4))
            addView(
                LinearLayout(this@PhoneHomeActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                },
            )
        }
        val epgScroll = findViewById<android.view.View>(R.id.epgScroll)
        val parent = epgScroll?.parent as? LinearLayout
        if (parent != null) {
            val idx = parent.indexOfChild(epgScroll)
            parent.addView(scroll, if (idx >= 0) idx else 0)
        }
        dateBarScroll = scroll
        return scroll
    }

    // ---------------------------------------------------------------- PlayerManager.Callback

    override fun onEngineChanged(kind: EngineKind) {
        playEngine.text = kind.label
    }

    override fun onPrepared(width: Int, height: Int) = Unit

    private fun loadReminders() {
        reservedStarts.clear()
        reservedStarts.addAll(com.litv.tv.data.ReminderStore.reservedStarts(this))
    }

    override fun onPlaying() {
        // 播放页在这个 Activity 里（底部「播放」标签），不是 PhonePlayerActivity，
        // 所以常亮必须在这里加，否则看着看着就黑屏。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loading.visibility = View.GONE
        playHint.visibility = View.GONE
        // 出画面时闪一下控件，让用户知道点画面能呼出操作条
        showControls()
        prefetchNeighbors()
    }

    /**
     * 预热上下相邻频道的播放地址。
     *
     * 上下键换台是最高频的操作，而每个频道都要先请求 /tv/play 才能拿到
     * 带签名的地址。提前把相邻两个频道的地址取好放进缓存，
     * 换台时就命中缓存、不用等这一趟网络。
     *
     * 只预热地址，不预开流——预开流要额外占一个解码器，低端盒子上内存吃不消。
     */
    private fun prefetchNeighbors() {
        val cur = currentChannel ?: return
        if (lastPrefetchChannel == cur.id) return   // onPlaying 会重复回调，去重
        lastPrefetchChannel = cur.id

        val i = all.indexOfFirst { it.id == cur.id }
        if (i < 0) return
        val now = System.currentTimeMillis() / 1000
        val want = listOfNotNull(all.getOrNull(i + 1), all.getOrNull(i - 1))
            .filter { PlayCacheStore.get(app.prefs, it.id, PlayCacheStore.identity(app.prefs, 0, phoneCacheMode()), now) == null }
        if (want.isEmpty()) return

        want.forEach { nb ->
            app.api.play(
                nb.id,
                direct = app.prefs.directMode != "relay",
                onOk = { info ->
                    PlayCacheStore.put(
                        app.prefs,
                        PlayCache(
                            identity = PlayCacheStore.identity(app.prefs, 0, phoneCacheMode()),
                            channelId = nb.id,
                            url = app.api.absolute(info.url),
                            headers = info.playHeaders("LITV/1.0 (Android)"),
                            mode = info.mode,
                            ua = info.ua,
                            expires = info.expires,
                        ),
                    )
                    android.util.Log.i(TAG, "预热相邻频道：${nb.name}")
                },
                onErr = { /* 预热失败无所谓，换台时再正常请求 */ },
            )
        }
    }

    override fun onError(message: String) {
        loading.visibility = View.GONE
        playHint.visibility = View.VISIBLE
        playHint.text = message
    }

    override fun onVideoSizeChanged(width: Int, height: Int, frameRate: Float) = Unit

    // ---------------------------------------------------------------- 生命周期

    override fun onResume() {
        // 从设置页回来：继续播（上面 onPause 被跳过了）
        if (::player.isInitialized && currentChannel?.id == player.playingChannelId) {
            runCatching { player.resume() }
        }
        super.onResume()
        // 从遥控器/设置返回时重新应用一次当前 Tab，
        // 避免两个面板的可见性因为重建而错乱
        // 以 prefs 为准：别的页面（遥控器/设置）可能改过目标 Tab
        if (::panelPlay.isInitialized) switchTab(app.prefs.lastTab)
        // 从设置页/遥控器页回来：Surface 是重建的，必须重接并恢复播放
        if (::panelPlay.isInitialized && currentTab == TAB_PLAY) reattachVideo()
        if (::adapter.isInitialized && all.isNotEmpty()) load()
    }

    /** 正在打开应用内页面（设置/遥控器）：此时 onPause 不该停播。 */
    private var suppressPauseForInternalPage = false

    override fun onPause() {
        super.onPause()
        // 离开前台：解除常亮，允许系统息屏
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (suppressPauseForInternalPage) {
            // 只是打开了设置/其它本应用页面：继续播，回来时画面还在
            suppressPauseForInternalPage = false
            return
        }
        // 只暂停，不改 paused —— 那是用户的意图，回来还要按它恢复
        player.pause()
    }

    override fun onDestroy() {
        app.api.onAuthExpired = null
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

    /** 手机端的缓存身份模式：直连/中转。必须和 get/put 用同一个表达式。 */
    private fun phoneCacheMode(): String =
        if (app.prefs.directMode == "relay") "relay" else "auto"



    /**
     * 凭据失效（2003/401）：清掉本机凭据并回绑定页。
     *
     * 不清凭据的话，用户会卡在"频道=0"的黑屏或空列表上，
     * 以为 App 坏了——实际是 token 被服务端换发/清掉了。
     */
    private fun onAuthExpired() {
        runOnUiThread {
            app.prefs.clearSession()
            startActivity(android.content.Intent(this, LauncherActivity::class.java))
            finish()
        }
    }

}
