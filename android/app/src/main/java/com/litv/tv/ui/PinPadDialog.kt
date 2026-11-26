package com.litv.tv.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.litv.tv.R
import com.litv.tv.player.KioskLock

/**
 * 在播放页之外输密码：把播放页那套数字键盘（`view_pin_pad.xml`）装进 Dialog 复用。
 *
 * 已有的内嵌键盘跟「长按 OK」绑在播放页里，设置页没法用；而「关掉儿童锁」必须验密码
 * （否则小孩自己进设置就把锁关了，用户 2026-11-26 反馈「打开童锁，设置页没法关闭」）。
 *
 * 输满 4 位就回调，**比对交给调用方** —— 这里只管收密码，不碰 prefs。
 */
object PinPadDialog {

    fun show(activity: Activity, title: String, onResult: (String) -> Unit) {
        val root = LayoutInflater.from(activity).inflate(R.layout.view_pin_pad, null)
        root.visibility = View.VISIBLE
        val dialog = Dialog(activity)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val width = (activity.resources.displayMetrics.widthPixels * 0.55f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        val dots = root.findViewById<TextView>(R.id.pinDots)
        root.findViewById<TextView>(R.id.pinTitle).text = title
        val input = StringBuilder()

        fun render() {
            dots.text = (0 until KioskLock.PIN_LENGTH).joinToString(" ") {
                if (it < input.length) "●" else "○"
            }
        }

        render()
        val grid = root.findViewById<ViewGroup>(R.id.pinGrid)
        for (r in 0 until grid.childCount) {
            val row = grid.getChildAt(r) as ViewGroup
            for (c in 0 until row.childCount) {
                val key = row.getChildAt(c) as TextView
                key.setOnClickListener {
                    when (val tag = key.tag as? String) {
                        "back" -> if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
                        "clear" -> input.setLength(0)
                        null -> Unit
                        else -> if (input.length < KioskLock.PIN_LENGTH) input.append(tag)
                    }
                    render()
                    if (input.length == KioskLock.PIN_LENGTH) {
                        val value = input.toString()
                        dialog.dismiss()
                        onResult(value)
                    }
                }
            }
        }
        dialog.show()
        // 焦点先落在「1」上，遥控器也能直接用方向键选数字。
        (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus()
    }
}
