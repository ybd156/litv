"""手机端返回二次确认改用 OnBackPressedDispatcher。

AppCompatActivity（androidx.activity 1.6+）把返回键交给 OnBackPressedDispatcher 处理，
直接 override onBackPressed() 不一定拦得住——实测第一次按就直接退出了。
用 dispatcher 注册回调最可靠。
"""
import io

p = "android/app/src/main/java/com/litv/tv/ui/PhoneHomeActivity.kt"
s = io.open(p, encoding="utf-8").read()

# 删掉旧的 override
old_override = """
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
"""
if old_override in s:
    s = s.replace(old_override, "\n")
    print("旧 override 已删")

if "addCallback" not in s:
    # 在 onCreate 里注册（挂在 setContentView 之后）
    anchor = "        epgList = findViewById(R.id.epgList)"
    reg = """        epgList = findViewById(R.id.epgList)

        // 返回二次确认：直接退太容易误触，参考实现都是「再按一次返回退出」。
        // 用 OnBackPressedDispatcher 注册，override onBackPressed() 在
        // androidx.activity 上不一定拦得住。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                lastBackAt = now
                android.widget.Toast.makeText(this@PhoneHomeActivity, "再按一次返回退出",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        })"""
    assert anchor in s, "找不到 onCreate 锚点"
    s = s.replace(anchor, reg, 1)

    # import
    if "androidx.activity.OnBackPressedCallback" not in s:
        s = s.replace("import android.content.Intent\n",
                      "import android.content.Intent\nimport androidx.activity.OnBackPressedCallback\n", 1)

    io.open(p, "w", encoding="utf-8").write(s)
    print("OnBackPressedDispatcher 已注册")
