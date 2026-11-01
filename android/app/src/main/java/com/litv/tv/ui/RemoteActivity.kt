package com.litv.tv.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.litv.tv.LitvApp
import com.litv.tv.R
import com.litv.tv.data.RemoteTarget
import org.json.JSONObject

/**
 * 手机端遥控器。
 *
 * 交互取舍：不做"虚拟触控板"，就做大按钮。
 * 遥控这件事上，手感来自"闭着眼睛也能按对"，不是来自花哨的滑动手势。
 */
class RemoteActivity : AppCompatActivity() {

    private lateinit var app: LitvApp
    private lateinit var status: TextView
    private lateinit var targets: LinearLayout
    private lateinit var numberPad: LinearLayout

    private var targetId = 0L
    private var targetName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        app = application as LitvApp

        status = findViewById(R.id.status)
        targets = findViewById(R.id.targets)
        numberPad = findViewById(R.id.numberPad)

        bindKey(R.id.keyUp, "up")
        bindKey(R.id.keyDown, "down")
        bindKey(R.id.keyLeft, "left")
        bindKey(R.id.keyRight, "right")
        bindKey(R.id.keyOk, "ok")
        bindKey(R.id.keyBack, "back")
        bindKey(R.id.keyMenu, "menu")

        buildNumberPad()
        loadTargets()
    }

    private fun bindKey(viewId: Int, key: String) {
        findViewById<TextView>(viewId).setOnClickListener { send("key", key) }
    }

    /** 3×4 数字键盘：0-9 + 分隔符，按一下发一个数字，电视端会累积跳台。 */
    private fun buildNumberPad() {
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("清空", "0", "线路"),
        )
        for (row in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (label in row) {
                val tv = TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    background = getDrawable(R.drawable.bg_card)
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 18f
                    layoutParams = LinearLayout.LayoutParams(dp(76), dp(56)).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                    }
                    setOnClickListener {
                        when (label) {
                            "清空" -> status.text = "已清空输入"
                            "线路" -> send("key", "ok") // 长按 OK 语义在电视端实现，这里先发 OK
                            else -> send("key", label)
                        }
                    }
                }
                line.addView(tv)
            }
            numberPad.addView(line)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun loadTargets() {
        app.api.callList(
            "/api/v1/tv/remote/targets",
            RemoteTarget::class.java,
            onOk = { list ->
                targets.removeAllViews()
                if (list.isEmpty()) {
                    status.text = "没有可遥控的设备"
                    return@callList
                }
                list.forEach { t ->
                    val tv = TextView(this).apply {
                        text = (if (t.online) "● " else "○ ") + t.name
                        setTextColor(getColor(if (t.online) R.color.accent else R.color.text_secondary))
                        textSize = 14f
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        background = getDrawable(R.drawable.bg_card)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginEnd = dp(8) }
                        setOnClickListener {
                            targetId = t.id
                            targetName = t.name
                            status.text = "当前：${t.name}"
                        }
                    }
                    targets.addView(tv)
                    // 默认选第一台在线的
                    if (targetId == 0L && t.online) {
                        targetId = t.id
                        targetName = t.name
                        status.text = "当前：${t.name}"
                    }
                }
            },
            onErr = { status.text = "获取设备失败：$it" },
        )
    }

    private fun send(type: String, key: String) {
        if (targetId == 0L) {
            status.text = "先选一台电视"
            return
        }
        // 注意：Api.call 内部用 Gson 序列化，必须传 Map/对象，
        // 传 String 会被序列化成带引号的 JSON 字符串而不是对象。
        val body = mapOf("type" to type, "key" to key)
        app.api.call(
            "/api/v1/tv/remote/$targetId",
            "POST",
            body,
            clazz = Any::class.java,
            onOk = { status.text = "→ $targetName" },
            onErr = { status.text = it },
        )
    }

}
