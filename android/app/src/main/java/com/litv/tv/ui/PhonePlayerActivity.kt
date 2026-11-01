package com.litv.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.litv.tv.LitvApp
import com.litv.tv.R
import com.litv.tv.data.Bootstrap
import com.litv.tv.data.Channel
import com.litv.tv.player.EngineKind
import com.litv.tv.player.PlayerManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 手机端播放页。
 *
 * 手势映射（手机上的肌肉记忆，和 TV 完全不同）：
 *   单击        → 呼出/收起 OSD
 *   双击        → 暂停/继续
 *   上下滑      → 换台（这是短视频时代养成的直觉）
 *   左半屏上下滑 → 音量
 *   右半屏上下滑 → 亮度
 *   底部按钮    → 上一台 / 频道 / 线路 / 下一台
 */
class PhonePlayerActivity : AppCompatActivity(), PlayerManager.Callback {

    private lateinit var app: LitvApp
    private lateinit var surface: SurfaceView
    private lateinit var loading: ProgressBar
    private lateinit var osd: LinearLayout
    private lateinit var actionBar: LinearLayout
    private lateinit var osdName: TextView
    private lateinit var osdNow: TextView
    private lateinit var osdEngine: TextView
    private lateinit var gestureTip: TextView

    private lateinit var player: PlayerManager
    private var channels: List<Channel> = emptyList()
    private var index = 0
    private var current: Channel? = null
    private var programs = emptyList<com.litv.tv.data.EpgProgram>()

    private val main = Handler(Looper.getMainLooper())
    private var osdVisible = false
    private lateinit var audio: AudioManager
    private var gestureMode = 0 // 0 无 / 1 音量 / 2 亮度 / 3 换台
    private var downY = 0f
    private var downVolume = 0
    private var downBrightness = 0.5f
    private var channelSwitched = false

    private val hideOsd = Runnable { setOsd(false) }

    private val gesture by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setOsd(!osdVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (player.let { true }) {
                    // 直播没有"暂停"概念，这里做成"重新加载当前台"
                    current?.let { playChannel(it) }
                }
                return true
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_phone_player)
        app = application as LitvApp
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        surface = findViewById(R.id.surface)
        loading = findViewById(R.id.loading)
        osd = findViewById(R.id.osd)
        actionBar = findViewById(R.id.actionBar)
        osdName = findViewById(R.id.osdName)
        osdNow = findViewById(R.id.osdNow)
        osdEngine = findViewById(R.id.osdEngine)
        gestureTip = findViewById(R.id.gestureTip)

        player = PlayerManager(this, app.prefs).also { it.callback = this }
        player.attach(surface, findViewById(android.R.id.content))

        loadChannels()
        setupGestures()
        setupVideoBox()

        findViewById<TextView>(R.id.btnPrev).setOnClickListener { switchBy(-1) }
        findViewById<TextView>(R.id.btnNext).setOnClickListener { switchBy(+1) }
        findViewById<TextView>(R.id.btnList).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnLines).setOnClickListener { showLinePicker() }

        val id = intent.getLongExtra(EXTRA_CHANNEL_ID, app.prefs.lastChannelId)
        val i = channels.indexOfFirst { it.id == id }
        index = if (i >= 0) i else 0
        if (channels.isNotEmpty()) playChannel(channels[index])
    }

    private fun loadChannels() {
        val cached = app.prefs.cachedBootstrap
        if (cached.isNotBlank()) {
            runCatching { channels = Gson().fromJson(cached, Bootstrap::class.java).channels }
        }
        if (channels.isEmpty()) {
            app.api.bootstrap(onOk = { b -> channels = b.channels }, onErr = {})
        }
    }

    private fun setupGestures() {
        surface.setOnTouchListener { _, ev ->
            gesture.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.y
                    gestureMode = 0
                    channelSwitched = false
                    downVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    downBrightness = window.attributes.screenBrightness
                        .takeIf { it >= 0f } ?: 0.5f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - downY
                    if (gestureMode == 0 && abs(dy) > 40f) {
                        gestureMode = if (ev.x < surface.width / 2f) 1 else 2
                    }
                    when (gestureMode) {
                        1 -> {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val delta = (-dy / surface.height * max).roundToInt()
                            val v = (downVolume + delta).coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            tip("音量 ${(v * 100 / max)}%")
                        }
                        2 -> {
                            val b = (downBrightness - dy / surface.height).coerceIn(0.05f, 1f)
                            val lp = window.attributes
                            lp.screenBrightness = b
                            window.attributes = lp
                            tip("亮度 ${(b * 100).roundToInt()}%")
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dy = ev.y - downY
                    // 没有触发音量/亮度且是明显的纵向滑动 → 换台
                    if (gestureMode == 0 && abs(dy) > 120f) {
                        if (dy < 0) switchBy(+1) else switchBy(-1)
                    }
                    if (gestureMode != 0) main.postDelayed({ gestureTip.visibility = View.GONE }, 600)
                    gestureMode = 0
                }
            }
            true
        }
    }

    private fun tip(msg: String) {
        gestureTip.text = msg
        gestureTip.visibility = View.VISIBLE
    }

    private fun playChannel(ch: Channel) {
        current = ch
        app.prefs.lastChannelId = ch.id
        loading.visibility = View.VISIBLE
        osdName.text = ch.name
        app.api.play(ch.id, direct = app.prefs.directMode != "relay",
            onOk = { info ->
                // 源配置里带了 UA 就用它，否则用默认
                player.play(
                    ch.id, app.api.absolute(info.url),
                    mapOf("User-Agent" to info.ua.ifBlank { "LITV/1.0 (Android)" }),
                    app.prefs.engine,
                )
                app.api.epg(ch.id, 0, onOk = { p ->
                    programs = p
                    renderNow()
                }, onErr = {})
                app.api.heartbeat(ch.id, "playing")
            },
            onErr = { msg ->
                loading.visibility = View.GONE
                tip("无法播放：$msg")
            })
    }

    private fun renderNow() {
        val now = System.currentTimeMillis() / 1000
        val cur = programs.firstOrNull { it.startAt <= now && it.endAt > now }
        osdNow.text = cur?.title ?: getString(R.string.no_epg)
        renderEpg()
    }

    private fun switchBy(delta: Int) {
        if (channels.isEmpty()) return
        index = ((index + delta) % channels.size + channels.size) % channels.size
        playChannel(channels[index])
        setOsd(true)
    }

    private fun showLinePicker() {
        val ch = current ?: return
        app.api.streams(ch.id, onOk = { list ->
            if (list.isEmpty()) { tip("只有一条线路"); return@streams }
            val labels = list.map { "${it.label} · ${it.healthText}" }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("选择线路")
                .setItems(labels) { _, which ->
                    val s = list[which]
                    app.api.play(ch.id, s.id, direct = app.prefs.directMode != "relay", onOk = { info ->
                        player.play(ch.id, app.api.absolute(info.url),
                            mapOf("User-Agent" to info.ua.ifBlank { "LITV/1.0 (Android)" }),
                            app.prefs.engine)
                    }, onErr = { tip("切换失败：$it") })
                }
                .setNegativeButton("取消", null)
                .show()
        }, onErr = { tip("获取线路失败：$it") })
    }

    /** dp → px。节目单那些 View 是代码里动态建的，只能手动换算。 */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 竖屏下名称栏和菜单栏是**常驻**的，不做自动隐藏。
     *
     * 横屏那份布局是沉浸式全屏，OSD 会 4 秒后自动收；
     * 竖屏这份把标题和菜单放在视频外面，收起来反而让版面跳。
     */
    private fun setOsd(show: Boolean) {
        osdVisible = show
        val portrait = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (portrait) {
            // 竖屏：名称栏和菜单栏在视频外面，常驻，收起反而让版面跳
            osd.visibility = View.VISIBLE
            actionBar.visibility = View.VISIBLE
            return
        }
        // 横屏：沉浸式全屏，OSD 4 秒后自动收
        osd.visibility = if (show) View.VISIBLE else View.GONE
        actionBar.visibility = if (show) View.VISIBLE else View.GONE
        main.removeCallbacks(hideOsd)
        if (show) main.postDelayed(hideOsd, 4000)
    }

    /** 视频框按 16:9 固定高度：竖屏铺满全高会把画面压成中间一条。 */
    private fun setupVideoBox() {
        val box = findViewById<View>(R.id.videoBox)
        box.post {
            val w = box.width
            if (w <= 0) return@post
            val h = w * 9 / 16
            box.layoutParams = box.layoutParams.apply { height = h }
        }
    }

    /** 节目单：当前节目高亮，其余灰。 */
    private fun renderEpg() {
        val list = findViewById<android.widget.LinearLayout>(R.id.epgList)
        list.removeAllViews()
        if (programs.isEmpty()) {
            val tv = TextView(this)
            tv.text = getString(R.string.no_epg)
            tv.setTextColor(getColor(R.color.text_secondary))
            tv.textSize = 13f
            tv.setPadding(0, dp(10), 0, dp(10))
            list.addView(tv)
            return
        }
        val now = System.currentTimeMillis() / 1000
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        programs.forEach { p ->
            val row = TextView(this)
            val playing = p.startAt <= now && p.endAt > now
            val time = "${fmt.format(java.util.Date(p.startAt * 1000))}  "
            row.text = time + p.title
            row.textSize = 14f
            row.setPadding(dp(10), dp(10), dp(10), dp(10))
            row.setTextColor(getColor(if (playing) R.color.accent else R.color.text_secondary))
            if (playing) {
                row.setTypeface(null, android.graphics.Typeface.BOLD)
                row.background = getDrawable(R.drawable.bg_card)
            }
            list.addView(row)
        }
    }

    override fun onEngineChanged(kind: EngineKind) {
        osdEngine.text = kind.label
    }

    override fun onPrepared(width: Int, height: Int) = Unit
    override fun onPlaying() { loading.visibility = View.GONE }
    override fun onError(message: String) {
        loading.visibility = View.GONE
        tip("播放出错：$message")
    }

    override fun onVideoSizeChanged(width: Int, height: Int) = Unit

    override fun onPause() { super.onPause(); player.pause() }
    override fun onResume() { super.onResume(); player.resume() }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
    }
}
