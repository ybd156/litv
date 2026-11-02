"""切回 IP 模式时，域名不该被拆进四个框。

现象：填了域名 iptv.683868.xyz，切到「用 IP 分框输入」，四个框里出现
iptv / 683868 / xyz / 空 —— 因为无脑按 "." 拆了。

规则：只有真的像 IPv4（4 段且每段 0-255）才填进去，否则清空，
让用户自己填，别把域名残片留在框里。
"""
import io

# ---------- Android ----------
p = "android/app/src/main/java/com/litv/tv/ui/BindActivity.kt"
s = io.open(p, encoding="utf-8").read()

old = """    private fun setDomainMode(on: Boolean) {
        usingDomain = on
        ipRow.visibility = if (on) View.GONE else View.VISIBLE
        domainInput.visibility = if (on) View.VISIBLE else View.GONE
        btnToggleHost.text = if (on) "用 IP 分框输入" else "用域名访问"
        if (on) domainInput.requestFocus() else ipBoxes.first().requestFocus()
    }"""

new = """    private fun setDomainMode(on: Boolean) {
        usingDomain = on
        ipRow.visibility = if (on) View.GONE else View.VISIBLE
        domainInput.visibility = if (on) View.VISIBLE else View.GONE
        btnToggleHost.text = if (on) "用 IP 分框输入" else "用域名访问"

        if (!on) {
            // 切回 IP 模式时，只有域名真的是 IPv4 才拆进框里。
            // 否则像 iptv.683868.xyz 会被拆成 iptv/683868/xyz，全是垃圾。
            val raw = domainInput.text.toString().trim()
            val host = raw.removePrefix("https://").removePrefix("http://").substringBefore(":").substringBefore("/")
            val octets = host.split(".")
            val isIPv4 = octets.size == 4 && octets.all { (it.toIntOrNull() ?: -1) in 0..255 }
            if (isIPv4) {
                ipBoxes.forEachIndexed { i, box -> box.setText(octets[i]) }
            } else if (host.isNotBlank()) {
                ipBoxes.forEach { it.setText("") }
            }
        } else {
            // 切到域名模式：如果四个框里有合法 IP，拼出来带过去
            val octets = ipBoxes.map { it.text.toString().trim() }
            val isIPv4 = octets.all { it.isNotEmpty() } && octets.all { (it.toIntOrNull() ?: -1) in 0..255 }
            if (isIPv4 && domainInput.text.isBlank()) {
                domainInput.setText(octets.joinToString("."))
            }
        }

        if (on) domainInput.requestFocus() else ipBoxes.first().requestFocus()
    }"""

assert old in s, "找不到 setDomainMode"
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
print("Android BindActivity: 已修")

# ---------- iOS ----------
p = "ios/LITVApp/Sources/BindView.swift"
s = io.open(p, encoding="utf-8").read()

old_ios = """        if useDomain {
            // 域名 -> 分框
            let host = parseHostPort(domain).host
            fillIPs(host)
            useDomain = false
        } else {"""
new_ios = """        if useDomain {
            // 域名 -> 分框：只有真的是 IPv4 才拆，否则清空。
            // 之前无脑按 "." 拆，iptv.683868.xyz 会变成 iptv/683868/xyz。
            let host = parseHostPort(domain).host
            let parts = host.split(separator: ".")
            let isIPv4 = parts.count == 4 && parts.allSatisfy { (0...255).contains(Int($0) ?? -1) }
            if isIPv4 {
                fillIPs(host)
            } else if !host.isEmpty {
                for i in 0..<4 { ip[i] = "" }
            }
            useDomain = false
        } else {"""
if old_ios in s:
    s = s.replace(old_ios, new_ios, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print("iOS BindView: 已修")
else:
    print("iOS: 未匹配（可能结构不同）")
