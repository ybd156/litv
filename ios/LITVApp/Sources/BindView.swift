import SwiftUI

/// 绑定页：iOS 上唯一需要手输的页面。
///
/// 地址输入有两种模式：
///   分框  四段 IP + 端口，纯数字键盘，输满自动跳下一格
///   域名  tv.example.com[:9527]，用 DDNS 或反向代理的走这个
///
/// 为什么两种都留：分框对遥控器/小屏更友好，但只收数字；
/// 用域名访问的人必须能填字母。
struct BindView: View {
    @EnvironmentObject var session: Session

    @State private var code = ""
    @State private var busy = false
    @State private var pending = false

    // 地址：分框模式
    @State private var ip = ["", "", "", ""]
    @State private var port = "9527"

    // 地址：域名模式
    @State private var domain = ""
    @State private var useDomain = false

    @FocusState private var focusedField: Int?

    private static let defaultPort = "9527"

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Text("LITV")
                .font(.system(size: 40, weight: .bold))
                .foregroundColor(.white)
            Text("直播多终端 · 私有部署")
                .font(.subheadline)
                .foregroundColor(.gray)

            VStack(spacing: 10) {
                if useDomain {
                    domainField
                } else {
                    ipFields
                }

                Button {
                    toggleMode()
                } label: {
                    Text(useDomain ? "用 IP 分框输入" : "用域名访问")
                        .font(.footnote)
                        .foregroundColor(Color(red: 0.176, green: 0.831, blue: 0.749))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                TextField("绑定码（可留空，去管理台批准）", text: $code)
                    .keyboardType(.numberPad)
                    .padding()
                    .background(Color.white.opacity(0.08))
                    .cornerRadius(10)
            }
            .padding(.horizontal, 28)

            Button(action: bind) {
                HStack {
                    if busy { ProgressView().padding(.trailing, 6) }
                    Text(busy ? "处理中…" : (pending ? "等待批准" : "绑定"))
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(Color(red: 0.176, green: 0.831, blue: 0.749))
                .foregroundColor(.black)
                .cornerRadius(10)
            }
            .disabled(busy)
            .padding(.horizontal, 28)

            if let err = session.errorText {
                Text(err).foregroundColor(.red).font(.footnote)
                    .multilineTextAlignment(.center).padding(.horizontal, 28)
            }
            if !session.statusText.isEmpty {
                Text(session.statusText).foregroundColor(.gray).font(.footnote)
                    .multilineTextAlignment(.center).padding(.horizontal, 28)
            }

            Spacer()
            Text("本 App 不提供任何直播源，请自行导入并确保来源合法")
                .font(.caption2).foregroundColor(.gray.opacity(0.7))
                .multilineTextAlignment(.center).padding(.horizontal, 40).padding(.bottom, 20)
        }
        .onAppear { fill(from: session.serverBase) }
    }

    // ---------------------------------------------------------------- 地址输入

    private var ipFields: some View {
        HStack(spacing: 4) {
            ForEach(0..<4, id: \.self) { i in
                TextField("", text: $ip[i])
                    .keyboardType(.numberPad)
                    .multilineTextAlignment(.center)
                    .focused($focusedField, equals: i)
                    .padding(.vertical, 14)
                    .background(Color.white.opacity(0.08))
                    .cornerRadius(10)
                    .frame(maxWidth: .infinity)
                    .onChange(of: ip[i]) { val in
                        // 只留数字，最多 3 位；输满自动跳到下一格
                        let digits = String(val.filter { $0.isNumber }.prefix(3))
                        if digits != val { ip[i] = digits }
                        if digits.count == 3, i < 3 { focusedField = i + 1 }
                    }
                if i < 3 {
                    Text(".").foregroundColor(.gray)
                }
            }
            Text(":").foregroundColor(.gray)
            TextField("", text: $port)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .focused($focusedField, equals: 4)
                .padding(.vertical, 14)
                .background(Color.white.opacity(0.08))
                .cornerRadius(10)
                .frame(width: 66)
                .onChange(of: port) { val in
                    let digits = String(val.filter { $0.isNumber }.prefix(5))
                    if digits != val { port = digits }
                }
        }
    }

    private var domainField: some View {
        TextField("tv.example.com 或 tv.example.com:9527", text: $domain)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.URL)
            .padding()
            .background(Color.white.opacity(0.08))
            .cornerRadius(10)
    }

    private func toggleMode() {
        // 切模式时把已填的内容带过去，别让人重输
        if useDomain {
            // 域名 -> 分框
            let host = parseHostPort(domain).host
            fillIPs(host)
            useDomain = false
        } else {
            let h = ip.joined(separator: ".")
            if !h.replacingOccurrences(of: ".", with: "").isEmpty {
                domain = "\(h):\(port.isEmpty ? Self.defaultPort : port)"
            }
            useDomain = true
        }
    }

    private func fill(from base: String) {
        guard !base.isEmpty else {
            port = Self.defaultPort
            return
        }
        let hp = parseHostPort(base)
        let octets = hp.host.split(separator: ".")
        let isIPv4 = octets.count == 4 && octets.allSatisfy { (Int($0) ?? -1) in 0...255 }
        if isIPv4 {
            for (i, o) in octets.enumerated() { ip[i] = String(o) }
            port = hp.port.isEmpty ? Self.defaultPort : hp.port
            useDomain = false
        } else {
            domain = hp.port.isEmpty ? hp.host : "\(hp.host):\(hp.port)"
            useDomain = true
        }
    }

    private func fillIPs(_ host: String) {
        let parts = host.split(separator: ".").map(String.init)
        for i in 0..<4 { ip[i] = i < parts.count ? parts[i] : "" }
    }

    /// 从 "http://a.b.c.d:9527" / "a.b.c.d:9527" / "域名" 里拆出 host 和 port
    private func parseHostPort(_ raw: String) -> (host: String, port: String) {
        var s = raw.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("http://") { s.removeFirst(7) }
        if s.hasPrefix("https://") { s.removeFirst(8) }
        if let slash = s.firstIndex(of: "/") { s = String(s[..<slash]) }
        let parts = s.split(separator: ":", maxSplits: 1).map(String.init)
        if parts.count == 2 { return (parts[0], parts[1]) }
        return (s, "")
    }

    /// 拼出完整地址；不合法返回 nil
    private func composeAddr() -> String? {
        if useDomain {
            let raw = domain.trimmingCharacters(in: .whitespaces)
            guard !raw.isEmpty else { return nil }
            let hp = parseHostPort(raw)
            guard !hp.host.isEmpty else { return nil }
            let p = hp.port.isEmpty ? Self.defaultPort : hp.port
            guard let n = Int(p), (1...65535).contains(n) else { return nil }
            return "http://\(hp.host):\(p)"
        }
        guard ip.allSatisfy({ !$0.isEmpty }), ip.allSatisfy({ (Int($0) ?? -1) in 0...255 }) else {
            return nil
        }
        let p = port.isEmpty ? Self.defaultPort : port
        guard let n = Int(p), (1...65535).contains(n) else { return nil }
        return "http://\(ip.joined(separator: ".")):\(p)"
    }

    // ---------------------------------------------------------------- 绑定

    private func bind() {
        guard let addr = composeAddr() else {
            session.errorText = useDomain
                ? "请填服务器域名（可带端口）"
                : "请把四段 IP 和端口填完整（每段 0-255）"
            return
        }
        busy = true
        session.errorText = nil
        Task {
            guard await session.probe(addr) else { busy = false; return }
            let bound = await session.register(code: code.isEmpty ? nil : code)
            busy = false
            if !bound {
                pending = true
                await session.waitForApproval()
                pending = false
            }
        }
    }
}
