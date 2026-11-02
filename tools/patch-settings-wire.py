"""右侧设置菜单接线。"""
import io

p = "android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt"
s = io.open(p, encoding="utf-8").read()

if "settingsMenu" not in s:
    # 字段
    s = s.replace(
        "    private lateinit var epgPanel: LinearLayout",
        "    private lateinit var settingsMenu: android.view.View\n"
        "    private var menuVisible = false\n"
        "    private lateinit var epgPanel: LinearLayout",
        1,
    )
    # findViewById + 点击
    s = s.replace(
        "        epgPanel = findViewById(R.id.epgPanel)",
        """        settingsMenu = findViewById(R.id.settingsMenu)
        // 右侧设置菜单：能就地改的就地改，改不了的转给完整设置弹层
        findViewById<TextView>(R.id.miAspect).setOnClickListener {
            app.prefs.aspect = cycleOf(app.prefs.aspect, listOf("auto", "16:9", "4:3", "fill"))
            applyAspect()
        }
        findViewById<TextView>(R.id.miDecode).setOnClickListener {
            app.prefs.decode = cycleOf(app.prefs.decode, listOf("auto", "hard", "soft"))
            toastTip("解码方式：" + app.prefs.decode)
        }
        findViewById<TextView>(R.id.miLine).setOnClickListener { openLinePicker() }
        findViewById<TextView>(R.id.miTimeout).setOnClickListener {
            SettingsDialog(this).show()
        }
        findViewById<TextView>(R.id.miCategory).setOnClickListener {
            closeSettingsMenu(); openPanel()
        }
        findViewById<TextView>(R.id.miList).setOnClickListener {
            closeSettingsMenu(); openPanel()
        }
        findViewById<TextView>(R.id.miDisplay).setOnClickListener {
            SettingsDialog(this).show()
        }
        findViewById<TextView>(R.id.miPrefs).setOnClickListener {
            SettingsDialog(this).show()
        }
        findViewById<TextView>(R.id.miOther).setOnClickListener {
            SettingsDialog(this).show()
        }
        epgPanel = findViewById(R.id.epgPanel)""",
        1,
    )
    # MENU 改成切换菜单
    s = s.replace(
        """            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                // 菜单键 = 打开设置弹层，改完继续看，不离开画面
                if (action == KeyEvent.ACTION_DOWN) SettingsDialog(this).show()
                return true""",
        """            KeyEvent.KEYCODE_MENU -> {
                // 规范：Menu 键呼出/隐藏右侧设置菜单
                if (action == KeyEvent.ACTION_DOWN) toggleSettingsMenu()
                return true""",
        1,
    )
    # 方法
    s = s.rstrip()
    assert s.endswith("}")
    s = s[:-1] + """
    // ---------------------------------------------------------------- 右侧设置菜单

    private fun toggleSettingsMenu() {
        if (menuVisible) closeSettingsMenu() else {
            menuVisible = true
            settingsMenu.visibility = android.view.View.VISIBLE
            hideOsd()
            settingsMenu.findViewById<TextView>(R.id.miLine)?.requestFocus()
        }
    }

    private fun closeSettingsMenu() {
        menuVisible = false
        settingsMenu.visibility = android.view.View.GONE
    }

    private fun cycleOf(cur: String, all: List<String>): String {
        val i = all.indexOf(cur)
        return all[(i + 1).mod(all.size)]
    }

    private fun toastTip(msg: String) {
        switchTip.text = msg
        switchTip.visibility = android.view.View.VISIBLE
        main.postDelayed({ switchTip.visibility = android.view.View.GONE }, 2000)
    }

    /** 线路选择：把当前频道的线路列出来，选中就切过去 */
    private fun openLinePicker() {
        val ch = current ?: return
        closeSettingsMenu()
        app.api.streams(ch.id, onOk = { list ->
            if (list.isEmpty()) { toastTip("这个台只有一条线路"); return@streams }
            val names = list.map { "${it.quality.ifBlank { "线路" }} ${it.latencyMs}ms" }
            android.app.AlertDialog.Builder(this)
                .setTitle("选择线路（${list.size} 条）")
                .setItems(names.toTypedArray()) { _, i ->
                    playChannel(ch, list[i].id)
                }
                .show()
        }, onErr = { toastTip("取线路失败：$it") })
    }
}
"""
    io.open(p, "w", encoding="utf-8").write(s)
    print("PlayerActivity: 设置菜单已接线")
else:
    print("已有 settingsMenu，跳过")
