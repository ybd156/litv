"""浮层方向键改成「在列之间移动焦点」，对齐参考实现。

参考操作：
    ←  从频道列表回到分组竖条；从节目单回到频道列表
    →  从分组竖条进入频道列表；从频道列表进入节目单
    ↑↓ 在当前列内移动
    OK 播放选中频道

之前是「← → 都用来切分组」，结果没法把焦点移到节目单列去滚动它。
"""
import io

p = "android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt"
s = io.open(p, encoding="utf-8").read()

old = """                        cycleGroup(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // 规范：焦点在频道列表时按右 → 打开节目单
                        openEpg()
                        return true
                    }"""

new = """                        movePanelFocus(-1); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        movePanelFocus(+1); return true
                    }"""

assert old in s, "找不到浮层左右键绑定"
s = s.replace(old, new, 1)

if "movePanelFocus" not in s:
    anchor = "    private fun cycleGroup() {"
    block = """    /**
     * 浮层里在列之间移动焦点：分组竖条 ←→ 频道列表 ←→ 节目单。
     *
     * 参考实现就是这么操作的，比「左右键都拿来切分组」合理：
     * 分组竖条本身是可点的，焦点落上去就能用上下切分组，
     * 左右则负责跨列。
     */
    private fun movePanelFocus(dir: Int) {
        val strip = findViewById<TextView>(R.id.grpStrip)
        when {
            // 在最左：往右进频道列表
            strip.hasFocus() && dir > 0 -> panelList.requestFocus()
            // 在频道列表：往左回分组竖条，往右进节目单
            panelList.hasFocus() && dir < 0 -> strip.requestFocus()
            panelList.hasFocus() && dir > 0 -> epgList.requestFocus()
            // 在节目单：往左回频道列表
            epgList.hasFocus() && dir < 0 -> panelList.requestFocus()
            else -> if (dir > 0) panelList.requestFocus() else strip.requestFocus()
        }
    }

""" + anchor
    s = s.replace(anchor, block, 1)

io.open(p, "w", encoding="utf-8").write(s)
print("浮层焦点导航已接入")
