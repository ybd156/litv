package com.litv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var ipBoxes: List<EditText>
    private lateinit var portInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var status: TextView
    private lateinit var bindButton: Button
    private lateinit var qrBox: LinearLayout
    private lateinit var qrImage: ImageView

    private val main = Handler(Looper.getMainLooper())
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bind)
        app = application as LitvApp

        // 服务器地址拆成 4 段 IP + 端口：遥控器上输 "http://192.168.1.10:9527"
        // 要反复切数字/符号键，拆框后只输数字、还能自动跳格。
        ipBoxes = listOf(
            findViewById(R.id.ip1),
            findViewById(R.id.ip2),
            findViewById(R.id.ip3),
            findViewById(R.id.ip4),
        )
        portInput = findViewById(R.id.portInput)
        codeInput = findViewById(R.id.codeInput)
        status = findViewById(R.id.status)
        bindButton = findViewById(R.id.bindButton)
        qrBox = findViewById(R.id.qrBox)
        qrImage = findViewById(R.id.qrImage)

        setupAddrBoxes()
        fillAddr(app.prefs.serverBase)
        // 如果之前填过地址，直接先把二维码亮出来——用户一眼就知道该拿手机扫
        if (app.prefs.serverBase.isNotBlank()) showQr(app.prefs.serverBase)

        bindButton.setOnClickListener { doBind() }
        bindButton.requestFocus()
    }

    /** 输满 3 位自动跳到下一格，退格到空自动回上一格。 */
    private fun setupAddrBoxes() {
        val chain: List<EditText> = ipBoxes + portInput
        chain.forEachIndexed { i, box ->
            box.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val txt = s?.toString().orEmpty()
                    val limit = if (i == chain.lastIndex) 5 else 3
                    if (txt.length >= limit && i < chain.lastIndex) {
                        chain[i + 1].requestFocus()
                    }
                    // 清空后按退格回上一格
                    if (txt.isEmpty() && i > 0 && !box.hasFocus()) {
                        chain[i - 1].requestFocus()
                    }
                }
            })
        }
    }

    /** 把保存的地址（http://a.b.c.d:port）回填到各个框。 */
    private fun fillAddr(base: String) {
        if (base.isBlank()) {
            portInput.setText(DEFAULT_PORT)
            ipBoxes.first().requestFocus()
            return
        }
        val m = Regex("""^(?:https?://)?([0-9.]+)(?::(\d+))?""").find(base.trim())
        val host = m?.groupValues?.getOrNull(1).orEmpty()
        val port = m?.groupValues?.getOrNull(2).orEmpty().ifBlank { DEFAULT_PORT }
        val parts = host.split(".")
        ipBoxes.forEachIndexed { i, box -> box.setText(parts.getOrElse(i) { "" }) }
        portInput.setText(port)
    }

    /** 从各个框拼出完整地址；非法返回 null。 */
    private fun composeAddr(): String? {
        val octets = ipBoxes.map { it.text.toString().trim() }
        if (octets.any { it.isEmpty() }) return null
        if (octets.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return null
        val port = portInput.text.toString().trim().ifBlank { DEFAULT_PORT }
        val p = port.toIntOrNull() ?: return null
        if (p !in 1..65535) return null
        return "http://${octets.joinToString(".")}:$p"
    }

    private companion object {
        const val DEFAULT_PORT = "9527"
    }

    /** 显示管理台二维码：手机扫完直接进「终端设备」页批准这台电视。 */
    private fun showQr(base: String) {
        val url = base.trimEnd('/') + "/"
        val bmp = QrUtil.encode(url, 512)
        if (bmp == null) {
            qrBox.visibility = View.GONE
            return
        }
        qrImage.setImageBitmap(bmp)
        qrBox.visibility = View.VISIBLE
    }

    private fun doBind() {
        val normalized = composeAddr()
        if (normalized == null) {
            status.text = "请把服务器 IP 四段和端口填完整（每段 0-255，端口 1-65535）"
            return
        }
        app.prefs.serverBase = normalized
        status.text = "正在连接 $normalized …"
        bindButton.isEnabled = false

        app.api.probe(normalized) { ok, why ->
            if (!ok) {
                status.text = "连不上服务端：$why"
                bindButton.isEnabled = true
                return@probe
            }
            showQr(normalized)
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
