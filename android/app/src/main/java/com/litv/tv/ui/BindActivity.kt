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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.litv.tv.data.ServerAddress
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
    private lateinit var ipRow: View
    private lateinit var domainInput: EditText
    private lateinit var btnToggleHost: TextView
    private lateinit var btnScheme: TextView
    private var usingDomain = false
    /** 是否走 https。域名默认 true，IP 默认 false，可手动切 */
    private var usingHttps = true
    private lateinit var codeInput: EditText
    private lateinit var status: TextView
    /** 当前打开的遥控键盘。连续切格时必须先关掉它，否则会叠出多个 Dialog。 */
    private var tvKb: TvKeyboard? = null

    private lateinit var bindButton: Button
    private lateinit var qrBox: LinearLayout
    private lateinit var qrImage: ImageView

    private val main = Handler(Looper.getMainLooper())
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bind)
        app = application as LitvApp

        // 服务器地址拆成 4 段 IP + 端口：遥控器上输 "http://192.168.1.10:28027"
        // 要反复切数字/符号键，拆框后只输数字、还能自动跳格。
        ipBoxes = listOf(
            findViewById(R.id.ip1),
            findViewById(R.id.ip2),
            findViewById(R.id.ip3),
            findViewById(R.id.ip4),
        )
        portInput = findViewById(R.id.portInput)
        ipRow = findViewById(R.id.ipRow)
        domainInput = findViewById(R.id.domainInput)
        btnToggleHost = findViewById(R.id.btnToggleHost)
        btnScheme = findViewById(R.id.btnScheme)
        codeInput = findViewById(R.id.codeInput)
        status = findViewById(R.id.status)
        bindButton = findViewById(R.id.bindButton)
        qrBox = findViewById(R.id.qrBox)
        qrImage = findViewById(R.id.qrImage)

        // 整页都不需要系统 IME：输入全走 TvKeyboard。
        // 只设 showSoftInputOnFocus=false 不够——实测系统 IME 仍会冒出来，
        // 占掉下半屏并吃掉方向键。这里在窗口层面直接要求永不自动弹出。
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )

        setupAddrBoxes()
        // 遥控适配：每个输入框关掉系统 IME、按 OK 弹自绘的 TvKeyboard。
        // 为什么不用系统软键盘：它是独立窗口，显示后方向键全被它吃掉，
        // 挂在输入框上的 OnKeyListener 一个键都收不到（详见 TvKeyboard 类注释）。
        (ipBoxes + listOf(portInput, domainInput, codeInput)).forEach { autoShowKeyboard(it) }
        btnToggleHost.setOnClickListener { setDomainMode(!usingDomain) }
        btnScheme.setOnClickListener {
            usingHttps = !usingHttps
            renderScheme()
        }
        renderScheme()
        fillAddr(app.prefs.serverBase)
        // 如果之前填过地址，直接先把二维码亮出来——用户一眼就知道该拿手机扫
        if (app.prefs.serverBase.isNotBlank()) showQr(app.prefs.serverBase)

        bindButton.setOnClickListener { doBind() }
        // ★ 必须 post 到布局完成之后。
        //
        // 在 onCreate 里直接 requestFocus() 是**无效的**：这时视图还没
        // measure/layout，拿不到尺寸，requestFocus 返回 false 而且不报错。
        // 结果——遥控上下左右按半天，屏幕上一点焦点高亮都没有，
        // 用户以为「遥控坏了」或者「这页面没做遥控适配」。
        bindButton.post { bindButton.requestFocus() }
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

    /**
     * 切换「IP 分框」和「域名输入」两种模式。
     *
     * 分框对遥控器友好（只输数字、自动跳格），但只收数字；
     * 用 DDNS 或反向代理的用户必须能填域名，所以两种模式都留着。
     */
    private fun renderScheme() {
        btnScheme.text = "协议：" + if (usingHttps) "HTTPS" else "HTTP"
    }

    private fun setDomainMode(on: Boolean) {
        usingDomain = on
        ipRow.visibility = if (on) View.GONE else View.VISIBLE
        domainInput.visibility = if (on) View.VISIBLE else View.GONE
        btnToggleHost.text = if (on) "用 IP 分框输入" else "用域名访问"

        if (!on) {
            // 切回 IP 模式时，只有域名真的是 IPv4 才拆进框里。
            // 否则像 iptv.683868.xyz 会被拆成 iptv/683868/xyz，全是垃圾。
            val raw = domainInput.text.toString().trim()
            val host = raw.removePrefix("https://").removePrefix("http://").substringBefore(":").substringBefore("/")
            val octets = host.split(".")
            val isIPv4 = octets.size == 4 && octets.all { (it.toIntOrNull() ?: -1) in 0..255 }
            if (isIPv4) {
                ipBoxes.forEachIndexed { i, box -> box.setText(octets[i]) }
            } else if (host.isNotBlank()) {
                ipBoxes.forEach { it.setText("") }
            }
        } else {
            // 切到域名模式：如果四个框里有合法 IP，拼出来带过去
            val octets = ipBoxes.map { it.text.toString().trim() }
            val isIPv4 = octets.all { it.isNotEmpty() } && octets.all { (it.toIntOrNull() ?: -1) in 0..255 }
            if (isIPv4 && domainInput.text.isBlank()) {
                domainInput.setText(octets.joinToString("."))
            }
        }

        if (on) domainInput.requestFocus() else ipBoxes.first().requestFocus()
    }

    /** 把保存的地址（http://a.b.c.d:port 或 http://域名:port）回填到各个框。 */
    private fun fillAddr(base: String) {
        if (base.isBlank()) {
            portInput.setText(DEFAULT_PORT)
            ipBoxes.first().requestFocus()
            return
        }
        val m = Regex("""^(?:https?://)?([^/:]+)(?::(\d+))?""").find(base.trim())
        val host = m?.groupValues?.getOrNull(1).orEmpty()
        val port = m?.groupValues?.getOrNull(2).orEmpty().ifBlank { DEFAULT_PORT }

        val octets = host.split(".")
        val isIPv4 = octets.size == 4 && octets.all { (it.toIntOrNull() ?: -1) in 0..255 }
        if (isIPv4) {
            ipBoxes.forEachIndexed { i, box -> box.setText(octets[i]) }
            portInput.setText(port)
            setDomainMode(false)
        } else {
            // 域名 / 主机名：切到域名模式，原样填进去
            domainInput.setText(if (port != DEFAULT_PORT) "$host:$port" else host)
            setDomainMode(true)
        }
    }

    /** 从当前模式拼出完整地址；非法返回 null。 */
    private fun composeAddr(): String? {
        if (usingDomain) {
            val raw = domainInput.text.toString().trim()
            if (raw.isBlank()) return null

            // 填了协议就尊重；没填默认 https（域名基本都是反代过来的）
            val hasScheme = raw.startsWith("http://") || raw.startsWith("https://")
            val scheme = when {
                raw.startsWith("https://") -> "https"
                raw.startsWith("http://") -> "http"
                else -> if (usingHttps) "https" else "http"
            }
            val withScheme = if (hasScheme) raw else "$scheme://$raw"

            val u = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
            val host = u.host ?: return null
            if (host.isBlank()) return null

            // 关键：没写端口就不要拼端口。
            // 反代域名（如 iptv.example.com）走 443，拼上服务端口 28027 反而连不上。
            if (u.port != -1) {
                if (u.port !in 1..65535) return null
                return "$scheme://$host:${u.port}"
            }
            return "$scheme://$host"
        }

        val octets = ipBoxes.map { it.text.toString().trim() }
        if (octets.any { it.isEmpty() }) return null
        if (octets.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return null
        val port = portInput.text.toString().trim().ifBlank { DEFAULT_PORT }
        val p = port.toIntOrNull() ?: return null
        if (p !in 1..65535) return null
        return "http://${octets.joinToString(".")}:$p"
    }

    private companion object {
        const val DEFAULT_PORT = "28027"
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
            // 按设备类型分流。电视端没有"首页"这个概念：绑完直接进全屏播放，
            // 频道列表是按 ← 呼出的浮层。
            val target = if (DeviceType.isTV(this)) {
                PlayerActivity::class.java
            } else {
                PhoneHomeActivity::class.java
            }
            startActivity(Intent(this, target))
            finish()
        }, 500)
    }

    override fun onDestroy() {
        polling = false
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * 电视端输入：**关掉系统 IME**，按 OK 弹自己画的键盘。
     *
     * 为什么不用系统软键盘：它是独立窗口，一旦显示（mInputShown=true），
     * 方向键全部被它吃掉，挂在输入框上的 OnKeyListener 一个键都收不到，
     * 焦点永远卡在框里。这是设计冲突，加 nextFocus 修不好。
     * 详见 [TvKeyboard] 的类注释。
     */
    private fun autoShowKeyboard(et: android.widget.EditText) {
        // 不让系统 IME 弹出来抢方向键
        et.showSoftInputOnFocus = false
        // 遥控 OK 弹我们自己的键盘。
        //
        // 注意：光 setOnClickListener 不够——EditText 获得焦点后会**自己消费**
        // DPAD_CENTER/ENTER（它当作"开始编辑"），click 根本不触发。
        // 所以要在 OnKeyListener 里也接一次。
        et.setOnClickListener { openTvKeyboard(et) }
        // 兜底：焦点进来时显式把系统 IME 收掉
        et.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
        }
        // 上下键在输入框之间移动（EditText 默认会把它们吃掉去移光标）
        et.setOnKeyListener { v, keyCode, ev ->
            // 只处理抬起：按下时让事件正常传给窗口，
            // 否则按下被这里吃掉、抬起才到 Dialog，行为会很怪。
            if (ev.action != android.view.KeyEvent.ACTION_UP) return@setOnKeyListener false
            // OK / ENTER：弹自绘键盘（EditText 自己会吞掉，必须在这里拦）
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            ) {
                openTvKeyboard(v as android.widget.EditText)
                return@setOnKeyListener true
            }
            val dir = when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> android.view.View.FOCUS_DOWN
                android.view.KeyEvent.KEYCODE_DPAD_UP -> android.view.View.FOCUS_UP
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> android.view.View.FOCUS_LEFT
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> android.view.View.FOCUS_RIGHT
                else -> return@setOnKeyListener false
            }
            val next = v.focusSearch(dir)
            if (next != null && next !== v) {
                next.requestFocus(); true
            } else {
                if (dir == android.view.View.FOCUS_UP && !usingDomain && v === codeInput) {
                    portInput.requestFocus(); true
                } else false
            }
        }
    }

    /** 弹自绘键盘。数字输入框（IP/端口/绑定码）只给数字键，界面更清爽。 */
    /**
     * 弹遥控键盘。
     *
     * 「上一格 / 下一格」不是只移动焦点，而是**关掉当前键盘、为新的一格再弹一个**
     * ——这样键盘顶部的标签和值都跟着换，用户不用退出去看自己现在在哪一格。
     */
    private fun openTvKeyboard(et: android.widget.EditText) {
        // ★ 先关掉可能还开着的键盘。
        //
        // 「上一格 / 下一格」的实现是「关旧、为新的一格再弹一个」，但如果这里
        // 不 dismiss，旧实例会一直留着 —— 连续切几格就叠出几个 Dialog，
        // 窗口焦点和触摸分发全乱套。注释早就这么写了，代码却没做。
        tvKb?.dismiss()
        tvKb = null

        // 键盘会盖住表单，所以顶部要写清楚正在编辑哪一格
        val label: String
        val maxLen: Int
        when {
            et === codeInput -> {
                label = "绑定码（6 位数字，可留空）"
                maxLen = 6
            }
            et === portInput -> {
                label = "端口"
                maxLen = 5
            }
            et === domainInput -> {
                label = "服务器地址"
                maxLen = 0
            }
            else -> {
                val idx = ipBoxes.indexOf(et)
                label = "服务器地址 · 第 ${idx + 1} 格"
                maxLen = 3
            }
        }

        // 当前模式下的输入顺序，决定上一格/下一格能去哪
        val order: List<android.widget.EditText> =
            if (usingDomain) listOf(domainInput, portInput, codeInput)
            else ipBoxes + listOf(portInput, codeInput)
        val i = order.indexOf(et)

        fun jump(target: android.widget.EditText) {
            // 关闭交给 openTvKeyboard 统一处理（它会先 dismiss 再弹新的）
            target.requestFocus()
            openTvKeyboard(target)
        }

        val kb = TvKeyboard(
            this,
            label = label,
            initial = et.text.toString(),
            maxLen = maxLen,
            onCommit = { text ->
                et.setText(text)
                et.setSelection(text.length)
            },
            onPrev = if (i > 0) ({ jump(order[i - 1]) }) else null,
            onNext = if (i >= 0 && i < order.lastIndex) ({ jump(order[i + 1]) }) else null,
        )
        kb.onClosed = { if (tvKb === kb) tvKb = null }
        tvKb = kb
        kb.show()
    }

}
