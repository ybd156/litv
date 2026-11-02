"""四列浮层接线：源分类 + 分组 两级筛选。"""
import io

# ---------- 1. 模型：补 source_id 和 sources ----------
p = "android/app/src/main/java/com/litv/tv/data/Models.kt"
s = io.open(p, encoding="utf-8").read()
if "sourceId" not in s:
    s = s.replace(
        "    @SerializedName(\"stream_count\") val streamCount: Int = 0,\n)",
        "    @SerializedName(\"stream_count\") val streamCount: Int = 0,\n"
        "    /** 所属源，电视端按源分类用 */\n"
        "    @SerializedName(\"source_id\") val sourceId: Long = 0,\n)",
        1,
    )
if "data class SourceBrief" not in s:
    s = s.rstrip() + """

/** /tv/bootstrap 里带的源清单（只有 id 和名称） */
data class SourceBrief(
    val id: Long = 0,
    val name: String = "",
)
"""
io.open(p, "w", encoding="utf-8").write(s)
print("Models: 已补 sourceId / SourceBrief")

# ---------- 2. Bootstrap 模型加 sources ----------
s = io.open(p, encoding="utf-8").read()
if "val sources: List<SourceBrief>" not in s:
    s = s.replace(
        "    val now: List<NowPlaying> = emptyList(),\n)",
        "    val now: List<NowPlaying> = emptyList(),\n"
        "    val sources: List<SourceBrief> = emptyList(),\n)",
        1,
    )
    io.open(p, "w", encoding="utf-8").write(s)
    print("Models: Bootstrap 已加 sources")

# ---------- 3. PlayerActivity 接线 ----------
p = "android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt"
s = io.open(p, encoding="utf-8").read()

if "srcList" not in s:
    s = s.replace(
        "    private lateinit var panelList: RecyclerView",
        "    private lateinit var panelList: RecyclerView\n"
        "    private lateinit var srcList: RecyclerView\n"
        "    private lateinit var grpList: RecyclerView\n"
        "    private val srcAdapter = GroupAdapter()\n"
        "    private val grpAdapter = GroupAdapter()\n"
        "    /** 当前选中的源 id，0 = 全部 */\n"
        "    private var selSource = 0L\n"
        "    /** 当前选中的分组，空 = 全部 */\n"
        "    private var selGroup = \"\"\n"
        "    private var allChannels: List<com.litv.tv.data.Channel> = emptyList()\n"
        "    private var srcNames: Map<Long, String> = emptyMap()",
        1,
    )
    s = s.replace(
        "        panelList = findViewById(R.id.panelList)",
        "        panelList = findViewById(R.id.panelList)\n"
        "        srcList = findViewById(R.id.srcList)\n"
        "        grpList = findViewById(R.id.grpList)\n"
        "        srcList.layoutManager = LinearLayoutManager(this)\n"
        "        grpList.layoutManager = LinearLayoutManager(this)\n"
        "        srcList.adapter = srcAdapter\n"
        "        grpList.adapter = grpAdapter\n"
        "        srcAdapter.onPick = { name ->\n"
        "            selSource = srcAdapter.sourceIdOf(name)\n"
        "            selGroup = \"\"\n"
        "            rebuildColumns()\n"
        "        }\n"
        "        grpAdapter.onPick = { name ->\n"
        "            selGroup = if (name == null || name == \"全部\") \"\" else name\n"
        "            applyChannelFilter()\n"
        "        }",
        1,
    )
    # 加载完频道后重建两列
    s = s.replace(
        "            panelAdapter.submit(channels, emptyMap(), favoriteIds)",
        "            allChannels = channels\n"
        "            rebuildColumns()\n"
        "            applyChannelFilter()",
        1,
    )
    # 追加两个方法
    s = s.rstrip()
    assert s.endswith("}")
    s = s[:-1] + """
    /** 根据当前源选择，重建「源」和「分组」两列 */
    private fun rebuildColumns() {
        val srcRows = ArrayList<GroupAdapter.Row>()
        srcRows.add(GroupAdapter.Row("全部源", allChannels.size))
        val bySrc = allChannels.groupBy { it.sourceId }
        for ((sid, list) in bySrc) {
            val nm = srcNames[sid] ?: "源 $sid"
            srcRows.add(GroupAdapter.Row(nm, list.size))
        }
        srcAdapter.submit(srcRows, if (selSource == 0L) "全部源" else srcNames[selSource])

        val base = if (selSource == 0L) allChannels else allChannels.filter { it.sourceId == selSource }
        val grpRows = ArrayList<GroupAdapter.Row>()
        grpRows.add(GroupAdapter.Row("全部", base.size))
        base.groupBy { it.group }.forEach { (g, list) ->
            grpRows.add(GroupAdapter.Row(g.ifBlank { "未分组" }, list.size))
        }
        grpAdapter.submit(grpRows, if (selGroup.isEmpty()) "全部" else selGroup)
    }

    /** 按当前源+分组过滤，刷到频道列表 */
    private fun applyChannelFilter() {
        var list = if (selSource == 0L) allChannels else allChannels.filter { it.sourceId == selSource }
        if (selGroup.isNotEmpty()) list = list.filter { it.group == selGroup }
        panelAdapter.submit(list, emptyMap())
        panelTitle.text = buildString {
            append(if (selSource == 0L) "全部频道" else (srcNames[selSource] ?: "频道"))
            if (selGroup.isNotEmpty()) append(" · ").append(selGroup)
            append("  ").append(list.size).append(" 个")
        }
    }
}
"""
    io.open(p, "w", encoding="utf-8").write(s)
    print("PlayerActivity: 四列已接线")

# ---------- 4. GroupAdapter 补 sourceIdOf ----------
p = "android/app/src/main/java/com/litv/tv/ui/GroupAdapter.kt"
s = io.open(p, encoding="utf-8").read()
if "sourceIdOf" not in s:
    s = s.rstrip()
    s = s[:-1] + """
    /** 源列表用：名称 -> 源 id（「全部源」返回 0） */
    private var srcIds: Map<String, Long> = emptyMap()

    fun submitSources(rows: List<Row>, ids: Map<String, Long>, selectedName: String?) {
        srcIds = ids
        submit(rows, selectedName)
    }

    fun sourceIdOf(name: String?): Long {
        if (name == null || name == "全部源") return 0L
        return srcIds[name] ?: 0L
    }
}
"""
    io.open(p, "w", encoding="utf-8").write(s)
    print("GroupAdapter: 已加 sourceIdOf")
