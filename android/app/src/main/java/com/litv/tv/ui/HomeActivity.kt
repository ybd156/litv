package com.litv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电视端首页：三列联动。
 *
 *   左  频道分组
 *   中  该分组下的频道（焦点落到哪个台，右侧就切到哪个台的节目单）
 *   右  节目单：台标 + 台名 + 当前节目 + 进度条 + 节目列表
 *
 * 布局参考 TVBox 系（酷9）的 dialog_channel_group：三列并排、遥控左右切换，
 * 不用弹窗打断观看。
 *
 * 开机自动播放上次的频道（可在设置里关）——电视就该打开就能看。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var app: LitvApp
    private lateinit var main: Handler

    private lateinit var groupList: RecyclerView
    private lateinit var list: RecyclerView
    private lateinit var epgList: RecyclerView
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var groupLabel: TextView
    private lateinit var resumeBanner: TextView
    private lateinit var epgLogo: ImageView
    private lateinit var epgChannelName: TextView
    private lateinit var epgNow: TextView
    private lateinit var epgProgress: View

    private lateinit var adapter: ChannelAdapter
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var epgAdapter: EpgAdapter

    private var allChannels: List<Channel> = emptyList()
    private var groups: List<String> = emptyList()
    private var selectedGroup: String? = null
    private var epgChannelId: Long = 0
    private var autoPlayed = false

    private val favorites = mutableSetOf<Long>()
    private val nowTitles = mutableMapOf<Long, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        app = application as LitvApp
        main = Handler(Looper.getMainLooper())

        if (app.prefs.token.isBlank()) {
            startActivity(Intent(this, BindActivity::class.java))
            finish()
            return
        }

        groupList = findViewById(R.id.groupList)
        list = findViewById(R.id.channelList)
        epgList = findViewById(R.id.epgList)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        groupLabel = findViewById(R.id.groupLabel)
        resumeBanner = findViewById(R.id.resumeBanner)
        epgLogo = findViewById(R.id.epgLogo)
        epgChannelName = findViewById(R.id.epgChannelName)
        epgNow = findViewById(R.id.epgNow)
        epgProgress = findViewById(R.id.epgProgress)

        setupLists()
        renderCache()
        refresh()
        loadResume()
    }

    private fun setupLists() {
        groupAdapter = GroupAdapter().also { ga ->
            ga.onPick = { g ->
                selectedGroup = g
                renderChannels()
                // 分组切换后焦点跳到频道列表第一项
                list.post { list.requestFocus() }
            }
        }
        groupList.layoutManager = LinearLayoutManager(this)
        groupList.adapter = groupAdapter
        groupList.itemAnimator = null

        adapter = ChannelAdapter(
            app.tier.logoMaxPx,
            onClick = { ch -> openPlayer(ch) },
            onLongClick = { ch -> toggleFavorite(ch) },
            onFocus = { ch -> loadEpg(ch) },
        )
        // 三列布局里中间是竖列表，不是网格
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        epgAdapter = EpgAdapter()
        epgAdapter.onProgramClick = { p ->
            val ch = allChannels.firstOrNull { it.id == epgChannelId }
            if (ch != null) {
                // 已播完的节目可以回看；未播的暂时只提示
                val now = System.currentTimeMillis() / 1000
                if (p.endAt in 1 until now) {
                    openPlayer(ch, timeshiftFrom = p.startAt)
                } else {
                    status.text = "「${p.title}」还没播，到点再看"
                }
            }
        }
        epgList.layoutManager = LinearLayoutManager(this)
        epgList.adapter = epgAdapter
        epgList.itemAnimator = null
    }

    // ---------------------------------------------------------------- 数据

    private fun loadResume() {
        app.api.resume { rp ->
            if (rp == null || !rp.available || rp.channelId <= 0) {
                resumeBanner.visibility = View.GONE
                return@resume
            }
            resumeBanner.text = "接着看：${rp.channelName}　·　${rp.deviceName} ${rp.ageText}"
            resumeBanner.visibility = View.VISIBLE
            resumeBanner.setOnClickListener {
                openPlayer(Channel(id = rp.channelId, name = rp.channelName, logo = rp.channelLogo))
            }
        }
    }

    private fun renderCache() {
        val cached = app.prefs.cachedBootstrap
        if (cached.isBlank()) return
        runCatching { applyData(Gson().fromJson(cached, Bootstrap::class.java), fromCache = true) }
    }

    private fun refresh() {
        status.text = getString(R.string.loading)
        app.api.bootstrap(
            onOk = { b ->
                app.prefs.cachedBootstrap = Gson().toJson(b)
                applyData(b, fromCache = false)
            },
            onErr = { msg ->
                if (allChannels.isEmpty()) {
                    status.text = "加载失败：$msg"
                    if (msg.contains("未绑定") || msg.contains("token")) {
                        app.prefs.clearSession()
                        startActivity(Intent(this, BindActivity::class.java))
                        finish()
                    }
                } else {
                    status.text = "刷新失败（显示的是缓存）：$msg"
                }
            })
    }

    private fun applyData(b: Bootstrap, fromCache: Boolean) {
        b.device?.config?.let { applyRemoteConfig(it) }

        nowTitles.clear()
        nowTitles.putAll(b.now.associate { it.channelId to it.title })
        favorites.clear()
        favorites.addAll(b.favorites)

        allChannels = b.channels
        groups = b.channels.map { it.group }.filter { it.isNotBlank() }.distinct()

        renderGroups()
        renderChannels()

        status.text = if (fromCache) "已加载缓存，正在刷新…" else "更新于 ${timeNow()}"

        // 开机自动播放上次的频道：只做一次，且要等拿到真实数据
        if (!fromCache && !autoPlayed) {
            autoPlayed = true
            val last = allChannels.firstOrNull { it.id == app.prefs.lastChannelId }
            if (app.prefs.autoPlayLast && last != null) {
                openPlayer(last)
            }
        }
    }

    private fun renderGroups() {
        val rows = buildList {
            add(GroupAdapter.Row("全部", allChannels.size))
            groups.forEach { g ->
                add(GroupAdapter.Row(g, allChannels.count { it.group == g }))
            }
        }
        groupAdapter.submit(rows, selectedGroup)
    }

    private fun renderChannels() {
        val shown = if (selectedGroup == null) allChannels
        else allChannels.filter { it.group == selectedGroup }
        adapter.submit(shown, nowTitles, favorites)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        groupLabel.text = "${selectedGroup ?: "全部"} · ${shown.size} 个频道"

        // 焦点回到上次看的台
        val idx = shown.indexOfFirst { it.id == app.prefs.lastChannelId }
        if (idx >= 0) list.post { list.scrollToPosition(idx) }

        shown.firstOrNull()?.let { loadEpg(it) }
    }

    /** 三列联动的核心：焦点到哪个台，右侧就加载哪个台的节目单。 */
    private fun loadEpg(ch: Channel) {
        if (ch.id == epgChannelId) return
        epgChannelId = ch.id
        epgChannelName.text = ch.name
        epgNow.text = "正在加载节目单…"
        epgProgress.layoutParams = epgProgress.layoutParams.apply { width = 0 }
        adapter.loadLogoFor(epgLogo, ch.logo)

        app.api.epg(ch.id, 0, onOk = { progs ->
            if (epgChannelId != ch.id) return@epg // 焦点已经移走了，丢弃过期结果
            renderEpg(progs)
        }, onErr = {
            if (epgChannelId == ch.id) {
                epgNow.text = "该频道没有节目单"
                epgAdapter.submit(emptyList())
            }
        })
    }

    private fun renderEpg(progs: List<EpgProgram>) {
        epgAdapter.submit(progs)
        val now = System.currentTimeMillis() / 1000
        val cur = progs.firstOrNull { it.startAt <= now && it.endAt > now }
        if (cur == null) {
            epgNow.text = getString(R.string.no_epg)
            return
        }
        epgNow.text = "正在播：${cur.title}"
        // 进度条：当前节目已播百分比
        val total = (cur.endAt - cur.startAt).coerceAtLeast(1)
        val done = (now - cur.startAt).coerceIn(0, total)
        epgProgress.post {
            val parentW = (epgProgress.parent as? View)?.width ?: 0
            epgProgress.layoutParams = epgProgress.layoutParams.apply {
                width = (parentW * done / total).toInt()
            }
        }
        epgList.post { epgList.scrollToPosition(epgAdapter.currentPosition()) }
    }

    // ---------------------------------------------------------------- 交互

    private fun openPlayer(ch: Channel, timeshiftFrom: Long = 0) {
        app.prefs.lastChannelId = ch.id
        val i = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, ch.id)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, ch.name)
        if (timeshiftFrom > 0) i.putExtra("timeshift_from", timeshiftFrom)
        startActivity(i)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_MENU,
                android.view.KeyEvent.KEYCODE_INFO -> {
                    // 菜单键 = 设置弹层（和播放页一致）
                    SettingsDialog(this).show()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleFavorite(ch: Channel) {
        val on = !favorites.contains(ch.id)
        app.api.favorite(ch.id, on,
            onOk = {
                if (on) favorites.add(ch.id) else favorites.remove(ch.id)
                adapter.submit(adapter.items(), nowTitles, favorites)
                status.text = if (on) "已收藏「${ch.name}」" else "已取消收藏「${ch.name}」"
            },
            onErr = { status.text = "收藏失败：$it" })
    }

    private fun applyRemoteConfig(cfg: com.litv.tv.data.DeviceConfig) {
        if (cfg.engine.isNotBlank() && cfg.engine != "auto") app.prefs.engine = cfg.engine
        if (cfg.decode.isNotBlank()) app.prefs.decode = cfg.decode
        if (cfg.bufferMs > 0) app.prefs.bufferMs = cfg.bufferMs
        if (cfg.aspect.isNotBlank()) app.prefs.aspect = cfg.aspect
    }

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    override fun onResume() {
        super.onResume()
        app.remote.onCommand = { cmd -> main.post { RemoteKeys.handle(this, cmd) } }
        app.remote.connect()
        if (::adapter.isInitialized && adapter.itemCount > 0) refresh()
    }

    override fun onPause() {
        super.onPause()
        app.remote.onCommand = null
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
