package com.litv.tv.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
import com.litv.tv.data.LastChannelResolver
import com.litv.tv.data.LineProbe
import com.litv.tv.data.PlayCache
import com.litv.tv.data.PlaybackErrorCard
import com.litv.tv.data.PlayCacheStore
import com.litv.tv.data.RelayDecision
import com.litv.tv.data.TvStream
import com.litv.tv.player.EngineKind
import com.litv.tv.player.PlayRequestGate
import com.litv.tv.player.PlayerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放页。整个 TV 端体验的核心。
 *
 * 交互设计参考了主流 IPTV 客户端的通行做法：
 *
 *   面板收起时：↑↓ 换台 · ← 呼出频道列表 · → 呼出设置 · OK 信息条
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
    /** 换台时冻结上一帧用的快照层（布局里一直有，但之前代码从没用过）。 */
    private lateinit var snapshot: android.widget.ImageView
    private var videoSurface: android.view.Surface? = null

    /** 换台转场幕布（见 [startCurtain]）。 */
    private lateinit var curtain: View

    /** 本次转场的方向：+1 从下往上扫，-1 从上往下扫。 */
    private var curtainDir = 1f
    private lateinit var loading: ProgressBar
    /** 加载圈下面的说明文字；与 loading 同步显示/隐藏。 */
    private var loadingText: TextView? = null
    /** 是否真的渲染过画面。没渲染过就冻结，冻到的可能是黑屏/转圈。 */
    private var hasRenderedFrame = false
    /** 复用的快照位图。每次换台新建一张 1920x1080 的位图 = 8.3MB，
     *  实测导致每次换台都有 6MB LOS 分配、GC 最长 2 秒，播放就会顿一下。 */
    private var snapshotBitmap: android.graphics.Bitmap? = null
    /** 冷启动开屏层；首帧到达后淡出。 */
    private var splashOverlay: View? = null
    /** 开屏层上的阶段文案。 */
    private var splashStage: TextView? = null
    private lateinit var switchTip: TextView
    private lateinit var osd: LinearLayout
    private lateinit var osdName: TextView
    private lateinit var osdNow: TextView
    private lateinit var osdNo: TextView
    private lateinit var osdNext: TextView
    private lateinit var osdLogo: ImageView
    /** 引擎标签已并入 OSD 角标，这个 View 不再存在；保留字段只为兼容旧调用点。 */
    /** OSD 角标容器：引擎 / 线路，动态填。 */
    private lateinit var osdBadges: android.widget.LinearLayout
    /** OSD 右下角码率。 */
    private lateinit var osdRate: TextView
    private lateinit var osdProgress: ProgressBar
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    /** 源 id -> 源名，面板标题要用。 */
    private val srcNames = HashMap<Long, String>()
    private lateinit var panelList: RecyclerView
    private lateinit var grpList: RecyclerView
    private val grpAdapter = GroupAdapter()
    /** 当前选中的源 id，0 = 全部 */
    private var selSource = 0L
    /** 当前选中的分组，空 = 全部 */
    private var selGroup = ""
    private lateinit var numberTip: TextView
    private lateinit var settingsMenu: android.view.View
    /** 二级面板：从一级菜单左侧展开，不遮挡画面。 */
    private var subMenu: android.view.View? = null
    private var subMenuTitle: android.widget.TextView? = null
    private var subMenuRow: android.widget.LinearLayout? = null
    /** 当前展开的二级属于哪个一级项，右键从这里退回时要用。 */
    private var subMenuOwner: TextView? = null
    private var menuVisible = false
    private lateinit var epgPanel: LinearLayout
    /** 节目单收起时的竖排把手。 */
    private lateinit var epgHandle: TextView
    /** 节目单收起时要把这块留白让出来，否则把手离频道列太远。 */
    private lateinit var panelRightGap: View
    /** 节目单左侧的日期选择器。 */
    private lateinit var epgDayList: androidx.recyclerview.widget.RecyclerView
    private val epgDayAdapter = EpgDayAdapter()
    /** 当前选中哪一天（默认今天）。 */
    private var epgDayEpoch: Long = 0L
    /** EPG 是在哪一天加载的（epochDay）。跨天要强制重下，否则节目单永远是昨天的。 */
    private var epgLoadedEpochDay: Long = 0L
    /** 服务端 EPG 结果缓存（channelId -> 节目）。换台来回切时不重复请求+解析：
     *  实测每次换台 3 个请求 + Gson 解析会产生约 18 万个小对象、GC 停顿 10ms+。 */
    private val serverEpgCache = HashMap<Long, MutableList<EpgProgram>>()
    /** 缓存对应的"哪一天"，跨天要失效。 */
    private var serverEpgCacheDay: Long = 0L
    /** bootstrap 下发的 xmltv 地址，跨天重下时要用。 */
    private var lastEpgUrl: String = ""
    /** 本机已预约的节目起点，用来把「预约」就地变成「已预约」。 */
    private val reservedStarts = mutableSetOf<Long>()
    private lateinit var epgTitle: TextView
    private lateinit var epgList: RecyclerView

    /** C1：收藏按钮（面板里可聚焦）与列表空态引导 */
    private lateinit var favBtn: TextView
    private lateinit var panelEmpty: TextView

    /** 本台观看起始时刻（毫秒）。QoE 的 watch_ms 与"稳定播放归零"要用。 */
    private var watchStartAt = 0L
    /** 服务端下发的封装类型：ts | hls | remux（QoE 的 path，**不是** direct/relay）。 */
    private var currentPlayKind = ""
    /** 离开页面时已经报过 stop，避免 onDestroy 重复上报 */
    private var reportedStop = false

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

    /**
     * 「每个频道此刻在播什么」，来自 bootstrap 的 `now`。
     *
     * 以前这里没有这个字段：`ChannelAdapter` 明明支持显示当前节目
     * （`holder.now` + 进度条），但两处 `submit(...)` 都传的是 `emptyMap()`，
     * 于是列表每行右边永远空着。数据一直在响应里，只是没接线。
     */
    private val nowMap = HashMap<Long, com.litv.tv.data.NowPlaying>()
    private var groups: List<String> = emptyList()
    private var groupIdx = 0
    private var index = 0
    private var current: Channel? = null
    private var programs: List<EpgProgram> = emptyList()

    private val main = Handler(Looper.getMainLooper())
    private val numberBuffer = StringBuilder()
    private var osdVisible = false
    /** OSD 层级：0 隐藏 / 1 台号+台名+收藏态 / 2 再加排障信息。 */
    private var osdLevel = 0
    private var panelVisible = false
    private var epgVisible = false
    /** 起播计时起点（毫秒），0 表示已上报过 */
    private var startAt = 0L

    /**
     * 换台请求的代际闸门。
     *
     * 连续换台时 /tv/play 的响应**不保证按序返回**：晚到的旧响应会把播放器
     * 切回它自己的频道，表现为"台名是 C、画面在放 A"；旧请求的失败提示
     * 也会盖掉新台的信息。详见 PlayRequestGate 的注释。
     */
    private val playGate = PlayRequestGate()

    // 长按判定：800ms（逆向实测值，不是 320ms）
    private val longPressRunnable = Runnable { onLongPressOk() }
    private var okDownTime = 0L
    /** 上一次按返回的时间，用于「再按一次退出」 */
    private var lastBackAt = 0L
    /** 首次进入还没自动播放过 */
    private var autoPlayPending = true
    /** 自动播放已决定、但播放器还没创建好时先存在这里，等 Surface 就绪再播。
     *  否则 playChannel 会因为 player 未初始化而静默失效，白等 2~3 秒。 */
    private var pendingAutoPlayChannel: Channel? = null
    /** 频道列里当前焦点对应的台：节目单跟着它，不跟着正在播的台。 */
    private var focusEpgChannelId: Long = 0L
    /** programs 里装的是哪个台的节目：用来判断要不要重取。 */
    private var epgProgramsChannelId: Long = 0L
    /** 面板频道列里最后一次聚焦的台。回列表/展开节目单都以它为准。 */
    private var panelLastFocusId: Long = 0L
    /** 进入节目单之前，频道列焦点所在的位置。回来时原样还给它。 */
    private var panelFocusPosBeforeEpg: Int = -1
    /** 进入节目单时频道列的滚动状态：第一个可见位置 + 它的像素偏移。 */
    private var panelFirstVisiblePos: Int = -1
    private var panelFirstVisibleOffset: Int = 0
    /** 进节目单时，焦点那一项的 view.top（像素）。回来按这个还���，视觉上完全不动。 */
    private var panelFocusedTopBeforeEpg: Int = 0
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
        app.api.onAuthExpired = { onAuthExpired() }

        bindViews()
        loadChannels()

        player = PlayerManager(this, app.prefs).also { it.callback = this }

        // 直连第一个内核就失败：**不再自动改走中转**（用户口径：中转默认不用）。
        // 仍然立刻放弃后面几个内核——直连失败基本都是网络层问题（源站在这个网络里
        // 不可达 / DNS 污染 / SNI 被重置），换内核修不好。
        player.onDirectFailed = { reason ->
            main.post {
                // 记住这个台直连不通：会话内再切回来时立刻进错误层并提示可以手动中转，
                // 不再让它每次都先撞一次 TLS 握手失败（那个往返在低端盒子上就是一两秒）。
                current?.let { directBrokenChannels += it.id }
                Log.w(TAG, "直连失败: $reason")
                handleDirectFailure(reason)
            }
        }

        player.playerView = playerView
        snapshot = findViewById(R.id.snapshot)
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
                    // 面板/设置菜单开着时，点空白处什么也不该发生。
                    // 原来只判了 panelVisible，漏了 menuVisible ——
                    // 设置开着的时候点一下空白（比如二级面板右侧那片），
                    // 触摸穿透到 root，直接把 OSD 呼出来了。
                    if (panelVisible || menuVisible) return false
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
                    player.onSurfaceDestroyed()
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
                    val pending = pendingAutoPlayChannel
                    if (pending != null) {
                        pendingAutoPlayChannel = null
                        Log.i(TAG, "Surface 就绪，补播挂起的台：${pending.name}")
                        playChannel(pending)
                    } else if (!player.isPlaying) {
                        current?.let { playChannel(it) }
                    }
                }

                override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {}

                override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                    // 必须通知内核松手：否则 libVLC 会拿着已销毁的 Surface
                    // 在自己的线程里抛 IllegalStateException，把播放页整个崩掉。
                    Log.i(TAG, "Surface 销毁，通知播放器断开")
                    player.onSurfaceDestroyed()
                }
            })
        }
    }

    private fun bindViews() {
        surface = findViewById(R.id.surface)
        surfaceTex = findViewById(R.id.surfaceTex)
        playerView = findViewById(R.id.playerView)
        curtain = findViewById(R.id.curtain)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        splashOverlay = findViewById(R.id.splashOverlay)
        // 12 秒撤不掉就强撤：起播卡住时不能让开屏层永远盖着
        main.postDelayed(splashTimeout, 12_000)
        // 每小时查一次跨天
        main.postDelayed(dayRolloverTick, 60 * 60 * 1000L)
        splashStage = findViewById(R.id.splashStage)
        switchTip = findViewById(R.id.switchTip)
        osd = findViewById(R.id.osd)
        osdName = findViewById(R.id.osdName)
        osdNow = findViewById(R.id.osdNow)
        osdNo = findViewById(R.id.osdNo)
        osdNext = findViewById(R.id.osdNext)
        osdLogo = findViewById(R.id.osdLogo)
        osdBadges = findViewById(R.id.osdBadges)
        osdRate = findViewById(R.id.osdRate)
        osdProgress = findViewById(R.id.osdProgress)
        panel = findViewById(R.id.channelPanel)
        panelTitle = findViewById(R.id.panelTitle)
        panelList = findViewById(R.id.panelList)
        panelEmpty = findViewById(R.id.panelEmpty)
        favBtn = findViewById(R.id.favBtn)
        // C1：收藏必须有**明确动作**。之前 Api.favorite 在本页零调用，
        // 「收藏」分组又只在非空时出现 —— 新用户既看不到也没法收藏。
        favBtn.setOnClickListener { toggleFavoriteOnFocused() }
        numberTip = findViewById(R.id.numberTip)
        settingsMenu = findViewById(R.id.settingsMenu)
        subMenu = findViewById(R.id.subMenu)
        subMenuTitle = findViewById(R.id.subMenuTitle)
        subMenuRow = findViewById(R.id.subMenuRow)
        // 右侧设置菜单：一级 9 项，二级**焦点移过去就展开**（对齐参考实现）。
        //
        // 之前要按 OK 才展开，和参考实现不一致：光标在菜单里上下移动时，
        // 左边应该实时显示当前项的选项，不然用户得先"点进去"才知道有什么可调。
        val specs = mapOf(
            R.id.miAspect to SubSpec("画面比例", listOf("自动", "16:9", "4:3", "铺满"),
                { listOf("auto", "16:9", "4:3", "fill").indexOf(app.prefs.aspect).coerceAtLeast(0) }) { i ->
                app.prefs.aspect = listOf("auto", "16:9", "4:3", "fill")[i]
                toastTip("画面比例：" + listOf("自动", "16:9", "4:3", "铺满")[i])
            },
            R.id.miDecode to SubSpec("解码方式", listOf("自动", "硬件解码", "软件解码"),
                { listOf("auto", "hard", "soft").indexOf(app.prefs.decode).coerceAtLeast(0) }) { i ->
                app.prefs.decode = listOf("auto", "hard", "soft")[i]
                toastTip("解码方式：" + listOf("自动", "硬件解码", "软件解码")[i])
            },
            R.id.miTimeout to SubSpec("超时换源", listOf("1 秒", "2 秒", "3 秒", "5 秒"),
                { listOf(1000, 2000, 3000, 5000).indexOf(app.prefs.bufferMs).coerceAtLeast(0) }) { i ->
                app.prefs.bufferMs = listOf(1000, 2000, 3000, 5000)[i]
                toastTip("缓冲：" + listOf("1 秒", "2 秒", "3 秒", "5 秒")[i])
            },
            R.id.miList to SubSpec("列表配置",
                com.litv.tv.data.UiScaleOptions.all.map { it.label },
                { com.litv.tv.data.UiScaleOptions.keys.indexOf(app.prefs.uiScale).coerceAtLeast(0) }) { i ->
                app.prefs.uiScale = com.litv.tv.data.UiScaleOptions.keys[i]
                toastTip("界面缩放：" + com.litv.tv.data.UiScaleOptions.all[i].label + "（重开列表生效）")
            },
        )
        // 焦点落到有选项的项上就展开；移到没有选项的项上则收起
        listOf(R.id.miLine, R.id.miAspect, R.id.miDecode, R.id.miTimeout,
                R.id.miList, R.id.miDisplay, R.id.miPrefs, R.id.miOther)
            .forEach { id ->
                findViewById<TextView>(id).setOnFocusChangeListener { _, has ->
                    if (!has) return@setOnFocusChangeListener
                    // 焦点到就展开：参考实现里光标移到哪项，左边就显示哪项的选项，
                    // 不用先按 OK 进去看。
                    subMenuOwner = findViewById(id)
                    // 焦点回到一级菜单，说明用户离开了设置行——立刻停用，
                    // 否则下一次下移又会飘进左边的行里。
                    if (rowsActive) setRowsFocusable(false)
                    val spec = specs[id]
                    when {
                        spec != null -> openSubMenu(spec, findViewById(id))
                        id == R.id.miDisplay -> openInlineRows("play")
                        id == R.id.miPrefs -> openInlineRows("interact")
                        id == R.id.miOther -> openInlineRows("device")
                        id == R.id.miLine -> openLinePicker()    // 和别的项一致：聚焦就展开
                        else -> closeSubMenu(keepFocus = true)
                    }
                }
            }
        // OK：焦点进二级；没有二级的直接执行
        // 左键进入左边的选项列（选项在左边，用左键最直觉）。
        // OK 留给"在选项里确认"，不再兼做进入。
        val enterable = specs.keys + setOf(R.id.miDisplay, R.id.miPrefs, R.id.miOther, R.id.miLine)
        enterable.forEach { id ->
            findViewById<TextView>(id).setOnKeyListener { _, code, ev ->
                if (code != android.view.KeyEvent.KEYCODE_DPAD_LEFT) return@setOnKeyListener false
                // ★ DOWN 就要执行并消费：只处理 UP 的话，DOWN 会先被 Activity
                // 的按键分发拿走（左键=开频道面板），UP 根本到不了这里。
                if (ev.action == android.view.KeyEvent.ACTION_DOWN) enterSub()
                true
            }
        }
        // 触摸支持：spec 类项（画面比例/解码方式/超时换源/列表配置）的二级面板
        // 原来只在**聚焦**时展开，而触摸模式下点击不会让 View 获得焦点，
        // 于是手指点上去毫无反应——这就是"触摸没接上"。
        specs.forEach { (id, spec) ->
            findViewById<TextView>(id).setOnClickListener {
                subMenuOwner = findViewById(id)
                openSubMenu(spec, findViewById(id))
                enterSub()
            }
        }
        // 线路选择要拉接口，不能跟着焦点走（每次移动都请求一次），保持点击触发
        findViewById<TextView>(R.id.miLine).setOnClickListener { openLinePicker() }
        findViewById<TextView>(R.id.miDisplay).setOnClickListener {
            openInlineRows("play", findViewById(R.id.miDisplay))
            setRowsFocusable(true); focusFirstRow()
        }
        findViewById<TextView>(R.id.miPrefs).setOnClickListener {
            openInlineRows("interact", findViewById(R.id.miPrefs))
            setRowsFocusable(true); focusFirstRow()
        }
        findViewById<TextView>(R.id.miOther).setOnClickListener {
            openInlineRows("device", findViewById(R.id.miOther))
            setRowsFocusable(true); focusFirstRow()
        }
        epgPanel = findViewById(R.id.epgPanel)
        epgHandle = findViewById(R.id.epgHandle)
        panelRightGap = findViewById(R.id.panelRightGap)

        epgDayList = findViewById(R.id.epgDayList)
        epgDayList.layoutManager = LinearLayoutManager(this)
        epgDayList.adapter = epgDayAdapter
        rebuildDayList()
        epgDayAdapter.onPick = { day ->
            epgDayEpoch = day.epochDay
            refreshEpgColumn()
            // 焦点留在刚点的日期上：之前这里直接 epgList.requestFocus()，
            // 用户刚选完日期焦点就跑到右边节目列表，感觉是"乱跳"。
            focusDayList()
        }
        // 把手：聚焦后按 OK 展开节目单（参考实现里节目单默认是收起的一列）
        epgHandle.isFocusable = true
        epgHandle.isClickable = true
        epgHandle.setOnClickListener { openEpg() }
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
            setSplashStage("正在连接服务器…")
            app.api.bootstrap(onOk = { b ->
                // 写缓存：不写的话下次冷启动还要等一次网络 bootstrap
                // （实测那是启动耗时里最大的 6.8 秒）
                runCatching { app.prefs.cachedBootstrap = Gson().toJson(b) }
                applyBootstrap(b)
            }, onErr = {})
        }
        panelAdapter = ChannelAdapter(
            logoMaxPx = app.tier.logoMaxPx,
            onClick = { ch ->
                closePanel()
                val i = channels.indexOfFirst { it.id == ch.id }
                if (i >= 0) index = i
                if (ch.id == current?.id && player.isPlaying) {
                    Log.i(TAG, "选中当前正在播的台，跳过重播：${ch.name}")
                } else {
                    playChannel(ch)
                }
                // 面板里换台也要给一次 OSD 反馈：↑↓ 换台和数字键跳台都会 showOsd()，
                // 只有这条路漏了——面板一关就什么都不显示，用户不知道切到了哪个台。
                showOsd()
            },
            onLongClick = { ch -> toggleFavorite(ch) },
            // 焦点移到哪个台，右侧节目单就显示哪个台的节目（不是正在播的那个）
            onFocus = { ch ->
                panelLastFocusId = ch.id
                focusEpgChannelId = ch.id
                if (epgVisible) refreshEpg(ch.id)
            },
        )
        panelList.layoutManager = LinearLayoutManager(this)
        panelList.adapter = panelAdapter
        // 界面缩放档位在设置里改完立刻生效（下次进面板就能看到）
        panelAdapter.setUiScale(app.prefs.uiScale)

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
        submitPanelList(channels)
        panelTitle.text = "频道列表（${channels.size}）"

        epgAdapter = EpgAdapter()
        epgList.layoutManager = LinearLayoutManager(this)
        epgList.adapter = epgAdapter
        // C3：点节目看详情。之前这个回调根本没接线，点节目毫无反应。
        epgAdapter.onProgramClick = { p -> onProgramAction(p) }
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


    /**
     * 点节目单里的某一档：**就地执行**，不弹对话框。
     *
     * 原来的做法是弹一个 AlertDialog（标题 + 时间 + 简介 + 三个按钮）。
     * 电视上弹层会整块盖住画面，用户按 OK 只是想"回看这一档"或"预约"，
     * 却被推进一个需要再按两次返回的模态框——这就是"太突兀"的来源。
     *
     * 现在：正在播 -> 提示；已播完 -> 直接回看；未播 -> 直接预约（再按取消）。
     */
    private fun onProgramAction(p: com.litv.tv.data.EpgProgram) {
        val ch = current ?: return
        val now = System.currentTimeMillis() / 1000
        // 记下当前焦点在节目列的位置：预约后要重新提交列表（星标/已预约要刷新），
        // 提交会清掉焦点，不还回去的话焦点会跳出节目列、落到日期列上。
        val keepPos = run {
            val f = epgList.focusedChild
            if (f != null) epgList.getChildAdapterPosition(f) else epgAdapter.currentPosition()
        }
        when {
            p.startAt <= now && p.endAt > now -> toastTip("正在播放：" + p.title)

            p.endAt in 1 until now -> {
                // 已播完：直接回看
                openTimeshiftFor(ch)
            }

            else -> {
                // 未播：预约 / 取消预约，就地切换
                if (reservedStarts.contains(p.startAt)) {
                    reservedStarts.remove(p.startAt)
                    // 服务端没有删除提醒的接口，这里只做本机标记撤销，
                    // 已经推送的提醒不受影响——如实提示，不假装已删。
                    toastTip("已取消本机标记（服务端已排的提醒不会撤回）")
                } else {
                    app.api.createReminder(ch.id, p.title, p.startAt,
                        onOk = {
                            reservedStarts.add(p.startAt)
                            toastTip("已预约：" + p.title)
                            refreshEpgColumn()
                            epgList.post {
                                val vh = epgList.findViewHolderForAdapterPosition(keepPos)
                                if (vh != null) vh.itemView.requestFocus() else epgList.requestFocus()
                            }
                        },
                        onErr = { toastTip("预约失败：$it") })
                }
            }
        }
    }


    /** 存全量频道 + 分组名 + 收藏，供「列表内左右键切分组」用。 */
    private fun applyBootstrap(b: Bootstrap) {
        // 记下服务端当前的频道指纹作为基线：之后心跳里发现不一样才重拉，
        // 避免每次心跳都白拉一次列表。
        if (b.channelsVersion.isNotBlank()) knownChannelsVersion = b.channelsVersion
        lastListRefreshAt = System.currentTimeMillis()
        allChannels = b.channels
        favoriteIds.clear()
        favoriteIds.addAll(b.favorites)
        // 当前节目：列表行右边显示"正在播什么"，数据本来就在响应里
        nowMap.clear()
        b.now.forEach { nowMap[it.channelId] = it }

        // 节目单：服务端只下发 xmltv 地址，客户端自己下载解析（不占 D1 额度）。
        // 解析在后台线程跑，完成后拿当前频道刷一次 OSD/节目单列。
        if (b.epgUrl.isNotBlank()) {
            lastEpgUrl = b.epgUrl
            epgLoadedEpochDay = todayEpochDay()
            // ★ 推迟解析：xmltv 有 3.7 万条，解析期间 GC 每 2 秒一次、
            // 每次释放 17~25 万对象，会把刚开始的播放抖成幻灯片。
            // 解析是后台线程，但 GC 是全进程的——只能错开时间。
            // 8 秒后再拉，此时首帧早就出来了。
            // 有解析缓存时读盘只要几百毫秒，不必等 8 秒；
            // 没缓存要解析 3.7 万条 XML，解析期间的 GC 会把起播抖成幻灯片，
            // 所以错开到首帧之后再拉。
            val epgDelay = if (app.epg.hasParsedCache()) 1_500L else 8_000L
            main.postDelayed({
                app.epg.ensure(b.epgUrl) { ok ->
                    if (ok) main.post { current?.let { refreshEpg(it.id) } }
                }
            }, epgDelay)
        }
        groups = allChannels.map { it.group }.filter { it.isNotBlank() }.distinct()

        // C2：先从**全量**里定位"上次看的台"，再决定默认分组。
        //
        // 顺序不能反：先设 selGroup → applyGroupFilter() 重建 channels →
        // 再在 channels 里取 index。反过来先算 index 再改分组，index 会指到别的台上。
        //
        // 另外：从桌面正常启动时 pendingChannelId 是 0（只有通知/外部跳转才带），
        // 所以必须回落到 app.prefs.lastChannelId —— 这个坑以前踩过（永远播第一个台）。
        val wantId = if (pendingChannelId > 0) pendingChannelId else app.prefs.lastChannelId
        val hit = LastChannelResolver.resolve(
            allChannels, wantId, app.prefs.lastChannelTvgId, app.prefs.lastChannelName,
        )
        val want = hit?.channel
        if (want != null) {
            Log.i(TAG, "恢复上次频道：${want.name}（依据=${hit.via} id=${want.id}）")
        } else {
            Log.i(TAG, "上次频道找不到（id=$wantId tvg=${app.prefs.lastChannelTvgId} " +
                "name=${app.prefs.lastChannelName}），回落到第一个台")
        }

        // 恢复"上次所在分组"。存的是**分组名**，不是索引 —— 索引会随分组数量变化而错位
        // （C1 让收藏行常显之后，所有索引整体后移一位，老用户升级就会落到隔壁分组）。
        // 上次那个台还在就按它的分组；彻底不在了才退回 lastGroup。
        val wantGroup = want?.group?.takeIf { it.isNotBlank() } ?: app.prefs.lastGroup
        selGroup = if (wantGroup.isNotBlank() && groups.contains(wantGroup)) wantGroup else ""
        applyGroupFilter()
        // 分组名在上游被改掉时过滤结果可能为空：退回「全部」，否则界面会是一片空白。
        if (channels.isEmpty() && selGroup.isNotEmpty()) {
            Log.i(TAG, "分组 $selGroup 已不存在或为空，退回全部频道")
            selGroup = ""
            applyGroupFilter()
        }

        // 冷启动走缓存时，applyBootstrap 在 panelAdapter 创建之前执行，
        // 过滤后的 channels 还是空的 -> 下面的自动播放分支直接跳过，
        // 结果要等第二次（网络）bootstrap 才起播，白等好几秒。
        // 这里直接算一遍，不依赖任何视图。
        if (channels.isEmpty()) {
            channels = when {
                selGroup == "收藏" -> allChannels.filter { favoriteIds.contains(it.id) }
                selGroup.isNotEmpty() -> allChannels.filter { it.group == selGroup }
                else -> allChannels
            }
        }
        Log.i(TAG, "bootstrap 完成: 频道=${allChannels.size} 过滤后=${channels.size} 待播=$pendingChannelId 待自动播放=$autoPlayPending")
        if (autoPlayPending && channels.isNotEmpty()) {
            autoPlayPending = false
            // 想播的台可能不在当前分组里（比如上次在 A 组、这次恢复到了 B 组）：
            // 这时在 channels 里找不到就退到确定性排序的第一个台，而不是 channels[0] 的偶然顺序。
            val idx = channels.indexOfFirst { it.id == (want?.id ?: -1L) }
            val pos = if (idx >= 0) idx else 0
            index = pos
            Log.i(TAG, "开始自动播放: ${channels[pos].name}（想要=${want?.id ?: 0} 命中=${idx >= 0} playerReady=${::player.isInitialized}）")
            // 让用户一眼看到自己的台在哪：滚动到它 + 打上"正在播"标记
            if (channels.isNotEmpty()) panelList.scrollToPosition(pos)
            // 注意：这里可能发生在 panelAdapter 创建之前（有本地缓存的冷启动路径），
            // 所以必须判 initialized，否则直接 UninitializedPropertyAccessException。
            if (::panelAdapter.isInitialized) panelAdapter.setPlayingId(channels[pos].id)
            // ★ loadChannels() 在 onCreate 里先于 PlayerManager 创建执行，
            // 这时直接 playChannel 会因为播放器不存在而失效（日志都没有）。
            // 先挂起，等 Surface 就绪再播。
            if (::player.isInitialized) playChannel(channels[pos])
            else pendingAutoPlayChannel = channels[pos]
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
        // C1：收藏行**始终显示**。
        // 以前是 if (favoriteIds.isNotEmpty()) 才加：全新设备上这一行根本不存在，
        // 用户既看不到「收藏」这个分类，也不知道有这个功能（反馈原话"没有收藏"）。
        rows.add(GroupAdapter.Row("收藏", favoriteIds.size))
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

    /**
     * 收藏行是否在分组索引里占位。
     *
     * C1 之后它**恒为 true**（收藏行常显）。`groupLabelText()` 与 `switchGroup()`
     * 里的 `groupIdx - 2 / - 1` 偏移都依赖这个值，所以只能改这一处定义，
     * 不要在别处硬编码偏移量。
     */
    private val hasFavGroup: Boolean get() = true

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

    /**
     * @param forceRebuild 强制重建播放器（跳过「缓存已起播就不要再 prepare」的短路）。
     *
     *   **失败重试必须传 true。** 起播时为了省一次网络往返，会先用本地缓存的地址
     *   直接 prepare（PlayCacheStore 一小时有效）；拿到新地址后为了不打断刚出的
     *   画面，会用 `if (!cacheUsable)` 跳过第二次 prepare。
     *
     *   但重试走的正是同一条路径：缓存还在 → cacheUsable 为 true → 那次
     *   player.play() 被跳过 → **重试等于什么都没做**，画面就一直卡着。
     *   换台反而能恢复，因为换了 channel id、缓存 miss、真的重建了播放器。
     *
     *   这类源的播放会话是**短时效**的（面板地址长期有效，但它 302 出去的上游
     *   m3u8 带时间戳），卡住就是上游会话过期；只有重新 prepare（重走一次 302
     *   换取新会话）才能恢复。
     */
    private fun playChannel(ch: Channel, streamId: Long = 0, forceRebuild: Boolean = false) {
        // 取走本次的转场方向并立刻清零：只有 switchBy 会写它，
        // 其余入口（点列表/数字键/恢复/重试）拿到的都是 0 = 只淡出。
        slideDir = pendingSlideDir
        pendingSlideDir = 0f
        Log.i(TAG, "playChannel: ${ch.name} streamId=$streamId rebuild=$forceRebuild slide=$slideDir direct=${app.prefs.directMode}")
        setSplashStage("正在起播…")
        // 立刻停掉上一次的起播计时器：下面要等签名地址（0.8~1.4 秒），
        // 这段时间上一次的"4 秒无数据"看门狗会把还没开始的新台判死。
        if (::player.isInitialized) player.cancelPendingStart()
        // 先把当前画面冻住：换台期间显示上一帧，不黑屏
        freezeFrame()
        // 取一个换台代际号。下面的回调只有在它仍然是"最后一次换台"时才允许生效。
        val seq = playGate.begin()
        current = ch        // 换到别的台就重置"已试线路"记录
        if (triedForChannel != ch.id) {
            triedStreams.clear()
            triedForChannel = ch.id
            relayRetried = false
            forceRelayOnce = false
            // 换台 = 重新给两次静默重试的机会（不同台/不同线路的失败互不牵连）
            directRetryLeft = DIRECT_RETRY_MAX
            playFailedPending = false
        }
        triedStreams += streamId
        currentStreamId = streamId
        app.prefs.lastChannelId = ch.id
        // C2：id 会随上游重建失效，把 tvg_id/名字一起存下来当兜底锚点
        app.prefs.lastChannelTvgId = ch.tvgId
        app.prefs.lastChannelName = ch.name
        // 分组按**名字**存（存索引会在分组增减后整体错位）
        app.prefs.lastGroup = ch.group
        // 起播计时：从按下换台到出第一帧，这是用户唯一能感知的"快不快"
        startAt = System.currentTimeMillis()
        // QoE：换台即重置本台观看窗口，stop 尚未上报
        watchStartAt = 0L
        reportedStop = false
        if (::panelAdapter.isInitialized) panelAdapter.setPlayingId(ch.id)

        // 换台转场：幕布从切换方向扫进来，新画面出来后由 endCurtain 擦走。
        //
        // 不再尝试抓旧画面：这台电视的视频走独立硬件层，
        // screencap / MediaProjection / PixelCopy 全都取不到帧（实测过），
        // 抓一次要白等 100ms 超时，反而拖慢换台，还会让幕布晚 100ms 才出现。
        startCurtain()

        // 加载圈只在「确实等太久」时出现，不是一换台就转。
        //
        // 用户反馈过两次「还是有加载中的圈圈」。根因是这里原来 300ms 就转 ——
        // 正常换台 1 秒内就出画面，圈还没来得及消失就先闪了一下，
        // 观感比"黑一下"更糟。
        // 现在改成 3 秒：绝大多数换台根本看不到圈，只有真的卡住才提示。
        main.removeCallbacks(showLoadingRunnable)
        main.postDelayed(showLoadingRunnable, LOADING_DELAY_MS)
        // 换台不再弹「正在切换…」：画面已经是冻结的上一帧，OSD 也换成了新台信息，
        // 再挂一行字是第三套提示。这行现在只留给错误/提醒类文案。
        if (switchTip.text?.startsWith("亮度") != true) switchTip.visibility = View.GONE
        osdName.text = osdNameText(ch)

        // 走直连还是中转。
        //
        // 只有两种情况强制中转：设置里选了 relay，或用户刚点了「用 NAS 中转试试」。
        // 以前还有第三个条件 `ch.id !in directBrokenChannels` —— 那等于"直连失败过的台
        // 以后一律走中转"，正好是用户要求取消的自动行为。现在那个集合只用来改进提示文案。
        val wantDirect = app.prefs.directMode != "relay" && !forceRelayOnce
        player.directMode = wantDirect

        // ---- 先用缓存地址起播，把 /tv/play 的往返从关键路径上摘掉 ----
        // 低端盒子上这一趟网络往返能占到起播时间的三分之一。
        // 只在「自动选线路 + 不是中转重试」时用，避免干扰已有的线路回退逻辑。
        val nowSec = System.currentTimeMillis() / 1000
        val cached = if (streamId == 0L && !forceRelayOnce) {
            PlayCacheStore.get(
                app.prefs, ch.id,
                PlayCacheStore.identity(app.prefs, streamId, if (forceRelayOnce) "relay" else "auto"),
                nowSec,
            )
        } else null
        val cacheUsable = cached != null && !forceRebuild
        if (cacheUsable) {
            Log.i(TAG, "缓存起播 ${ch.name}，签名还剩 ${cached!!.expires - nowSec}s")
            player.play(ch.id, app.api.absolute(cached.url), cached.headers, app.prefs.engine)
            reattachSurface()
        }

        app.api.play(ch.id, streamId,
            direct = wantDirect,
            onOk = { info ->
                // 过期回调整段丢弃：连按换台时旧响应可能晚到，
                // 让它继续往下走就会把播放器切回旧台（"台名 C、画面 A"）。
                if (!playGate.isCurrent(seq)) {
                    Log.i(TAG, "换台回调已过期(#$seq)，忽略：${ch.name}")
                    return@play
                }
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
                // QoE 的 path 是**封装类型**（ts/hls/remux），和 direct/relay 是两个正交维度，
                // 不能拿 currentPlayMode 冒充（那是链路，不是封装）。
                currentPlayKind = info.kind
                // 刷新缓存，供下次起播/换台直接命中
                PlayCacheStore.put(app.prefs, PlayCache(
                    // 必须和上面 get 用**同一个表达式**：identity 是请求侧身份（想要直连还是中转），
                    // 不是服务端最后返回的 mode。两边不一致的话缓存永远命不中。
                    identity = PlayCacheStore.identity(app.prefs, streamId, if (forceRelayOnce) "relay" else "auto"),
                    channelId = ch.id,
                    url = abs,
                    headers = headers,
                    mode = info.mode,
                    ua = info.ua,
                    expires = info.expires,
                ))
                // 播放 URL 可能含签名、设备标识或上游凭据，Release 日志不得记录。
                Log.i(TAG, "play 返回: mode=${info.mode} kind=${info.kind} 请求头=${headers.keys}")
                // 缓存已经起播过就不要再来一次：重复 prepare 会打断刚出的画面
                // （表现为"换台先亮一下又黑一下"）。手机首页早就这么做了
                // （PhoneHomeActivity 的 if (!cacheUsable)），电视端漏了。
                //
                // 但 forceRebuild（失败重试）时必须走到这里：否则缓存命中 →
                // 上面那次 prepare 被跳过 → 这里也被跳过 → 重试什么也没做。
                if (!cacheUsable || forceRebuild) {
                    player.play(ch.id, abs, headers, app.prefs.engine)
                    reattachSurface()
                }
                refreshEpg(ch.id)
                // 底部信息条：线路 x/y 与延迟
                refreshLineInfo(ch.id, info.streamId)
                renderInfoBar()
                app.api.heartbeat(ch.id, "playing") { hb -> onChannelsVersion(hb.channelsVersion) }
            },
            onErr = { msg ->
                // 过期请求的失败提示同样要丢：它会把新台的信息盖成旧台的错误。
                if (!playGate.isCurrent(seq)) return@play
                hideLoading()
                switchTip.text = "无法播放：$msg"
                main.postDelayed({ switchTip.visibility = View.GONE }, 3000)
            })
    }

    private fun switchBy(delta: Int) {
        if (channels.isEmpty()) return
        val n = channels.size
        val step = if (app.prefs.reverseChannelKeys) -delta else delta
        // 换台方向决定转场方向：切下一个台时旧画面向上滑走，上一个则向下。
        // 用 step 而不是 delta —— 用户开了「反转频道键」时 step 才是实际方向。
        pendingSlideDir = if (step > 0) 1f else -1f
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


    /**
     * 多天节目单：先取今天，再补明天/后天。
     *
     * 服务端 `day` 参数按天分页，某一天返回空就说明源里没有更远的数据，直接停。
     * 这样源只有今天时不会白发请求，有多天时也能一次补全。
     */
    private fun loadEpgMultiDay(channelId: Long, day: Int, acc: MutableList<EpgProgram>) {
        // 命中缓存：直接刷，不发请求
        val today = todayEpochDay()
        if (day == 0) {
            if (serverEpgCacheDay != today) { serverEpgCache.clear(); serverEpgCacheDay = today }
            serverEpgCache[channelId]?.let { cached ->
                if (cached.isNotEmpty()) { applyEpgList(cached, channelId); return }
            }
        }
        if (day > 1) { applyEpgList(acc, channelId); return }   // 只补今天+明天，源里通常没有第三天
        app.api.epg(channelId, day, onOk = { list ->
            if (list.isEmpty()) {
                applyEpgList(acc, channelId)
            } else {
                acc.addAll(list)
                loadEpgMultiDay(channelId, day + 1, acc)
            }
        }, onErr = { applyEpgList(acc, channelId) })
    }

    /** 合并去重后刷到界面。 */
    private fun applyEpgList(list: List<EpgProgram>, channelId: Long) {
        // ★ 缓存键必须用"被请求的那个台"，不能用 current（正在播的台）——
        // 否则 A 台的节目单会被存到 B 台名下，之后查 B 台拿到的就是 A 的数据。
        if (list.isEmpty()) {
            // 请求的台没有节目单：清掉旧数据，别让上一个台的节目继续挂在界面上
            if (epgProgramsChannelId == channelId) {
                programs = emptyList()
                renderOsdNow()
                if (panelVisible || epgVisible) refreshEpgColumn()
            }
            return
        }
        programs = list.distinctBy { it.startAt }.sortedBy { it.startAt }
        epgProgramsChannelId = channelId
        if (channelId > 0) serverEpgCache[channelId] = programs.toMutableList()
        renderOsdNow()
        if (panelVisible || epgVisible) refreshEpgColumn()
        main.post { renderInfoBar() }
    }

    private fun refreshEpg(channelId: Long) {
        epgProgramsChannelId = channelId
        // 优先用**本地解析的 xmltv**（服务端只下发地址，不存节目数据）。
        // 本地还没就绪时才回落到服务端接口——那条路在旧的灌库模式下仍然有效，
        // 也覆盖「客户端刚装上、xmltv 还没下完」的那几秒。
        val epgId = allChannels.firstOrNull { it.id == channelId }?.epgId
            ?: current?.takeIf { it.id == channelId }?.epgId
            ?: ""

        val local = app.epg.programsFor(epgId)
        if (local != null && local.isNotEmpty()) {
            programs = local.map {
                com.litv.tv.data.EpgProgram(
                    startAt = it.start,
                    endAt = it.end,
                    title = it.title,
                    description = it.description,
                    category = it.category,
                )
            }
            renderOsdNow()
            if (panelVisible) refreshEpgColumn()
            main.post { renderInfoBar() }

            // ★ 有本地数据就直接用，**不再按天回源**。
            // 之前每次换台都发 1~3 个请求 + Gson 解析，纯属浪费：
            // 节目单是启动时一次性下载的 xml/gz，换台只是换一个 channel 的切片。
            return
            return
        }

        loadEpgMultiDay(channelId, 0, mutableListOf())
    }


    /**
     * OSD 角标：只放**真的能拿到**的信息。
     *
     * 机顶盒 OSD 上那排 [FHD][立体声][IPV4] 需要播放器上报分辨率/声道数/连接协议，
     * 目前 PlayerManager 只暴露了引擎标签和线路数，所以只画这两个——
     * 编造一个 "FHD" 出来比不画更糟。
     */
    private fun renderOsdBadges() {
        if (!::osdBadges.isInitialized) return
        osdBadges.removeAllViews()

        val st = runCatching { player.stats() }.getOrNull()
        val items = mutableListOf<String>()

        // 分辨率：ExoPlayer / VLC / mpv 都能给到宽高，按实际值分档
        val h = st?.height ?: 0
        if (h > 0) {
            items.add(
                when {
                    h >= 1080 -> "FHD"
                    h >= 720 -> "HD"
                    else -> "SD"
                }
            )
        }
        // 音频编码：拿不到声道数，就报编码格式，不硬写"立体声"
        st?.audioCodec?.takeIf { it.isNotBlank() }?.let { items.add(friendlyAudio(it)) }
        // 引擎：libVLC 这种英文标签换成人话
        player.engineLabel().takeIf { it.isNotBlank() }?.let { label ->
            items.add(label.replace("libVLC", "VLC 兜底").replace("libmpv", "mpv"))
        }
        if (streamTotal > 0) items.add("线路 $streamIndex/$streamTotal")

        val padH = (8 * resources.displayMetrics.density).toInt()
        val padV = (3 * resources.displayMetrics.density).toInt()
        items.forEachIndexed { i, text ->
            val tv = TextView(this).apply {
                this.text = text
                textSize = 13f
                setPadding(padH, padV, padH, padV)
                setTextColor(getColor(R.color.text_primary))
                background = getDrawable(R.drawable.bg_osd_chip)
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            if (i > 0) lp.marginStart = padH
            osdBadges.addView(tv, lp)
        }

        // 码率：MB/s，和机顶盒 OSD 一个口径
        if (::osdRate.isInitialized) {
            val kbps = st?.bitrateKbps ?: 0
            osdRate.text = if (kbps > 0) String.format("%.2fMB/s", kbps / 8.0 / 1000.0) else ""
        }
    }

    /** 音频编码换成人话：机顶盒 OSD 上写的是"立体声/AAC"，不是 MP4A.40.2 这种。 */
    private fun friendlyAudio(codec: String): String = when {
        codec.startsWith("mp4a", ignoreCase = true) -> "AAC"
        codec.equals("ac-3", true) -> "AC3"
        codec.equals("ec-3", true) -> "E-AC3"
        codec.equals("ac-4", true) -> "AC4"
        codec.contains("opus", true) -> "Opus"
        codec.contains("av3a", true) -> "AV3A"
        else -> codec.uppercase()
    }

    private fun renderOsdNow() {
        val now = System.currentTimeMillis() / 1000
        val cur = programs.firstOrNull { it.startAt <= now && it.endAt > now }
        val next = programs.firstOrNull { it.startAt > now }
        if (cur != null) {
            // 带结束时间：只给开始时间的话，"现在播到哪儿了"还得自己算
            osdNow.text = "${fmt(cur.startAt)}-${fmt(cur.endAt)}  ${cur.title}"
            val span = (cur.endAt - cur.startAt).coerceAtLeast(1)
            val pct = (((now - cur.startAt).toFloat() / span) * 100).toInt().coerceIn(0, 100)
            osdProgress.progress = pct
            osdProgress.visibility = View.VISIBLE
        } else {
            // 区分两种"没有节目"：xmltv 还没下载解析完 vs 这个台确实没数据。
            // 之前一律写"暂无节目单"，而实测 xmltv 在模拟器上要 25 秒才就绪，
            // 这段时间用户看到的是误导性的"暂无节目单"。
            osdNow.text = if (app.epg.ready) getString(R.string.no_epg) else "节目单加载中…"
            osdProgress.visibility = View.GONE
        }
        osdNext.text = next?.let { "${fmt(it.startAt)}-${fmt(it.endAt)}  ${it.title}" } ?: ""
        renderOsdBadges()

        // 台号必须和频道面板里显示的**同一个号**。
        //
        // 面板用的是 ch.number（真实频道号），这里原来用的是"当前过滤列表里的序号"，
        // 于是同一个台：面板显示 36，OSD 显示 034 —— 两个号对不上。
        // 现在同样优先 ch.number，没有编号时才退回序号；仍补成三位（036 这种）。
        val c = current
        val no = when {
            c == null -> 0
            c.number > 0 -> c.number
            else -> channels.indexOfFirst { it.id == c.id } + 1
        }
        osdNo.text = if (no > 0) String.format("%03d", no) else ""
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
        // OSD 左侧台标卡：之前漏了这一步，卡片一直是空的
        if (::osdLogo.isInitialized) {
            osdLogo.setImageDrawable(null)
            if (ch.logo.isNotBlank() && ::panelAdapter.isInitialized) {
                panelAdapter.loadLogoFor(osdLogo, ch.logo)
            }
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
        // 开屏层自己就写着"正在起播…"，再叠一个转圈就是两套加载提示，
        // 观感像"转完又转"。开屏层在场时不再显示小转圈。
        if (splashOverlay?.visibility == View.VISIBLE) return@Runnable
        // 首帧还没出现过 = 这是冷启动的第一次起播。开屏层就是这一阶段的加载提示，
        // 再叠一个小转圈就成了"开屏撤掉 -> 又转圈 -> 才出画面"。
        if (!hasRenderedFrame) return@Runnable

        if (loading.visibility == View.VISIBLE) return@Runnable
        loading.alpha = 0f
        loading.visibility = View.VISIBLE
        loading.animate().alpha(1f).setDuration(180).start()
        loadingText?.apply {
            text = "正在起播…"
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(180).start()
        }
        // 起播总预算 29 秒。这么长时间只转圈不说话，用户会以为死机了。
        // 8 秒还没起来就换成明确的"慢"提示。
        main.removeCallbacks(loadingSlowRunnable)
        main.postDelayed(loadingSlowRunnable, 8_000)
    }

    /** 转圈超过 8 秒时换文案，让人知道还在等而不是卡死。 */
    private val loadingSlowRunnable = Runnable {
        if (!::loading.isInitialized || loading.visibility != View.VISIBLE) return@Runnable
        loadingText?.text = "起播较慢，正在重试…（按返回可取消）"
    }

    /** 起播成功/失败时调用：把加载圈淡出，而不是直接 GONE。 */

    /**
     * 冷启动开屏层：首帧到达或确定失败后淡出。
     *
     * 文案要跟着阶段走——只放一个转圈，用户不知道在等什么，
     * 超过十几秒就会以为卡死。见 setSplashStage。
     */

    // ------------------------------------------------------------ 二级设置项
    //
    // 这些原来是直接挂在一级菜单上的，现在由「画面 / 播放」二级菜单调起。
    // 比例和解码不再「按一下循环到下一个值」——改完不知道变成了什么。


    // ------------------------------------------------------------ 二级面板

    /**
     * 从一级菜单左侧展开选项面板。
     *
     * 对齐参考实现的做法：二级不弹居中 Dialog，而是贴在菜单左边展开，
     * 画面不被盖住，改完立刻能看到效果。选中即生效并收起。
     */
    /** 二级面板的内容定义。 */
    private data class SubSpec(
        val title: String,
        val labels: List<String>,
        /** 实时算当前值：写成固定 Int 的话，改完之后再打开还是旧的。 */
        val selected: () -> Int,
        val onPick: (Int) -> Unit,
    )

    /**
     * 从一级菜单左侧展开选项面板。
     *
     * 对齐参考实现：焦点移到某一项就展开它的选项，不用先按 OK 进去看。
     * 选中即生效并收起，画面不被盖住。
     */
    private fun openSubMenu(spec: SubSpec, owner: TextView? = null) {
        if (owner != null) subMenuOwner = owner
        val row = subMenuRow ?: return
        subMenuTitle?.visibility = android.view.View.GONE
        row.removeAllViews()
        // 竖排：参考实现的二级是一列选项，不是横向一条
        row.orientation = android.widget.LinearLayout.VERTICAL
        val padH = (16 * resources.displayMetrics.density).toInt()
        val padV = (10 * resources.displayMetrics.density).toInt()
        spec.labels.forEachIndexed { i, label ->
            val sel = spec.selected()
            currentSubSelected = sel
            val selected = i == sel
            val tv = TextView(this).apply {
                // 选中项带 › ‹：表示"当前值，可左右切换"（对齐参考实现）
                text = if (selected) "› $label ‹" else label
                // 13sp：选项框固定 130dp，「› 线路 1·未检测 ‹」14sp 仍会把右侧箭头挤到第二行
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                minHeight = (44 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                setTextColor(getColor(if (selected) android.R.color.white else R.color.text_primary))
                background = getDrawable(
                    if (selected) R.drawable.bg_select_solid else R.drawable.bg_setting_row
                )
                isFocusable = false   // 默认不参与遍历，按左键进入后才激活
                isClickable = true
                setOnClickListener {
                    spec.onPick(i)
                    exitSub()
                }
                setOnKeyListener { _, code, ev ->
                    if (code != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
                    if (ev.action == android.view.KeyEvent.ACTION_DOWN) exitSub()
                    true
                }

            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            if (i > 0) lp.topMargin = padV / 2
            row.addView(tv, lp)
        }
        subMenu?.visibility = android.view.View.VISIBLE
    }

    /** 按 OK：把焦点送进二级，落在当前值上。 */

    // ---------------------------------------------------- 内联设置行（不弹窗、不整页）

    /** 选项集合与设置页保持一致，避免两处各写一套。 */

    /**
     * 把设置项就地铺在二级面板里，**不弹窗、不跳整页**。
     *
     * 参考实现的做法：所有设置都在右侧这一列里展开，画面始终可见。
     * 弹窗和整页都会遮住画面，调画面相关的项时等于盲调。
     * 行的交互是「OK 就地切到下一个值」，电视上最省按键。
     */
    /** 当前展开的设置行属于哪一项；以及是否已经"进入"（可聚焦）。 */
    private var rowsOwner: TextView? = null
    private var rowsActive = false

    /** 左键进入左边的选项列。 */
    private fun enterSub() {
        val row = subMenuRow ?: return
        // 线路探测会真的去拉流测速，和正在播的流抢带宽。
        // 之前绑在"聚焦即展开"上，于是每次打开设置菜单（第一项就是线路选择）
        // 都会在后台发起 3 次请求 + N 次 Range 探测，播放就随机变卡。
        // 现在只在用户明确按左键进入线路选项时才测。
        if (subMenuOwner?.id == R.id.miLine) {
            current?.let { ch -> lineCache[ch.id]?.let { list -> probeLines(ch, list) } }
        }
        if (subMenu?.visibility != android.view.View.VISIBLE) return
        setRowsFocusable(true)
        val sel = currentSubSelected
        row.post {
            if (row.childCount == 0) return@post
            // 从 sel 开始找第一个**可聚焦**的子项。
            // 原来直接 getChildAt(sel)：像「其他设置」这种面板前几行是纯信息
            // （设备名称/服务器/渲染档位，isClickable=false 所以不可聚焦），
            // requestFocus 会静默失败，焦点留在右边一级菜单上，
            // 表现就是"最下面那项按左键进不去子菜单"。
            val start = sel.coerceIn(0, row.childCount - 1)
            for (i in 0 until row.childCount) {
                val v = row.getChildAt((start + i) % row.childCount)
                if (v.isFocusable) { v.requestFocus(); return@post }
            }
            row.getChildAt(start)?.requestFocus()
        }
    }

    /** 从选项列退回一级菜单。 */
    private fun exitSub() {
        val owner = subMenuOwner
        setRowsFocusable(false)
        owner?.requestFocus()
    }

    /** 当前二级的选中下标（进入时用来定位焦点）。 */
    private var currentSubSelected = 0

    /** 激活/停用左侧设置行/选项的焦点。未激活时不参与方向键遍历，避免焦点乱飘。 */
    private fun setRowsFocusable(active: Boolean) {
        rowsActive = active
        val row = subMenuRow ?: return
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            v.isFocusable = active && v.isClickable
        }
        // 在行内按上键要回到"打开这组的那一项"，而不是菜单第一项
        if (active) {
            val ownerId = rowsOwner?.id ?: android.view.View.NO_ID
            for (i in 0 until row.childCount) {
                val v = row.getChildAt(i)
                if (v.isFocusable) { v.nextFocusUpId = ownerId; break }
            }
        }
    }

    // 选项集合：与设置页保持一致，避免两处各写一套
    private val rowEngines = listOf("auto", "exo", "vlc", "mpv", "avs3")
    private val rowBuffers = listOf(2000, 3000, 5000, 10000, 15000)
    private val rowOffsets = listOf(3000, 6000, 10000, 20000, 30000)
    private val rowNumTimeouts = listOf(1500, 2000, 2500, 3000)

    private fun openInlineRows(group: String, owner: TextView? = null) {
        if (owner != null) rowsOwner = owner
        val row = subMenuRow ?: return
        row.removeAllViews()
        row.orientation = android.widget.LinearLayout.VERTICAL
        when (group) {
            "play" -> {
                subMenuTitle?.visibility = android.view.View.GONE
                cycleRow(row, "播放内核", rowEngines, app.prefs.engine) { v -> app.prefs.engine = v }
                cycleRow(row, "解码方式", listOf("auto", "hard", "soft"), app.prefs.decode) { v -> app.prefs.decode = v }
                cycleRow(row, "缓冲", rowBuffers.map { it.toString() }, app.prefs.bufferMs.toString()) { v ->
                    app.prefs.bufferMs = v.toIntOrNull() ?: 2000
                }
                cycleRow(row, "直播延迟", rowOffsets.map { it.toString() }, app.prefs.liveOffsetMs.toString()) { v ->
                    app.prefs.liveOffsetMs = v.toIntOrNull() ?: 20000
                }
                cycleRow(row, "画面比例", listOf("auto", "16:9", "4:3", "fill"), app.prefs.aspect) { v ->
                    app.prefs.aspect = v
                }
            }
            "interact" -> {
                subMenuTitle?.visibility = android.view.View.GONE
                toggleRow(row, "换台方向反转", app.prefs.reverseChannelKeys) { app.prefs.reverseChannelKeys = it }
                cycleRow(row, "数字键跳台等待", rowNumTimeouts.map { it.toString() },
                    app.prefs.numberJumpTimeoutMs.toString()) { v ->
                    app.prefs.numberJumpTimeoutMs = v.toIntOrNull() ?: 2500
                }
                toggleRow(row, "开机自动播放上次频道", app.prefs.autoPlayLast) { app.prefs.autoPlayLast = it }
            }
            else -> {
                subMenuTitle?.visibility = android.view.View.GONE
                infoRow(row, "设备名称", app.prefs.deviceName.ifBlank { "未命名" })
                infoRow(row, "服务器", app.prefs.serverBase.ifBlank { "未配置" })
                infoRow(row, "渲染档位", app.tier.name)
                // 解除绑定要二次确认：遥控器上 OK 和方向键挨得近，
                // 一次误触就得重新绑定才能看。第一次 OK 只是把行变成"再按一次确认"。
                var armed = false
                val unbind = TextView(this).apply {
                    text = "解除绑定"
                    textSize = 16f
                    setPadding(dpRow(18), dpRow(12), dpRow(18), dpRow(12))
                    setTextColor(getColor(R.color.text_primary))
                    background = getDrawable(R.drawable.bg_setting_row)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener {
                        if (!armed) {
                            armed = true
                            text = "再按一次确认解除绑定"
                            setTextColor(getColor(R.color.accent))
                        } else {
                            app.prefs.clearSession()
                            closeSettingsMenu()
                            recreate()
                        }
                    }
                    setOnFocusChangeListener { _, has -> if (!has && armed) { armed = false; text = "解除绑定"; setTextColor(getColor(R.color.text_primary)) } }
                }
                row.addView(unbind, rowLp())
            }
        }
        subMenu?.visibility = android.view.View.VISIBLE
        setRowsFocusable(false)   // 先不参与遍历，按 OK 才进去
    }

    /** 按 OK 后把焦点送进内联行，落在第一行。 */
    private fun focusFirstRow() {
        subMenuRow?.post { subMenuRow?.getChildAt(0)?.requestFocus() }
    }

    private fun rowLp() = android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dpRow(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cycleRow(
        parent: android.widget.LinearLayout,
        label: String,
        options: List<String>,
        current: String,
        apply: (String) -> Unit,
    ) {
        var cur = current
        fun render(v: String) = "$label    $v"
        val tv = TextView(this).apply {
            text = render(cur)
            textSize = 16f
            setPadding(dpRow(18), dpRow(12), dpRow(18), dpRow(12))
            setTextColor(getColor(R.color.text_primary))
            background = getDrawable(R.drawable.bg_setting_row)
            isFocusable = true
            isClickable = true
            setOnClickListener {
                val i = options.indexOf(cur)
                cur = options[(i + 1).mod(options.size)]
                apply(cur)
                text = render(cur)
            }
        }
        parent.addView(tv, rowLp())
    }

    private fun toggleRow(
        parent: android.widget.LinearLayout,
        label: String,
        value: Boolean,
        apply: (Boolean) -> Unit,
    ) {
        var cur = value
        fun render(v: Boolean) = "$label    " + if (v) "开" else "关"
        val tv = TextView(this).apply {
            text = render(cur)
            textSize = 16f
            setPadding(dpRow(18), dpRow(12), dpRow(18), dpRow(12))
            setTextColor(getColor(R.color.text_primary))
            background = getDrawable(R.drawable.bg_setting_row)
            isFocusable = true
            isClickable = true
            setOnClickListener {
                cur = !cur
                apply(cur)
                text = render(cur)
            }
        }
        parent.addView(tv, rowLp())
    }

    private fun infoRow(parent: android.widget.LinearLayout, label: String, value: String) {
        val tv = TextView(this).apply {
            text = "$label    $value"
            textSize = 15f
            setPadding(dpRow(18), dpRow(10), dpRow(18), dpRow(10))
            setTextColor(getColor(R.color.text_secondary))
            isFocusable = false
        }
        parent.addView(tv, rowLp())
    }

    private fun focusSubMenu(spec: SubSpec) {
        val row = subMenuRow ?: return
        if (subMenu?.visibility != android.view.View.VISIBLE) openSubMenu(spec)
        row.post {
            row.getChildAt(spec.selected().coerceIn(0, spec.labels.size - 1))?.requestFocus()
        }
    }

    /** 收起二级面板，焦点还给一级菜单。 */
    private fun closeSubMenu(keepFocus: Boolean = false) {
        val m = subMenu ?: return
        if (m.visibility != android.view.View.VISIBLE) return
        // ★ 先把 owner 存下来再收面板。
        // 直接 subMenu.visibility = GONE 会让系统立刻把焦点丢给第一个可聚焦项，
        // 那一项获得焦点时会覆盖 subMenuOwner，导致焦点回到错误的菜单项。
        val owner = subMenuOwner
        m.visibility = android.view.View.GONE
        if (!keepFocus) owner?.requestFocus()
    }





    /**
     * 开屏层兜底：起播卡住 / 首帧一直不来时也要撤掉。
     *
     * 没有这个兜底时，只要 first frame 到不了，开屏层就一直盖在最上层——
     * 用户按键能操作下面的界面（面板还会展开），但看到的永远是"正在起播…"。
     * 这个现象实测遇到过：频道面板已经展开、截图却还是开屏层。
     */
    private val splashTimeout = Runnable {
        android.util.Log.w("LITV", "开屏层等不到首帧，超时撤掉")
        hideSplashOverlay()
    }

    private fun hideSplashOverlay() {
        main.removeCallbacks(splashTimeout)
        splashOverlay?.let { v ->
            if (v.visibility != View.VISIBLE) return
            v.animate().alpha(0f).setDuration(220).withEndAction {
                v.visibility = View.GONE
                v.alpha = 1f
            }.start()
        }
    }

    /** 更新开屏层上的阶段说明。只在主线程调用。 */
    private fun setSplashStage(text: String) {
        splashStage?.text = text
    }

    private fun hideLoading() {
        if (!::loading.isInitialized) return
        main.removeCallbacks(showLoadingRunnable)
        main.removeCallbacks(loadingSlowRunnable)
        if (loading.visibility != View.VISIBLE) return
        loadingText?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
            loadingText?.visibility = View.GONE
            loadingText?.alpha = 1f
        }?.start()
        loading.animate().alpha(0f).setDuration(150).withEndAction {
            loading.visibility = View.GONE
            loading.alpha = 1f
        }.start()

    }

    /**
     * 显示 OSD。
     *
     * @param level 默认 2 = 完整信息（台号/台名/当前节目/下一档/徽标/码率）。
     *              原来默认 1、再按一次 OK 才到 2，用户要按两次才看到想看的东西。
     */
    private fun showOsd(level: Int = 2) {
        renderOsdBadges()
        // 面板/设置菜单打开时不显示 OSD：面板底部已经有完整信息条，
        // 两个同时出现会叠在一起（截图里就是 OSD 压在设置菜单下面）。
        if (panelVisible || menuVisible) return
        osdVisible = true
        osd.visibility = View.VISIBLE
        osdName.text = osdNameText(current ?: return)
        renderOsdNow()
        setOsdLevel(level)
        main.removeCallbacks(hideOsdRunnable)
        main.postDelayed(hideOsdRunnable, 5000)
    }

    private fun hideOsd() {
        osdVisible = false
        osd.visibility = View.GONE
    }

    /**
     * OSD 层级可见性。
     *
     * @param level 1 = 只要台号 + 台名 + 收藏态；2 = 加上节目/进度/下一档/线路/内核
     */
    private fun setOsdLevel(level: Int) {
        osdLevel = level
        val detail = level >= 2 && app.prefs.showPlayStatus
        osdNo.visibility = View.VISIBLE
        osdName.visibility = View.VISIBLE
        // 节目名在第一层也给：它是"这个台在演什么"，不是排障信息
        osdNow.visibility = View.VISIBLE
        osdProgress.visibility = if (detail) View.VISIBLE else View.GONE
        osdNext.visibility = if (detail) View.VISIBLE else View.GONE
        // 角标和码率也是排障信息，跟线路/内核一样只在第二层出现
        if (::osdBadges.isInitialized) osdBadges.visibility = if (detail) View.VISIBLE else View.GONE
        if (::osdRate.isInitialized) osdRate.visibility = if (detail) View.VISIBLE else View.GONE
        // 底部信息条（线路 x/y、延迟、时间）同样只在第二层出现
        infoLine.visibility = if (detail) View.VISIBLE else View.GONE
        infoTime.visibility = if (detail) View.VISIBLE else View.GONE
        infoProgress.visibility = if (detail) View.VISIBLE else View.GONE
    }

    /** OK 键循环：隐 → 第一层 → 第二层 → 隐。 */
    private fun toggleOsd() {
        // 两态：隐藏 <-> 完整信息。原来中间还有一档"精简"，要按两次 OK 才看到
        // 当前节目/下一档，用户反馈多余。
        if (osdVisible) hideOsd() else showOsd()
    }

    /** 放开焦点限制并把面板淡入。落焦点前不能放开，否则「全部」会先抢到焦点。 */
    private fun revealPanel() {
        if (!panelVisible) return
        if (panel.descendantFocusability != android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS) {
            panel.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        if (panel.alpha < 1f) panel.animate().alpha(1f).setDuration(120).start()
    }

    private fun openPanel() {
        // 三个浮层互斥：频道面板 / 设置菜单 / OSD 同时只允许一个存在。
        if (menuVisible) closeSettingsMenu()
        hideOsd()
        panelVisible = true
        // 面板一变 VISIBLE，系统会立刻把焦点给第一个可聚焦项（分类列的「全部」），
        // 然后我们再挪到当前频道——中间那一帧用户就看见"焦点先跑到全部再闪回来"。
        // 所以显示前先禁止子视图抢焦点，等定位好再放开。
        panel.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panel.alpha = 0f
        panel.visibility = View.VISIBLE

        // 列表超过 30 秒没更新就先静默拉一次。
        //
        // 为什么在这里做：管理员在服务端启用/新增频道后，电视端以前永远看不到
        // （只在打开播放页时 bootstrap 一次）。而用户发现"台没出现"时，
        // 第一个动作就是按 ← 打开频道面板 —— 在这里刷新最对症。
        // 30 秒的窗口是防抖：连按 ← 不会每次都打服务端。
        if (System.currentTimeMillis() - lastListRefreshAt > 30_000) {
            Log.i(TAG, "打开面板，列表已过期，静默重载")
            refreshChannelsSilently()
        }
        // 面板自己底部就有信息条，OSD 再压上来就重了——
        // 真机上看到的是「线路 1/1 / 04:27 周五」叠在频道列表上。
        hideOsd()
        // 四列：源 / 分组 / 频道。这里必须走 applyChannelFilter()，
        // 原来直接 submit(channels) 会把还没加载的空列表刷进去，频道列就是空的。
        applyChannelFilter()
        if (channels.isEmpty() && selGroup.isNotEmpty()) {
            Log.i(TAG, "分组 $selGroup 为空，退回全部")
            selGroup = ""
            applyChannelFilter()
        }
        refreshCategories()   // 分类列必须跟着刷，否则面板左侧空着
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
        // 焦点落位：优先当前频道，拿不到才退到分组列。
        //
        // 不能一上来就判断 channels.isEmpty() 早退——openPanel 里刚做完
        // applyChannelFilter，列表视图还没布局完，这时 channels 可能仍是空，
        // 早退会导致"面板打开了但焦点在分组列、频道列点不动"。
        val idx = channels.indexOfFirst { it.id == current?.id }
        val pos = if (idx >= 0) idx else 0
        (panelList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
        // 焦点必须真的落进频道列——只有那里按上下才是换台、按 OK 才是切换。
        // 之前只重试 8 帧（320ms）就放弃，失败后焦点留在分类列，
        // 于是"在频道页按上下"变成了切分类。这里轮询到 1.5 秒。
        val deadline = System.currentTimeMillis() + 1_500
        val focusTick = object : Runnable {
            override fun run() {
                if (panelList.hasFocus()) return
                val vh = panelList.findViewHolderForAdapterPosition(pos)
                if (vh != null) {
                    revealPanel()
                    vh.itemView.requestFocus()
                    return
                }
                if (System.currentTimeMillis() < deadline) {
                    panelList.postDelayed(this, 80)
                } else {
                    // 拿不到目标项（列表为空/未布局完）：放开焦点限制再淡入，
                    // 至少让面板可用，不再无限等。
                    if (channels.isEmpty()) Log.i(TAG, "频道列为空，焦点交给分组列")
                    revealPanel()
                    if (channels.isEmpty()) grpList.post { grpList.requestFocus() }
                }
            }
        }
        panelList.postDelayed(focusTick, 60)
        main.removeCallbacks(panelProgressTick)
        main.postDelayed(panelProgressTick, 20_000)
        // 兜底：极端情况（比如列表一直没布局）也别让面板永远透明。
        // 1.2s 比落焦点的 1.5s 上限短，但比正常落位（~60-200ms）长得多，
        // 不会在落焦点途中抢先把面板显出来。
        main.postDelayed({ if (panelVisible && panel.alpha < 1f) revealPanel() }, 1_200)
        hideOsd()
    }

    private fun closePanel() {
        panelVisible = false
        panel.visibility = View.GONE
        // 节目单要一起收回把手：否则这次展开了，下次按 ← 打开面板时
        // 它还是展开状态——用户看到的就是"一进来节目单就开着"。
        epgVisible = false
        if (::epgPanel.isInitialized) epgPanel.visibility = View.GONE
        if (::epgHandle.isInitialized) epgHandle.visibility = View.VISIBLE
        if (::panelRightGap.isInitialized) panelRightGap.visibility = View.VISIBLE
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
        // 先按选中的那天过滤，再按分类过滤。
        // 日期是"选择器"不是"行标签"：左边选哪天，右边就只显示那天。
        var list = programs
        if (epgDayEpoch > 0L) {
            val dayEnd = epgDayEpoch + 24 * 60 * 60 * 1000L
            list = list.filter { it.startAt * 1000 >= epgDayEpoch && it.startAt * 1000 < dayEnd }
        }
        val c = epgCategory ?: return list
        return list.filter { it.category == c }
    }

    private fun epgTitleText(): String {
        // ★ 标题必须跟着"节目单实际显示的那个台"，不能跟着正在播的台。
        // 之前用 current?.name，于是焦点换到别的台、节目单也换了数据，
        // 标题却一直写着正在播的那个台 —— 看着就像"节目单没跟着换"。
        val shownId = if (epgProgramsChannelId > 0) epgProgramsChannelId
                      else panelLastFocusId.takeIf { it > 0 } ?: (current?.id ?: 0L)
        val name = allChannels.firstOrNull { it.id == shownId }?.name
            ?: current?.name ?: "节目单"
        val day = epgDayAdapter.selectedDay()?.label ?: ""
        val suffix = epgCategory ?: "全部"
        return "$name · $day · $suffix"
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
        epgTitle.text = epgTitleText()
        epgAdapter.submit(filteredPrograms())
        epgAdapter.setReserved(reservedStarts)
        val pos = epgAdapter.currentPosition()
        if (pos >= 0) epgList.scrollToPosition(pos)
    }


    /** 把焦点送进日期选择器的选中项（直接 requestFocus 到 RecyclerView 会被标题抢走）。 */
    /** 把焦点送进节目列表的当前节目。 */
    private fun focusEpgList() {
        epgList.post {
            val pos = epgAdapter.currentPosition()
            val vh = epgList.findViewHolderForAdapterPosition(pos)
            if (vh != null) vh.itemView.requestFocus() else epgList.requestFocus()
        }
    }

    private fun focusDayList() {
        epgDayList.post {
            val pos = epgDayAdapter.selectedPosition()
            val vh = epgDayList.findViewHolderForAdapterPosition(pos)
            if (vh != null) vh.itemView.requestFocus()
            else epgDayList.requestFocus()
        }
    }


    /** 重建日期选择器（跨天用），默认选今天。 */
    private fun rebuildDayList() {
        epgDayAdapter.submit(epgDayAdapter.buildDays(), selectToday = true)
        epgDayEpoch = epgDayAdapter.selectedDay()?.epochDay ?: 0L
    }

    private fun todayEpochDay(): Long {
        val c = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    /**
     * 跨天检查：日期选择器要重建（今天/明天标签会变），EPG 也要重下。
     *
     * 电视盒子常年不关机，只在启动时加载一次的话，第二天看到的还是昨天的节目单。
     * 这里在 onResume 和定时器里各查一次，成本可以忽略。
     */
    private fun checkEpgDayRollover() {
        val today = todayEpochDay()
        if (epgLoadedEpochDay == 0L) { epgLoadedEpochDay = today; return }
        if (today == epgLoadedEpochDay) return

        Log.i(TAG, "跨天：重建日期选择器并重下节目单")
        epgLoadedEpochDay = today
        rebuildDayList()
        val url = lastEpgUrl
        if (url.isNotBlank()) {
            app.epg.forceRefresh(url) { ok ->
                if (ok) main.post { current?.let { refreshEpg(it.id) } }
            }
        }
    }

    /** 展开节目单列并藏起竖排把手（不抢焦点，由调用方决定）。 */
    private fun expandEpgPanel() {
        // 记下"进节目单之前焦点在哪一项"，回来时原地返回，不重新定位
        panelFocusPosBeforeEpg = run {
            val f = panelList.focusedChild
            if (f != null) {
                panelFocusedTopBeforeEpg = f.top
                panelList.getChildAdapterPosition(f)
            } else -1
        }
        // 同时记住滚动状态：回来要原样还原，否则列表会整体跳位置
        (panelList.layoutManager as? LinearLayoutManager)?.let { lm ->
            panelFirstVisiblePos = lm.findFirstVisibleItemPosition()
            val v = lm.findViewByPosition(panelFirstVisiblePos)
            panelFirstVisibleOffset = v?.top ?: 0
        }
        Log.i(TAG, "进节目单: focusPos=$panelFocusPosBeforeEpg firstVisible=$panelFirstVisiblePos offset=$panelFirstVisibleOffset")
        // 节目单展开会让面板重新布局，频道列会跟着挪一格。
        // 记下来的位置在这里立刻还原一次，避免"展开就跳"。
        val fp = panelFirstVisiblePos
        val fo = panelFirstVisibleOffset
        if (fp >= 0) {
            panelList.post {
                (panelList.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(fp, fo)
                Log.i(TAG, "进节目单后还原滚动: firstVisible=$fp offset=$fo")
            }
        }
        epgVisible = true
        beginPanelTransition()
        epgPanel.visibility = View.VISIBLE
        epgHandle.visibility = View.GONE
        // 展开时按"焦点所在的台"取节目单，而不是正在播的那个台。
        // 之前只在 epg 已展开时（onFocus 里）才换数据，第一次展开一定是错的。
        val byChild = focusedPanelChannelId()
        val targetId = byChild.takeIf { it > 0 }
            ?: panelLastFocusId.takeIf { it > 0 }
            ?: focusEpgChannelId.takeIf { it > 0 }
            ?: (current?.id ?: 0L)
        Log.i(TAG, "展开节目单: focusedChild=$byChild lastFocus=$panelLastFocusId current=${current?.id} -> 取 $targetId")
        if (targetId > 0 && targetId != epgProgramsChannelId) refreshEpg(targetId)
        panelRightGap.visibility = View.GONE
        epgTitle.text = epgTitleText()
        epgAdapter.submit(filteredPrograms())
        val pos = epgAdapter.currentPosition()
        if (pos >= 0) epgList.scrollToPosition(pos)
    }

    /** 呼出节目单面板（右侧）。数据是进频道时就预取的，所以这里是瞬开。 */
    /**
     * 触摸/点击「节目单 »」把手展开。
     *
     * 原来这里是 expandEpgPanel() 的一份复制，但漏了
     * `panelRightGap.visibility = GONE` —— 右侧留白不收起来，节目单就被挤成
     * 很窄的一条，文字全折行。现在统一走 expandEpgPanel()，只多一步把焦点送进节目列。
     */
    private fun openEpg() {
        expandEpgPanel()
        val pos = epgAdapter.currentPosition()
        epgList.post {
            (epgList.layoutManager as? LinearLayoutManager)
                ?.findViewByPosition(pos)?.requestFocus()
        }
        hideOsd()
    }

    /**
     * 面板尺寸变化的统一过渡入口。
     *
     * 用 TransitionManager 而不是手写 ValueAnimator / LayoutTransition：
     * - beginDelayedTransition 之后布局是**立即**生效的，动画只是视觉补间，
     *   所以紧接着做的滚动还原不会被下一帧覆盖（之前正是因为这个才要 postDelayed，
     *   而 postDelayed 又和面板淡入抢时序，导致列表跑位）。
     * - Fade 管显隐，ChangeBounds 管宽度/位置变化。
     */
    private fun beginPanelTransition() {
        val group = findViewById<android.view.ViewGroup>(R.id.panelColumns) ?: return
        runCatching {
            androidx.transition.TransitionManager.beginDelayedTransition(
                group,
                androidx.transition.TransitionSet()
                    .addTransition(androidx.transition.ChangeBounds())
                    .addTransition(androidx.transition.Fade())
                    .setDuration(160),
            )
        }
    }

    /**
     * 收起节目单。
     *
     * 宽度用动画收到 0 再 GONE：直接 GONE 会让面板瞬间重排，
     * RecyclerView 会自己调一次滚动位置，表现就是"返回时列表跳一下/闪一下"。
     * 渐变过去就没有那一帧错位。
     *
     * @param then 收起完成后回调（回频道列时用来还原滚动位置并聚焦）
     */
    private fun closeEpg(then: (() -> Unit)? = null) {
        if (!epgVisible) { then?.invoke(); return }
        epgVisible = false
        val lp = epgPanel.layoutParams as? android.widget.LinearLayout.LayoutParams
        val startWidth = epgPanel.width
        if (lp == null || startWidth <= 0) {
            epgPanel.visibility = View.GONE
            epgHandle.visibility = View.VISIBLE
            panelRightGap.visibility = View.VISIBLE
            then?.invoke()
            return
        }
        // 有 weight 时宽度不受 width 控制，先摘掉 weight 固化成像素宽度
        lp.weight = 0f
        lp.width = startWidth
        epgPanel.layoutParams = lp
        android.animation.ValueAnimator.ofInt(startWidth, 0).apply {
            duration = 160
            addUpdateListener { va ->
                val w = va.animatedValue as Int
                val cur = epgPanel.layoutParams as android.widget.LinearLayout.LayoutParams
                cur.width = w
                epgPanel.layoutParams = cur
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    epgPanel.visibility = View.GONE
                    // 还原成"可展开"的参数，下次展开还能吃到权重
                    val reset = epgPanel.layoutParams as android.widget.LinearLayout.LayoutParams
                    reset.width = 0
                    reset.weight = 1f
                    epgPanel.layoutParams = reset
                    epgHandle.visibility = View.VISIBLE
                    panelRightGap.visibility = View.VISIBLE
                    then?.invoke()
                }
            })
            start()
        }
    }

    /**
     * 线路切换：长按 OK 呼出。
     * 线路信息由服务端下发（只给标签和健康度，不给上游真实地址，防抓包盗链）。
     */

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
        // 用户已经在按键了，说明不需要再看开屏层——无论起播成不成功都撤掉
        if (action == KeyEvent.ACTION_DOWN) hideSplashOverlay()

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

        // 播放失败后按 OK = 直接重试。
        //
        // 这里**不弹任何面板**。之前失败会弹一张卡片，卡片可见时所有按键都
        // 交给它，↑↓ 变成在按钮之间移动 —— 用户想换台却换不了，
        // 表现就是"失败还截取我的控制焦点"。
        // 现在失败只在底部留一行提示，OK 就地重试，其它键一律照常。
        if (action == KeyEvent.ACTION_DOWN && playFailedPending &&
            (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
        ) {
            playFailedPending = false
            directRetryLeft = DIRECT_RETRY_MAX
            switchTip.visibility = View.GONE
            // forceRebuild：用户明确要求"再播一次"，必须真的重建播放器。
            // 缓存命中时会跳过 prepare，那样按 OK 就成了摆设。
            current?.let { playChannel(it, currentStreamId, forceRebuild = true) }
            return true
        }

        // 节目单面板打开时，BACK/← 收起，其余交给列表
        if (epgVisible) {
            if (action == KeyEvent.ACTION_DOWN &&
                (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT)
            ) {
                // 收起节目单后必须把焦点和滚动位置还给频道列。
                //
                // 左键和返回键都要还：返回键原来只 closeEpg()、不管焦点，
                // 焦点被系统丢给第一个可聚焦项（分类列的「全部」），
                // 用户看到的就是"从日期按返回，跑到分类去了"。
                val pos = panelFocusPosBeforeEpg
                val top = panelFocusedTopBeforeEpg
                closeEpg { restorePanelScroll(pos, top) }
                return true
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
            if (action == KeyEvent.ACTION_DOWN) {
                if (com.litv.tv.BuildConfig.DEBUG) android.util.Log.i("LITV", "菜单按键 code=$code subVis=${subMenu?.visibility} rowsActive=$rowsActive")
                when {
                    code == KeyEvent.KEYCODE_BACK -> {
                        // 二级开着先收二级，再收菜单
                        if (rowsActive) { setRowsFocusable(false); subMenuOwner?.requestFocus() }
                        else closeSettingsMenu()
                        return true
                    }
                    // 左键进入左边的选项列。必须在 Activity 这一层拦：
                    // 挂在控件上的 OnKeyListener 收不到——DOWN 会先被这里的分发拿走。
                    code == KeyEvent.KEYCODE_DPAD_LEFT &&
                        subMenu?.visibility == View.VISIBLE && !rowsActive -> {
                        enterSub(); return true
                    }
                    // 右键从选项列退回一级菜单
                    code == KeyEvent.KEYCODE_DPAD_RIGHT && rowsActive -> {
                        exitSub(); return true
                    }
                }
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

    /**
     * 长按 OK。
     *
     * 原来这里弹一个独立的「选择线路」弹窗，但设置菜单第一项已经是「线路选择」
     * （openLinePicker，内嵌子面板）——同一件事两套实现，长按这个还关不掉。
     * 现在统一走设置菜单那条，这里不再重复。
     */
    private fun onLongPressOk() = Unit

    // ---------------------------------------------------------------- PlayerManager 回调

    override fun onEngineChanged(kind: EngineKind) {
        // 按实际选中的内核切换渲染目标。
        //
        // Exo 用 media3 的 PlayerView（它自己管 Surface）；
        // VLC / mpv 用外部 Surface（TextureView / SurfaceView）。
        // PlayerView 在布局里 z 序最高，给 VLC / mpv 时不隐藏就会盖住画面。
        val isExo = kind == EngineKind.EXO
        playerView.visibility = if (isExo) View.VISIBLE else View.GONE
    }

    override fun onPrepared(width: Int, height: Int) = Unit

    /**
     * 本次换台的转场方向：+1 = 旧画面向上滑走（切到下一个台），
     * -1 = 向下滑走（上一个台），0 = 只淡出（点列表、数字键、恢复播放等没有方向感的路径）。
     *
     * 为什么靠「旧画面滑走」而不是「新画面滑入」：画面是 SurfaceView，
     * 内容由播放器直接往硬件层画，**没法给它做 View 动画**。
     * 能动的只有盖在上面的这张快照 —— 它滑走、下面已经在播的新画面露出来，
     * 观感就是翻页。
     */
    private var slideDir = 0f

    /**
     * 下一次换台的转场方向，由 [switchBy] 写入、[playChannel] 在开头取走。
     *
     * 分两个字段是因为换台入口不止 switchBy：点列表、数字键跳台、恢复播放、
     * 失败重试都会调 playChannel。只有 switchBy 有方向，其余都该是「只淡出」。
     * 用一个字段的话，上一次的方向会残留到这些路径上（表现为"点个台莫名往上滑"）。
     */
    private var pendingSlideDir = 0f

    /**
     * 换台转场幕布：从切换方向滑进来盖住屏幕。
     *
     * 为什么是"幕布扫过"而不是"旧画面滑走"：这台电视的视频走独立硬件层，
     * screencap / MediaProjection / PixelCopy 三种软件抓帧全都拿不到画面
     * （只有厂商 screencontrol 硬件回采能拿，那要 root）。没有那一帧静止图，
     * 就没有可动的旧画面，只能动一层叠在上面的 View。
     *
     * 新画面出来时由 [endCurtain] 接着朝同方向滑走，观感即"翻了一页"。
     */


    private fun startCurtain() {
        if (!::curtain.isInitialized) return
        val h = resources.displayMetrics.heightPixels.toFloat()
        // slideDir 在 playChannel 开头已经被取走；没有方向（点列表/数字键）时按"下一个"处理
        curtainDir = if (slideDir == 0f) 1f else slideDir
        curtain.animate().cancel()
        curtain.translationY = curtainDir * h
        curtain.visibility = View.VISIBLE
        curtain.animate()
            .translationY(0f)
            .setDuration(140L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        // 兜底：新台起播失败时不会收到 onFirstFrame，幕布会一直盖着。
        // 到点自动撤，最多黑这么久。
        main.removeCallbacks(curtainTimeout)
        main.postDelayed(curtainTimeout, CURTAIN_TIMEOUT_MS)
    }

    private fun endCurtain() {
        if (!::curtain.isInitialized || curtain.visibility != View.VISIBLE) return
        main.removeCallbacks(curtainTimeout)
        val h = resources.displayMetrics.heightPixels.toFloat()
        curtain.animate().cancel()
        curtain.animate()
            .translationY(-curtainDir * h)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                curtain.visibility = View.GONE
                curtain.translationY = 0f
            }
            .start()
    }


    /** 拿一张尺寸匹配的复用位图；尺寸变了才重建。 */
    private fun obtainSnapshotBitmap(w: Int, h: Int): android.graphics.Bitmap? {
        val cur = snapshotBitmap
        if (cur != null && cur.width == w && cur.height == h && !cur.isRecycled) return cur
        cur?.recycle()
        return runCatching {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        }.getOrNull()?.also { snapshotBitmap = it }
    }

    private val unfreezeTimeout = Runnable {
        Log.w(TAG, "冻结帧超时，强制解除")
        unfreezeFrame()
    }

    /**
     * 撤帧轮询。
     *
     * 为什么不用 onPlaying / onFirstFrame 就够：这两个回调在起播预算耗尽
     * （budgetExpired）时会被直接丢掉，冻结帧就永远撤不掉，画面停住。
     * 这里每 150ms 看一眼：播放器真的在播 -> 立刻撤；最多等 5 秒。
     */
    private val unfreezePoll = object : Runnable {
        private var ticks = 0
        override fun run() {
            if (!::snapshot.isInitialized || snapshot.visibility != View.VISIBLE) return
            if (player.isPlaying) {
                unfreezeFrame()
                return
            }
            // 2.5 秒还没出画（约 17 跳）：说明这路流起播慢。
            // 冻结帧继续盖着会像"死机"，这时换成转圈，至少看得出在动。
            if (ticks == 17) {
                unfreezeFrame()
                main.removeCallbacks(showLoadingRunnable)
                main.post(showLoadingRunnable)
            }
            if (ticks++ > 33) {
                unfreezeFrame()
                return
            }
            main.postDelayed(this, 150)
        }
    }


    /**
     * 换台后重新挂一次 Surface。
     *
     * 实测：切台瞬间会打出
     *   SurfaceUtils: disconnecting from surface, reason disconnectFromSurface
     * 之后新流虽然选了新的解码器，画面却一直是旧的最后一帧——因为 Surface
     * 断了没再接上。复用了播放器实例时 surfaceCreated 不会重新触发，
     * 所以必须在这里显式补挂。
     */
    private fun reattachSurface() {
        runCatching {
            if (::surface.isInitialized && surface.holder.surface.isValid) {
                player.attach(surface.holder.surface, findViewById(android.R.id.content))
            }
        }
    }


    /** 频道列里"焦点实际停在哪一项"对应的台。直接问 RecyclerView，不依赖回调是否及时。 */
    private fun focusedPanelChannelId(): Long {
        if (!::panelList.isInitialized) return 0L
        val f = recyclerChildOf(panelList, panelList.focusedChild) ?: return 0L
        val pos = panelList.getChildAdapterPosition(f)
        if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return 0L
        return channels.getOrNull(pos)?.id ?: 0L
    }

    /** 把焦点放回频道列里指定台的那一项（找不到就用当前播放的台）。 */
    /**
     * 从节目单回到频道列。
     *
     * 不重新按"正在播的台"定位——那个台可能离用户浏览的位置很远，
     * 一回去列表就被划走了。这里直接回到进节目单之前的那一项。
     */
    private fun backToPanelList() {
        restorePanelScroll(panelFocusPosBeforeEpg, panelFocusedTopBeforeEpg)
    }

    /** 还原频道列的滚动位置并聚焦指定项。 */
    private fun restorePanelScroll(pos: Int, top: Int) {
        val lm = panelList.layoutManager as? LinearLayoutManager
        // ★ 同步还原，不 post：closeEpg() 会让面板重新布局，如果还原放到下一帧，
        // 中间会有一帧是"错位"的画面 —— 看到的就是"返回时闪一下"。
        if (lm != null && pos in 0 until channels.size) {
            lm.scrollToPositionWithOffset(pos, top)
            val vh = panelList.findViewHolderForAdapterPosition(pos)
            if (vh != null) {
                vh.itemView.requestFocus()
            } else {
                panelList.post { panelList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
            }
        } else {
            panelList.post { panelList.requestFocus() }
        }
        Log.i(TAG, "回列表: pos=$pos top=$top")
    }

    private fun focusPanelChannel(channelId: Long) {
        // 优先用传入的（面板最后聚焦的台）；都没有才退回正在播的台。
        val id = channelId.takeIf { it > 0 }
            ?: panelLastFocusId.takeIf { it > 0 }
            ?: (current?.id ?: 0L)
        val pos = channels.indexOfFirst { it.id == id }.let { if (it >= 0) it else 0 }
        panelList.post {
            val vh = panelList.findViewHolderForAdapterPosition(pos)
            if (vh != null) vh.itemView.requestFocus() else panelList.scrollToPosition(pos)
        }
    }


    /**
     * 统一的频道列提交：先记下"焦点停在哪一项"，提交后再放回去。
     *
     * 凡是面板重载/切分组都会走 notifyDataSetChanged，焦点会被系统清掉；
     * 各自补一次很容易漏（静默重载就因为漏了导致"隔很久开面板焦点消失"）。
     * 这里收口成一个入口。
     */
    private fun submitPanelList(list: List<Channel>) {
        // 只有焦点本来就在频道列时才恢复；焦点在分类列（正在挑分组）就别动它，
        // 否则每选一次分类，焦点就被搬到频道列 —— 用户看到的是"焦点乱飘"。
        val focusInList = panelVisible && panelList.hasFocus()
        val keep = if (focusInList) focusedPanelChannelId().takeIf { it > 0 } else null
        panelAdapter.submit(list, nowMap)
        if (focusInList) {
            val id = keep ?: current?.id ?: 0L
            if (id > 0) main.post { focusPanelChannel(id) }
        }
    }

    private fun startUnfreezePoll() {
        main.removeCallbacks(unfreezePoll)
        main.postDelayed(unfreezePoll, 150)
    }

    /**
     * 冻结当前画面，换台期间一直显示它。
     *
     * 布局里一直有 @id/snapshot 这个 ImageView，之前代码从没用过，
     * 换台靠的是纯色幕布，用户看到的就是"黑一下"。
     *
     * 只在**真的渲染过画面**时才冻结：否则可能把黑屏或转圈冻下来。
     */
    private fun freezeFrame() {
        if (!::snapshot.isInitialized || !hasRenderedFrame) return
        try {
            if (::surfaceTex.isInitialized && surfaceTex.isAvailable
                && surfaceTex.width > 0 && surfaceTex.height > 0
            ) {
                val bmp = surfaceTex.getBitmap(surfaceTex.width, surfaceTex.height)
                if (bmp != null) {
                    snapshot.setImageBitmap(bmp)
                    snapshot.visibility = View.VISIBLE
                    startUnfreezePoll()
                    return
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
                && ::surface.isInitialized && surface.width > 0 && surface.height > 0
            ) {
                val bmp = obtainSnapshotBitmap(surface.width, surface.height) ?: return
                android.view.PixelCopy.request(
                    surface, bmp,
                    { result ->
                        if (result == android.view.PixelCopy.SUCCESS) {
                            main.post {
                                snapshot.setImageBitmap(bmp)
                                snapshot.visibility = View.VISIBLE
                                main.removeCallbacks(unfreezeTimeout)
                                main.postDelayed(unfreezeTimeout, 4000)
                            }
                        }
                    },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "冻结当前帧失败：${t.javaClass.simpleName}")
        }
    }

    /** 新画面出来（或超时）后把冻结帧淡出。 */
    private fun unfreezeFrame() {
        main.removeCallbacks(unfreezeTimeout)
        if (!::snapshot.isInitialized) return
        if (snapshot.visibility != View.VISIBLE) return
        snapshot.animate().alpha(0f).setDuration(160).withEndAction {
            snapshot.visibility = View.GONE
            snapshot.alpha = 1f
            snapshot.setImageDrawable(null)
        }.start()
    }

    private val curtainTimeout = Runnable {
        Log.w(TAG, "转场幕布等不到首帧，自动撤掉")
        endCurtain()
    }

    override fun onFirstFrame() {
        // 起播计时与观看窗口都从"真正看到画面"开始算 —— 转圈的那几十秒不算观看时长，
        // 否则 rebuffer ratio 的分母会被拉大、显得卡顿率更低（自欺欺人）。
        hasRenderedFrame = true
        unfreezeFrame()
        if (watchStartAt == 0L) watchStartAt = System.currentTimeMillis()
        // 新画面已经画出来了，把转场幕布朝同方向擦走。
        // 幕布在下面（视频之上、OSD 之下），擦走的过程就是"翻页"。
        endCurtain()
        // 冷启动开屏层要等到**真的画出第一帧**才撤。
        // 之前挂在 onPlaying 上，但那一刻画面往往还是黑的，撤了就是黑屏。
        hideSplashOverlay()

        // 换台方向用完即弃，别残留到下一次
        slideDir = 0f
    }

    override fun onPlaying() {
        hasRenderedFrame = true
        prefetchNeighbours()
        // onPlaying 比 onFirstFrame 可靠得多（首帧回调在起播预算耗尽时会被丢掉），
        // 这里也撤一次冻结帧，避免画面停在旧帧。
        unfreezeFrame()
        hideLoading()
        // 重试/换线路成功后必须把错误卡片收掉，否则它会一直盖在画面上。
        playFailedPending = false
        switchTip.visibility = View.GONE
        // 转场幕布不在这里撤：onPlaying 时画面往往还是黑的，
        // 等 onFirstFrame 真正画出第一帧再擦走（见 endCurtain）。

        // 上报起播耗时（只报第一次出画面，重连不重复计）
        if (startAt > 0) {
            val ms = (System.currentTimeMillis() - startAt).toInt()
            startAt = 0
            current?.let { app.api.reportStats(it.id, "start", ms) }
        }
    }

    override fun onError(message: String) {
        hideLoading()

        // 恢复顺序（真机事故后调整过）：
        //   ① 先换同一台的下一条线路 ② 再考虑重试直连 ③ 都不行才提示
        //
        // 为什么把"换线路"提到最前面：源里同一个台常有多条线路
        // （如 cctv1-MCP / cctv1-AV3A），一条坏不代表别的坏。
        // 以前是先重试直连、重试同一条坏线路 —— 那条线路本身有问题时，
        // 重试多少次都一样，用户看到的就是"突然失败，然后一直不播"。
        if (tryNextStreamLine(message)) return

        // 没有其它线路可换 → 才按直连失败处理（静默重试 / 底部提示）。
        if (currentPlayMode == "direct") {
            current?.let { directBrokenChannels += it.id }
            Log.w(TAG, "直连失败（内核层）: $message")
            if (handleDirectFailure(message)) return
        }
        // 走到这里才是真正放弃：上报 error，让服务端能区分"起播失败"与"卡顿"。
        current?.let {
            app.api.reportStats(
                it.id, "error", 0, message,
                watchMs = watchedSec(), path = currentPlayKind, engine = player.engineKey(),
            )
        }
        // 不自动弹层：只在底部留一行提示，用户按 OK 才展开可操作面板。
        //
        // 真机反馈：「突然就弹出来」「影响观感」。盖住画面的弹层会打断观看，
        // 而多数失败用户只想让它自己再试一次，不想被问"你要重试还是中转"。
        switchTip.text = "播放出错 · 按 OK 重试"
        switchTip.visibility = View.VISIBLE
        playFailedPending = true
            hideSplashOverlay()
    }

    /**
     * 卡顿上报（QoE）。
     *
     * 这是**整个 App 里唯一**的 stall 数据来源：在它之前，客户端只上报过 `start`，
     * 服务端的 StallRate 与新增的 rebuffer_ratio 分子**恒为 0**——
     * 管理台会大大方方地显示"卡顿率 0%"，比没有数据更误导。
     *
     * @param frozenMs PlayerManager 判定"画面完全没动"的时长
     */
    override fun onStall(frozenMs: Int) {
        val ch = current ?: return
        app.api.reportStats(
            ch.id, "stall", 0, "frozen=${frozenMs}ms",
            stallMs = frozenMs,
            watchMs = watchedSec(),
            // path = 封装（ts/hls/remux），engine = 内核 key（exo/vlc/mpv/avs3）
            path = currentPlayKind,
            engine = player.engineKey(),
        )
    }

    /** 本台已观看的秒数（从第一帧开始算）。未起播过返回 0。 */
    private fun watchedSec(): Int {
        if (watchStartAt <= 0L) return 0
        return ((System.currentTimeMillis() - watchStartAt) / 1000).toInt().coerceAtLeast(0)
    }

    /**
     * 离开页面时上报一次 stop（带 watch_ms）。
     *
     * 幂等：同一段观看只报一次；没起播过（watchStartAt == 0）不报，
     * 否则会往统计里灌一堆 0 秒的观看记录，把 rebuffer ratio 的分母拉偏。
     */
    private fun reportStopIfWatched() {
        if (reportedStop) return
        val ch = current ?: return
        if (watchStartAt <= 0L) return
        reportedStop = true
        app.api.reportStats(
            ch.id, "stop", 0, "",
            watchMs = watchedSec(), path = currentPlayKind, engine = player.engineKey(),
        )
    }

    /** 上一次播放实际用的模式（direct / relay），用于直连失败后改走中转 */
    private var currentPlayMode: String = ""
    /** 本次选台是否已经因为直连失败改走过中转 */
    private var relayRetried = false
    /** 这一次 play 请求强制走中转 */
    private var forceRelayOnce = false

    /**
     * 本次运行内直连不通的频道。
     *
     * 直连失败几乎都是"这台电视所在网络连不上这个源站"（TLS 被中断、CDN 限 IP、
     * DNS 不通），换内核修不好、重试也修不好，所以记住它、下次直接走中转。
     * 只放内存：重启后重新探一次是合理的（网络可能变了）；
     * 要跨启动记住需要落盘 + TTL，留到下一轮。
     */
    private val directBrokenChannels = mutableSetOf<Long>()

    /**
     * 播放失败、等用户按 OK 重试。
     *
     * 只是个标记，不带任何 UI：失败时底部留一行提示，按 OK 就地重试。
     * 不用弹层——弹层会抢遥控器焦点，导致用户换不了台。
     */
    private var playFailedPending = false

    /** 直连失败后自动静默重试的次数（换台重置）。 */
    private val DIRECT_RETRY_MAX = 2

    /**
     * 加载圈的延迟。
     *
     * 原来是 300ms，正常换台 1 秒内出画面，圈还没消失就先闪一下 ——
     * 观感比"黑一下"更糟（用户反馈过两次）。改成 3 秒：绝大多数换台
     * 根本看不到圈，只有真的卡住才提示"在加载"。
     */
    private val LOADING_DELAY_MS = 3_000L

    /** 转场幕布等首帧的上限。超时自动撤，避免起播失败时永久黑屏。 */
    private val CURTAIN_TIMEOUT_MS = 8_000L

    /**
     * 把直连失败的原始异常翻成一句人话。
     *
     * 电视上直接弹 SDK 的异常串（ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
     * javax.net.ssl.SSLHandshakeException ... Connection reset by peer）
     * 用户看不懂，还挡住画面；完整原因写 logcat 就够了。
     */
    private fun directFailureText(raw: String): String = when {
        raw.contains("handshake", true) || raw.contains("HandshakeException", true) ->
            "源站中断了 TLS 握手"
        raw.contains("reset by peer", true) -> "源站主动断开连接"
        raw.contains("refused", true) -> "源站拒绝连接"
        raw.contains("timeout", true) -> "源站响应超时"
        raw.contains("UnknownHost", true) || raw.contains("No address", true) -> "解析不到源站地址"
        else -> "连不上源站"
    }


    /**
     * 直连失败的统一出口（两条路径都走这里：PlayerManager.onDirectFailed 与 onError）。
     *
     * 策略在 [RelayDecision] 里，默认（用户没开自动中转）**不自动切**，
     * 而是把原因说清楚 + 给两个明确动作。
     *
     * @return true 表示已经接手（要么已改走中转重播，要么已弹出选择），调用方别再往下报错
     */
    private fun handleDirectFailure(reason: String): Boolean {
        val ch = current ?: return false
        hideLoading()

        // ① 先自己重试，不打扰用户。
        //
        // 直播源的直连失败绝大多数是瞬时的：握手被打断、DNS 刚换过、
        // 上游短暂抖动。以前第一下失败就弹一张盖住画面的卡片，
        // 用户看的好好的突然被打断 —— 这是最烦人的体验。
        // 现在先静默重试，只在底部用一行小字提示，画面不动。
        if (directRetryLeft > 0) {
            directRetryLeft--
            Log.i(TAG, "直连失败（${directFailureText(reason)}），静默重试，还剩 $directRetryLeft 次")
            switchTip.text = "重连中…"
            switchTip.visibility = View.VISIBLE
            main.postDelayed({
                // 期间用户可能已经换台了，代际号会拦住旧的重试。
                // forceRebuild：必须真的重建播放器，否则缓存命中会跳过 prepare，
                // 重试等于什么都不做（画面一直卡着）。
                current?.let { if (it.id == ch.id) playChannel(it, currentStreamId, forceRebuild = true) }
            }, 1200)
            return true
        }

        // ② 重试用尽：给底部提示条（不遮画面），按 OK 才展开可操作面板。
        switchTip.text = "这条线路连不上（${directFailureText(reason)}）· 按 OK 重试"
        switchTip.visibility = View.VISIBLE
        playFailedPending = true
            hideSplashOverlay()

        when (RelayDecision.decide(
            autoRelay = app.prefs.autoRelay,
            directFailed = true,
            userTappedRelay = false,
        )) {
            RelayDecision.Action.USE_RELAY -> {
                // 只有用户自己在设置里打开了「直连失败时自动改用中转」才走这里
                relayRetried = true
                forceRelayOnce = true
                toastTip("直连不通（${directFailureText(reason)}），已自动改走中转")
                playChannel(ch, currentStreamId)
            }
            // 默认什么都不弹：底部提示条已经说明情况，按 OK 才展开可操作面板。
            RelayDecision.Action.SHOW_ERROR, RelayDecision.Action.RETRY_DIRECT -> Unit
        }
        return true
    }

    // 播放错误不再用弹层：弹层会抢遥控器焦点（用户换不了台），
    // 也盖住画面（"影响观感"）。失败只在底部留一行提示，按 OK 就地重试。
    // 相关代码已删除，不要加回来。


    /**
     * 直连失败的静默重试余额。
     *
     * 为什么要有：直播源直连失败绝大多数是瞬时的（握手被打断、上游抖动）。
     * 第一下失败就弹盖住画面的卡片，用户会炸——"看的好好的突然弹出来"。
     * 先自己重试两次，画面不中断、不弹层；两次都失败才给底部提示条。
     */
    private var directRetryLeft = DIRECT_RETRY_MAX

    /** 服务端频道列表指纹（bootstrap 时记下，心跳里比对） */
    private var knownChannelsVersion = ""

    /** 上次拉列表的时间（毫秒）。打开面板时据此判断要不要先刷一次。 */
    private var lastListRefreshAt = 0L

    /**
     * 心跳带回频道指纹：变了就静默重载列表。
     *
     * 为什么不在重载时重新走 applyBootstrap：那个函数会重新解析"上次看的台"
     * 并可能触发自动播放，播放中调用会打断当前播放。这里只更新列表数据。
     */
    private fun onChannelsVersion(version: String) {
        if (version.isBlank() || version == knownChannelsVersion) return
        knownChannelsVersion = version
        Log.i(TAG, "频道列表有变化（$version），静默重载")
        refreshChannelsSilently()
    }

    /**
     * 只更新频道列表，不碰播放状态。
     *
     * 服务端启用了新频道 / 改了分组之后，用户按 ← 打开面板就能看到，
     * 不需要杀进程重进。
     */
    private fun refreshChannelsSilently() {
        app.api.bootstrap(
            onOk = { b ->
                if (b.channelsVersion.isNotBlank()) knownChannelsVersion = b.channelsVersion
                lastListRefreshAt = System.currentTimeMillis()
                allChannels = b.channels
                favoriteIds.clear()
                favoriteIds.addAll(b.favorites)
                groups = allChannels.map { it.group }.filter { it.isNotBlank() }.distinct()
                // 分组可能被改名/删掉了：当前选中的分组不存在就回到"全部"
                if (selGroup.isNotEmpty() && selGroup != "收藏" && !groups.contains(selGroup)) {
                    selGroup = ""
                }
                refreshCategories()
                applyChannelFilter()
                // channels 被重建过，index 必须按"当前正在播的台"重新定位，
                // 否则上下换台会跳到别的台。
                val curId = current?.id ?: 0
                index = channels.indexOfFirst { it.id == curId }.coerceAtLeast(0)
                // 静默重载会 notifyDataSetChanged，把面板里的焦点清掉。
                // 面板开着就把焦点放回原来的台——否则"隔很久再按左键开面板，
                // 焦点从频道列表消失了"（落焦点只轮询 1.5 秒，早结束了）。
                if (panelVisible) main.post { focusPanelChannel(curId) }
            },
            onErr = { /* 静默失败：下次心跳还会再试 */ },
        )
    }

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
        // 从后台回来先看是否跨天：盒子上没人会主动重启 App
        checkEpgDayRollover()
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

    /** 每小时查一次跨天。电视盒子常年不关，只靠 onResume 不够。 */
    /** 面板开着时每 20 秒刷一次进度条（30~60 分钟的节目，20 秒粒度够用）。 */
    private val panelProgressTick = object : Runnable {
        override fun run() {
            if (!panelVisible) return
            if (::panelAdapter.isInitialized) panelAdapter.tickProgress(panelList)
            main.postDelayed(this, 20_000)
        }
    }

    private val dayRolloverTick = object : Runnable {
        override fun run() {
            checkEpgDayRollover()
            main.postDelayed(this, 60 * 60 * 1000L)
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(dayRolloverTick)
        app.api.onAuthExpired = null
        // QoE：离开播放页时补一条 stop（带 watch_ms）。必须在清 Handler 之前调，
        // 因为它会走一次异步网络请求。
        reportStopIfWatched()
        main.removeCallbacksAndMessages(null)
        // 离开页面后到达的 /tv/play 回调一律作废，别再往已销毁的界面上写。
        playGate.invalidate()
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
    /**
     * 频道页焦点链：分类列 ↔ 频道列 ↔ 日期列 ↔ 节目列。
     *
     * 日期列是节目单的"选择器"，左键从节目列要回到它，不是直接跳回频道列。
     */
    private fun movePanelFocus(dir: Int) {
        when {
            // 分类列已经是最左，再按左键 = 关面板回到全屏。
            // 之前这里是死键（落到 else 分支 requestFocus 自己），
            // 想退出去只能按返回，很反直觉。
            grpList.hasFocus() && dir < 0 -> closePanel()
            grpList.hasFocus() && dir > 0 -> panelList.requestFocus()
            panelList.hasFocus() && dir < 0 -> grpList.requestFocus()
            // 频道列右键：展开节目单，焦点落在日期选择器上
            panelList.hasFocus() && dir > 0 -> {
                expandEpgPanel()
                focusDayList()
            }
            // 节目单里不管是日期列还是节目列，按左键都直接回频道列。
            // 原来只判 epgDayList，焦点在节目列时左键只是退到日期列，
            // 用户以为"没回去"。
            (epgDayList.hasFocus() || epgList.hasFocus()) && dir < 0 -> backToPanelList()
            epgDayList.hasFocus() && dir > 0 -> focusEpgList()
            epgList.hasFocus() && dir < 0 -> focusDayList()
            epgList.hasFocus() && dir > 0 -> favBtn.requestFocus()
            favBtn.hasFocus() && dir < 0 -> epgList.requestFocus()
            // 兜底
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
        panelAdapter.submit(list, nowMap)
        panelTitle.text = buildString {
            append(if (selSource == 0L) "全部频道" else (srcNames[selSource] ?: "频道"))
            if (selGroup.isNotEmpty()) append(" · ").append(selGroup)
            append("  ").append(list.size).append(" 个")
        }
        // C1：空列表不留白 —— 尤其是「收藏」分组，要告诉用户怎么把台加进来。
        val emptyHint = when {
            list.isNotEmpty() -> ""
            selGroup == "收藏" -> "还没有收藏的频道\n选中一个台，按「☆ 收藏」加入"
            selGroup.isNotEmpty() -> "这个分组里没有频道"
            else -> "没有可用频道，请先在管理台导入源"
        }
        panelEmpty.text = emptyHint
        panelEmpty.visibility = if (emptyHint.isEmpty()) View.GONE else View.VISIBLE
        // 收藏按钮的文字跟当前台的收藏状态走（★ / ☆），是 TV 上唯一的状态提示。
        updateFavButton()
    }

    /** 收藏按钮只反映"正在播 / 焦点所在"那个台的状态。 */
    private fun updateFavButton() {
        if (!::favBtn.isInitialized) return
        val pos = focusedChannelPosition()
        val ch = channels.getOrNull(pos) ?: current
        val fav = ch != null && favoriteIds.contains(ch.id)
        favBtn.text = if (fav) "★ 已收藏" else "☆ 收藏"
    }

    /**
     * 把任意焦点视图归到 RecyclerView 的直接子视图。
     *
     * findFocus() 返回的是**最深**的焦点视图（往往是行内的 TextView / LinearLayout），
     * 直接传给 getChildAdapterPosition 会抛
     * `ClassCastException: LinearLayout$LayoutParams cannot be cast to RecyclerView$LayoutParams`
     * ——实测点一下分类列就崩。
     */
    private fun recyclerChildOf(rv: RecyclerView, v: android.view.View?): android.view.View? {
        var cur = v
        while (cur != null && cur.parent !== rv) cur = cur.parent as? android.view.View
        return cur
    }

    /** 焦点所在的频道位置；焦点不在频道列时退回到正在播的台。 */
    private fun focusedChannelPosition(): Int {
        val child = recyclerChildOf(panelList, panelList.findFocus())
        if (child != null) {
            val p = panelList.getChildAdapterPosition(child)
            if (p != RecyclerView.NO_POSITION) return p
        }
        val playing = channels.indexOfFirst { it.id == current?.id }
        return if (playing >= 0) playing else 0
    }

    /** 面板里的「☆ 收藏」按钮：对焦点所在（或正在播）的台做收藏/取消。 */
    /**
     * 预取上下相邻频道的播放地址。
     *
     * 只请求签名地址（一个小 JSON），不拉流，不占带宽。
     * 结果进 Api 的内存缓存，按 ↑↓ 换台时省掉一次网络往返。
     */
    private fun prefetchNeighbours() {
        val n = channels.size
        if (n < 2 || index !in channels.indices) return
        for (d in intArrayOf(1, -1)) {
            val k = ((index + d) % n + n) % n
            channels.getOrNull(k)?.let { app.api.prefetchPlay(it.id) }
        }
    }

    private fun toggleFavoriteOnFocused() {
        val ch = channels.getOrNull(focusedChannelPosition()) ?: current ?: return
        toggleFavorite(ch)
    }

    /** OSD 上的台名：收藏过的台带一个 ★，让用户随时知道"这个台我收了没有"。 */
    private fun osdNameText(ch: Channel): String =
        if (favoriteIds.contains(ch.id)) "${ch.name}  ★" else ch.name

    /**
     * 收藏 / 取消收藏。
     *
     * 与手机端 PhoneHomeActivity.toggleFavorite 同一形态：
     * 成功 → 本地立即更新（不等下次 bootstrap）+ 重算分组与列表 + 提示；
     * 失败 → **必须**有可见反馈，不能静默（用户按了没反应会以为功能坏了）。
     */
    private fun toggleFavorite(ch: Channel) {
        val on = !favoriteIds.contains(ch.id)
        app.api.favorite(ch.id, on,
            onOk = {
                if (on) favoriteIds.add(ch.id) else favoriteIds.remove(ch.id)
                // 收藏分组的内容、左侧计数、列表星标都要立刻变
                applyGroupFilter()
                // 重提交会清掉焦点，长按完必须把焦点还给同一个台，
                // 否则用户按完收藏，列表里没有任何高亮，接着按上下不知道会跳哪。
                main.post { focusPanelChannel(ch.id) }
                // 正在播的台还要同步 OSD 上的 ★
                if (ch.id == current?.id) osdName.text = osdNameText(ch)
                toastTip(if (on) "已收藏：${ch.name}" else "已取消收藏：${ch.name}")
            },
            onErr = { msg -> toastTip("收藏失败：$msg") })
    }

    // ---------------------------------------------------------------- 右侧设置菜单

    private fun toggleSettingsMenu() {
        if (menuVisible) closeSettingsMenu() else {
            // 互斥：开设置就先收频道面板
            if (panelVisible) closePanel()
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
        subMenu?.visibility = android.view.View.GONE
        settingsMenu.visibility = android.view.View.GONE
    }


    private fun toastTip(msg: String) {
        switchTip.text = msg
        switchTip.visibility = android.view.View.VISIBLE
        main.postDelayed({ switchTip.visibility = android.view.View.GONE }, 2000)
    }

    /** 线路选择：把当前频道的线路列出来，选中就切过去 */
    /** 线路列表缓存（channelId -> 线路）。聚焦就会展开，没有缓存每次移动都请求一次。 */
    private val lineCache = HashMap<Long, List<TvStream>>()

    /** 客户端自测结果（streamId -> 结果）。5 分钟内复用，避免每次聚焦都打网络。 */
    private val lineProbeCache = HashMap<Long, LineProbe.Result>()
    private var lineProbeAt = 0L
    private val LINE_PROBE_TTL_MS = 5 * 60 * 1000L
    /** 正在探测中的 streamId，防止重复起任务 */
    private val lineProbing = HashSet<Long>()

    private fun openLinePicker() {
        val ch = current ?: return
        // 有缓存直接出，不发请求
        lineCache[ch.id]?.let { list ->
            openSubMenu(buildLineSpec(ch, list), findViewById(R.id.miLine))
            return
        }
        // 先就地显示"加载中"，不要先关菜单：取线路是异步的，
        // 关掉菜单再失败，用户看到的就是"点了没反应、菜单也没了"。
        val owner = findViewById<TextView>(R.id.miLine)
        val loading = SubSpec("线路选择", listOf("加载中…"), { 0 }) { }
        openSubMenu(loading, owner)

        app.api.streams(ch.id, onOk = { list ->
            lineCache[ch.id] = list
            val spec = buildLineSpec(ch, list)
            openSubMenu(spec, owner)
            focusSubMenu(spec)
        }, onErr = { e ->
            // 失败也留在面板里显示原因，而不是只闪一个 toast
            openSubMenu(
                SubSpec("线路选择", listOf("取线路失败：$e"), { 0 }) { },
                owner,
            )
        })
    }

    private fun isLineProbeFresh(): Boolean =
        System.currentTimeMillis() - lineProbeAt < LINE_PROBE_TTL_MS

    /**
     * 客户端自己测线路连通性。
     *
     * 对每条线路：先向 /tv/play 要签名地址，再发一个只取首字节的请求量耗时。
     * 全部测完（或超时）后重绘面板。
     */
    private fun probeLines(ch: Channel, list: List<TvStream>) {
        if (isLineProbeFresh()) return
        val todo = list.filter { it.id !in lineProbing }
        if (todo.isEmpty()) return
        lineProbing.addAll(todo.map { it.id })
        Thread({
            val results = HashMap<Long, LineProbe.Result>()
            todo.forEach { st ->
                val info = java.util.concurrent.CountDownLatch(1)
                var url = ""
                var ua = ""
                app.api.play(ch.id, st.id, onOk = { p ->
                    url = p.url; ua = p.ua; info.countDown()
                }, onErr = { info.countDown() })
                // 等签名地址最多 3 秒
                info.await(3, java.util.concurrent.TimeUnit.SECONDS)
                results[st.id] = if (url.isBlank()) LineProbe.Result(null)
                                 else LineProbe.measure(url, ua)
            }
            main.post {
                lineProbeCache.putAll(results)
                lineProbeAt = System.currentTimeMillis()
                lineProbing.removeAll(todo.map { it.id }.toSet())
                if (menuVisible && subMenu?.visibility == android.view.View.VISIBLE) {
                    // 面板还开着才重绘，避免用户已经切走还弹回来
                    val spec = buildLineSpec(ch, list)
                    openSubMenu(spec, findViewById(R.id.miLine))
                }
            }
        }, "litv-line-probe").start()
    }

    /**
     * 线路选项文案。
     *
     * 用 label + healthText，不用 quality：实测服务端下发的 quality 是空的、
     * latency_ms 是 0，拼出来就是「线路 0ms」——看着像没数据。
     */
    private fun buildLineSpec(ch: Channel, list: List<TvStream>): SubSpec {
        // 文案用**客户端自测**的结果，不用服务端下发的 health——
        // 服务端几乎不跑探测任务，health 恒为 -1，界面上就是没信息量的「未检测」。
        // 紧凑写法：选项框只有 130dp，带空格的「线路 1 · 120ms」会折成两行。
        val names = list.map { s ->
            val label = s.label.ifBlank { "线路" }
            val probe = if (isLineProbeFresh()) lineProbeCache[s.id] else null
            when {
                probe == null -> "$label …"
                probe.ok -> "$label ${probe.latencyMs}ms"
                else -> "$label 失效"
            }
        }.ifEmpty { listOf("没有可用线路") }
        val cur = list.indexOfFirst { it.id == currentStreamId }.coerceAtLeast(0)
        return SubSpec("线路选择（${list.size} 条）", names, { cur }) { i ->
            if (list.isNotEmpty()) playChannel(ch, list[i].id)
        }
    }

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
