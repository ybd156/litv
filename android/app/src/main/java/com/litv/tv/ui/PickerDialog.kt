package com.litv.tv.ui

import com.litv.tv.R
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 深色选择弹框（单选列表）。
 *
 * ## 为什么不用系统 AlertDialog
 *
 * 试过 `AlertDialog.Builder(ctx, R.style.LitvAlertDialog)`，主题压不住：
 * `Theme.AppCompat.Dialog.Alert` 的 windowBackground 是 AppCompat 自带的
 * 灰色圆角 drawable，只覆盖 `android:windowBackground` 不生效，弹出来仍是
 * 一块灰底方块，和 app 的深色界面完全是两套东西——用户的原话是"割裂感"。
 *
 * 与其在主题上层层打补丁（colorBackground / alertDialogTheme / 各种 attr），
 * 不如自己画：一个 Dialog + LinearLayout，样式完全由我们控制，和
 * SettingsDialog 用同一套颜色与间距。
 *
 * ## 遥控支持
 *
 * 每一项都是 focusable + clickable，D-pad 上下移动、OK 确认、BACK 关闭。
 * 打开时把焦点落到当前选中项，避免"弹出来还不知道光标在哪"。
 */
class PickerDialog(
    context: Context,
    private val title: String,
    private val options: List<String>,
    private val selected: Int,
    /** 可选的正文（节目简介之类）。给了就显示在标题下面。 */
    private val message: String? = null,
    private val onPick: (Int) -> Unit,
) : Dialog(context, R.style.LitvDialog) {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        val pad = dp(ctx, 24)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = ctx.getDrawable(R.drawable.bg_picker)
        }

        val titleView = TextView(ctx).apply {
            text = title
            setTextColor(ctx.getColor(R.color.text_secondary))
            textSize = 15f
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 14))
        }
        root.addView(titleView)

        if (!message.isNullOrBlank()) {
            val msgView = TextView(ctx).apply {
                text = message
                setTextColor(ctx.getColor(R.color.text_primary))
                textSize = 16f
                setLineSpacing(dp(ctx, 4).toFloat(), 1f)
                setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 16))
            }
            root.addView(msgView)
        }

        var firstFocus: View? = null
        var firstOption: View? = null
        options.forEachIndexed { i, label ->
            val row = TextView(ctx).apply {
                text = label
                textSize = 19f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 16), dp(ctx, 15), dp(ctx, 16), dp(ctx, 15))
                isFocusable = true
                isClickable = true
                // 当前值给青色，其余白色——一眼看出现在是哪个
                setTextColor(
                    if (i == selected) ctx.getColor(R.color.accent) else ctx.getColor(R.color.text_primary)
                )
                background = ctx.getDrawable(R.drawable.bg_picker_row)
                setOnClickListener {
                    onPick(i)
                    dismiss()
                }
                setOnKeyListener { _, keyCode, ev ->
                    if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    // 左右键也当确认/取消用：遥控器上这两个键离拇指最近
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            onPick(i); dismiss(); true
                        }
                        else -> false
                    }
                }
            }
            root.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            if (i == 0) firstOption = row
            if (i == selected) firstFocus = row
        }

        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(ctx, 420), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        // 焦点落在当前值上；没有当前值（selected=-1，比如线路选择）就落第一项。
        // 不能写 root.getChildAt(1)——加了 message 之后那个 index 不是选项。
        // 必须 post：onCreate 时视图还没 measure/layout，requestFocus 会
        // 直接返回 false 且不报错，弹框出来没有任何焦点高亮，遥控按了没反应。
        val target = firstFocus ?: firstOption
        target?.post { target.requestFocus() }
    }

    /**
     * 自己接管按键。原因同 TvKeyboard：实测 Dialog 里的子 View 收不到
     * 遥控 DPAD 事件（触摸能点、按键没反应），所以在 Dialog 层直接处理
     * 焦点移动和点击，不依赖默认分发链。
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val focused = window?.currentFocus ?: currentFocus
        // 只处理 ACTION_UP：实测 Dialog 收不到按下事件（被 Activity 侧先消费），
        // 原来写在 DOWN 分支里的逻辑一次都没执行过。
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
                    if (focused != null) {
                        focused.performClick()
                        return true
                    }
                }
            }
        }
        if (event.action == android.view.KeyEvent.ACTION_UP &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
