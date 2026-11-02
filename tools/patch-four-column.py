"""把播放页的频道面板改成参考实现的四列浮层。

    图标栏 | 源分类 | 分组 | 频道列表

原来是单列（360dp 宽的一条），和参考实现差得远。
保留 panelTitle / panelList 这两个 id，活动里的原有绑定不用改。
"""
import io
import re

p = "android/app/src/main/res/layout/activity_player.xml"
s = io.open(p, encoding="utf-8").read()

start = s.find('android:id="@+id/channelPanel"')
assert start != -1, "找不到 channelPanel"
# 回退到它所在的 <LinearLayout
open_at = s.rfind("<LinearLayout", 0, start)
# 找到这个面板的结束：epgPanel 注释之前
end_marker = "<!-- 右侧节目单面板 -->"
end_at = s.find(end_marker)
assert end_at != -1, "找不到 epgPanel 注释"

new_panel = '''<!-- 频道列表浮层：图标栏 / 源分类 / 分组 / 频道列表（对齐参考实现） -->
    <LinearLayout
        android:id="@+id/channelPanel"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#F20B0E11"
        android:orientation="horizontal"
        android:paddingVertical="16dp"
        android:visibility="gone">

        <!-- 1. 图标竖栏 -->
        <LinearLayout
            android:layout_width="88dp"
            android:layout_height="match_parent"
            android:gravity="center_horizontal"
            android:orientation="vertical"
            android:paddingHorizontal="8dp">

            <TextView
                android:id="@+id/railAll"
                style="@style/RailItem"
                android:text="全部" />

            <TextView
                android:id="@+id/railFav"
                style="@style/RailItem"
                android:text="收藏" />

            <TextView
                android:id="@+id/railSet"
                style="@style/RailItem"
                android:text="设置" />
        </LinearLayout>

        <!-- 2. 源分类 -->
        <LinearLayout
            android:layout_width="200dp"
            android:layout_height="match_parent"
            android:orientation="vertical">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingBottom="8dp"
                android:text="源"
                android:textColor="@color/text_secondary"
                android:textSize="12sp" />

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/srcList"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:scrollbars="none" />
        </LinearLayout>

        <!-- 3. 分组 -->
        <LinearLayout
            android:layout_width="170dp"
            android:layout_height="match_parent"
            android:orientation="vertical">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingBottom="8dp"
                android:text="分组"
                android:textColor="@color/text_secondary"
                android:textSize="12sp" />

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/grpList"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:scrollbars="none" />
        </LinearLayout>

        <!-- 4. 频道列表 -->
        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:orientation="vertical"
            android:paddingHorizontal="12dp">

            <TextView
                android:id="@+id/panelTitle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingBottom="8dp"
                android:textColor="@color/accent"
                android:textSize="14sp" />

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/panelList"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:scrollbars="none" />
        </LinearLayout>
    </LinearLayout>

    '''

s = s[:open_at] + new_panel + s[end_at:]
io.open(p, "w", encoding="utf-8").write(s)
print("activity_player.xml: 频道面板已改成四列")
