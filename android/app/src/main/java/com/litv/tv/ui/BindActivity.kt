package com.litv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.litv.tv.LitvApp
import com.litv.tv.R

/**
 * 绑定页：电视首次启动时唯一需要人动手的页面。
 *
 * 为什么要有这一步：服务端不信任任何匿名终端，必须由管理员在管理台确认。
 * 两种放行方式：
 *   A. 电视上填 6 位绑定码（管理台生成）→ 立即绑定
 *   B. 电视只填服务器地址 → 服务端出现"待批准"设备 → 管理员点批准
 * 这里两条都支持，绑定成功后轮询心跳，直到服务端放行。
 */
class BindActivity : AppCompatActivity() {

    private lateinit var app: LitvApp
    private lateinit var serverInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var status: TextView
    private lateinit var bindButton: Button

    private val main = Handler(Looper.getMainLooper())
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bind)
        app = application as LitvApp

        serverInput = findViewById(R.id.serverInput)
        codeInput = findViewById(R.id.codeInput)
        status = findViewById(R.id.status)
        bindButton = findViewById(R.id.bindButton)

        serverInput.setText(app.prefs.serverBase)

        bindButton.setOnClickListener { doBind() }
        bindButton.requestFocus()
    }

    private fun doBind() {
        val base = serverInput.text.toString().trim()
        if (base.isBlank()) {
            status.text = "请先填服务器地址，例如 http://192.168.1.10:9527"
            return
        }
        val normalized = if (base.startsWith("http")) base else "http://$base"
        app.prefs.serverBase = normalized
        status.text = "正在连接 $normalized …"
        bindButton.isEnabled = false

        app.api.probe(normalized) { ok, why ->
            if (!ok) {
                status.text = "连不上服务端：$why"
                bindButton.isEnabled = true
                return@probe
            }
            registerAndMaybePoll()
        }
    }

    private fun registerAndMaybePoll() {
        status.text = "正在注册设备…"
        val code = codeInput.text.toString().trim().ifBlank { null }
        app.api.register(code,
            onOk = { r ->
                bindButton.isEnabled = true
                when (r.status) {
                    "bound" -> {
                        status.text = getString(R.string.bind_success)
                        goHome()
                    }
                    else -> {
                        status.text = r.hint.ifBlank { getString(R.string.bind_pending) }
                        startPolling()
                    }
                }
            },
            onErr = { msg ->
                bindButton.isEnabled = true
                status.text = msg
                // 已注册过的设备（重装/重启）会返回 409，此时直接进入轮询
                if (msg.contains("已注册")) startPolling()
            })
    }

    /** 每 3 秒试一次 bootstrap，服务端放行后自动进入首页。 */
    private fun startPolling() {
        if (polling) return
        polling = true
        val tick = object : Runnable {
            override fun run() {
                if (!polling) return
                app.api.bootstrap(
                    onOk = {
                        polling = false
                        status.text = getString(R.string.bind_success)
                        goHome()
                    },
                    onErr = { main.postDelayed(this, 3000) },
                )
            }
        }
        main.postDelayed(tick, 3000)
    }

    private fun goHome() {
        main.postDelayed({
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }, 500)
    }

    override fun onDestroy() {
        polling = false
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
