"""按操作规范改四列浮层的键位。

规范（电视端）：
  ←     呼出频道列表
  面板内 ←→ 在列之间移动焦点（分组列就是"切换分组"）
  面板内焦点在频道列表按 →  打开节目单
  面板内 OK  播放选中频道
  BACK   关闭面板
  长按 OK  线路切换
  OK 短按  OSD 信息栏（5 秒自动隐藏）
  MENU   设置栏

原来面板内 ←→ 走的是旧的 switchGroup()（单列时代"切分组"），
和现在的四列布局冲突，改成移动焦点。
"""
import io

p = "android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt"
s = io.open(p, encoding="utf-8").read()

old = """		if (panelVisible) {
			if (action == KeyEvent.ACTION_DOWN) {
				when (code) {
					KeyEvent.KEYCODE_BACK -> {
						closePanel(); return true
					}
					KeyEvent.KEYCODE_DPAD_LEFT -> {
						switchGroup(-1); return true
					}
					KeyEvent.KEYCODE_DPAD_RIGHT -> {
						switchGroup(+1); return true
					}
				}
			}
			return super.dispatchKeyEvent(event)
		}"""

new = """		if (panelVisible) {
			if (action == KeyEvent.ACTION_DOWN) {
				when (code) {
					KeyEvent.KEYCODE_BACK -> {
						closePanel(); return true
					}
					KeyEvent.KEYCODE_DPAD_LEFT -> {
						moveColumn(-1); return true
					}
					KeyEvent.KEYCODE_DPAD_RIGHT -> {
						// 规范：焦点在频道列表时按 → 打开节目单
						if (panelList.hasFocus()) openEpg() else moveColumn(+1)
						return true
					}
				}
			}
			return super.dispatchKeyEvent(event)
		}"""

assert old in s, "找不到面板键位段"
s = s.replace(old, new, 1)

# 追加 moveColumn
if "private fun moveColumn" not in s:
    anchor = "    private fun switchGroup(delta: Int) {"
    s = s.replace(anchor, """    /** 四列之间移动焦点：源 → 分组 → 频道，反向同理 */
    private fun moveColumn(delta: Int) {
        when {
            panelList.hasFocus() && delta < 0 -> grpList.requestFocus()
            grpList.hasFocus() && delta > 0 -> panelList.requestFocus()
            grpList.hasFocus() && delta < 0 -> srcList.requestFocus()
            srcList.hasFocus() && delta > 0 -> grpList.requestFocus()
            else -> if (delta > 0) panelList.requestFocus() else srcList.requestFocus()
        }
    }

""" + anchor, 1)

io.open(p, "w", encoding="utf-8").write(s)
print("PlayerActivity: 四列焦点导航已接入")
