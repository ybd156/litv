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
class SettingsDialog(
    context: Context,
    /** 打开时落在哪个分类：0=播放 1=交互 2=设备。右侧菜单的「显示/偏好/其他」
        分别对应这三个，直接落到目标页比让人自己再点一次少一步。 */
    private val initialTheme: Int = 0,
) : Dialog(context, R.style.LitvDialog) {

    private val app = context.applicationContext as LitvApp
    private lateinit var optionBox: LinearLayout
    private lateinit var tabs: List<TextView>
    private var theme = 0

    private val engines = listOf("auto", "exo", "vlc", "mpv", "avs3")
    private val decodes = listOf("auto", "hard", "soft")
    private val buffers = listOf(2000, 3000, 5000, 10000, 15000)
    private val aspects = listOf("auto", "16:9", "4:3", "fill")
    private val numTimeouts = listOf(1500, 2000, 2500, 3000)
    /** 直播延迟候选（毫秒）。上限受服务器分片窗口限制，30 秒是常见源的安全值。 */
    private val offsets = listOf(3000, 6000, 10000, 20000, 30000)

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

        switchTheme(initialTheme.coerceIn(0, 2))
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
        // 焦点落在第一项，遥控上下就能改。
        //
        // 必须 post：optionBox 里是刚 addView 进去的，还没 measure/layout，
        // 这时 requestFocus 返回 false 且不报错——切分类后右侧一片没有焦点，
        // 用户以为点坏了。
        if (optionBox.childCount > 0) {
            val first = optionBox.getChildAt(0)
            first.post { first.requestFocus() }
        }
    }

    // ---------------------------------------------------------------- 各主题

    /**
     * 值的中文说法。
     *
     * 原来直接把内部值原样显示：`auto` / `hard` / `avs3` / `3000`。
     * 那是给写代码的人看的——家人看到「缓冲 3000」既不知道单位，
     * 也不知道改大改小会怎样。显示层换成中文，**存储仍然用原值**。
     */
    private fun engineLabel(v: String) = when (v) {
        "auto" -> "自动（推荐）"
        "exo" -> "ExoPlayer"
        "vlc" -> "VLC（兼容性好）"
        "mpv" -> "mpv（延迟低）"
        "avs3" -> "AVS3（需解码库）"
        else -> v
    }

    private fun decodeLabel(v: String) = when (v) {
        "auto" -> "自动"
        "hard" -> "硬件解码"
        "soft" -> "软件解码（更兼容）"
        else -> v
    }

    private fun bufferLabel(v: String) = when (v) {
        "2000" -> "2 秒（省内存）"
        "3000" -> "3 秒"
        "5000" -> "5 秒"
        "10000" -> "10 秒（推荐）"
        "15000" -> "15 秒（最稳）"
        else -> "${v.toIntOrNull()?.div(1000) ?: 0} 秒"
    }

    private fun aspectLabel(v: String) = when (v) {
        "auto" -> "自动"
        "16:9" -> "16:9"
        "4:3" -> "4:3"
        "fill" -> "铺满"
        else -> v
    }

    private fun offsetLabel(v: String) = "${v.toIntOrNull()?.div(1000) ?: 0} 秒"

    private fun timeoutLabel(v: String) = when (v) {
        "1500" -> "1.5 秒"
        "2500" -> "2.5 秒"
        "3500" -> "3.5 秒"
        else -> "$v 毫秒"
    }

    private fun buildPlay() {
        addCycle("播放内核", engines, app.prefs.engine, ::engineLabel) { v ->
            app.prefs.engine = v
        }
        addCycle("解码方式", decodes, app.prefs.decode, ::decodeLabel) { v ->
            app.prefs.decode = v
        }
        addCycle("缓冲", buffers.map { it.toString() }, app.prefs.bufferMs.toString(), ::bufferLabel) { v ->
            app.prefs.bufferMs = v.toIntOrNull() ?: 2000
        }
        // 直播延迟：主动落后直播边缘多久。加大它 = 少卡顿、多一点延迟。
        addCycle("直播延迟", offsets.map { it.toString() }, app.prefs.liveOffsetMs.toString(), ::offsetLabel) { v ->
            app.prefs.liveOffsetMs = v.toIntOrNull() ?: 20000
        }
        addCycle("画面比例", aspects, app.prefs.aspect, ::aspectLabel) { v ->
            app.prefs.aspect = v
        }
    }

    private fun buildInteract() {
        addToggle("换台方向反转", app.prefs.reverseChannelKeys) { v ->
            app.prefs.reverseChannelKeys = v
        }
        addCycle("数字键跳台等待", numTimeouts.map { it.toString() },
            app.prefs.numberJumpTimeoutMs.toString(), ::timeoutLabel) { v ->
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
            // ★ 必须确认。遥控器上方向键和 OK 挨得近，一次误触的代价是
            // 「得重新绑定才能看」，而且用户不知道刚才清掉了什么。
            //
            // 默认焦点停在「取消」：误按 OK 的结果是不做任何事。
            PickerDialog(
                context,
                "解除绑定？",
                listOf("取消", "解除绑定"),
                0,
                message = "会清除本机的登录凭据和设备信息。之后需要在管理台重新批准，" +
                    "或者用绑定码重新绑定才能继续观看。",
            ) { which ->
                if (which == 1) {
                    app.prefs.clearSession()
                    dismiss()
                    (context as? android.app.Activity)?.recreate()
                }
            }.show()
        }
    }

    // ---------------------------------------------------------------- 行构造

    private fun row(label: String, value: String): Triple<LinearLayout, TextView, TextView> {
        val ctx = context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = ctx.getDrawable(R.drawable.bg_setting_row)
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

    /**
     * 多选一设置项：**按 OK 弹选择列表，选中才生效**。
     *
     * 为什么不再"按一下循环到下一个值"：遥控器误触一下，播放内核就从 auto
     * 变成 avs3、解码从 auto 变成 hard —— 两个都会直接导致播不了，而用户
     * 根本不知道刚才改了什么（走查时我自己就误触了两次，还得按十几次改回来）。
     *
     * 用系统 AlertDialog 而不是自绘：遥控器 D-pad 的上下移动和确认是原生
     * 支持的，不用再写一套焦点管理。
     */
    private fun addCycle(
        label: String,
        options: List<String>,
        initial: String,
        labelOf: (String) -> String = { it },
        onPick: (String) -> Unit,
    ) {
        val (row, _, valView) = row(label, labelOf(initial))
        var idx = options.indexOf(initial).coerceAtLeast(0)
        row.setOnClickListener {
            PickerDialog(context, label, options.map(labelOf), idx) { which ->
                idx = which
                onPick(options[which])
                valView.text = labelOf(options[which])
            }.show()
        }
    }

    private fun addToggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        var on = initial
        val (row, _, valView) = row(label, if (on) "开" else "关")
        // 同上：按 OK 弹「开 / 关」，不再按一下就翻面
        row.setOnClickListener {
            PickerDialog(context, label, listOf("开", "关"), if (on) 0 else 1) { which ->
                on = which == 0
                onChange(on)
                valView.text = if (on) "开" else "关"
            }.show()
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
