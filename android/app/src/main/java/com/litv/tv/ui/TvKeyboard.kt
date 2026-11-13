package com.litv.tv.ui

import com.litv.tv.BuildConfig
import com.litv.tv.R
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * 遥控键盘：自己画的输入键盘，**只在电视端使用**。
 *
 * 手机端走系统 IME（见 [BindActivity.autoShowKeyboard]），不要复用这个类——
 * 它按遥控器设计，塞到竖屏上会被裁切。
 *
 * ## 为什么要自己画
 *
 * 电视上用系统软键盘 + 方向键导航焦点，这两件事是**互斥**的：
 * 软键盘是独立窗口，一旦 `mInputShown=true`，D-pad 全部被它吃掉，
 * 挂在输入框上的 OnKeyListener 一个按键都收不到——焦点永远卡在输入框里。
 * 实测确认过（`dumpsys input_method` 看 mInputShown）。
 *
 * ## 键位设计（对齐 Leanback guided step 的通行做法）
 *
 * 1. **数字置顶**。输 IP、端口、绑定码都是数字，占九成使用场景。
 * 2. **字母 10 列 3 行**，`⇧` 切换大小写（域名用小写，密码可能要大写）。
 * 3. **符号单独一行**：`. - _ : / @`。域名只要 `.`，但反向代理地址可能要 `:` 和 `/`。
 * 4. **值显示在键盘里**（`label` + 大字值）。键盘盖住上半屏的表单，
 *    眼睛不用来回跳——正在编辑哪一格、已经输了什么，键盘顶部就写着。
 * 5. **长度反馈**：有 `maxLen` 时标题显示 `3/6`，到上限不再静默吞键。
 * 6. **删除支持长按连删**；**清空放在最右**，与删除隔开一格，防误触。
 * 7. **上一格 / 下一格**：输完一格不用退出去按方向键。
 * 8. **宽度自适应**：`min(880dp, 屏宽 × 94%)`。写死 dp 会在窄屏/竖屏上被裁掉，
 *    表现为"键盘少了几行字母"——真机上踩过。
 *
 * ## 按键分发
 *
 * 见 [dispatchKeyEvent]：Dialog 里的子 View 收不到遥控 DPAD（触摸能点、
 * 按键没反应），所以焦点移动和点击都在 Dialog 这一层自己做。
 */
class TvKeyboard(
    context: Context,
    /** 正在编辑哪一格，显示在键盘顶部（如「绑定码」「服务器地址 · 第 2 格」）。 */
    private val label: String,
    initial: String,
    /** 最大长度，0 表示不限。到长度后不再追加，并在标题上显示 n/max。 */
    private val maxLen: Int = 0,
    private val onCommit: (String) -> Unit,
    /** 上一格 / 下一格。为 null 时对应按钮置灰不显示。 */
    private val onPrev: (() -> Unit)? = null,
    private val onNext: (() -> Unit)? = null,
) : Dialog(context, R.style.LitvDialog) {

    private val buffer = StringBuilder(initial)
    private lateinit var preview: TextView
    private lateinit var titleView: TextView
    private lateinit var body: LinearLayout

    /** 大小写开关。默认小写——域名、地址、绑定码都是小写或数字。 */
    private var upper = false
    private val letterKeys = mutableListOf<Pair<TextView, Char>>()

    /** 关闭回调。调用方用它清掉自己持有的引用，避免持有已销毁的 Dialog。 */
    var onClosed: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRun: Runnable? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        val pad = dp(16)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(10), pad, dp(12))
            background = ctx.getDrawable(R.drawable.bg_picker)
        }

        // 顶部：正在编辑哪一格 + 当前值。键盘盖住了表单，这两行就是眼睛的落点。
        titleView = TextView(ctx).apply {
            setTextColor(ctx.getColor(R.color.text_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        root.addView(
            titleView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        preview = TextView(ctx).apply {
            setTextColor(ctx.getColor(R.color.accent))
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        // 必须 MATCH_PARENT：默认 wrap_content 会让它缩在左上角，
        // 而 gravity=CENTER 对 wrap_content 的自身不起作用——
        // 结果输入了字却看不见，表现得像"按键没反应"。
        root.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body)

        renderKeys()
        syncPreview()

        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 宽度自适应：写死 dp(880) 会在窄屏/竖屏上超出屏幕被裁掉。
            val maxW = (ctx.resources.displayMetrics.widthPixels * 0.94f).toInt()
            setLayout(min(dp(880), maxW), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }

        // 关闭时：停掉长按删除的循环（否则 Handler 继续跑），
        // 并通知调用方释放引用。
        setOnDismissListener {
            stopRepeatDelete()
            onClosed?.invoke()
        }

        body.post { firstKey()?.requestFocus() }
    }

    /**
     * 自己接管按键分发。
     *
     * 实测：点按（touch）能正常输入，但遥控 DPAD 完全没反应——事件没送到
     * 子 View 的焦点/点击链路上。与其猜窗口标志组合，不如在这一层直接做：
     * 方向键 focusSearch，OK 对当前焦点 performClick。
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val focused = window?.currentFocus ?: currentFocus
        if (BuildConfig.DEBUG) {
            Log.d("LITV", "TvKeyboard key=${event.keyCode} action=${event.action} focused=${focused?.javaClass?.simpleName}")
        }

        // ★ 只处理 ACTION_UP。
        //
        // 实测日志：这个 Dialog 只收到抬起（action=1），从来收不到按下（action=0）
        // ——按下被 Activity 那一侧先消费掉了。原来把逻辑写在 DOWN 分支里，
        // 等于一次都没执行过（用户看到的就是「方向键按了没反应」）。
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    if (focused != null) {
                        val dir = when (event.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                            else -> View.FOCUS_DOWN
                        }
                        val next = focused.focusSearch(dir)
                        if (next != null && next !== focused) {
                            next.requestFocus()
                            return true
                        }
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    stopRepeatDelete()
                    if (focused != null) {
                        focused.performClick()
                        return true
                    }
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun renderKeys() {
        // 数字行：IP、端口、绑定码都是数字，最常用，放第一行
        body.addView(row(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")))

        // 字母区：10 个一行 → 26 个字母正好 3 行（最后一行补 ⇧ 占位）
        val letters = "abcdefghijklmnopqrstuvwxyz".toList()
        letters.chunked(10).forEachIndexed { idx, chunk ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { c ->
                val b = charKey(c.toString()) { typeChar(if (upper) c.uppercaseChar().toString() else c.toString()) }
                letterKeys.add(b to c)
                line.addView(b, weightParam())
            }
            // 第三行只有 6 个字母，右侧补 ⇧ 与空位，保证列宽一致
            if (idx == 2) {
                line.addView(
                    navKey("⇧", active = upper) { toggleCase() },
                    weightParam(NAV_H),
                )
                repeat(3) { line.addView(View(context), weightParam()) }
            }
            body.addView(line)
        }

        // 符号行：域名只要 `.`，反向代理地址可能要 `:` `/`，密码可能带 `_@-`
        body.addView(row(listOf(".", "-", "_", ":", "/", "@")))

        // 导航行：上一格 / 删除 / 下一格 / 清空
        // 清空放最右，与删除隔开，避免误触把整段输入清掉。
        val nav = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(navKey("‹ 上一格", enabled = onPrev != null) { commitAnd { onPrev?.invoke() } }, weightParam(NAV_H))
        val del = navKey("删除") { backspace() }
        // 长按连删：输错了不用按十几次
        del.setOnLongClickListener {
            startRepeatDelete()
            true
        }
        nav.addView(del, weightParam(NAV_H))
        nav.addView(navKey("下一格 ›", enabled = onNext != null) { commitAnd { onNext?.invoke() } }, weightParam(NAV_H))
        nav.addView(navKey("清空") {
            buffer.setLength(0); syncPreview()
        }, weightParam(NAV_H))
        body.addView(nav)

        // 确定单独一行：它是主操作，占满宽度，不容易误按
        body.addView(
            funcKey("确定") { commitAnd { dismiss() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CONFIRM_H)),
        )
    }

    /** 大小写切换：只改键面文字，不动已输入的内容。 */
    private fun toggleCase() {
        upper = !upper
        letterKeys.forEach { (view, c) ->
            view.text = if (upper) c.uppercaseChar().toString() else c.toString()
        }
    }

    /** 先把当前值交给调用方，再执行下一步（上一格/下一格/确定都要先落值）。 */
    private fun commitAnd(then: () -> Unit) {
        onCommit(buffer.toString())
        then()
    }

    private fun charKey(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            minHeight = dp(KEY_H)
            setPadding(0, dp(7), 0, dp(7))
            setTextColor(context.getColor(R.color.text_primary))
            background = context.getDrawable(R.drawable.bg_picker_row)
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }

    private fun row(keys: List<String>): LinearLayout {
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        keys.forEach { k -> line.addView(charKey(k) { typeChar(k) }, weightParam()) }
        return line
    }

    private fun navKey(
        label: String,
        enabled: Boolean = true,
        active: Boolean = false,
        action: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            minHeight = dp(NAV_H)
            setPadding(0, dp(9), 0, dp(9))
            setTextColor(
                context.getColor(
                    when {
                        !enabled -> R.color.text_secondary
                        active -> R.color.accent
                        else -> R.color.text_primary
                    }
                )
            )
            background = context.getDrawable(R.drawable.bg_picker_row)
            isFocusable = enabled
            isClickable = enabled
            alpha = if (enabled) 1f else 0.4f
            setOnClickListener { action() }
        }

    private fun funcKey(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            minHeight = dp(CONFIRM_H)
            setPadding(0, dp(11), 0, dp(11))
            setTextColor(context.getColor(R.color.accent))
            background = context.getDrawable(R.drawable.bg_picker_row)
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }

    /**
     * 键位用**显式高度**，不用 WRAP_CONTENT + minHeight。
     *
     * 踩过的坑：窗口高度被内容撑满后，LinearLayout 会把溢出的子行压成 0 高——
     * 表现是键盘只画到字母行，下面的符号行、导航行、「确定」全部消失，
     * 面板却还是满屏高。显式高度让每行高度与内容无关，不会被压没。
     */
    private fun weightParam(h: Int = KEY_H) =
        LinearLayout.LayoutParams(0, dp(h), 1f)

    private fun firstKey(): View? {
        val line = body.getChildAt(0) as? LinearLayout ?: return null
        return line.getChildAt(0)
    }

    /**
     * 追加一个字符。
     *
     * ★ 不能叫 append：`TextView` 自己有 `append(CharSequence)`，在
     * `TextView(context).apply { setOnClickListener { append(k) } }` 里，
     * Kotlin 会优先解析成 TextView 的成员，字符被追加到**键位自己的文字**上，
     * 而不是我们的 buffer。真机现象就是键位变成「22」「77」、顶部预览始终为空。
     */
    private fun typeChar(s: String) {
        // 到上限不再静默吞键：预览条上已经有 n/max，用户看得到自己输了几个字
        if (maxLen > 0 && buffer.length >= maxLen) return
        buffer.append(s)
        if (BuildConfig.DEBUG) {
            Log.d("LITV", "TvKeyboard append=$s buffer=$buffer")
        }
        syncPreview()
    }

    private fun backspace() {
        if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
        syncPreview()
    }

    /** 长按删除：先等 350ms，然后每 80ms 删一个，松手即停。 */
    private fun startRepeatDelete() {
        stopRepeatDelete()
        val r = object : Runnable {
            override fun run() {
                backspace()
                handler.postDelayed(this, 80)
            }
        }
        repeatRun = r
        handler.postDelayed(r, 350)
    }

    private fun stopRepeatDelete() {
        repeatRun?.let { handler.removeCallbacks(it) }
        repeatRun = null
    }

    private fun syncPreview() {
        preview.text = if (buffer.isEmpty()) " " else buffer.toString()
        // 有长度上限时把进度写进标题，避免"按了没反应"的错觉
        titleView.text = if (maxLen > 0) "$label · ${buffer.length}/$maxLen" else label
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        /** 键位最小高度，保证不同内容（1 个字符 / 4 个汉字）行高一致。 */
        const val KEY_H = 40
        const val NAV_H = 36
        const val CONFIRM_H = 44
    }
}
