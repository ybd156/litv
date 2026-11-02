"""让 ChannelAdapter / EpgAdapter / HomeActivity 适配新布局。

ChannelAdapter: 现在传 NowPlaying（含起止时间）而不是纯标题，用来画行内进度条；
                新增台号、选中竖条、进度条三个控件。
EpgAdapter:     布局里去掉了简介走马灯，绑定代码要跟着删。
HomeActivity:   nowTitles 的类型从 String 改成 NowPlaying。
"""
import io
import re

# ---------- ChannelAdapter ----------
p = "android/app/src/main/java/com/litv/tv/ui/ChannelAdapter.kt"
s = io.open(p, encoding="utf-8").read()

s = s.replace(
    "    private val nowMap = HashMap<Long, String>()",
    "    private val nowMap = HashMap<Long, com.litv.tv.data.NowPlaying>()",
)
s = s.replace(
    "fun submit(list: List<Channel>, now: Map<Long, String>, favs: Set<Long> = favorites) {",
    "fun submit(list: List<Channel>, now: Map<Long, com.litv.tv.data.NowPlaying>, favs: Set<Long> = favorites) {",
)

old_bind = """        holder.name.text = ch.name
        val now = nowMap[ch.id]
        holder.now.text = now ?: ""
        holder.now.visibility = if (now.isNullOrBlank()) View.GONE else View.VISIBLE"""
new_bind = """        holder.name.text = ch.name
        holder.tvNo.text = if (ch.number > 0) ch.number.toString() else ""
        // 走马灯必须 isSelected=true 才会滚（TV 上长台名不会截断）
        holder.name.isSelected = true
        holder.now.isSelected = true

        val np = nowMap[ch.id]
        holder.now.text = np?.title.orEmpty()
        holder.now.visibility = if (np?.title.isNullOrBlank()) View.GONE else View.VISIBLE

        // 行内节目进度：mediaplayer 的做法，2dp 细条
        if (np != null && np.end > np.start) {
            val nowSec = System.currentTimeMillis() / 1000
            val pct = (((nowSec - np.start) * 100) / (np.end - np.start)).coerceIn(0, 100).toInt()
            holder.prog.progress = pct
            holder.prog.visibility = View.VISIBLE
        } else {
            holder.prog.visibility = View.GONE
        }"""
assert old_bind in s, "找不到 onBind 的绑定段"
s = s.replace(old_bind, new_bind, 1)

# 焦点时点亮左侧竖条
s = s.replace(
    """            v.animate().scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(120).start()""",
    """            v.animate().scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(120).start()
            holder.selBar.visibility = if (hasFocus) View.VISIBLE else View.GONE""",
    1,
)

# VH 补字段
s = s.replace(
    """    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)""",
    """    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val selBar: View = v.findViewById(R.id.selBar)
        val tvNo: TextView = v.findViewById(R.id.tvNo)
        val prog: android.widget.ProgressBar = v.findViewById(R.id.prog)
        val logo: ImageView = v.findViewById(R.id.logo)""",
    1,
)
io.open(p, "w", encoding="utf-8").write(s)
print("ChannelAdapter: 已适配")

# ---------- EpgAdapter：去掉 desc ----------
p = "android/app/src/main/java/com/litv/tv/ui/EpgAdapter.kt"
s = io.open(p, encoding="utf-8").read()
s = re.sub(
    r"\n        // 简介走马灯.*?\n        \}\n",
    "\n",
    s,
    flags=re.S,
)
s = s.replace("        val desc: TextView = v.findViewById(R.id.desc)\n", "")
io.open(p, "w", encoding="utf-8").write(s)
print("EpgAdapter: 已去掉简介绑定")

# ---------- HomeActivity：nowTitles 类型 ----------
p = "android/app/src/main/java/com/litv/tv/ui/HomeActivity.kt"
s = io.open(p, encoding="utf-8").read()
s = s.replace(
    "    private val nowTitles = mutableMapOf<Long, String>()",
    "    private val nowTitles = mutableMapOf<Long, com.litv.tv.data.NowPlaying>()",
)
s = s.replace(
    "nowTitles.putAll(b.now.associate { it.channelId to it.title })",
    "nowTitles.putAll(b.now.associate { it.channelId to it })",
)
io.open(p, "w", encoding="utf-8").write(s)
print("HomeActivity: 已适配")
