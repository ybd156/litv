"""删掉旧的独立节目单抽屉（新的已经作为第三列内联了）。"""
import io

p = "android/app/src/main/res/layout/activity_player.xml"
s = io.open(p, encoding="utf-8").read()

marker = "<!-- 右侧节目单面板 -->"
i = s.find(marker)
if i == -1:
    print("没有旧面板，跳过")
else:
    # 从注释后第一个 <LinearLayout 开始，找到匹配的结束标签
    open_at = s.find("<LinearLayout", i)
    depth = 0
    j = open_at
    while j < len(s):
        nxt_open = s.find("<LinearLayout", j + 1)
        nxt_close = s.find("</LinearLayout>", j + 1)
        if nxt_close == -1:
            break
        if nxt_open != -1 and nxt_open < nxt_close:
            depth += 1
            j = nxt_open
        else:
            if depth == 0:
                end = nxt_close + len("</LinearLayout>")
                s = s[:i] + s[end:]
                io.open(p, "w", encoding="utf-8").write(s)
                print("旧节目单抽屉已删除")
                break
            depth -= 1
            j = nxt_close
