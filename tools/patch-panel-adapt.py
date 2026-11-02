"""适配新频道浮层：去掉 srcList/grpList/epgTab，分组改由左侧窄竖条循环切换。"""
import io
import re

p = "android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt"
s = io.open(p, encoding="utf-8").read()

# 1) 字段：去掉 srcList/grpList 声明，保留适配器（rebuildColumns 里还会用到计数）
s = s.replace("    private lateinit var srcList: RecyclerView\n", "")
s = s.replace("    private lateinit var grpList: RecyclerView\n", "")

# 2) onCreate 里那几行绑定
for line in [
    "        srcList = findViewById(R.id.srcList)\n",
    "        grpList = findViewById(R.id.grpList)\n",
    "        srcList.layoutManager = LinearLayoutManager(this)\n",
    "        grpList.layoutManager = LinearLayoutManager(this)\n",
    "        srcList.adapter = srcAdapter\n",
    "        grpList.adapter = grpAdapter\n",
    "        // 浮层右边缘的「节目单」竖条\n",
    "        findViewById<TextView>(R.id.epgTab)?.setOnClickListener { openEpg() }\n",
]:
    s = s.replace(line, "")

# 3) srcAdapter/grpAdapter 的 onPick 回调块整段删掉
s = re.sub(
    r"\n        srcAdapter\.onPick = \{ name ->[\s\S]*?\n        \}\n        grpAdapter\.onPick = \{ name ->[\s\S]*?\n        \}\n",
    "\n",
    s,
    count=1,
)

# 4) rebuildColumns：不再建两列，只按分组过滤频道
old_rebuild = re.search(r"\n    /\*\* 根据当前源选择，重建「源」和「分组」两列 \*/\n    private fun rebuildColumns\(\) \{[\s\S]*?\n    \}\n", s)
if old_rebuild:
    s = s[:old_rebuild.start()] + """
    /** 分组窄竖条点击：在所有分组之间循环 */
    private fun cycleGroup() {
        val all = listOf("") + allChannels.map { it.group }.filter { it.isNotBlank() }.distinct()
        val i = all.indexOf(selGroup)
        selGroup = all[(i + 1).mod(all.size)]
        findViewById<TextView>(R.id.grpStrip)?.text =
            (if (selGroup.isEmpty()) "全部" else selGroup) + "\\n《\\n频道"
        applyChannelFilter()
    }
""" + s[old_rebuild.end():]

# 5) 浮层打开时重建（原来调 rebuildColumns，现在只要过滤）
s = s.replace("        rebuildColumns()\n        applyChannelFilter()", "        applyChannelFilter()")

# 6) grpStrip 点击
if "grpStrip" not in s:
    s = s.replace(
        "        settingsMenu = findViewById(R.id.settingsMenu)",
        "        settingsMenu = findViewById(R.id.settingsMenu)\n"
        "        findViewById<TextView>(R.id.grpStrip)?.setOnClickListener { cycleGroup() }",
        1,
    )

io.open(p, "w", encoding="utf-8").write(s)
print("PlayerActivity 已适配新浮层")
