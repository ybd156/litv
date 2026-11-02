"""手机端频道行接线：台号 + 台标首字兜底。"""
import io

p = "android/app/src/main/java/com/litv/tv/ui/PhoneChannelAdapter.kt"
s = io.open(p, encoding="utf-8").read()

# VH 补字段
if "val tvNo" not in s:
    s = s.replace(
        "        val logo: ImageView = v.findViewById(R.id.logo)",
        "        val tvNo: TextView = v.findViewById(R.id.tvNo)\n"
        "        val logoText: TextView = v.findViewById(R.id.logoText)\n"
        "        val logo: ImageView = v.findViewById(R.id.logo)",
        1,
    )

# 绑定
if "holder.tvNo.text" not in s:
    s = s.replace(
        "        loadLogo(holder.logo, ch.logo)",
        """        holder.tvNo.text = if (ch.number > 0) ch.number.toString() else (position + 1).toString()
        // 台标：先摆首字方块兜底，图真加载出来了再盖掉（外链台标失败很常见）
        holder.logo.setImageDrawable(null)
        holder.logoText.text = ch.name.take(2)
        holder.logoText.visibility = View.VISIBLE
        if (ch.logo.isNotBlank()) loadLogo(holder.logo, ch.logo)""",
        1,
    )

io.open(p, "w", encoding="utf-8").write(s)
print("PhoneChannelAdapter: 台号 + 台标兜底已接线")

# loadLogo 成功时藏掉兜底
if "logoText" in s and "findViewById<TextView>(R.id.logoText)" not in s:
    s = io.open(p, encoding="utf-8").read()
    old = "                    if (target.tag == url || target.tag == null) target.setImageBitmap(bmp)"
    new = """                    if (target.tag == url || target.tag == null) {
                        target.setImageBitmap(bmp)
                        (target.parent as? android.view.ViewGroup)
                            ?.findViewById<TextView>(R.id.logoText)
                            ?.visibility = View.GONE
                    }"""
    if old in s:
        s = s.replace(old, new, 1)
        io.open(p, "w", encoding="utf-8").write(s)
        print("PhoneChannelAdapter: 加载成功隐藏兜底")
    else:
        print("PhoneChannelAdapter: 未找到加载成功分支（可能结构不同）")
