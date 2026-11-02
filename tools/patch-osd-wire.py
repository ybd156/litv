"""播放页 OSD 接线：台标 / 台号 / 标签。"""
import io

p = "android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt"
s = io.open(p, encoding="utf-8").read()

if "osdNo" not in s:
    s = s.replace(
        "    private lateinit var osdName: TextView",
        "    private lateinit var osdNo: TextView\n"
        "    private lateinit var osdTags: TextView\n"
        "    private lateinit var osdLogo: android.widget.ImageView\n"
        "    private lateinit var osdName: TextView",
        1,
    )
    s = s.replace(
        "        osdName = findViewById(R.id.osdName)",
        "        osdNo = findViewById(R.id.osdNo)\n"
        "        osdTags = findViewById(R.id.osdTags)\n"
        "        osdLogo = findViewById(R.id.osdLogo)\n"
        "        osdName = findViewById(R.id.osdName)",
        1,
    )
    s = s.replace(
        "        osdName.text = ch.name",
        "        osdName.text = ch.name\n"
        "        osdNo.text = if (ch.number > 0) ch.number.toString() else \"\"\n"
        "        // 标签行：画质 + 线路数（参考实现是 [FHD][立体声][IPV4][线路1/1]）\n"
        "        osdTags.text = buildList {\n"
        "            if (ch.streamCount > 1) add(\"线路1/${ch.streamCount}\")\n"
        "        }.joinToString(\"  \")\n"
        "        if (ch.logo.isBlank()) osdLogo.setImageDrawable(null)",
        1,
    )
    io.open(p, "w", encoding="utf-8").write(s)
    print("PlayerActivity: OSD 已接线")
else:
    print("PlayerActivity: 已有")
