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

        // 起播超时：慢设备/走代理时 3 秒会误判成"起不来"，这里可调
        bindCycle(R.id.rowStartTimeout, valueStartTimeout,
            listOf("3000", "5000", "8000", "12000"), app.prefs.startTimeoutMs.toString()) { v ->
            app.prefs.startTimeoutMs = v.toIntOrNull() ?: 5000
            labelStartTimeout(app.prefs.startTimeoutMs)
        }

        // 超时重试次数：网络抖动时多试几次比直接换内核好
        bindCycle(R.id.rowRetry, valueRetry,
            listOf("0", "1", "2", "3"), app.prefs.retryCount.toString()) { v ->
            app.prefs.retryCount = v.toIntOrNull() ?: 1
            labelRetry(app.prefs.retryCount)
        }

        bindCycle(R.id.rowUiMode, valueUiMode, listOf("auto", "tv", "phone"),
            app.prefs.uiMode) { v ->
            app.prefs.uiMode = v
            labelUiMode(v)
        }

        // 设备信息（只读）
        findViewById<View>(R.id.rowDevice).setOnClickListener {
            status.text = "渲染档位 ${app.tier.name} · Android ${Build.VERSION.SDK_INT} · " +
                "${Build.MANUFACTURER} ${Build.MODEL}"
        }
        valueDevice.text = "${Build.MANUFACTURER} ${Build.MODEL}".take(22)
        valueServer.text = app.prefs.serverBase

        // 解绑
        findViewById<View>(R.id.rowUnbind).setOnClickListener { confirmUnbind() }

        refreshAllLabels()
        findViewById<View>(R.id.rowEngine).requestFocus()
    }

    // ---------------------------------------------------------------- 循环切换

    /**
     * 把一行做成「左右键循环切换」。
     * 注意：这里不用 SeekBar / Spinner —— TV 上它们很难用遥控器操作。
     */
    private fun bindCycle(
        rowId: Int,
        valueView: TextView,
        options: List<String>,
        current: String,
        onPick: (String) -> Unit,
    ) {
        val row = findViewById<View>(rowId)
        var idx = options.indexOf(current).let { if (it < 0) 0 else it }

        fun apply() {
            val v = options[idx]
            onPick(v)
            valueView.text = v
            status.text = ""
        }

        row.setOnClickListener {
            idx = (idx + 1) % options.size
            apply()
        }
        // 左右键也能改，符合"看到高亮就能改"的直觉
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    idx = (idx + 1) % options.size; apply(); true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    idx = (idx - 1 + options.size) % options.size; apply(); true
                }
                else -> false
            }
        }
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
}
