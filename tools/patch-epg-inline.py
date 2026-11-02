"""节目单列改成常显（紧挨频道列表），不再是要按 → 才出来的抽屉。"""
import io

# 1) 布局：去掉 gone
p = "android/app/src/main/res/layout/activity_player.xml"
s = io.open(p, encoding="utf-8").read()
s = s.replace(
    """            android:paddingHorizontal="8dp"
            android:visibility="gone">""",
    """            android:paddingHorizontal="8dp">""",
    1,
)
io.open(p, "w", encoding="utf-8").write(s)
print("布局: 节目单列改为常显")

# 2) Activity：开面板时刷新节目单列；关闭面板时一起收起
p = "android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt"
s = io.open(p, encoding="utf-8").read()

if "refreshEpgColumn" not in s:
    # openPanel 里加上刷新
    s = s.replace(
        "        applyChannelFilter()\n        val i = channels.indexOfFirst { it.id == current?.id }",
        "        applyChannelFilter()\n        refreshEpgColumn()\n        val i = channels.indexOfFirst { it.id == current?.id }",
        1,
    )
    # 追加方法
    anchor = "    /** 呼出节目单面板（右侧）。数据是进频道时就预取的，所以这里是瞬开。 */"
    s = s.replace(anchor, """    /**
     * 刷新节目单列。
     *
     * 参考实现里节目单是紧挨频道列表常显的第三列，不是抽屉，
     * 所以开浮层时要跟着一起刷，切台时也要跟着换。
     */
    private fun refreshEpgColumn() {
        epgPanel.visibility = View.VISIBLE
        epgTitle.text = "${current?.name ?: "节目单"}"
        epgAdapter.submit(programs)
        val pos = epgAdapter.currentPosition()
        if (pos >= 0) epgList.scrollToPosition(pos)
    }

""" + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print("Activity: 已加 refreshEpgColumn")

# 3) closeEpg 不再隐藏整列（它是浮层的一部分）
s = io.open(p, encoding="utf-8").read()
s = s.replace(
    """    private fun closeEpg() {
        epgVisible = false
        epgPanel.visibility = View.GONE
    }""",
    """    private fun closeEpg() {
        // 节目单现在是浮层里常显的一列，不单独隐藏，把焦点还给频道列表即可
        epgVisible = false
        panelList.requestFocus()
    }""",
    1,
)
io.open(p, "w", encoding="utf-8").write(s)
print("Activity: closeEpg 已调整")
