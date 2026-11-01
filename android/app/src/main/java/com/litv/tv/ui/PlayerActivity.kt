package com.litv.tv.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
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
import com.litv.tv.player.EngineKind
import com.litv.tv.player.PlayerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放页。整个 TV 端体验的核心。
 *
 * 交互设计直接来自对 5 个生产级 IPTV App 的逆向（docs/research/03-apk-re.md §6.4）：
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

    private lateinit var app: LitvApp
    private lateinit var surface: SurfaceView
    private lateinit var snapshotView: ImageView
    private lateinit var loading: ProgressBar
    private lateinit var switchTip: TextView
    private lateinit var osd: LinearLayout
    private lateinit var osdName: TextView
    private lateinit var osdNow: TextView
    private lateinit var osdNext: TextView
    private lateinit var osdEngine: TextView
    private lateinit var osdProgress: ProgressBar
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelList: RecyclerView
    private lateinit var numberTip: TextView

    private lateinit var player: PlayerManager
    private lateinit var panelAdapter: ChannelAdapter

    private var channels: List<Channel> = emptyList()
    private var index = 0
    private var current: Channel? = null
    private var programs: List<EpgProgram> = emptyList()

    private val main = Handler(Looper.getMainLooper())
    private val numberBuffer = StringBuilder()
    private var osdVisible = false
    private var panelVisible = false

    // 长按判定：800ms（逆向实测值，不是 320ms）
    private val longPressRunnable = Runnable { onLongPressOk() }
    private var okDownTime = 0L

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
        player.attach(surface, findViewById(android.R.id.content))

        val id = intent.getLongExtra(EXTRA_CHANNEL_ID, 0)
        val idx = channels.indexOfFirst { it.id == id }
        index = if (idx >= 0) idx else 0
        if (channels.isNotEmpty()) playChannel(channels[index])
    }

    private fun bindViews() {
        surface = findViewById(R.id.surface)
        snapshotView = findViewById(R.id.snapshot)
        loading = findViewById(R.id.loading)
        switchTip = findViewById(R.id.switchTip)
        osd = findViewById(R.id.osd)
        osdName = findViewById(R.id.osdName)
        osdNow = findViewById(R.id.osdNow)
        osdNext = findViewById(R.id.osdNext)
        osdEngine = findViewById(R.id.osdEngine)
        osdProgress = findViewById(R.id.osdProgress)
        panel = findViewById(R.id.channelPanel)
        panelTitle = findViewById(R.id.panelTitle)
        panelList = findViewById(R.id.panelList)
        numberTip = findViewById(R.id.numberTip)
    }

    private fun loadChannels() {
        val cached = app.prefs.cachedBootstrap
        if (cached.isNotBlank()) {
            runCatching {
                val b = Gson().fromJson(cached, Bootstrap::class.java)
                channels = b.channels
            }
        }
        if (channels.isEmpty()) {
            app.api.bootstrap(onOk = { b -> channels = b.channels }, onErr = {})
        }
        panelAdapter = ChannelAdapter(app.tier.logoMaxPx) { ch ->
            closePanel()
            val i = channels.indexOfFirst { it.id == ch.id }
            if (i >= 0) {
                index = i
                playChannel(ch)
            }
        }
        panelList.layoutManager = LinearLayoutManager(this)
        panelList.adapter = panelAdapter
        panelAdapter.submit(channels, emptyMap())
        panelTitle.text = "频道列表（${channels.size}）"
    }

    // ---------------------------------------------------------------- 播放

    private fun playChannel(ch: Channel) {
        current = ch
        app.prefs.lastChannelId = ch.id

        // 换台时先抓一帧盖住，避免黑屏闪烁
        player.snapshot()?.let {
            snapshotView.setImageBitmap(it)
            snapshotView.visibility = View.VISIBLE
        }

        loading.visibility = View.VISIBLE
        switchTip.visibility = View.VISIBLE
        osdName.text = ch.name

        app.api.play(ch.id,
            onOk = { info ->
                val headers = mapOf("User-Agent" to "LITV/1.0 (Android TV)")
                player.play(ch.id, info.url, headers, app.prefs.engine)
                refreshEpg(ch.id)
                app.api.heartbeat(ch.id, "playing")
            },
            onErr = { msg ->
                loading.visibility = View.GONE
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
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts * 1000))

    // ---------------------------------------------------------------- OSD / 面板

    private fun showOsd() {
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
        panelAdapter.submit(channels, emptyMap())
        val i = channels.indexOfFirst { it.id == current?.id }
        if (i >= 0) {
            panelList.scrollToPosition(i)
            panelList.post { panelList.getChildAt(i)?.requestFocus() }
        }
        hideOsd()
    }

    private fun closePanel() {
        panelVisible = false
        panel.visibility = View.GONE
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

        // 面板打开时，方向键交给列表自己处理
        if (panelVisible) {
            if (code == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
                closePanel(); return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && action == KeyEvent.ACTION_DOWN) {
                closePanel(); return true
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
                if (action == KeyEvent.ACTION_DOWN) openPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (action == KeyEvent.ACTION_DOWN) showOsd()
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
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                if (action == KeyEvent.ACTION_DOWN) showOsd()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (osdVisible) {
                        hideOsd()
                    } else {
                        finish()
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 长按 OK：轮换内核（排查"这个台到底哪个内核能播"最实用）。 */
    private fun onLongPressOk() {
        val order = listOf(EngineKind.EXO, EngineKind.VLC, EngineKind.MPV, EngineKind.AVS3)
        val cur = EngineKind.from(app.prefs.engine) ?: EngineKind.EXO
        val next = order[(order.indexOf(cur) + 1) % order.size]
        app.prefs.engine = next.key
        current?.let { playChannel(it) }
        showOsd()
        osdEngine.text = "已切到 ${next.label}"
    }

    // ---------------------------------------------------------------- PlayerManager 回调

    override fun onEngineChanged(kind: EngineKind) {
        osdEngine.text = kind.label
    }

    override fun onPrepared(width: Int, height: Int) = Unit

    override fun onPlaying() {
        loading.visibility = View.GONE
        switchTip.visibility = View.GONE
        // 有画面了才撤掉快照遮罩
        snapshotView.postDelayed({ snapshotView.visibility = View.GONE }, 200)
    }

    override fun onError(message: String) {
        loading.visibility = View.GONE
        switchTip.text = "播放出错：$message"
        switchTip.visibility = View.VISIBLE
    }

    override fun onVideoSizeChanged(width: Int, height: Int) = Unit

    // ---------------------------------------------------------------- 生命周期

    override fun onPause() {
        super.onPause()
        player.pause()
    }

    override fun onResume() {
        super.onResume()
        player.resume()
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
}
