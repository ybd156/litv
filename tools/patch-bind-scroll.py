"""电视端绑定页加 ScrollView。

原来根布局是 LinearLayout + gravity=center，键盘一弹（adjustResize 缩小窗口）
内容就溢出，标题被顶出屏幕——就是「点 IP 输入框就乱了」。
包一层 ScrollView（fillViewport=true），内容再多也能滚。
"""
import io

p = "android/app/src/main/res/layout/activity_bind.xml"
s = io.open(p, encoding="utf-8").read()

if "<ScrollView" in s:
    print("已有 ScrollView，跳过")
else:
    old_head = """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="60dp">"""
    new_head = """<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg"
    android:fillViewport="true">

    <!-- 键盘弹出时（adjustResize 缩小窗口）靠这层滚动，避免标题被顶出屏幕 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:orientation="vertical"
        android:padding="60dp">"""
    assert old_head in s, "找不到根布局"
    s = s.replace(old_head, new_head, 1)

    # 结尾补 </ScrollView>
    s = s.rstrip()
    assert s.endswith("</LinearLayout>"), "结尾不是 </LinearLayout>"
    s = s[: -len("</LinearLayout>")] + "</LinearLayout>\n</ScrollView>\n"

    io.open(p, "w", encoding="utf-8").write(s)
    print("activity_bind.xml: 已包 ScrollView")
