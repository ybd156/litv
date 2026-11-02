"""按参考实现重做频道浮层。

参考结构（DIYP / TVBox 系）：
    [分组窄竖条「全部《频道」] [台号框 + 台名 + 当前节目] [星期 + 时间 + 节目名 + 回看] [视频可见]

我之前的版本：
    [图标栏 88dp] [源分类 200dp] [分组 170dp] [频道列表] —— 分组太宽、节目单是抽屉不是常显列。

这次改成：分组收成窄竖条，节目单作为第三列常显，右边留出视频。
"""
import io
import re

p = "android/app/src/main/res/layout/activity_player.xml"
s = io.open(p, encoding="utf-8").read()

start = s.find('android:id="@+id/channelPanel"')
assert start != -1
open_at = s.rfind("<LinearLayout", 0, start)
end_marker = "<!-- 右侧节目单面板 -->"
end_at = s.find(end_marker)
assert end_at != -1

new_panel = '''<!-- 频道浮层：分组窄竖条 / 频道列表 / 节目单，右侧露出视频 -->
    <LinearLayout
        android:id="@+id/channelPanel"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@android:color/transparent"
        android:orientation="horizontal"
        android:paddingVertical="12dp"
        android:visibility="gone">

        <!-- 1. 分组窄竖条（参考实现里是竖排文字） -->
        <TextView
            android:id="@+id/grpStrip"
            android:layout_width="66dp"
            android:layout_height="match_parent"
            android:background="#E60B0E11"
            android:clickable="true"
            android:focusable="true"
            android:gravity="center"
            android:lineSpacingExtra="6dp"
            android:text="全部
《
频道"
            android:textColor="@color/text_primary"
            android:textSize="16sp" />

        <!-- 2. 频道列表：台号框 + 台名 + 当前节目 -->
        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="0.40"
            android:background="#E60B0E11"
            android:orientation="vertical"
            android:paddingHorizontal="8dp">

            <TextView
                android:id="@+id/panelTitle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingBottom="6dp"
                android:paddingStart="4dp"
                android:textColor="@color/accent"
                android:textSize="13sp" />

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/panelList"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:scrollbars="none" />
        </LinearLayout>

        <!-- 3. 节目单：星期 + 时间 + 节目名 + 回看 -->
        <LinearLayout
            android:id="@+id/epgPanel"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="0.34"
            android:background="#D90B0E11"
            android:orientation="vertical"
            android:paddingHorizontal="8dp"
            android:visibility="gone">

            <TextView
                android:id="@+id/epgTitle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingBottom="6dp"
                android:paddingStart="4dp"
                android:textColor="@color/accent"
                android:textSize="13sp" />

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/epgList"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:scrollbars="none" />
        </LinearLayout>

        <!-- 4. 右侧留白，露出正在播的画面 -->
        <View
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="0.26" />
    </LinearLayout>

    '''

s = s[:open_at] + new_panel + s[end_at:]
io.open(p, "w", encoding="utf-8").write(s)
print("channelPanel 已重做：分组窄竖条 / 频道列表 / 节目单第三列 / 右侧露视频")
