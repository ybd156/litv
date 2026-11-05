package com.litv.tv.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放页。整个 TV 端体验的核心。
 *
 * 交互设计参考了主流 IPTV 客户端的通行做法：
 *
 *   面板收起时：↑↓ 换台 · ← 呼出频道列表 · → 呼出节目单 · OK 信息条
 *   面板展开时：↑↓ 选台 · OK 播放 · ←→ 切分组/收起 · BACK 关闭
 *   数字键    ：连按累积，2500ms 无输入后跳台
 *   长按 OK   ：800ms 触发线路/内核切换
 *   BACK 层级 ：面板 → OSD → 退出（逐级返回，不能一按就退出）
 *
 * 三个"不能省"的细节：
 *   1. 换台时用上一帧截图盖住黑屏（否则每次切台闪一下黑，观感极差）
 *   2. 起播失败自动换内核，并把结果记住
 *   3. OSD 5 秒自动隐藏，但换台时立刻刷新内容
 */
class PlayerActivity : AppCompatActivity(), PlayerManager.Callback {
    private val TAG = "LITV"


    private lateinit var app: LitvApp
    private lateinit var surface: SurfaceView
    private lateinit var surfaceTex: android.view.TextureView
    private lateinit var playerView: androidx.media3.ui.PlayerView
    private var videoSurface: android.view.Surface? = null
    private lateinit var snapshotView: ImageView
    private lateinit var loading: ProgressBar
    private lateinit var switchTip: TextView
    private lateinit var osd: LinearLayout
    private lateinit var osdName: TextView
    private lateinit var osdNow: TextView
    private lateinit var osdNo: TextView
    private lateinit var osdNext: TextView
    private lateinit var osdEngine: TextView
    private lateinit var osdProgress: ProgressBar
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelList: RecyclerView
    private lateinit var grpList: RecyclerView
    private val srcAdapter = GroupAdapter()
    private val grpAdapter = GroupAdapter()
    /** 当前选中的源 id，0 = 全部 */
    private var selSource = 0L
    /** 当前选中的分组，空 = 全部 */
    private var selGroup = ""
    private var srcNames: Map<Long, String> = emptyMap()
    private lateinit var numberTip: TextView
    private lateinit var settingsMenu: android.view.View
    private var menuVisible = false
    private lateinit var epgPanel: LinearLayout
    private lateinit var epgTitle: TextView
    private lateinit var epgList: RecyclerView

    // 底部信息条（对齐 DIYP / 群晖电视的样式）
    private lateinit var infoLogo: ImageView
    private lateinit var infoName: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoLine: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var infoTime: TextView

    /** 当前频道的线路总数 / 正在用的第几条 / 该线路延迟 */
    private var streamTotal = 0
    private var streamIndex = 0
    private var streamLatency = 0

    private lateinit var player: PlayerManager
    private lateinit var panelAdapter: ChannelAdapter
    private lateinit var epgAdapter: EpgAdapter

    private var channels: List<Channel> = emptyList()
    private var allChannels: List<Channel> = emptyList()
    private val favoriteIds = mutableSetOf<Long>()
    private var groups: List<String> = emptyList()
    private var groupIdx = 0
    private var index = 0
    private var current: Channel? = null
    private var programs: List<EpgProgram> = emptyList()

    private val main = Handler(Looper.getMainLooper())
    private val numberBuffer = StringBuilder()
    private var osdVisible = false
    private var panelVisible = false
    private var epgVisible = false
    /** 起播计时起点（毫秒），0 表示已上报过 */
    private var startAt = 0L

    // 长按判定：800ms（逆向实测值，不是 320ms）
    private val longPressRunnable = Runnable { onLongPressOk() }
    private var okDownTime = 0L
    /** 上一次按返回的时间，用于「再按一次退出」 */
    private var lastBackAt = 0L
    /** 首次进入还没自动播放过 */
    private var autoPlayPending = true
    /** 进播放页时想播的频道 id（0 = 上次那个） */
    private var pendingChannelId = 0L
    /**
     * 遥控发来的待切频道 id。
     *
     * singleTask 下 Activity 不会重建，只是收到 onNewIntent，
     * 所以换台不能再依赖 onCreate 的 intent。
     * 如果收到指令时频道列表还没 bootstrap 完，就先记下来，
     * 等列表就绪后再执行——否则会出现"遥控没反应"。
     */
    private var pendingRemoteChannelId = 0L

    private val hideOsdRunnable = Runnable { hideOsd() }
    private val numberJumpRunnable = Runnable { jumpByNumber() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        app = application as LitvApp

        bindViews()
        loadChannels()

        player = PlayerManager(this, app.prefs).also { it.callback = this }

        // 直连第一个内核就失败 → 立刻改走中转重播，不轮完所有内核（那要 20 秒）。
        // 直连失败基本都是网络层问题（源站在这个网络里不可达），换内核修不好。
        player.onDirectFailed = { reason ->
            main.post {
                if (!relayRetried) {
                    relayRetried = true
                    forceRelayOnce = true
                    toastTip("直连不通（$reason），改走服务端中转")
                    current?.let { playChannel(it, currentStreamId) }
                }
            }
        }

        player.playerView = playerView
        setupRenderTarget()

        // 自动播放不能在这里做：没有本地缓存时 loadChannels() 走的是异步 bootstrap，
        // 这一刻 channels 还是空的，直接判断会永远不播。交给 applyBootstrap 触发。
        pendingChannelId = intent.getLongExtra(EXTRA_CHANNEL_ID, 0)
        Log.i(TAG, "onCreate: 初始 channels=${channels.size} 待播=$pendingChannelId")

        setupTouchGestures()
    }

    // ---------------------------------------------------------------- 触控手势
    //
    // 电视上很多场景没有遥控器（触摸屏一体机、用鼠标的盒子、模拟器调试），
    // 所以补一套和遥控器等价的手势：
    //
    //   单击画面        呼出 / 收起 OSD
    //   从左往右滑      打开频道面板（分类 + 频道 + 节目单）
    //   从右往左滑      打开设置菜单
    //   上滑 / 下滑     下一个台 / 上一个台
    //   左侧边缘上下拖  调亮度（和换台区分开，否则垂直方向会打架）
    private var gestureDetector: android.view.GestureDetector? = null
    private var downX = 0f
    private var downY = 0f
    private var brightnessDragging = false
    private var brightnessStart = -1f

    private fun setupTouchGestures() {
        val root = findViewById<android.view.View>(R.id.root) ?: return
        gestureDetector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    // 面板开着时点画面交给面板自己处理
                    if (panelVisible) return false
                    if (osdVisible) hideOsd() else showOsd()
                    return true
                }
            })

        root.setOnTouchListener { _, ev ->
            gestureDetector?.onTouchEvent(ev)
            handleGesture(ev)
            true
        }
    }

    private fun handleGesture(ev: android.view.MotionEvent): Boolean {
        val slop = (24 * resources.displayMetrics.density).coerceAtLeast(24f)
        val screenW = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val screenH = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)

        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                brightnessDragging = false
                brightnessStart = -1f
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 只在「左边缘 + 以垂直为主」时进入亮度模式，
                // 避免和上下滑换台抢事件。
                if (!brightnessDragging &&
                    downX < screenW * 0.25f &&
                    Math.abs(dy) > slop &&
                    Math.abs(dy) > Math.abs(dx) * 1.5f
                ) {
                    brightnessDragging = true
                    brightnessStart = currentScreenBrightness()
                }
                if (brightnessDragging) {
                    val v = (brightnessStart - dy / screenH).coerceIn(0.05f, 1f)
                    setScreenBrightness(v)
                    switchTip.text = "亮度 ${(v * 100).toInt()}%"
                    switchTip.visibility = android.view.View.VISIBLE
                }
            }

            android.view.MotionEvent.ACTION_UP -> {
                if (brightnessDragging) {
                    brightnessDragging = false
                    main.postDelayed({
                        if (switchTip.text.startsWith("亮度")) switchTip.visibility = android.view.View.GONE
                    }, 900)
                    return true
                }
                val dx = ev.x - downX
                val dy = ev.y - downY
                when {
                    Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f -> {
                        // 方向按「从哪边滑出来」算，不是「往哪边滑」：
                        //   从左往右滑（dx > 0） -> 频道面板从左边出来
                        //   从右往左滑（dx < 0） -> 设置菜单从右边出来
                        if (dx > 0) openPanel() else toggleSettingsMenu()
                    }
                    Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx) * 1.5f -> {
                        // 上滑 = 下一个台，下滑 = 上一个台
                        switchBy(if (dy < 0) +1 else -1)
                    }
                }
            }
        }
        return false
    }

    /** 当前窗口亮度；未显式设置过时返回系统亮度。 */
    private fun currentScreenBrightness(): Float {
        val v = window.attributes.screenBrightness
        if (v >= 0f) return v
        return try {
            android.provider.Settings.System.getInt(
                contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS
            ) / 255f
        } catch (t: Throwable) {
            0.5f
        }
    }

    /** 只改本窗口亮度，不动系统设置（退出播放页自动恢复）。 */
    private fun setScreenBrightness(v: Float) {
        val attrs = window.attributes
        attrs.screenBrightness = v
        window.attributes = attrs
    }

    /**
     * 遥控换台的入口。
     *
     * 为什么必须实现：PlayerActivity 在 manifest 里是 singleTask，
     * 已经在播时 startActivity 不会新建实例，只会回调 onNewIntent。
     * 没有这个方法，遥控指令虽然发出去了（服务端也显示已转发），
     * 电视却停在原来的台上——用户看到的就是"遥控换台没反应"。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 必须更新 Activity 持有的 intent，否则后续 getIntent() 还是旧的
        setIntent(intent)
        val targetId = intent?.getLongExtra(EXTRA_CHANNEL_ID, 0) ?: 0
        if (targetId <= 0) return
        Log.i(TAG, "onNewIntent: 遥控换台 -> $targetId")
        switchToChannelById(targetId)
    }

    /**
     * 按 id 换台。频道列表可能还没就绪，这种情况先记账。
     *
     * 只接受列表里真实存在的频道：遥控传的是不可信输入，
     * 指向不存在的 id 应当安静忽略，而不是让播放器去请求一个空频道。
     */
    private fun switchToChannelById(id: Long) {
        val idx = channels.indexOfFirst { it.id == id }
        if (idx < 0) {
            if (channels.isEmpty()) {
                // 列表还没加载完：等 applyBootstrap 再处理
                pendingRemoteChannelId = id
                Log.i(TAG, "onNewIntent: 列表未就绪，暂存 $id")
            } else {
                // 列表已就绪但没有这个频道：拒绝，不改变当前播放
                Log.w(TAG, "onNewIntent: 频道 $id 不存在，忽略")
            }
            return
        }
        pendingRemoteChannelId = 0L
        index = idx
        playChannel(channels[idx])
    }

    /**
     * 按「渲染方式」设置选一套渲染目标并绑定播放器。
     *
     * 自动 = 优先 TextureView：实测这台 Amlogic 盒子用 SurfaceView 时
     * 解码器不往 Surface 写帧（DequeueBuffer time out），画面全黑。
     */
    private fun setupRenderTarget() {
        val mode = app.prefs.renderView
        // 默认改为 SurfaceView。
        //
        // 历史：这台 Amlogic 盒子用 SurfaceView 曾报 DequeueBuffer time out，
        // 于是改成优先 TextureView。但实测 TextureView 下解码器同样出不了帧
        // （wait timeout 刷屏，画面全黑），两条路都试过。
        // 现在以 SurfaceView 为默认——它是硬解的正常输出路径，
        // TextureView 需要 GPU 拷贝，在 Amlogic 上更容易出问题。
        // 设置里仍可切回 TextureView。
        val useTexture = mode == "texture"
        Log.i(TAG, "渲染方式=$mode 使用=" + if (useTexture) "TextureView" else "SurfaceView")

        // 渲染目标必须真正 VISIBLE。
        //
        // 这里曾经把 surfaceTex 设成 GONE，而 GONE 的视图**不参与布局**，
        // onSurfaceTextureAvailable 永远不会回调 —— videoSurface 一直是 null，
        // VLC / mpv 拿不到 Surface，画面全黑。
        // 这台电视的 Exo 硬解会失败并回退到 VLC，于是表现为「电视无法播放」。
        //
        // 两个目标互斥：按「渲染方式」只显示一个，另一个 GONE。
        surface.visibility = if (useTexture) android.view.View.GONE else android.view.View.VISIBLE
        surfaceTex.visibility = if (useTexture) android.view.View.VISIBLE else android.view.View.GONE

        // PlayerView 只给 Exo 用。它在布局里位于最上层，
        // 给 VLC / mpv 时必须隐藏，否则会盖住下面的渲染目标。
        // 初始显示它（默认链首选 Exo），真正的切换在 onEngineChanged 里做。
        playerView.visibility = android.view.View.VISIBLE

        if (useTexture) {
            surfaceTex.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    Log.i(TAG, "TextureView 就绪 ${w}x$h")
                    videoSurface = android.view.Surface(st)
                    player.attach(videoSurface, findViewById(android.R.id.content))
                    if (!player.isPlaying) current?.let { playChannel(it) }
                }

                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}

                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    videoSurface?.release()
                    videoSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
            }
        } else {
            surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(h: android.view.SurfaceHolder) {
                    Log.i(TAG, "SurfaceView 就绪，绑定播放器")
                    player.attach(surface.holder.surface, findViewById(android.R.id.content))
                    if (!player.isPlaying) current?.let { playChannel(it) }
                }

                override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
            })
        }
    }

    private fun bindViews() {
        surface = findViewById(R.id.surface)
        surfaceTex = findViewById(R.id.surfaceTex)
        playerView = findViewById(R.id.playerView)
        snapshotView = findViewById(R.id.snapshot)
        loading = findViewById(R.id.loading)
        switchTip = findViewById(R.id.switchTip)
        osd = findViewById(R.id.osd)
        osdName = findViewById(R.id.osdName)
        osdNow = findViewById(R.id.osdNow)
        osdNo = findViewById(R.id.osdNo)
        osdNext = findViewById(R.id.osdNext)
        osdEngine = findViewById(R.id.osdEngine)
        osdProgress = findViewById(R.id.osdProgress)
        panel = findViewById(R.id.channelPanel)
        panelTitle = findViewById(R.id.panelTitle)
        panelList = findViewById(R.id.panelList)
        numberTip = findViewById(R.id.numberTip)
        settingsMenu = findViewById(R.id.settingsMenu)
        // 右侧设置菜单：能就地改的就地改，改不了的转给完整设置弹层
        findViewById<TextView>(R.id.miAspect).setOnClickListener {
            app.prefs.aspect = cycleOf(app.prefs.aspect, listOf("auto", "16:9", "4:3", "fill"))
            // 比例是引擎在 prepare 时读的，改完下次起播生效
            toastTip("画面比例：" + app.prefs.aspect)
        }
        findViewById<TextView>(R.id.miDecode).setOnClickListener {
            app.prefs.decode = cycleOf(app.prefs.decode, listOf("auto", "hard", "soft"))
            toastTip("解码方式：" + app.prefs.decode)
        }
        findViewById<TextView>(R.id.miLine).setOnClickListener { openLinePicker() }
        findViewById<TextView>(R.id.miTimeout).setOnClickListener {
            // 先收掉右侧菜单，否则弹窗和菜单叠在一起
            closeSettingsMenu()
            SettingsDialog(this).show()
        }
        findViewById<TextView>(R.id.miCategory).setOnClickListener {
            closeSettingsMenu(); openPanel()
        }
        findViewById<TextView>(R.id.miList).setOnClickListener {
            closeSettingsMenu(); openPanel()
        }
        findViewById<TextView>(R.id.miDisplay).setOnClickListener {
            // 先收掉右侧菜单，否则弹窗和菜单叠在一起
            closeSettingsMenu()
            SettingsDialog(this).show()
        }
        findViewById<TextView>(R.id.miPrefs).setOnClickListener {
            // 先收掉右侧菜单，否则弹窗和菜单叠在一起
            closeSettingsMenu()
            SettingsDialog(this).show()
        }
        findViewById<TextView>(R.id.miOther).setOnClickListener {
            // 先收掉右侧菜单，否则弹窗和菜单叠在一起
            closeSettingsMenu()
            SettingsDialog(this).show()
        }
        epgPanel = findViewById(R.id.epgPanel)
        epgTitle = findViewById(R.id.epgTitle)
        epgList = findViewById(R.id.epgList)
        infoLogo = findViewById(R.id.infoLogo)
        infoName = findViewById(R.id.infoName)
        infoNow = findViewById(R.id.infoNow)
        infoNext = findViewById(R.id.infoNext)
        infoLine = findViewById(R.id.infoLine)
        infoProgress = findViewById(R.id.infoProgress)
        infoTime = findViewById(R.id.infoTime)
    }

    private fun loadChannels() {
        val cached = app.prefs.cachedBootstrap
        if (cached.isNotBlank()) {
            runCatching {
                val b = Gson().fromJson(cached, Bootstrap::class.java)
                applyBootstrap(b)
            }
        }
        if (channels.isEmpty()) {
            app.api.bootstrap(onOk = { b -> applyBootstrap(b) }, onErr = {})
        }
        panelAdapter = ChannelAdapter(
            logoMaxPx = app.tier.logoMaxPx,
            onClick = { ch ->
                closePanel()
                val i = channels.indexOfFirst { it.id == ch.id }
                if (i >= 0) {
                    index = i
                    playChannel(ch)
                }
            },
        )
        panelList.layoutManager = LinearLayoutManager(this)
        panelList.adapter = panelAdapter

        // 左侧分类列表
        grpList = findViewById(R.id.grpList)
        grpList.layoutManager = LinearLayoutManager(this)
        grpList.adapter = grpAdapter
        grpAdapter.onPick = { name ->
            selGroup = name ?: ""
            applyChannelFilter()
            index = 0
            panelList.scrollToPosition(0)
        }
        panelAdapter.submit(channels, emptyMap())
        panelTitle.text = "频道列表（${channels.size}）"

        epgAdapter = EpgAdapter()
        epgList.layoutManager = LinearLayoutManager(this)
        epgList.adapter = epgAdapter
        // C3：点节目看详情。之前这个回调根本没接线，点节目毫无反应。
        epgAdapter.onProgramClick = { p -> showProgramDetails(p) }
        // C4：点标题切换分类
        epgTitle.setOnClickListener { cycleEpgCategory() }
    }

    /**
     * 节目详情弹层（C3），顺带提供 C5 提醒 / C6 预约录制。
     *
     * 为什么放在这里而不是单独一页：电视上多一层页面就多一次"返回"，
     * 用户看完详情多半是要么设提醒、要么接着看，用弹层最省事。
     */
    /**
     * 回看某个频道（时移缓冲）。
     *
     * 服务端只在「有人看过这个台」时才有缓冲，所以先问 available_seconds；
     * 没有缓冲就明确告诉用户，而不是切过去黑屏。
     */
    private fun openTimeshiftFor(ch: Channel) {
        app.api.timeshift(ch.id,
            onOk = { info ->
                if (!info.enabled || info.availableSeconds <= 0) {
                    toastTip("这个台暂时没有可回看的缓冲")
                    return@timeshift
                }
                val abs = app.api.absolute(info.url)
                val minutes = info.availableSeconds / 60
                toastTip("回看中（可回看约 $minutes 分钟）")
                player.play(
                    ch.id, abs,
                    mapOf("User-Agent" to "LITV/1.0 (Android)"),
                    app.prefs.engine,
                )
            },
            onErr = { toastTip("回看不可用：$it") })
    }

    private fun showProgramDetails(p: com.litv.tv.data.EpgProgram) {
        val ch = current ?: return
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis() / 1000
        val timeText = fmt.format(Date(p.startAt * 1000)) + " - " +
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(p.endAt * 1000))

        val lines = StringBuilder()
        lines.append(timeText)
        if (p.category.isNotBlank()) lines.append("  ·  ").append(p.category)
        if (p.description.isNotBlank()) lines.append("\n\n").append(p.description)

        val finished = p.endAt in 1 until now
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(p.title.ifBlank { "（无标题）" })
            .setMessage(lines.toString())
            .setNegativeButton("关闭", null)

        if (finished) {
            // 已播完：给回看入口
            builder.setPositiveButton("回看") { _, _ -> openTimeshiftFor(ch) }
        } else {
            // 未播完：给提醒 + 预约录制
            builder.setPositiveButton("提醒我") { _, _ ->
                app.api.createReminder(ch.id, p.title, p.startAt,
                    onOk = { toastTip("已设置提醒，开播时推送") },
                    onErr = { toastTip("设置提醒失败：$it") })
            }
            builder.setNeutralButton("预约录制") { _, _ ->
                val dur = (p.endAt - p.startAt).toInt().coerceIn(60, 8 * 3600)
                app.api.createRecording(ch.id, p.title, p.startAt, dur,
                    onOk = { toastTip("已预约录制：${p.title}") },
                    onErr = { toastTip("预约失败：$it") })
            }
        }
        builder.show()
    }

    /** 存全量频道 + 分组名 + 收藏，供「列表内左右键切分组」用。 */
    private fun applyBootstrap(b: Bootstrap) {
        allChannels = b.channels
        favoriteIds.clear()
        favoriteIds.addAll(b.favorites)
        groups = allChannels.map { it.group }.filter { it.isNotBlank() }.distinct()
        applyGroupFilter()
        Log.i(TAG, "bootstrap 完成: 频道=${allChannels.size} 待播=$pendingChannelId 待自动播放=$autoPlayPending")
        if (autoPlayPending && channels.isNotEmpty()) {
            autoPlayPending = false
            // 优先用 Intent 指定要播的台（点通知、外部跳转）；
            // 没有就退回「上次看的台」。
            //
            // 之前只看了 pendingChannelId，而从桌面正常启动时它是 0，
            // indexOfFirst 必然返回 -1，于是永远播第一个台——
            // 「退出重进不回上次频道」就是这么来的。
            val wantId = if (pendingChannelId > 0) pendingChannelId else app.prefs.lastChannelId
            val idx = channels.indexOfFirst { it.id == wantId }
            index = if (idx >= 0) idx else 0
            Log.i(TAG, "开始自动播放: ${channels[index].name}（想要=$wantId 命中=${idx >= 0}）")
            playChannel(channels[index])
        } else if (pendingRemoteChannelId > 0) {
            // 遥控指令在 bootstrap 完成前就到了：现在补执行。
            // 不补的话，用户开机瞬间按遥控换台会"按了没反应"。
            val want = pendingRemoteChannelId
            pendingRemoteChannelId = 0L
            Log.i(TAG, "bootstrap 后补执行遥控换台: $want")
            switchToChannelById(want)
        }
    }

    /** 刷新左侧分类列表：全部 / 收藏 / 各分组，并带上数量。 */
    private fun refreshCategories() {
        if (!::grpList.isInitialized) return
        val rows = ArrayList<GroupAdapter.Row>()
        rows.add(GroupAdapter.Row("全部", allChannels.size))
        if (favoriteIds.isNotEmpty()) rows.add(GroupAdapter.Row("收藏", favoriteIds.size))
        allChannels.map { it.group }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { g -> rows.add(GroupAdapter.Row(g, allChannels.count { it.group == g })) }
        grpAdapter.submit(rows, if (selGroup.isEmpty()) "全部" else selGroup)
    }

    private fun applyGroupFilter() {
        // 四列模式下，过滤交给 rebuildColumns() + applyChannelFilter()。
        //
        // 这里原来还有一段单列时代的分组逻辑，其中 `allChannels = channels`
        // 会把全量列表覆盖成过滤后的结果（越点越少），
        // 后面再用 groupLabelText() 把四列的标题盖回去。整段删掉。
        refreshCategories()
        if (::panelAdapter.isInitialized) {
            applyChannelFilter()
        }
    }

    private val hasFavGroup: Boolean get() = favoriteIds.isNotEmpty()

    private fun groupLabelText(): String {
        val name = when {
            groupIdx <= 0 -> "全部"
            hasFavGroup && groupIdx == 1 -> "收藏"
            else -> {
                val gi = if (hasFavGroup) groupIdx - 2 else groupIdx - 1
                groups.getOrNull(gi) ?: "全部"
            }
        }
        return "分组：$name（${channels.size}）  ←→ 切换"
    }


    private fun switchGroup(delta: Int) {
        val total = groups.size + 1 + if (hasFavGroup) 1 else 0
        groupIdx = ((groupIdx + delta) % total + total) % total
        applyGroupFilter()
        if (channels.isNotEmpty()) {
            index = 0
            panelList.scrollToPosition(0)
        }
    }

    // ---------------------------------------------------------------- 播放

    private fun playChannel(ch: Channel, streamId: Long = 0) {
        Log.i(TAG, "playChannel: ${ch.name} streamId=$streamId direct=${app.prefs.directMode}")
        current = ch
        // 换到别的台就重置"已试线路"记录
        if (triedForChannel != ch.id) {
            triedStreams.clear()
            triedForChannel = ch.id
            relayRetried = false
            forceRelayOnce = false
        }
        triedStreams += streamId
        currentStreamId = streamId
        app.prefs.lastChannelId = ch.id
        // 起播计时：从按下换台到出第一帧，这是用户唯一能感知的"快不快"
        startAt = System.currentTimeMillis()

        // 换台时先抓一帧盖住，避免黑屏闪烁
        player.snapshot()?.let {
            snapshotView.setImageBitmap(it)
            snapshotView.visibility = View.VISIBLE
        }

        // 换台时**不显示加载圈**。
        //
        // 旧画面已经用快照盖住了，再叠一个转圈的很突兀——
        // 用户的反馈就是「还是有加载中的圈圈」。
        // 只有第一次起播（没有快照可盖、屏幕是黑的）才需要圈。
        main.removeCallbacks(showLoadingRunnable)
        if (snapshotView.visibility != View.VISIBLE) {
            main.postDelayed(showLoadingRunnable, 300)
        }
        switchTip.visibility = View.VISIBLE
        osdName.text = ch.name

        // 直连失败一次后，这次强制走服务端中转
        val wantDirect = app.prefs.directMode != "relay" && !forceRelayOnce
        player.directMode = wantDirect

        // ---- 先用缓存地址起播，把 /tv/play 的往返从关键路径上摘掉 ----
        // 低端盒子上这一趟网络往返能占到起播时间的三分之一。
        // 只在「自动选线路 + 不是中转重试」时用，避免干扰已有的线路回退逻辑。
        val nowSec = System.currentTimeMillis() / 1000
        val cached = if (streamId == 0L && !forceRelayOnce) {
            PlayCacheStore.get(app.prefs, ch.id, nowSec)
        } else null
        val cacheUsable = cached != null
        if (cacheUsable) {
            Log.i(TAG, "缓存起播 ${ch.name}，签名还剩 ${cached!!.expires - nowSec}s")
            player.play(ch.id, app.api.absolute(cached.url), cached.headers, app.prefs.engine)
        }

        app.api.play(ch.id, streamId,
            direct = wantDirect,
            onOk = { info ->
                // R09：用服务端下发的整份线路头（已清洗），不再只带 UA。
                // 要求 Referer / Origin 校验的源，只带 UA 会直接 403。
                // 默认 UA 必须和手机端完全一致（LITV/1.0 (Android)）。
                // 之前电视端多带了个 " TV" 后缀，很多 IPTV 服务商做 UA 白名单，
                // 结果同一份源手机能播、电视被挡——排查了很久才发现是这个。
                val headers = info.playHeaders("LITV/1.0 (Android)")
                val abs = app.api.absolute(info.url)

                // 时钟偏移检查：签名地址的过期时间是服务端按**自己的时钟**算的。
                // 如果设备时间比服务器快，地址拿到手就已经过期，服务端会 403，
                // 三个内核全部起不来——表现就是"所有播放内核都无法播放"。
                // 这种情况换内核、换线路都没用，必须校准时间。
                val nowSec = System.currentTimeMillis() / 1000
                if (info.expires in 1 until nowSec) {
                    val skewMin = (nowSec - info.expires) / 60
                    hideLoading()
                    switchTip.text = "设备时间比服务器快约 ${skewMin} 分钟，播放地址已过期，请校准时间"
                    switchTip.visibility = View.VISIBLE
                    toastTip("请在系统设置里开启自动校准时间")
                    return@play
                }
                currentPlayMode = info.mode
                // 刷新缓存，供下次起播/换台直接命中
                PlayCacheStore.put(app.prefs, PlayCache(
                    ch.id, abs, headers, info.mode, info.ua, info.expires))
                // 播放 URL 可能含签名、设备标识或上游凭据，Release 日志不得记录。
                Log.i(TAG, "play 返回: mode=${info.mode} kind=${info.kind} 请求头=${headers.keys}")
                player.play(ch.id, abs, headers, app.prefs.engine)
                refreshEpg(ch.id)
                // 底部信息条：线路 x/y 与延迟
                refreshLineInfo(ch.id, info.streamId)
                renderInfoBar()
                app.api.heartbeat(ch.id, "playing")
            },
            onErr = { msg ->
                hideLoading()
                switchTip.text = "无法播放：$msg"
                main.postDelayed({ switchTip.visibility = View.GONE }, 3000)
            })
    }

    private fun switchBy(delta: Int) {
        if (channels.isEmpty()) return
        val n = channels.size
        val step = if (app.prefs.reverseChannelKeys) -delta else delta
        index = ((index + step) % n + n) % n
        playChannel(channels[index])
        showOsd()
    }

    private fun jumpByNumber() {
        val text = numberBuffer.toString()
        numberBuffer.setLength(0)
        numberTip.visibility = View.GONE
        if (text.isBlank()) return
        val want = text.toIntOrNull() ?: return

        // 先按频道号找，再退化为列表序号
        var i = channels.indexOfFirst { it.number == want }
        if (i < 0) i = want - 1
        if (i in channels.indices) {
            index = i
            playChannel(channels[i])
            showOsd()
        }
    }

    // ---------------------------------------------------------------- EPG

    private fun refreshEpg(channelId: Long) {
        app.api.epg(channelId, 0, onOk = { list ->
            programs = list
            renderOsdNow()
            // 节目单是浮层里常显的一列，取到数据要立刻刷上去，
            // 否则打开浮层时看到的永远是加载圈。
            if (panelVisible) refreshEpgColumn()
            main.post { renderInfoBar() }
        }, onErr = { programs = emptyList() })
    }

    private fun renderOsdNow() {
        val now = System.currentTimeMillis() / 1000
        val cur = programs.firstOrNull { it.startAt <= now && it.endAt > now }
        val next = programs.firstOrNull { it.startAt > now }
        if (cur != null) {
            osdNow.text = "${fmt(cur.startAt)} ${cur.title}"
            val span = (cur.endAt - cur.startAt).coerceAtLeast(1)
            val pct = (((now - cur.startAt).toFloat() / span) * 100).toInt().coerceIn(0, 100)
            osdProgress.progress = pct
            osdProgress.visibility = View.VISIBLE
        } else {
            osdNow.text = getString(R.string.no_epg)
            osdProgress.visibility = View.GONE
        }
        osdNext.text = next?.let { "下一档 ${fmt(it.startAt)} ${it.title}" } ?: ""
        osdEngine.text = player.engineLabel()

        // 台号：这个 TextView 之前从来没被赋值过，一直是空的。
        // 用当前过滤列表里的序号，和频道面板里看到的一致，补成三位（005 这种）。
        val pos = current?.let { c -> channels.indexOfFirst { it.id == c.id } } ?: -1
        osdNo.text = if (pos >= 0) String.format("%03d", pos + 1) else ""
    }

    /**
     * 刷新底部信息条：台标 / 台号+名称 / 正在播 / 下一档 / 线路 / 进度 / 时间。
     *
     * 参考 DIYP 与群晖电视：两个实现都在底部放这样一条信息卡，
     * 好处是不用呼出 OSD 就能看到「这个台在播什么、还有几条线路」。
     */
    private fun renderInfoBar() {
        val ch = current ?: return
        val now = System.currentTimeMillis() / 1000

        infoName.text = if (ch.number > 0) "${ch.number}  ${ch.name}" else ch.name
        // 复用频道列表那套台标加载（内存 LRU + 降采样），不额外引图片库
        infoLogo.setImageDrawable(null)
        if (ch.logo.isNotBlank() && ::panelAdapter.isInitialized) {
            panelAdapter.loadLogoFor(infoLogo, ch.logo)
        }

        val cur = programs.firstOrNull { it.startAt <= now && it.endAt > now }
        val next = programs.firstOrNull { it.startAt > now }
        if (cur != null) {
            infoNow.text = "正在播放：" + cur.title
            val span = (cur.endAt - cur.startAt).coerceAtLeast(1)
            val pct = (((now - cur.startAt).toFloat() / span) * 100).toInt().coerceIn(0, 100)
            infoProgress.progress = pct
            infoProgress.visibility = View.VISIBLE
        } else {
            infoNow.text = "正在播放：" + getString(R.string.no_epg)
            infoProgress.visibility = View.GONE
        }
        infoNext.text = if (next != null) "下一节目：" + next.title else ""

        // 线路 x/y · 延迟
        infoLine.text = if (streamTotal > 0) {
            val base = "线路 " + streamIndex + "/" + streamTotal
            if (streamLatency > 0) base + "  ·  " + streamLatency + "ms" else base
        } else ""

        // 时间 + 星期
        val cal = java.util.Calendar.getInstance()
        val days = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        infoTime.text = String.format("%02d:%02d %s",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1])
    }

    /** 拉一次线路列表，算出「第几条 / 共几条 / 延迟」。 */
    private fun refreshLineInfo(channelId: Long, usedStreamId: Long) {
        app.api.streams(channelId,
            onOk = { list ->
                main.post {
                    streamTotal = list.size
                    val idx = list.indexOfFirst { it.id == usedStreamId }
                    streamIndex = if (idx >= 0) idx + 1 else if (list.isNotEmpty()) 1 else 0
                    streamLatency = list.getOrNull((streamIndex - 1).coerceAtLeast(0))?.latencyMs ?: 0
                    renderInfoBar()
                }
            },
            onErr = {
                main.post {
                    streamTotal = 0; streamIndex = 0; streamLatency = 0
                    renderInfoBar()
                }
            })
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts * 1000))

    // ---------------------------------------------------------------- OSD / 面板

    // ---------------------------------------------------------------- 换台过渡
    //
    // 换台时的观感问题：
    //   - 加载圈瞬间出现、瞬间消失，很突兀
    //   - 换台本来就快的时候，圈只闪一下，反而显得卡
    //
    // 做法：
    //   1. 加载圈延迟 220ms 才淡入——快换台根本不会出现
    //   2. 淡入淡出各 150~180ms，不是硬切
    //   3. 旧画面快照一直盖着，等第一帧真的画出来再淡出

    private val showLoadingRunnable = Runnable {
        if (!::loading.isInitialized) return@Runnable
        if (loading.visibility == View.VISIBLE) return@Runnable
        loading.alpha = 0f
        loading.visibility = View.VISIBLE
        loading.animate().alpha(1f).setDuration(180).start()
    }

    /** 起播成功/失败时调用：把加载圈淡出，而不是直接 GONE。 */
    private fun hideLoading() {
        if (!::loading.isInitialized) return
        main.removeCallbacks(showLoadingRunnable)
        if (loading.visibility != View.VISIBLE) return
        loading.animate().alpha(0f).setDuration(150).withEndAction {
            loading.visibility = View.GONE
            loading.alpha = 1f
        }.start()
    }

    private fun showOsd() {
        // 面板打开时不显示 OSD：面板底部已经有完整信息条，
        // 两个同时出现会叠在一起。
        if (panelVisible) return
        osdVisible = true
        osd.visibility = View.VISIBLE
        osdName.text = current?.name ?: ""
        renderOsdNow()
        main.removeCallbacks(hideOsdRunnable)
        main.postDelayed(hideOsdRunnable, 5000)
    }

    private fun hideOsd() {
        osdVisible = false
        osd.visibility = View.GONE
    }

    private fun toggleOsd() {
        if (osdVisible) hideOsd() else showOsd()
    }

    private fun openPanel() {
        panelVisible = true
        panel.visibility = View.VISIBLE
        // 面板自己底部就有信息条，OSD 再压上来就重了——
        // 真机上看到的是「线路 1/1 / 04:27 周五」叠在频道列表上。
        hideOsd()
        // 四列：源 / 分组 / 频道。这里必须走 applyChannelFilter()，
        // 原来直接 submit(channels) 会把还没加载的空列表刷进去，频道列就是空的。
        applyChannelFilter()
        refreshEpgColumn()

        // 把焦点放到当前频道上。
        //
        // 这里原来用 panelList.getChildAt(i) —— 但 getChildAt 收的是
        // **可视区子索引**，不是适配器位置。频道在列表靠后时它返回 null，
        // 焦点就没落到任何一项上：面板打开了，但 OK 键按下去没有任何反应，
        // 用户看到的就是「频道页无法换台」。
        //
        // 正确做法：先按适配器位置滚动，再用 findViewHolderForAdapterPosition
        // 取真正的 itemView；取不到就退回到让列表本身获得焦点。
        val idx = channels.indexOfFirst { it.id == current?.id }
        val pos = if (idx >= 0) idx else 0
        if (channels.isNotEmpty()) {
            (panelList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
            panelList.post {
                val vh = panelList.findViewHolderForAdapterPosition(pos)
                if (vh != null) {
                    vh.itemView.requestFocus()
                } else {
                    panelList.requestFocus()
                }
            }
        }
        hideOsd()
    }

    private fun closePanel() {
        panelVisible = false
        panel.visibility = View.GONE
    }

    /**
     * 刷新节目单列。
     *
     * 参考实现里节目单是紧挨频道列表常显的第三列，不是抽屉，
     * 所以开浮层时要跟着一起刷，切台时也要跟着换。
     */
    /** C4：节目单分类筛选。null 表示全部分类。 */
    private var epgCategory: String? = null

    /** 当前节目单里出现过的分类（去重，保持出现顺序）。 */
    private fun epgCategories(): List<String> =
        programs.map { it.category }.filter { it.isNotBlank() }.distinct()

    /** 按当前分类筛选后的节目。 */
    private fun filteredPrograms(): List<com.litv.tv.data.EpgProgram> {
        val c = epgCategory ?: return programs
        return programs.filter { it.category == c }
    }

    private fun epgTitleText(): String {
        val name = current?.name ?: "节目单"
        val cat = epgCategory
        return if (cat == null) "$name · 全部" else "$name · $cat"
    }

    /** 依次切换：全部 → 各分类 → 全部。 */
    private fun cycleEpgCategory() {
        val cats = epgCategories()
        if (cats.isEmpty()) {
            toastTip("这个频道没有分类信息")
            return
        }
        val order = listOf<String?>(null) + cats
        val i = order.indexOf(epgCategory)
        epgCategory = order[((if (i < 0) 0 else i) + 1) % order.size]
        refreshEpgColumn()
        toastTip("分类：" + (epgCategory ?: "全部"))
    }

    private fun refreshEpgColumn() {
        epgPanel.visibility = View.VISIBLE
        epgTitle.text = epgTitleText()
        epgAdapter.submit(filteredPrograms())
        val pos = epgAdapter.currentPosition()
        if (pos >= 0) epgList.scrollToPosition(pos)
    }

    /** 呼出节目单面板（右侧）。数据是进频道时就预取的，所以这里是瞬开。 */
    private fun openEpg() {
        epgVisible = true
        epgPanel.visibility = View.VISIBLE
        epgTitle.text = epgTitleText()
        epgAdapter.submit(filteredPrograms())
        val pos = epgAdapter.currentPosition()
        epgList.scrollToPosition(pos)
        epgList.post {
            (epgList.layoutManager as? LinearLayoutManager)
                ?.findViewByPosition(pos)?.requestFocus()
        }
        hideOsd()
    }

    private fun closeEpg() {
        // 节目单现在是浮层里常显的一列，不单独隐藏，把焦点还给频道列表即可
        epgVisible = false
        panelList.requestFocus()
    }

    /**
     * 线路切换：长按 OK 呼出。
     * 线路信息由服务端下发（只给标签和健康度，不给上游真实地址，防抓包盗链）。
     */
    private fun showStreamPicker() {
        val ch = current ?: return
        app.api.streams(ch.id,
            onOk = { list ->
                if (list.isEmpty()) {
                    statusToast("这个频道只有一条线路")
                    return@streams
                }
                val labels = list.map { s ->
                    "${s.label}  ·  ${s.healthText}" + if (s.latencyMs > 0) "  ${s.latencyMs}ms" else ""
                }.toTypedArray()
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                    .setTitle("选择线路（${ch.name}）")
                    .setItems(labels) { _, which ->
                        val s = list[which]
                        playChannel(ch, s.id)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            },
            onErr = { statusToast("获取线路失败：$it") })
    }

    private fun statusToast(msg: String) {
        switchTip.text = msg
        switchTip.visibility = View.VISIBLE
        main.postDelayed({
            if (switchTip.text == msg) switchTip.visibility = View.GONE
        }, 2500)
    }

    // ---------------------------------------------------------------- 按键

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val action = event.action

        // 数字键：累积跳台
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (action == KeyEvent.ACTION_DOWN) {
                numberBuffer.append(code - KeyEvent.KEYCODE_0)
                numberTip.text = numberBuffer.toString()
                numberTip.visibility = View.VISIBLE
                main.removeCallbacks(numberJumpRunnable)
                main.postDelayed(numberJumpRunnable, app.prefs.numberJumpTimeoutMs.toLong())
            }
            return true
        }

        // 节目单面板打开时，BACK/← 收起，其余交给列表
        if (epgVisible) {
            if (action == KeyEvent.ACTION_DOWN &&
                (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT)
            ) {
                closeEpg(); return true
            }
            return super.dispatchKeyEvent(event)
        }

        // 频道列表打开时：←→ 切分组，BACK 收起，↑↓ 交给列表自己
        if (panelVisible) {
            if (action == KeyEvent.ACTION_DOWN) {
                when (code) {
                    KeyEvent.KEYCODE_BACK -> {
                        closePanel(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        movePanelFocus(-1); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        movePanelFocus(+1); return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // 设置菜单打开时：按键全部交给菜单自己处理。
        //
        // 之前**没有这个分支**，于是菜单虽然显示出来了，
        // 上下键仍然被当成换台、OK 仍然去呼出 OSD、左键去开频道页，
        // 结果就是"右键能打开设置，但焦点选不中任何一项"。
        if (menuVisible) {
            if (action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BACK) {
                closeSettingsMenu(); return true
            }
            return super.dispatchKeyEvent(event)
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (action == KeyEvent.ACTION_DOWN) switchBy(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (action == KeyEvent.ACTION_DOWN) switchBy(+1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (action == KeyEvent.ACTION_DOWN) switchBy(-1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (action == KeyEvent.ACTION_DOWN) switchBy(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // 左键 = 频道页（分类 → 频道 → 节目单都在这个面板里）
                if (action == KeyEvent.ACTION_DOWN) openPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 右键 = 设置菜单。
                //
                // 原来右键打开的是独立的节目单面板，但按电视的通用习惯
                // （也是用户明确要求）：左=频道页、右=设置、OK=OSD。
                // 节目单不再单独占一个方向键——频道面板里已经有节目单列，
                // 而且从那里看节目单更符合「选台→看这个台播什么」的路径。
                if (action == KeyEvent.ACTION_DOWN) toggleSettingsMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    okDownTime = System.currentTimeMillis()
                    main.postDelayed(longPressRunnable, 800)
                } else {
                    main.removeCallbacks(longPressRunnable)
                    if (System.currentTimeMillis() - okDownTime < 800) toggleOsd()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                // 规范：Menu 键呼出/隐藏右侧设置菜单
                if (action == KeyEvent.ACTION_DOWN) toggleSettingsMenu()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    when {
                        osdVisible -> hideOsd()
                        menuVisible -> closeSettingsMenu()
                        panelVisible -> closePanel()
                        // 二次确认：直接退出太容易误触（遥控器返回键和方向键挨着）
                        System.currentTimeMillis() - lastBackAt < 2000 -> finish()
                        else -> {
                            lastBackAt = System.currentTimeMillis()
                            toastTip("再按一次返回退出")
                        }
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 长按 OK：呼出线路选择（排查"这个台哪个源能播"最实用）。 */
    private fun onLongPressOk() {
        showStreamPicker()
    }

    // ---------------------------------------------------------------- PlayerManager 回调

    override fun onEngineChanged(kind: EngineKind) {
        osdEngine.text = kind.label
        // 按实际选中的内核切换渲染目标。
        //
        // Exo 用 media3 的 PlayerView（它自己管 Surface）；
        // VLC / mpv 用外部 Surface（TextureView / SurfaceView）。
        // PlayerView 在布局里 z 序最高，给 VLC / mpv 时不隐藏就会盖住画面。
        val isExo = kind == EngineKind.EXO
        playerView.visibility = if (isExo) View.VISIBLE else View.GONE
    }

    override fun onPrepared(width: Int, height: Int) = Unit

    override fun onFirstFrame() {
        // 第一帧已经画出来了，把换台快照淡出（200ms）。
        // 直接 GONE 会看到一次硬切，淡出看着是"渐显"，不突兀。
        if (!::snapshotView.isInitialized || snapshotView.visibility != View.VISIBLE) return
        snapshotView.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                snapshotView.visibility = View.GONE
                snapshotView.alpha = 1f
            }
            .start()
    }

    override fun onPlaying() {
        hideLoading()
        switchTip.visibility = View.GONE
        // 快照不在这里撤——onPlaying 时画面往往还是黑的。
        // 等 onFirstFrame 真正画出第一帧再淡出，避免「遮罩没了→黑一下→画面出现」。
        if (snapshotView.visibility != View.VISIBLE) {
            snapshotView.visibility = View.GONE
        }

        // 上报起播耗时（只报第一次出画面，重连不重复计）
        if (startAt > 0) {
            val ms = (System.currentTimeMillis() - startAt).toInt()
            startAt = 0
            current?.let { app.api.reportStats(it.id, "start", ms) }
        }
    }

    override fun onError(message: String) {
        hideLoading()

        // 直连失败 → 改走服务端中转再试。
        //
        // 这是"电视播不了、手机能播"最常见的原因：
        // 直连时是电视自己去连源站，电视所在网络连不上源站（CDN 被墙、
        // 源站限 IP、DNS 不通）就全内核失败；而中转是 NAS 去拉，
        // NAS 能连就一定能播。
        if (currentPlayMode == "direct" && !relayRetried) {
            relayRetried = true
            forceRelayOnce = true
            toastTip("直连失败，改走服务端中转重试")
            playChannel(current ?: return, currentStreamId)
            return
        }

        // 所有内核都失败时，先自动换这个台的下一条线路再试。
        // 源里同一个台常有多条线路（如 cctv1-MCP / cctv1-AV3A），
        // 其中一条编码不支持，不代表别的也不行。
        if (tryNextStreamLine(message)) return
        switchTip.text = "播放出错：$message"
        switchTip.visibility = View.VISIBLE
    }

    /** 上一次播放实际用的模式（direct / relay），用于直连失败后改走中转 */
    private var currentPlayMode: String = ""
    /** 本次选台是否已经因为直连失败改走过中转 */
    private var relayRetried = false
    /** 这一次 play 请求强制走中转 */
    private var forceRelayOnce = false
    /** 当前正在播的线路 id，重试时要用 */
    private var currentStreamId: Long = 0

    /** 本次选台已经试过的线路，避免来回死循环 */
    private val triedStreams = mutableSetOf<Long>()
    private var triedForChannel: Long = 0

    /**
     * 自动换下一条线路。
     *
     * 返回 true 表示"已经在换线，先别显示错误"；false 表示没有可换的线路，
     * 让调用方照常把错误显示出来。
     */
    private fun tryNextStreamLine(reason: String): Boolean {
        val ch = current ?: return false
        app.api.streams(ch.id,
            onOk = { list ->
                val next = list.firstOrNull { it.id !in triedStreams }
                if (next == null) {
                    switchTip.text = "播放出错：$reason"
                    switchTip.visibility = View.VISIBLE
                    toastTip("这个台的线路都试过了，仍无法播放")
                    return@streams
                }
                toastTip("换线路重试：${next.label}")
                playChannel(ch, next.id)
            },
            onErr = {
                switchTip.text = "播放出错：$reason"
                switchTip.visibility = View.VISIBLE
            })
        return true
    }

    override fun onVideoSizeChanged(width: Int, height: Int) = Unit

    // ---------------------------------------------------------------- 生命周期

    override fun onResume() {
        super.onResume()
        player.resume()
        // 接管遥控指令：手机发来的按键直接走本页原有的按键逻辑
        app.remote.onCommand = { cmd -> main.post { RemoteKeys.handle(this, cmd) } }
        app.remote.connect()
    }

    override fun onPause() {
        super.onPause()
        player.pause()
        app.remote.onCommand = null
        // 跨端续播：离开时记下看到哪个台，别的设备打开就能"接着看"
        current?.let { app.api.saveProgress(it.id, 0) }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
    }

    /** 分组窄竖条点击：在所有分组之间循环 */
    /**
     * 浮层里在列之间移动焦点：分组竖条 <-> 频道列表 <-> 节目单。
     *
     * 参考实现就是这么操作的。分组竖条本身可点，焦点落上去用上下切分组，
     * 左右负责跨列，比「左右键都拿来切分组」合理。
     */
    private fun movePanelFocus(dir: Int) {
        when {
            grpList.hasFocus() && dir > 0 -> panelList.requestFocus()
            panelList.hasFocus() && dir < 0 -> grpList.requestFocus()
            panelList.hasFocus() && dir > 0 -> epgList.requestFocus()
            epgList.hasFocus() && dir < 0 -> panelList.requestFocus()
            else -> if (dir > 0) panelList.requestFocus() else grpList.requestFocus()
        }
    }


    /** 按当前源+分组过滤，刷到频道列表 */
    private fun applyChannelFilter() {
        var list = if (selSource == 0L) allChannels else allChannels.filter { it.sourceId == selSource }
        list = when {
            selGroup == "收藏" -> list.filter { favoriteIds.contains(it.id) }
            selGroup.isNotEmpty() -> list.filter { it.group == selGroup }
            else -> list
        }
        // 必须写回 channels：切台、自动播放、上下一台全靠它。
        // 改成四列浮层时这里漏了赋值，导致 channels 永远为空、自动播放从不触发，
        // 表现就是黑屏转圈、服务端收不到 /tv/play。
        channels = list
        panelAdapter.submit(list, emptyMap())
        panelTitle.text = buildString {
            append(if (selSource == 0L) "全部频道" else (srcNames[selSource] ?: "频道"))
            if (selGroup.isNotEmpty()) append(" · ").append(selGroup)
            append("  ").append(list.size).append(" 个")
        }
    }

    // ---------------------------------------------------------------- 右侧设置菜单

    private fun toggleSettingsMenu() {
        if (menuVisible) closeSettingsMenu() else {
            menuVisible = true
            settingsMenu.visibility = android.view.View.VISIBLE
            hideOsd()
            // 必须 post 到下一帧：刚设成 VISIBLE 时还没布局，
            // 立刻 requestFocus() 会失败（返回 false），焦点留在画面上。
            settingsMenu.post {
                settingsMenu.findViewById<TextView>(R.id.miLine)?.requestFocus()
            }
        }
    }

    private fun closeSettingsMenu() {
        menuVisible = false
        settingsMenu.visibility = android.view.View.GONE
    }

    private fun cycleOf(cur: String, all: List<String>): String {
        val i = all.indexOf(cur)
        return all[(i + 1).mod(all.size)]
    }

    private fun toastTip(msg: String) {
        switchTip.text = msg
        switchTip.visibility = android.view.View.VISIBLE
        main.postDelayed({ switchTip.visibility = android.view.View.GONE }, 2000)
    }

    /** 线路选择：把当前频道的线路列出来，选中就切过去 */
    private fun openLinePicker() {
        val ch = current ?: return
        closeSettingsMenu()
        app.api.streams(ch.id, onOk = { list ->
            if (list.isEmpty()) { toastTip("这个台只有一条线路"); return@streams }
            val names = list.map { "${it.quality.ifBlank { "线路" }} ${it.latencyMs}ms" }
            android.app.AlertDialog.Builder(this)
                .setTitle("选择线路（${list.size} 条）")
                .setItems(names.toTypedArray()) { _, i ->
                    playChannel(ch, list[i].id)
                }
                .show()
        }, onErr = { toastTip("取线路失败：$it") })
    }
}
