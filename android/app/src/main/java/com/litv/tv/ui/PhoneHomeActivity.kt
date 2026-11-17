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
        // 播放器归本 Activity 所有（和电视端一致）：
        // 全局单例 + 前台服务那套要自己维护「surface 何时有效 / 引擎换到哪 /
        // 起播进行到哪一步」，状态散在十几个字段里，退后台回来黑屏、
        // VLC 回来黑屏、首帧反复重建都是它的衍生问题。
        // 代价是每次进播放页重建一次（约 0.7 秒），换来状态干净。
        player = com.litv.tv.player.PlayerManager(this, app.prefs).also { it.callback = this }
        // 视频输出统一交给 PlayerManager：Exo / VLC / mpv 都往同一个 Surface 上画。
        // 不再用 media3 的 PlayerView——它和裸 SurfaceView 并存时，
        // 「同一个播放器再绑一次」是空操作，新 Surface 应用不上，
        // 表现就是「退出去再进黑屏、声音还在」。

        // 必须监听 Surface 生命周期，不能只在 onCreate 里 attach 一次。
        // SurfaceView 被设为 GONE（切 Tab）或 Activity 被覆盖（去设置页）时，
        // Surface 会被销毁；回来时系统会给一个**全新的** Surface，
        // 播放器还指着旧的 —— 表现就是画面全黑但台名还在。
        surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                // 首次创建：正常 attach，等上层 playChannel
                if (!surfaceReadyOnce) {
                    surfaceReadyOnce = true
                    player.attachWithHolder(h.surface, h, findViewById(R.id.videoBox))
                    // 进出全屏时 Surface 会重建：延后一拍让布局量完，再让 VLC 按新尺寸重排，
                    // 否则退出全屏会停在最后一帧。
                    h.surfaceFrame.let {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            runCatching { player.resyncWindow() }
                        }, 200)
                    }
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
                    player.attachWithHolder(h.surface, h, findViewById(R.id.videoBox))
                    runCatching { player.resume() }
                    return
                }

                // 播放器已经不在了（进程被杀/换了台）：重新拉流
                android.util.Log.i(TAG, "Surface 重建，播放器已停，重新拉流")
                recoverPlayback()
            }

            override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                player.attach(h.surface, findViewById(R.id.videoBox))
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

        player.attach(surface.holder.surface, findViewById(R.id.videoBox))
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
            btnMute.text = if (muted) "✕" else "♪"
        }

        findViewById<TextView>(R.id.btnFullscreen).setOnClickListener { toggleOrientation() }

        // 全屏工具：比例 / 内核 / 解码，点一次切下一档。
        // ★ 这三个监听器在改控件布局时被整块替换掉了 —— 按钮能收到点击却没有反应，
        //   日志里只有触摸、没有"全屏工具"就是这个问题。
        findViewById<TextView>(R.id.fsAspect)?.setOnClickListener {
            val order = listOf("auto", "16:9", "4:3", "fill")
            app.prefs.aspect = order[(order.indexOf(app.prefs.aspect).coerceAtLeast(0) + 1) % order.size]
            syncFsTools()
            applyVideoRatio()
        }
        findViewById<TextView>(R.id.fsEngine)?.setOnClickListener {
            val order = listOf("auto", "exo", "vlc", "mpv")
            app.prefs.engine = order[(order.indexOf(app.prefs.engine).coerceAtLeast(0) + 1) % order.size]
            syncFsTools()
            // 改了内核要真正重建（同台会命中"复用引擎实例"），见 restartCurrent
            restartCurrent()
        }
        findViewById<TextView>(R.id.fsDecode)?.setOnClickListener {
            val order = listOf("auto", "hard", "soft")
            app.prefs.decode = order[(order.indexOf(app.prefs.decode).coerceAtLeast(0) + 1) % order.size]
            syncFsTools()
            // 解码同理：prepare() 只在重建时才带新参数
            restartCurrent()
        }

        // 投屏：发现由服务端做（iOS 拿不到多播权限，安卓也统一走服务端）
        findViewById<TextView>(R.id.btnCast).setOnClickListener { openCastPicker() }

        // 点画面：呼出/收起控件浮层（现代播放器的通用交互）。
        // 原来点一下就直接暂停，用户完全看不到有哪些控件。
        surface.setOnClickListener { toggleControls() }
        // 画面现在渲染在 exoView / vlcLayout 里（不在 surface 上），
        // 只给 surface 挂监听就永远唤不出控件。挂到父容器 videoBox 上，
        // 子视图都设了 clickable=false，点击会冒泡到这里。
        findViewById<View>(R.id.videoBox)?.setOnClickListener { toggleControls() }
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

    /**
     * 全屏时被我们藏起来的子项 → 原来的可见性。
     * 退出全屏要**按原值**恢复：panelPlay 里的 playRoute / 日期条 / epgScroll
     * 本来各有自己的显隐逻辑，无脑设 VISIBLE 会把该藏的也放出来。
     */
    private val hiddenForFullscreen = mutableMapOf<View, Int>()

    /** 按是否全屏调整可见性与播放器高度。 */
    private fun applyFullscreen(on: Boolean) {
        findViewById<View>(R.id.bottomTabs)?.visibility = if (on) View.GONE else View.VISIBLE
        // 全屏时连频道名标题也藏掉——用户要的是"只剩画面"
        findViewById<View>(R.id.playHeader)?.visibility = if (on) View.GONE else View.VISIBLE

        // ★ 把 panelPlay 里**除视频框所在分支之外**的子项全部收起。
        //
        // 实测（2026-11-17 12:50）：只藏 epgList 不够 —— 它的父 epgScroll
        // （layout_weight=1）空壳仍吃 329px，加上 playRoute(57) 和日期条(100)，
        // 兄弟合计 486，usableH 只剩 1172-486=686 → 视频被挤成 1219×686
        // （≈屏幕宽 45%，左右各约 740px 黑边）。
        // 这里按"保留视频框分支"的方式处理，以后界面再加控件也不会漏。
        val box = findViewById<View>(R.id.videoBox)
        val panel = findViewById<android.view.ViewGroup>(R.id.panelPlay)
        if (box != null && panel != null) {
            if (on) {
                for (i in 0 until panel.childCount) {
                    val c = panel.getChildAt(i)
                    if (c === box || c === box.parent) continue
                    if (c.visibility == View.VISIBLE) {
                        hiddenForFullscreen[c] = View.VISIBLE
                        c.visibility = View.GONE
                    }
                }
            } else {
                for ((v, vis) in hiddenForFullscreen) v.visibility = vis
                hiddenForFullscreen.clear()
            }
        }
        // 沉浸式：隐藏状态栏与导航栏，下滑可临时唤出
        setSystemBarsHidden(on)
        findViewById<View>(R.id.panelChannel)?.visibility =
            if (on) View.GONE else if (currentTab == TAB_CHANNEL) View.VISIBLE else View.GONE

        findViewById<View>(R.id.fsTools)?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) syncFsTools()

        applyVideoRatio()
        // ★ 再算一次：上面这次跑的时候，被 GONE 掉的兄弟还没重新 measure，
        //   `it.height` 还是旧值（486），算出来仍是 686。
        //   等布局落定后再算，才能拿到"兄弟全部收起后"的真实可用高度。
        findViewById<View>(R.id.videoBox)?.postDelayed({ applyVideoRatio() }, 120)
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

        // 竖屏：保持原样（宽度铺满、高度按 16:9）。比例档位只影响全屏，
        // 之前把宽度也改成计算值、还改了父容器 gravity，竖屏布局直接被搞乱。
        // 方向以**当前配置**为准，不信缓存字段（见 onConfigurationChanged 的说明）
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            if (box.width > 0) videoHeight169 = box.width * 9 / 16
            lp.height = if (videoHeight169 > 0) videoHeight169
                         else ViewGroup.LayoutParams.WRAP_CONTENT
            box.layoutParams = lp
        // 视频区尺寸变了：VLC 必须重新同步窗口，否则画面卡在旧尺寸（全屏后卡死）
        // 布局还没量完时取到的是旧尺寸，延后一拍再同步（全屏后画面才会铺满）
        box.postDelayed({ runCatching { if (::player.isInitialized) player.resyncWindow() } }, 150)
            (parent as? LinearLayout)?.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            return
        }

        // 画面比例：auto/16:9 走 16:9，4:3 走 4:3，fill 铺满可用空间。
        // 以前这里写死 16:9，所以点「比例」只改标签、画面纹丝不动。
        val ratio = when (app.prefs.aspect) {
            "4:3" -> 4f / 3f
            "fill" -> 0f          // 0 表示铺满
            else -> 16f / 9f
        }
        if (ratio == 0f) {
            lp.width = availW
            lp.height = availH
            box.layoutParams = lp
            (parent as? LinearLayout)?.gravity = android.view.Gravity.CENTER
            return
        }

        // 按源比例 **同时受宽高约束**：
        // 只按宽度算的话，横屏 2712×9/16=1526 > 屏高 1220，画面会比屏幕还高，
        // 控件被顶到可视区外 —— 就是"全屏控件不见了、画面放大到看不见"。
        //
        // ★ 可用高度必须扣掉同容器里的**其它可见子项**（顶部标题栏 playHeader 78px 等）。
        //   真机实测（2026-11-17）：不扣的话按 parent.height=932 算出 16:9 的 h=932，
        //   而 LinearLayout 只给 box 854 → 里面的 AspectRatioFrameLayout 仍量成 932 高，
        //   多出的 78px 落到 tab 栏背后，**字幕被从中间切断**。
        //   用"父高度 − 兄弟可见高度"是确定性的，不依赖 measure 时序
        //   （之前用 box.post 读真实高度，post 跑在最终 measure 之前，读到 932 就跳过了）。
        val siblingsH = (parent as? android.view.ViewGroup)?.let { g ->
            (0 until g.childCount)
                .map { g.getChildAt(it) }
                .filter { it !== box && it.visibility == android.view.View.VISIBLE }
                .sumOf { it.height.coerceAtLeast(0) }
        } ?: 0
        val usableH = (availH - siblingsH).coerceAtLeast(1)

        var w = availW
        var h = (availW / ratio).toInt()
        if (h > usableH) {
            h = usableH
            w = (usableH * ratio).toInt()
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
        // ★ 所有分支最后都要同步一次：之前只在 fill 那一支里加了，
        //   16:9/4:3 分支走不到，全屏后 VLC 还按竖屏尺寸渲染。
        box.postDelayed({ runCatching { if (::player.isInitialized) player.resyncWindow() } }, 150)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // ★ 必须先把方向状态同步过来，再算尺寸。
        //   真机 bug（2026-11-17）：`landscape` 只在 App 自己的全屏按钮里翻转，
        //   系统旋转（或 adb 改 user_rotation）时它还是旧值 →
        //   applyVideoRatio 按竖屏逻辑算：宽度 MATCH_PARENT + 高度沿用竖屏缓存的 686
        //   → 容器变成 2624×686，16:9 内容只能左右留 700px 黑边。
        landscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        // ★ 横屏自动进全屏（隐藏标题栏 / 底部 tab / 系统栏）。
        //   不这么做的话，横屏可用高度只剩 932-246=686，视频只能撑到屏幕宽的 44.9%
        //   （左右各约 740px 黑边）—— 用户要的"横屏铺满"就是这个。
        //
        //   只在「播放」页生效：频道列表页在横屏仍需要底部 tab 才能切页，
        //   藏起来用户就没法操作了。
        applyFullscreen(landscape && currentTab == TAB_PLAY)

        // ★ 还要等**父容器真的换过尺寸**再算。
        //   直接 `box.post { applyVideoRatio() }` 会跑在父容器 re-measure 之前，
        //   `parent.width/height` 还是竖屏旧值（实测拿到 1220×2712）→
        //   横屏下算出 1220×686 的容器，高度根本没撑开（内容占宽 46.5%）。
        //   所以挂一个一次性布局监听：尺寸真变了才重算；再加 200ms 兜底。
        val box = findViewById<View>(R.id.videoBox) ?: return
        val parent = box.parent as? View ?: run {
            box.post { applyVideoRatio() }
            return
        }
        val oldW = parent.width
        val oldH = parent.height
        val once = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, or_: Int, ob: Int,
            ) {
                if (v.width == oldW && v.height == oldH) return
                v.removeOnLayoutChangeListener(this)
                applyVideoRatio()
            }
        }
        parent.addOnLayoutChangeListener(once)
        parent.postDelayed({
            runCatching {
                parent.removeOnLayoutChangeListener(once)
                applyVideoRatio()
            }
        }, 200)
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
                player.attach(sv, findViewById(R.id.videoBox))
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

    private var loadStarted = false

    private fun load() {
        // 只加载一次：onCreate/onStart 都可能触发，重复跑会建出两套数据，
        // 界面上表现为"有节目单的播放页"和"没有节目单的同款页面"来回切。
        if (loadStarted) return
        loadStarted = true
        val cached = app.prefs.cachedBootstrap
        if (cached.isNotBlank()) {
            runCatching { apply(Gson().fromJson(cached, Bootstrap::class.java), true) }
        }
        app.api.bootstrap(
            onOk = { b ->
                android.util.Log.i(
                    TAG,
                    "bootstrap: clientParse=${b.clientParseFlag != 0} channels=${b.channels.size} " +
                        "version=${b.channelsVersion} epg=${b.epgUrl.isNotEmpty()}",
                )
                app.prefs.cachedBootstrap = Gson().toJson(b)
                apply(b, false)
            },
            onErr = { msg ->
                android.util.Log.w(TAG, "bootstrap 失败：$msg（频道来自缓存 ${all.size} 个）")
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
        android.util.Log.i(
            TAG,
            "EPG 匹配 ${fresh.size}/${all.size}；示例 epgId=" +
                all.take(3).joinToString(",") { it.epgId.ifBlank { "(空)" } },
        )
        if (fresh.isEmpty()) return
        nowTitles.clear()
        nowTitles.putAll(fresh)
        render()

        // ★ 下载完成后必须**整页重绘**：日期条只在初始化时建过一次，
        //   首次绑定进来时节目单为空 → 日期条也是空的 → 之后即使数据到了也不会重建，
        //   用户看到的就是"节目单不显示，退出重进才有"。
        if (currentTab == TAB_PLAY) {
            dayInit = false
            ensureDateBar()
            renderEpg()
        }
    }

    private fun apply(b: Bootstrap, fromCache: Boolean) {
        // 网络回来的才可信；缓存里可能没有 client_parse 字段
        if (!fromCache) app.prefs.clientParseSeen = b.clientParseFlag != 0
        all = b.channels
        favorites.clear(); favorites.addAll(b.favorites)
        nowTitles.clear(); nowTitles.putAll(b.now.associate { it.channelId to it.title })
        // ★ 手机界面以前**从来没有调用过 app.epg.ensure()**，节目单根本没下载，
        //   而服务端 bootstrap 的 now 又是空的（重建后就没数据了），
        //   所以手机端一行节目名都没有。
        //   现在自己下载 xmltv 并按本地节目单算「正在播」，服务端 now 只作兜底。
        app.epg.ensure(b.epgUrl) { main.post { refreshFromEpg() } }
        // 只从**网络**那次触发：缓存那次也会进来，两次都拉就重复下载 m3u，
        // 并把结果回调到可能已销毁的实例上（表现是"两个界面"）。
        if (!fromCache &&
            (b.clientParseFlag != 0 || app.prefs.clientParseSeen || app.prefs.channelSource == "client")
        ) {
            com.litv.tv.data.M3uClient.load(this, app.api, app.prefs.dlKey, app.epg) { parsed ->
                if (parsed != null && parsed.channels.isNotEmpty()) {
                    all = parsed.channels
                    if (waitingForParse) {
                        waitingForParse = false
                        val target = all.firstOrNull { it.name == pendingChannelName }
                            ?: all.firstOrNull()
                        if (target != null) playChannel(target)
                    }
                    groups = all.map { it.group }.filter { it.isNotBlank() }.distinct()
                    status.text = "客户端解析 ${all.size} 个频道"
                    // 把 currentChannel 指向新列表里同一个台：
                    // 节目单是按 currentChannel.epgId 查的，老对象是服务端频道，
                    // 不换就会一直「暂无节目单」。
                    currentChannel?.let { old ->
                        all.firstOrNull { it.name == old.name }?.let { currentChannel = it }
                    }
                    renderChips()
                    render()
                    // 频道表换了，必须重算"正在播"并重绘节目单，
                    // 否则播放页的节目单还是按旧频道算的（表现：节目单空）
                    dayInit = false
                    refreshFromEpg()
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

    /** 把播放头里的 UA 拼到链路文案（为空就返回空串） */
    private fun uaLine(h: Map<String, String>): String {
        val ua = h["User-Agent"].orEmpty()
        return if (ua.isBlank()) "" else " · UA: " + ua.take(30)
    }

    /** 是否客户端解析模式（服务端 D1 已不存频道表，服务端播放路径只是残留） */
    private fun clientMode(): Boolean =
        app.prefs.clientParseSeen || app.prefs.channelSource == "client"

    /** 等客户端解析完成后再起播 */
    private var waitingForParse = false

    /** 等待解析时记下想播的台名，解析完成后按名字找回 */
    private var pendingChannelName: String = ""

    private fun playChannel(ch: Channel) {
        controlsFlashedForChannel = false
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
        if (clientMode() && com.litv.tv.data.M3uClient.urlOf(ch.id) == null) {
            // 客户端模式：地址只能来自本地解析。
            // 解析还没完成就**等**——绝不能退回服务端签名地址，
            // 否则同一个版本里两套地址混用，用户看到"一会儿服务端一会儿客户端"。
            // 解析结果已经有了、只是这个 id 对不上（比如 currentChannel 还是服务端那份），
            // 就按名字在解析结果里重新找一次；找不到再等，避免"停在正在解析"再也起不来。
            val parsed = com.litv.tv.data.M3uClient.current
            if (parsed != null && parsed.channels.isNotEmpty()) {
                val alt = parsed.channels.firstOrNull { it.name == ch.name }
                if (alt != null && com.litv.tv.data.M3uClient.urlOf(alt.id) != null) {
                    currentChannel = alt
                    playChannel(alt)
                    return
                }
            }
            val mc = com.litv.tv.data.M3uClient.current
            android.util.Log.w(
                TAG,
                "waiting: ch=${ch.id}/${ch.name} url=${com.litv.tv.data.M3uClient.urlOf(ch.id)} " +
                    "current=${mc != null} count=${mc?.channels?.size ?: -1} " +
                    "hasThisId=${mc?.channels?.any { it.id == ch.id } ?: false}",
            )
            waitingForParse = true
            // 兜底：8 秒还没等到地址，就挑第一个有地址的台起播，绝不无限停在"正在解析"
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!waitingForParse) return@postDelayed
                val alt = com.litv.tv.data.M3uClient.current?.channels
                    ?.firstOrNull { com.litv.tv.data.M3uClient.urlOf(it.id) != null }
                if (alt != null) {
                    android.util.Log.w(TAG, "等待解析超时，改用 ${alt.name} 起播")
                    waitingForParse = false
                    currentChannel = alt
                    player.stop()
                    playChannel(alt)
                } else {
                    waitingForParse = false
                    playHint.text = "频道地址解析失败，请换台或重试"
                    playHint.visibility = View.VISIBLE
                    android.util.Log.w(TAG, "等待解析超时且无可用地址")
                }
            }, 8000)
            // 这个 ch 来自**缓存的服务端频道**，它的 id 在客户端解析结果里不存在，
            // 拿它去重播会永远等下去（用户看到的就是"一直解析"）。
            // 记下名字，解析完成后按名字重新定位。
            pendingChannelName = ch.name
            currentChannel = null
            playRoute.text = "正在解析频道…"
            loading.visibility = View.VISIBLE
            playHint.text = "正在解析频道地址…"
            playHint.visibility = View.VISIBLE
            return
        }

        val directUrl = com.litv.tv.data.M3uClient.urlOf(ch.id)
        if (directUrl != null) {
            // 地址是客户端自己从源 m3u 里解析出来的
            playRoute.text = "客户端直连（本地解析）" + uaLine(com.litv.tv.data.M3uClient.headersOf(ch.id))
            player.play(ch.id, directUrl, com.litv.tv.data.M3uClient.headersOf(ch.id), app.prefs.engine)
            // ★ 这里 return 会跳过下面那段"本地节目单优先"的加载，
            //   于是 programs 一直是空的 → 界面永远「暂无节目单」。
            //   客户端模式下必须在这里自己把节目单填上。
            val epgId = ch.epgId
            val local = if (epgId.isBlank()) null else app.epg.programsFor(epgId)
            programs = local?.map {
                com.litv.tv.data.EpgProgram(
                    startAt = it.start, endAt = it.end, title = it.title,
                    description = it.description, category = it.category,
                )
            } ?: emptyList()
            dayInit = false
            renderNow()
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
        val cacheUsable = !clientMode() && cached != null && !replayingSameChannel   // 留 60 秒余量，避免刚好卡在过期边界

        if (cacheUsable) {
            android.util.Log.i(TAG, "缓存起播 ${ch.name}，签名还剩 ${cached!!.expires - nowSec}s")
            playRoute.text = (if (cached.mode == "direct") "服务端签名直连（缓存地址）" else "服务端中转（缓存地址）") +
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
                val modeText = if (info.mode == "direct") "服务端签名直连" else "服务端中转"
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
    /** 节目单还没就绪（首次绑定后要下 xmltv）时给一行明确提示，而不是空白。 */
    private fun showEpgLoadingIfNeeded() {
        if (app.epg.ready) return
        val tip = findViewById<android.widget.TextView>(R.id.playHint) ?: return
        tip.visibility = android.view.View.VISIBLE
        tip.text = "正在下载节目单…（首次需要几秒到几十秒）"
    }

    private fun renderEpg() {
        showEpgLoadingIfNeeded()
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
        // ★ 首页预览框**不再给 VLC 传 VLCVideoLayout**。
        //
        // 真机实测（2026-11-17）：只要用 VLCVideoLayout，librtcp 就把内层
        // `surface_video` 算成 686×386（把容器高度当成宽度做方窗），
        // 1220×686 的框里画面只占 56%，居中或贴左上。
        // 我们把它压成 MATCH_PARENT 也没用 —— 布局压缩成功 1 次后
        // libVLC 又在下一帧写回 686×386（日志：`压回 1220x686（第 1/20 次）` 之后再没触发）。
        //
        // 现在改成和其他内核一样，用布局里那个普通 SurfaceView：
        // VLC 走 `setVideoSurface` + `attachViews()`，**不接管内层尺寸**，
        // SurfaceView 是 match_parent 的 1220×686，画面就按它铺满。
        // 全屏播放页（activity_phone_player.xml）本来就是这个结构，一直是好的。
        findViewById<android.view.View>(R.id.surface)?.visibility = android.view.View.VISIBLE
        findViewById<org.videolan.libvlc.util.VLCVideoLayout>(R.id.vlcLayout)?.visibility =
            android.view.View.GONE
        // 显式清掉，避免引擎还握着那块 layout
        player.setVlcLayout(null)
    }

    override fun onPrepared(width: Int, height: Int) = Unit

    private fun loadReminders() {
        reservedStarts.clear()
        reservedStarts.addAll(com.litv.tv.data.ReminderStore.reservedStarts(this))
    }

    /** 本次选台是否已经"闪"过一次控件（避免卡顿/重连时反复弹出） */
    private var controlsFlashedForChannel = false

    override fun onPlaying() {
        // 播放页在这个 Activity 里（底部「播放」标签），不是 PhonePlayerActivity，
        // 所以常亮必须在这里加，否则看着看着就黑屏。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loading.visibility = View.GONE
        playHint.visibility = View.GONE
        // 只在**这个台第一次出画面**时闪一下控件。
        // 以前每次进 playing 都闪：缓冲卡一下、重连一次就冒出一条控件，
        // 看着像界面在抽搐。
        // 出画面说明地址已就绪：清掉"正在解析"的等待态，避免残留卡在那句话上
        if (waitingForParse) {
            waitingForParse = false
            playHint.visibility = View.GONE
            playRoute.text = "客户端直连（本地解析）" +
                uaLine(currentChannel?.let { com.litv.tv.data.M3uClient.headersOf(it.id) } ?: emptyMap())
        }
        if (!controlsFlashedForChannel) {
            controlsFlashedForChannel = true
            showControls()
        }
        // 兜底：控件曾因自动隐藏变 GONE，这里确保它在起播后可见（4 秒后照常自动隐藏）
        findViewById<View>(R.id.playerControls)?.let {
            if (it.visibility != View.VISIBLE) showControls()
        }
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

    override fun onVideoSizeChanged(width: Int, height: Int, frameRate: Float) {
        // 画面比例：PlayerView 拿掉之后由这里负责。
        // auto 用实际视频比例（未知时不动，避免 0 尺寸把画面算没），
        // 16:9 / 4:3 强制比例，fill 拉伸、zoom 裁切、其余留黑边。
        val box = findViewById<androidx.media3.ui.AspectRatioFrameLayout>(R.id.videoAspect) ?: return
        val mode = app.prefs.aspect
        val ratio = when (mode) {
            "16:9" -> 16f / 9f
            "4:3" -> 4f / 3f
            else -> if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f
        }
        if (ratio > 0f) box.setAspectRatio(ratio)
        box.resizeMode = when (mode) {
            "fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** 用当前偏好的内核/解码重起当前台。currentChannel 可能是 null，要能兜住。 */
    private fun restartCurrent() {
        // 优先选**本地解析里有地址**的台：currentChannel 可能是服务端那份对象，
        // 它的 id 在解析结果里不存在，拿去重启会永远停在「正在解析频道地址…」。
        val hasUrl = { ch: Channel -> com.litv.tv.data.M3uClient.urlOf(ch.id) != null }
        val target = currentChannel?.takeIf(hasUrl)
            ?: all.firstOrNull { it.id == player.playingChannelId && hasUrl(it) }
            ?: all.firstOrNull(hasUrl)
            ?: currentChannel
            ?: all.firstOrNull()
        if (target == null) {
            android.util.Log.w(TAG, "restartCurrent: 没有可用频道，跳过")
            return
        }
        android.util.Log.i(TAG, "restartCurrent: ${target.name} 内核=${app.prefs.engine} 解码=${app.prefs.decode}")
        currentChannel = target
        val pm = player
        // 停当前引擎也是重活（libvlc 释放要几秒），放到后台，完成后再起播
        Thread {
            runCatching { pm.stop() }
            android.os.Handler(android.os.Looper.getMainLooper()).post { playChannel(target) }
        }.start()
    }

    private var fsBound = false

    /** 全屏工具监听器：在 onResume 里挂（确保视图已就位），只挂一次。 */
    private fun bindFsTools() {
        if (fsBound) return
        val a = findViewById<TextView>(R.id.fsAspect)
        val e = findViewById<TextView>(R.id.fsEngine)
        val d = findViewById<TextView>(R.id.fsDecode)
        android.util.Log.i(TAG, "bindFsTools: aspect=${a != null} engine=${e != null} decode=${d != null}")
        if (a == null || e == null || d == null) return
        a.setOnClickListener {
            android.util.Log.i(TAG, "全屏工具：比例 点击")
            val order = listOf("auto", "16:9", "4:3", "fill")
            app.prefs.aspect = order[(order.indexOf(app.prefs.aspect).coerceAtLeast(0) + 1) % order.size]
            syncFsTools(); applyVideoRatio()
        }
        e.setOnClickListener {
            android.util.Log.i(TAG, "全屏工具：内核 点击")
            val order = listOf("auto", "exo", "vlc", "mpv")
            app.prefs.engine = order[(order.indexOf(app.prefs.engine).coerceAtLeast(0) + 1) % order.size]
            syncFsTools()
            restartCurrent()   // 真正生效：重起当前台
        }
        d.setOnClickListener {
            android.util.Log.i(TAG, "全屏工具：解码 点击")
            val order = listOf("auto", "hard", "soft")
            app.prefs.decode = order[(order.indexOf(app.prefs.decode).coerceAtLeast(0) + 1) % order.size]
            syncFsTools()
            restartCurrent()   // 真正生效：重起当前台
        }
        fsBound = true
    }

    override fun onResume() {
        bindFsTools()
        resumeOrRebuildAfterBackground()
        // Activity 重建后引擎没变，onEngineChanged 不会回调，
        // 右上角的解码器标签就会空着。这里从播放器补一次。
        if (::player.isInitialized) {
            val lbl = player.engineLabel()
            if (lbl.isNotBlank()) playEngine.text = lbl
            // 渲染目标可见性也要补：VLC 现在和别的内核一样用普通 SurfaceView
            //（不再走 vlcLayout，见 onEngineChanged 的说明），所以这里只要保证
            // surface 可见、vlcLayout 不可见即可。冷启动时 onEngineChanged 不触发，
            // 不补这一次就会出现"已经播了但画面不出来"。
            findViewById<android.view.View>(R.id.surface)?.visibility = android.view.View.VISIBLE
            findViewById<android.view.View>(R.id.vlcLayout)?.visibility = android.view.View.GONE
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

    /**
     * 退后台超过这个秒数就不再续播，直接重建播放器。
     *
     * 为什么只有 2 秒：pause() 之后这台设备的硬件解码器经常**再也出不了帧**——
     * 回来 resume() 是"已起播、无画面"，要等 5 秒无帧看门狗把硬解换成软解
     * 才能看。用户看到的就是「退后台一会再打开黑屏，一会儿自己换解码器才用」。
     * 重建一次约 1 秒（播放器实例还会复用），比黑 5 秒等降级快得多。
     *
     * 打开设置/遥控器不会走到这里：那种情况 onPause 被跳过，没记后台起点。
     */
    private val resumeMaxBgSec = 2L

    /** 上一次**真正**暂停的时刻（ms）；0 = 没有在后台暂停过。 */
    private var pausedAtMs = 0L

    /**
     * 从后台回来：极短（<2s）续播，其余一律重建播放器。
     *
     * 另有一层保险：后台超过 60 秒时，客户端解析模式下上游 token 可能已经轮换，
     * 先重新解析一次 m3u 再起播。
     */
    private fun resumeOrRebuildAfterBackground() {
        // 标准做法（官方 Media3 指南）：resume 只 resume，不重建播放器。
        // 以前这里「后台超过 2 秒就整个重建」，是当初把「退后台回来黑屏」
        // 误判成解码器问题加上的补丁；真正原因是重复 Activity 实例
        // （singleTask 已修）。这个补丁在 VLC 上直接表现为回来黑屏
        // ——vlcLayout 的 Surface 接不上。
        // 现在 Exo / VLC 走同一条路：Surface 重建交给 surfaceCreated，
        // 换台交给 playChannel，resume 不触发任何重建。
        if (!::player.isInitialized) return
        pausedAtMs = 0L
        val ch = currentChannel ?: return
        if (paused) return
        if (ch.id != player.playingChannelId) return
        runCatching { player.resume() }
    }

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
        pausedAtMs = System.currentTimeMillis()
        android.util.Log.i(TAG, "onPause: 记录后台起点")
        player.pause()
    }

    override fun onDestroy() {
        app.api.onAuthExpired = null
        super.onDestroy()
        // 播放器归本 Activity：界面走就释放，状态归零。
        if (::player.isInitialized) {
            player.callback = null
            player.attach(null, null)
            runCatching { player.release() }
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
