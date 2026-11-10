package com.litv.tv.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.litv.tv.LitvApp
import com.litv.tv.R
import com.litv.tv.data.DohDns
import com.litv.tv.data.Keymap
import com.litv.tv.data.PlaybackDefaults
import com.litv.tv.data.UiScaleOptions
import com.litv.tv.player.EngineKind

/**
 * 设置页。
 *
 * TV 上的设置页有个硬要求：**不能有需要打字/滑动的控件**。
 * 所以全部做成「一行一个选项，左右键循环切换，OK 确认」，
 * 用遥控器上下走、左右改，老人也能改明白。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var app: LitvApp

    private lateinit var valueEngine: TextView
    private lateinit var valueDecode: TextView
    private lateinit var valueBuffer: TextView
    private lateinit var valueAspect: TextView
    private lateinit var valueReverse: TextView
    private lateinit var valueAv3a: TextView
    private lateinit var valueAutoPlay: TextView
    private lateinit var valueNumberTimeout: TextView
    private lateinit var valueUiMode: TextView
    private lateinit var valueStartTimeout: TextView
    private lateinit var valueRetry: TextView
    private lateinit var valueDoh: TextView
    private lateinit var valueDohProvider: TextView
    private lateinit var valueAutoRelay: TextView
    private lateinit var valueUiScale: TextView
    private lateinit var valueShowStatus: TextView
    private lateinit var valueKeymap: TextView
    private lateinit var valueDevice: TextView
    private lateinit var valueServer: TextView
    private lateinit var status: TextView

    private val engines = listOf("auto", "exo", "vlc", "mpv", "avs3")
    private val decodes = listOf("auto", "hard", "soft")
    private val buffers = listOf(1000, 2000, 3000, 5000, 8000)
    private val aspects = listOf("auto", "16:9", "4:3", "fill")
    private val numTimeouts = listOf(1500, 2000, 2500, 3000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 这个页面电视和手机共用，操作提示得按设备类型给
        findViewById<TextView>(R.id.settingsHint)?.text =
            if (DeviceType.isTV(this)) "用遥控器上下移动，左右键修改，OK 确认"
            else "点按条目切换，长按可查看说明"
        BottomTabs.wire(this, BottomTabs.TAB_SETTINGS)
        app = application as LitvApp

        valueEngine = findViewById(R.id.valueEngine)
        valueDecode = findViewById(R.id.valueDecode)
        valueBuffer = findViewById(R.id.valueBuffer)
        valueAspect = findViewById(R.id.valueAspect)
        valueReverse = findViewById(R.id.valueReverse)
        valueAv3a = findViewById(R.id.valueAv3a)
        valueAutoPlay = findViewById(R.id.valueAutoPlay)
        valueNumberTimeout = findViewById(R.id.valueNumberTimeout)
        valueUiMode = findViewById(R.id.valueUiMode)
        valueStartTimeout = findViewById(R.id.valueStartTimeout)
        valueRetry = findViewById(R.id.valueRetry)
        valueDoh = findViewById(R.id.valueDoh)
        valueDohProvider = findViewById(R.id.valueDohProvider)
        valueAutoRelay = findViewById(R.id.valueAutoRelay)
        valueUiScale = findViewById(R.id.valueUiScale)
        valueShowStatus = findViewById(R.id.valueShowStatus)
        valueKeymap = findViewById(R.id.valueKeymap)
        valueDevice = findViewById(R.id.valueDevice)
        valueServer = findViewById(R.id.valueServer)
        status = findViewById(R.id.status)

        bindCycle(R.id.rowEngine, valueEngine, engines, app.prefs.engine) { v ->
            app.prefs.engine = v; labelEngine(v)
        }
        bindCycle(R.id.rowDecode, valueDecode, decodes, app.prefs.decode) { v ->
            app.prefs.decode = v; labelDecode(v)
        }
        bindCycle(R.id.rowBuffer, valueBuffer, buffers.map { it.toString() },
            app.prefs.bufferMs.toString()) { v ->
            app.prefs.bufferMs = v.toInt(); labelBuffer(v.toInt())
        }
        bindCycle(R.id.rowAspect, valueAspect, aspects, app.prefs.aspect) { v ->
            app.prefs.aspect = v; labelAspect(v)
        }
        bindCycle(R.id.rowReverse, valueReverse, listOf("off", "on"),
            if (app.prefs.reverseChannelKeys) "on" else "off") { v ->
            app.prefs.reverseChannelKeys = (v == "on"); labelReverse(v == "on")
        }
        // AV3A 这行不走 bindCycle：那边会在回调后把文字覆盖成 "off"/"on"，
        // 而我们要显示的是加载状态（"开 · 已加载" / "开 · 未找到解码库"）。
        findViewById<View>(R.id.rowAv3a).setOnClickListener {
            app.prefs.av3aEnabled = !app.prefs.av3aEnabled
            labelAv3a()
        }
        labelAv3a()
        bindCycle(R.id.rowAutoPlay, valueAutoPlay, listOf("off", "on"),
            if (app.prefs.autoPlayLast) "on" else "off") { v ->
            app.prefs.autoPlayLast = (v == "on")
            valueAutoPlay.text = if (v == "on") "开（打开就看）" else "关（先进列表）"
        }
        bindCycle(R.id.rowNumberTimeout, valueNumberTimeout,
            numTimeouts.map { it.toString() }, app.prefs.numberJumpTimeoutMs.toString()) { v ->
            app.prefs.numberJumpTimeoutMs = v.toInt(); labelNumTimeout(v.toInt())
        }

        // 起播超时：慢设备/走代理时 3 秒会误判成"起不来"，这里可调。
        // 候选与默认值都来自 PlaybackDefaults（单一来源）；
        // wrap = false：这是唯一一条"越界代价很大"的行——15 秒上的用户
        // 再点一下不应该直接掉到 3 秒（那等于把起播判死）。
        bindCycle(R.id.rowStartTimeout, valueStartTimeout,
            PlaybackDefaults.START_TIMEOUT_OPTIONS_MS.map { it.toString() },
            app.prefs.startTimeoutMs.toString(), wrap = false) { v ->
            app.prefs.startTimeoutMs = v.toIntOrNull() ?: PlaybackDefaults.START_TIMEOUT_MS
            labelStartTimeout(app.prefs.startTimeoutMs)
        }

        // 超时重试次数：网络抖动时多试几次比直接换内核好
        bindCycle(R.id.rowRetry, valueRetry,
            PlaybackDefaults.RETRY_OPTIONS.map { it.toString() },
            app.prefs.retryCount.toString()) { v ->
            app.prefs.retryCount = v.toIntOrNull() ?: PlaybackDefaults.RETRY_COUNT
            labelRetry(app.prefs.retryCount)
        }

        bindCycle(R.id.rowUiMode, valueUiMode, listOf("auto", "tv", "phone"),
            app.prefs.uiMode) { v ->
            app.prefs.uiMode = v
            labelUiMode(v)
        }

        // ---- 网络：加密 DNS / 直连策略 ----
        bindCycle(R.id.rowDoh, valueDoh, listOf("auto", "off"), app.prefs.dohMode) { v ->
            app.prefs.dohMode = v
            // 立刻生效：不用等下次启动（否则用户改了设置、当场换台还是旧解析）
            DohDns.configure(app.prefs.dohEnabled(), app.prefs.dohProvider)
            labelDoh(v)
        }
        bindCycle(R.id.rowDohProvider, valueDohProvider,
            DohDns.Provider.entries.map { it.key }, app.prefs.dohProvider) { v ->
            app.prefs.dohProvider = v
            DohDns.configure(app.prefs.dohEnabled(), v)
            labelDohProvider(v)
        }
        bindCycle(R.id.rowAutoRelay, valueAutoRelay, listOf("off", "on"),
            if (app.prefs.autoRelay) "on" else "off") { v ->
            app.prefs.autoRelay = (v == "on")
            labelAutoRelay(v == "on")
        }

        // 设备信息（只读）
        findViewById<View>(R.id.rowDevice).setOnClickListener {
            status.text = "渲染档位 ${app.tier.name} · Android ${Build.VERSION.SDK_INT} · " +
                "${Build.MANUFACTURER} ${Build.MODEL}"
        }
        valueDevice.text = "${Build.MANUFACTURER} ${Build.MODEL}".take(22)
        valueServer.text = app.prefs.serverBase

        // ---- 界面 ----
        bindCycle(R.id.rowUiScale, valueUiScale, UiScaleOptions.keys, app.prefs.uiScale) { v ->
            app.prefs.uiScale = v
            labelUiScale(v)
            // 立即生效：下次进频道面板就能看到新字号/行高
            status.text = "已保存 · 立即生效"
        }
        bindCycle(R.id.rowShowStatus, valueShowStatus, listOf("on", "off"),
            if (app.prefs.showPlayStatus) "on" else "off") { v ->
            app.prefs.showPlayStatus = (v == "on")
            labelShowStatus(v == "on")
            status.text = "已保存 · 下次呼出信息条生效"
        }

        // ---- 关于：遥控器操作说明（键位表的唯一可查入口）----
        valueKeymap.text = "查看"
        valueKeymap.setTextColor(getColor(R.color.accent))
        findViewById<View>(R.id.rowKeymap).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("遥控器操作说明")
                .setMessage(Keymap.asText())
                .setPositiveButton("知道了", null)
                .show()
        }

        // 解绑
        findViewById<View>(R.id.rowUnbind).setOnClickListener { confirmUnbind() }

        refreshAllLabels()
        findViewById<View>(R.id.rowEngine).requestFocus()
    }

    // ---------------------------------------------------------------- 循环切换

    /**
     * 把一行做成「左右键循环切换」。
     * 注意：这里不用 SeekBar / Spinner —— TV 上它们很难用遥控器操作。
     *
     * @param wrap 到头之后是否回绕。大多数行回绕更顺手；但"起播超时"这种
     *             越界代价很大的行要关掉——15 秒上再点一下变成 3 秒，
     *             等于把本来能起播的流判死。
     */
    private fun bindCycle(
        rowId: Int,
        valueView: TextView,
        options: List<String>,
        current: String,
        wrap: Boolean = true,
        onPick: (String) -> Unit,
    ) {
        val row = findViewById<View>(rowId)
        // 当前值可能不在候选里（历史值 / 手工改过偏好）。以前是 indexOf 找不到就
        // 静默回落到 0：界面显示"3 秒"，用户一按右键就真被改成 5 秒——
        // 看到的值和实际在用的值根本不是一回事。这里把当前值并进候选，
        // 保证"显示的就是在用的"。
        val list = mergeCandidate(options, current)
        var idx = list.indexOf(current).let { if (it < 0) 0 else it }

        fun apply() {
            val v = list[idx]
            onPick(v)
            valueView.text = v
            status.text = ""
        }

        row.setOnClickListener {
            idx = stepIndex(idx, list.size, +1, wrap)
            apply()
        }
        // 左右键也能改，符合"看到高亮就能改"的直觉
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    idx = stepIndex(idx, list.size, +1, wrap); apply(); true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    idx = stepIndex(idx, list.size, -1, wrap); apply(); true
                }
                else -> false
            }
        }
    }

    /**
     * 把「当前值」并进候选列表。
     *
     * 全是数字的候选按数值升序重排（保证循环方向符合直觉）；
     * 遇到非数字候选（auto/exo/tv…）就原样追加到末尾，不动原有顺序。
     */
    private fun mergeCandidate(options: List<String>, current: String): List<String> {
        if (options.contains(current)) return options
        val asNumber = current.toIntOrNull() ?: return options + current
        val numbers = ArrayList<Int>(options.size + 1)
        options.forEach { v -> v.toIntOrNull()?.let { numbers.add(it) } }
        numbers.add(asNumber)
        return numbers.distinct().sorted().map { it.toString() }
    }

    /** 循环切换的下标推进。[wrap] = false 时空转（停在边界上）。 */
    private fun stepIndex(idx: Int, size: Int, delta: Int, wrap: Boolean): Int {
        if (size <= 0) return 0
        val next = idx + delta
        if (wrap) return ((next % size) + size) % size
        return next.coerceIn(0, size - 1)
    }

    private fun refreshAllLabels() {
        labelEngine(app.prefs.engine)
        labelDecode(app.prefs.decode)
        labelBuffer(app.prefs.bufferMs)
        labelAspect(app.prefs.aspect)
        labelReverse(app.prefs.reverseChannelKeys)
        labelNumTimeout(app.prefs.numberJumpTimeoutMs)
        // 新加的行也要在这里给初值，否则第一次进设置页是空白的
        valueAutoPlay.text = if (app.prefs.autoPlayLast) "开（打开就看）" else "关（先进列表）"
        labelUiMode(app.prefs.uiMode)
        labelStartTimeout(app.prefs.startTimeoutMs)
        labelRetry(app.prefs.retryCount)
        labelDoh(app.prefs.dohMode)
        labelDohProvider(app.prefs.dohProvider)
        labelAutoRelay(app.prefs.autoRelay)
        labelUiScale(app.prefs.uiScale)
        labelShowStatus(app.prefs.showPlayStatus)
    }

    private fun labelUiScale(key: String) {
        valueUiScale.text = UiScaleOptions.from(key).label
    }

    private fun labelShowStatus(on: Boolean) {
        valueShowStatus.text = if (on) "开（信息条第二层给线路/内核）" else "关（只显示台名）"
    }

    private fun labelEngine(v: String) {
        valueEngine.text = when (v) {
            "auto" -> "自动（推荐）"
            else -> EngineKind.from(v)?.label ?: v
        }
    }

    private fun labelDecode(v: String) = run {
        valueDecode.text = when (v) {
            "hard" -> "硬解优先"
            "soft" -> "软解优先"
            else -> "自动"
        }
    }

    private fun labelBuffer(ms: Int) = run {
        valueBuffer.text = "${ms / 1000} 秒"
    }

    private fun labelAspect(v: String) = run {
        valueAspect.text = when (v) {
            "16:9" -> "16:9 拉伸"
            "4:3" -> "4:3"
            "fill" -> "铺满"
            else -> "自动"
        }
    }

    /**
     * AV3A 状态文字。
     *
     * 开关只是"允不允许加载"；真正能不能用取决于用户有没有把 .so 放到位。
     * 所以这里直接把加载结果摊开显示，用户不用去翻日志。
     */
    private fun labelAv3a() = run {
        if (!app.prefs.av3aEnabled) {
            valueAv3a.text = "关"
            return@run
        }
        val st = com.litv.tv.player.Av3aLoader.load(this)
        valueAv3a.text = if (st.ok) "开 · 已加载" else "开 · " + st.message
    }

    private fun labelReverse(on: Boolean) = run {
        valueReverse.text = if (on) "已反转（↑ 下一台）" else "正常（↑ 上一台）"
    }

    private fun labelNumTimeout(ms: Int) = run {
        valueNumberTimeout.text = "${ms} 毫秒"
    }

    // ---------------------------------------------------------------- 解绑

    private fun confirmUnbind() {
        AlertDialog.Builder(this)
            .setTitle("解绑本机？")
            .setMessage("解绑后需要重新在管理台批准，或用绑定码重新绑定。")
            .setPositiveButton("解绑") { _, _ ->
                app.prefs.clearSession()
                app.prefs.cachedBootstrap = ""
                startActivity(Intent(this, BindActivity::class.java))
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun labelUiMode(v: String) {
        valueUiMode.text = when (v) {
            "tv" -> "电视"
            "phone" -> "手机"
            else -> "自动"
        }
    }

    private fun labelStartTimeout(ms: Int) {
        valueStartTimeout.text = "${ms / 1000} 秒"
    }

    private fun labelRetry(n: Int) {
        valueRetry.text = if (n == 0) "不重试" else "$n 次"
    }

    private fun labelDoh(v: String) {
        valueDoh.text = if (v == "off") "关闭（只用系统 DNS）" else "自动（DoH 优先，失败走系统）"
    }

    private fun labelDohProvider(key: String) {
        valueDohProvider.text = DohDns.Provider.from(key).label
    }

    private fun labelAutoRelay(on: Boolean) {
        valueAutoRelay.text = if (on) "开（一失败就中转）" else "关（失败时询问）"
    }
}
