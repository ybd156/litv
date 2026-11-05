package com.litv.tv.ui

import android.app.Dialog
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.litv.tv.LitvApp
import com.litv.tv.R
import java.util.Locale

/**
 * 播放中的设置弹层：左主题、右选项。
 *
 * 参考 TVBox 系（酷9）的做法——设置按主题拆成弹层，播放中按 MENU 弹出，
 * 改完继续看，不离开画面。以前要跳独立页面，看一半想调缓冲就得退出再进。
 *
 * 操作方式和 TV 上的习惯一致：焦点落在某行，按 OK 或左右键循环切值。
 */
class SettingsDialog(context: Context) : Dialog(context, R.style.LitvDialog) {

    private val app = context.applicationContext as LitvApp
    private lateinit var optionBox: LinearLayout
    private lateinit var tabs: List<TextView>
    private var theme = 0

    private val engines = listOf("auto", "exo", "vlc", "mpv", "avs3")
    private val decodes = listOf("auto", "hard", "soft")
    private val buffers = listOf(1000, 2000, 3000, 5000, 8000)
    private val aspects = listOf("auto", "16:9", "4:3", "fill")
    private val numTimeouts = listOf(1500, 2000, 2500, 3000)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        // 不设这个，Dialog 会用默认的最小宽度渲染——
        // 布局里写的 720dp 根本不生效，真机上弹窗只有屏宽的 36%，
        // 左边主题和右边选项挤在一起，字也小。
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)
        setContentView(root)

        optionBox = root.findViewById(R.id.optionBox)
        tabs = listOf(
            root.findViewById(R.id.tabPlay),
            root.findViewById(R.id.tabInteract),
            root.findViewById(R.id.tabDevice),
        )
        tabs.forEachIndexed { i, t -> t.setOnClickListener { switchTheme(i) } }

        switchTheme(0)
    }

    override fun onStart() {
        super.onStart()
        val dm = context.resources.displayMetrics
        // 宽度定死 88%（不定的话 Dialog 按默认最小宽度渲染，只有屏宽 36%）；
        // 高度用 WRAP_CONTENT 贴合内容——之前写死 84%，内容少的时候
        // 下面一大片空白很难看。
        window?.setLayout(
            (dm.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun switchTheme(i: Int) {
        theme = i
        tabs.forEachIndexed { idx, t ->
            t.setTextColor(context.getColor(if (idx == i) R.color.accent else R.color.text_primary))
        }
        optionBox.removeAllViews()
        when (i) {
            0 -> buildPlay()
            1 -> buildInteract()
            else -> buildDevice()
        }
        // 焦点落在第一项，遥控上下就能改
        if (optionBox.childCount > 0) optionBox.getChildAt(0).requestFocus()
    }

    // ---------------------------------------------------------------- 各主题

    private fun buildPlay() {
        addCycle("播放内核", engines, app.prefs.engine) { v ->
            app.prefs.engine = v
        }
        addCycle("解码方式", decodes, app.prefs.decode) { v ->
            app.prefs.decode = v
        }
        addCycle("缓冲", buffers.map { it.toString() }, app.prefs.bufferMs.toString()) { v ->
            app.prefs.bufferMs = v.toIntOrNull() ?: 2000
        }
        addCycle("画面比例", aspects, app.prefs.aspect) { v ->
            app.prefs.aspect = v
        }
    }

    private fun buildInteract() {
        addToggle("换台方向反转", app.prefs.reverseChannelKeys) { v ->
            app.prefs.reverseChannelKeys = v
        }
        addCycle("数字键跳台等待", numTimeouts.map { it.toString() },
            app.prefs.numberJumpTimeoutMs.toString()) { v ->
            app.prefs.numberJumpTimeoutMs = v.toIntOrNull() ?: 2500
        }
        addToggle("开机自动播放上次频道", app.prefs.autoPlayLast) { v ->
            app.prefs.autoPlayLast = v
        }
    }

    private fun buildDevice() {
        addInfo("设备名称", app.prefs.deviceName.ifBlank { "未命名" })
        addInfo("服务器", app.prefs.serverBase.ifBlank { "未配置" })
        addInfo("渲染档位", app.tier.name)
        addAction("解除绑定") {
            app.prefs.clearSession()
            dismiss()
            (context as? android.app.Activity)?.recreate()
        }
    }

    // ---------------------------------------------------------------- 行构造

    private fun row(label: String, value: String): Triple<LinearLayout, TextView, TextView> {
        val ctx = context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = ctx.getDrawable(R.drawable.bg_focusable)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }
        val name = TextView(ctx).apply {
            text = label
            setTextColor(ctx.getColor(R.color.text_primary))
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valView = TextView(ctx).apply {
            text = value
            setTextColor(ctx.getColor(R.color.accent))
            textSize = 18f
        }
        row.addView(name)
        row.addView(valView)
        optionBox.addView(row)
        return Triple(row, name, valView)
    }

    /** 循环切值：OK 或左右键都改，符合「看到高亮就能改」的直觉。 */
    private fun addCycle(label: String, options: List<String>, initial: String, onPick: (String) -> Unit) {
        val (row, _, valView) = row(label, initial)
        var idx = options.indexOf(initial).coerceAtLeast(0)
        val apply = {
            val v = options[idx]
            onPick(v)
            valView.text = v
        }
        row.setOnClickListener {
            idx = (idx + 1) % options.size
            apply()
        }
        row.setOnKeyListener { _, keyCode, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { idx = (idx + 1) % options.size; apply(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { idx = (idx - 1 + options.size) % options.size; apply(); true }
                else -> false
            }
        }
    }

    private fun addToggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        var on = initial
        val (row, _, valView) = row(label, if (on) "开" else "关")
        row.setOnClickListener {
            on = !on
            onChange(on)
            valView.text = if (on) "开" else "关"
        }
    }

    private fun addInfo(label: String, value: String) {
        val (row, _, _) = row(label, value)
        row.isFocusable = false
        row.isClickable = false
    }

    private fun addAction(label: String, onClick: () -> Unit) {
        val (row, name, valView) = row(label, "")
        name.setTextColor(context.getColor(R.color.text_secondary))
        valView.text = ""
        row.setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
