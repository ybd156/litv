"""BACK 键加二次确认：第一次提示「再按一次退出」，两秒内再按才真的退出。

参考实现（TVBox/DIYP 系）都是这个做法——直接退出太容易误触，
尤其遥控器上返回键和方向键挨着。
"""
import io

# ---------- 电视端 ----------
p = "android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt"
s = io.open(p, encoding="utf-8").read()

old = """            KeyEvent.KEYCODE_BACK -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (osdVisible) {
                        hideOsd()
                    } else {
                        finish()
                    }
                }
                return true
            }"""

new = """            KeyEvent.KEYCODE_BACK -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    when {
                        osdVisible -> hideOsd()
                        menuVisible -> closeSettingsMenu()
                        panelVisible -> closePanel()
                        // 二次确认：直接退出太容易误触（遥控器返回键和方向键挨着）
                        System.currentTimeMillis() - lastBackAt < 2000 -> finish()
                        else -> {
                            lastBackAt = System.currentTimeMillis()
                            toastTip("再按一次返回退出")
                        }
                    }
                }
                return true
            }"""

assert old in s, "找不到 BACK 处理"
s = s.replace(old, new, 1)

if "lastBackAt" not in s:
    s = s.replace(
        "    private var okDownTime = 0L",
        "    private var okDownTime = 0L\n"
        "    /** 上一次按返回的时间，用于「再按一次退出」 */\n"
        "    private var lastBackAt = 0L",
        1,
    )

io.open(p, "w", encoding="utf-8").write(s)
print("PlayerActivity: 二次确认已加")

# ---------- 手机端 ----------
p = "android/app/src/main/java/com/litv/tv/ui/PhoneHomeActivity.kt"
s = io.open(p, encoding="utf-8").read()

if "onBackPressed" not in s:
    s = s.rstrip()
    assert s.endswith("}")
    s = s[:-1] + """
    // ---------------------------------------------------------------- 返回

    private var lastBackAt = 0L

    /**
     * 二次确认退出。
     * 直接退太容易误触，参考实现都是「再按一次返回退出」。
     */
    @Deprecated("老 API，但 minSdk 21 上最省事")
    override fun onBackPressed() {
        if (System.currentTimeMillis() - lastBackAt < 2000) {
            super.onBackPressed()
            return
        }
        lastBackAt = System.currentTimeMillis()
        android.widget.Toast.makeText(this, "再按一次返回退出", android.widget.Toast.LENGTH_SHORT).show()
    }
}
"""
    io.open(p, "w", encoding="utf-8").write(s)
    print("PhoneHomeActivity: 二次确认已加")
