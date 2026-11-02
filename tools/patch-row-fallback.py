"""频道行在「没有台标 / 没有台号 / 没有节目」时也要好看。

三个降级：
  1. 没台标 -> 显示频道名首字（带背景块），不再是空白
  2. 没台号 -> 用列表序号
  3. 没节目 -> 显示分组名，不留空行
"""
import io

p = "android/app/src/main/java/com/litv/tv/ui/ChannelAdapter.kt"
s = io.open(p, encoding="utf-8").read()

# 1) VH 补 logoText
s = s.replace(
    "        val logo: ImageView = v.findViewById(R.id.logo)",
    "        val logo: ImageView = v.findViewById(R.id.logo)\n"
    "        val logoText: TextView = v.findViewById(R.id.logoText)",
    1,
)

# 2) 绑定：台号兜底序号 + 台标兜底首字 + 节目兜底分组
old = """        holder.name.text = ch.name
        holder.tvNo.text = if (ch.number > 0) ch.number.toString() else \"\""""
new = """        holder.name.text = ch.name
        // 没台号就用序号，别留一格空白
        holder.tvNo.text = if (ch.number > 0) ch.number.toString() else (position + 1).toString()

        // 台标：有图显示图；没图显示首字方块
        if (ch.logo.isBlank()) {
            holder.logo.setImageDrawable(null)
            holder.logoText.visibility = View.VISIBLE
            holder.logoText.text = ch.name.take(2)
        } else {
            holder.logoText.visibility = View.GONE
            loadLogo(holder.logo, ch.logo)
        }"""
assert old in s, "找不到台号绑定段"
s = s.replace(old, new, 1)

# 3) 节目为空时显示分组名
old2 = """        val np = nowMap[ch.id]
        holder.now.text = np?.title.orEmpty()
        holder.now.visibility = if (np?.title.isNullOrBlank()) View.GONE else View.VISIBLE"""
new2 = """        val np = nowMap[ch.id]
        val nowText = np?.title.orEmpty().ifBlank { ch.group }
        holder.now.text = nowText
        holder.now.visibility = if (nowText.isBlank()) View.GONE else View.VISIBLE"""
assert old2 in s, "找不到节目绑定段"
s = s.replace(old2, new2, 1)

io.open(p, "w", encoding="utf-8").write(s)
print("ChannelAdapter: 已加三处降级")
