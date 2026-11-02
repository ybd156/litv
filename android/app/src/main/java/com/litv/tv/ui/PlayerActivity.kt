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
            SettingsDialog(this).show()
        }
        findViewById<TextView>(R.id.miCategory).setOnClickListener {
            closeSettingsMenu(); openPanel()
        }
        findViewById<TextView>(R.id.miList).setOnClickListener {
            closeSettingsMenu(); openPanel()
        }
        findViewById<TextView>(R.id.miDisplay).setOnClickListener {
            SettingsDialog(this).show()
        }
        findViewById<TextView>(R.id.miPrefs).setOnClickListener {
            SettingsDialog(this).show()
        }
        findViewById<TextView>(R.id.miOther).setOnClickListener {
            SettingsDialog(this).show()
        }
        epgPanel = findViewById(R.id.epgPanel)
        epgTitle = findViewById(R.id.epgTitle)
        epgList = findViewById(R.id.epgList)
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
        panelAdapter.submit(channels, emptyMap())
        panelTitle.text = "频道列表（${channels.size}）"

        epgAdapter = EpgAdapter()
        epgList.layoutManager = LinearLayoutManager(this)
        epgList.adapter = epgAdapter
    }

    /** 存全量频道 + 分组名 + 收藏，供「列表内左右键切分组」用。 */
    private fun applyBootstrap(b: Bootstrap) {
        allChannels = b.channels
        favoriteIds.clear()
        favoriteIds.addAll(b.favorites)
        groups = allChannels.map { it.group }.filter { it.isNotBlank() }.distinct()
        applyGroupFilter()
    }

    private fun applyGroupFilter() {
        // 四列模式下，过滤交给 rebuildColumns() + applyChannelFilter()。
        //
        // 这里原来还有一段单列时代的分组逻辑，其中 `allChannels = channels`
        // 会把全量列表覆盖成过滤后的结果（越点越少），
        // 后面再用 groupLabelText() 把四列的标题盖回去。整段删掉。
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
        current = ch
        app.prefs.lastChannelId = ch.id
        // 起播计时：从按下换台到出第一帧，这是用户唯一能感知的"快不快"
        startAt = System.currentTimeMillis()

        // 换台时先抓一帧盖住，避免黑屏闪烁
        player.snapshot()?.let {
            snapshotView.setImageBitmap(it)
            snapshotView.visibility = View.VISIBLE
        }

        loading.visibility = View.VISIBLE
        switchTip.visibility = View.VISIBLE
        osdName.text = ch.name

        app.api.play(ch.id, streamId, direct = app.prefs.directMode != "relay",
            onOk = { info ->
                // 源配置里带了 UA 就用它（源站校验 UA 时没它直接 403），否则用默认
                val headers = mapOf(
                    "User-Agent" to info.ua.ifBlank { "LITV/1.0 (Android TV)" },
                )
                player.play(ch.id, app.api.absolute(info.url), headers, app.prefs.engine)
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
        // 四列：源 / 分组 / 频道。这里必须走 applyChannelFilter()，
        // 原来直接 submit(channels) 会把还没加载的空列表刷进去，频道列就是空的。
        applyChannelFilter()
        refreshEpgColumn()
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

    /**
     * 刷新节目单列。
     *
     * 参考实现里节目单是紧挨频道列表常显的第三列，不是抽屉，
     * 所以开浮层时要跟着一起刷，切台时也要跟着换。
     */
    private fun refreshEpgColumn() {
        epgPanel.visibility = View.VISIBLE
        epgTitle.text = "${current?.name ?: "节目单"}"
        epgAdapter.submit(programs)
        val pos = epgAdapter.currentPosition()
        if (pos >= 0) epgList.scrollToPosition(pos)
    }

    /** 呼出节目单面板（右侧）。数据是进频道时就预取的，所以这里是瞬开。 */
    private fun openEpg() {
        epgVisible = true
        epgPanel.visibility = View.VISIBLE
        epgTitle.text = "${current?.name ?: ""} · 节目单"
        epgAdapter.submit(programs)
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
                        cycleGroup(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // 规范：焦点在频道列表时按右 → 打开节目单
                        openEpg()
                        return true
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
                if (action == KeyEvent.ACTION_DOWN) openPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (action == KeyEvent.ACTION_DOWN) openEpg()
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

    /** 长按 OK：呼出线路选择（排查"这个台哪个源能播"最实用）。 */
    private fun onLongPressOk() {
        showStreamPicker()
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

        // 上报起播耗时（只报第一次出画面，重连不重复计）
        if (startAt > 0) {
            val ms = (System.currentTimeMillis() - startAt).toInt()
            startAt = 0
            current?.let { app.api.reportStats(it.id, "start", ms) }
        }
    }

    override fun onError(message: String) {
        loading.visibility = View.GONE
        switchTip.text = "播放出错：$message"
        switchTip.visibility = View.VISIBLE
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
    private fun cycleGroup() {
        val all = listOf("") + allChannels.map { it.group }.filter { it.isNotBlank() }.distinct()
        val i = all.indexOf(selGroup)
        selGroup = all[(i + 1).mod(all.size)]
        findViewById<TextView>(R.id.grpStrip)?.text =
            (if (selGroup.isEmpty()) "全部" else selGroup) + "\n《\n频道"
        applyChannelFilter()
    }

    /** 按当前源+分组过滤，刷到频道列表 */
    private fun applyChannelFilter() {
        var list = if (selSource == 0L) allChannels else allChannels.filter { it.sourceId == selSource }
        if (selGroup.isNotEmpty()) list = list.filter { it.group == selGroup }
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
            settingsMenu.findViewById<TextView>(R.id.miLine)?.requestFocus()
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
