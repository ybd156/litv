"""给 ChannelAdapter 加公开的台标加载方法，给 Prefs 加开机自动播放开关。"""
import io

# 1) ChannelAdapter
p = "android/app/src/main/java/com/litv/tv/ui/ChannelAdapter.kt"
s = io.open(p, encoding="utf-8").read()
old = "    private fun loadLogo(target: ImageView, url: String) {"
new = (
    "    /** 公开出来：三列布局右侧的台标复用同一份缓存 */\n"
    "    fun loadLogoFor(target: ImageView, url: String) = loadLogo(target, url)\n\n"
    "    private fun loadLogo(target: ImageView, url: String) {"
)
if "loadLogoFor" not in s:
    s = s.replace(old, new, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print("ChannelAdapter: 已加 loadLogoFor")
else:
    print("ChannelAdapter: 已有")

# 2) Prefs
p = "android/app/src/main/java/com/litv/tv/data/Prefs.kt"
s = io.open(p, encoding="utf-8").read()
anchor = "    /**\n     * 播放方式：auto（默认，能直连就直连）"
block = (
    "    /**\n"
    "     * 开机自动播放上次看的频道。\n"
    "     *\n"
    "     * 电视的使用习惯就是「打开就看」，不该先让人在列表里找台。\n"
    "     */\n"
    "    var autoPlayLast: Boolean\n"
    "        get() = sp.getBoolean(K_AUTOPLAY, true)\n"
    "        set(v) = sp.edit().putBoolean(K_AUTOPLAY, v).apply()\n\n"
)
if "autoPlayLast" not in s:
    assert anchor in s, "找不到 Prefs 插入点"
    s = s.replace(anchor, block + anchor, 1)
    s = s.replace(
        '        const val K_UI_MODE = "ui_mode"',
        '        const val K_UI_MODE = "ui_mode"\n        const val K_AUTOPLAY = "auto_play_last"',
        1,
    )
    io.open(p, "w", encoding="utf-8").write(s)
    print("Prefs: 已加 autoPlayLast")
else:
    print("Prefs: 已有")
