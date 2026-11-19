import SwiftUI

/// 首页：分组横向 chip + 频道列表。
struct HomeView: View {
    @EnvironmentObject var session: Session
    @State private var selectedGroup: String? = nil
    @State private var onlyFav = false
    @State private var showSettings = false
    @State private var keyword = ""

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // 搜索：频道一多就必须能搜（和 Android 端保持一致）
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").foregroundColor(.gray).font(.caption)
                    TextField("搜频道名 / 分组", text: $keyword)
                        .textFieldStyle(.plain)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    if !keyword.isEmpty {
                        Button { keyword = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundColor(.gray)
                        }
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(Color.white.opacity(0.06))
                .cornerRadius(9)
                .padding(.horizontal, 14)
                .padding(.top, 8)

                // 解析状态必须可见：解析失败时列表里显示的是服务端旧频道，
                // 点播才会报「本地表未就绪」——原因只能从这里看出来。
                if !session.statusText.isEmpty {
                    Text(session.statusText)
                        .font(.caption2)
                        .foregroundColor(.gray)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                }
                groupBar
                list
            }
            .navigationTitle("LITV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
        }
        .navigationViewStyle(.stack)
        .refreshable { session.bootstrap() }
    }

    private var groupBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip("全部", active: selectedGroup == nil && !onlyFav) {
                    selectedGroup = nil; onlyFav = false
                }
                if !session.favorites.isEmpty {
                    chip("★ 收藏", active: onlyFav) {
                        onlyFav = true; selectedGroup = nil
                    }
                }
                ForEach(session.groups) { g in
                    chip("\(g.name) \(g.count)", active: selectedGroup == g.name) {
                        selectedGroup = g.name; onlyFav = false
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
        }
    }

    private func chip(_ label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13))
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(active
                            ? Color(red: 0.176, green: 0.831, blue: 0.749).opacity(0.25)
                            : Color.white.opacity(0.07))
                .foregroundColor(active
                                 ? Color(red: 0.176, green: 0.831, blue: 0.749)
                                 : .gray)
                .cornerRadius(14)
        }
    }

    private var filtered: [Channel] {
        let base = onlyFav
            ? session.channels.filter { session.favorites.contains($0.id) }
            : session.channels(in: selectedGroup)
        let k = keyword.trimmingCharacters(in: .whitespaces)
        guard !k.isEmpty else { return base }
        return base.filter {
            $0.name.localizedCaseInsensitiveContains(k) ||
                $0.group.localizedCaseInsensitiveContains(k)
        }
    }

    /// 列表为空时的原因。以前只有一句「还没有频道」，
    /// 分不清是「真没数据」「搜索没命中」还是「分组对不上」——
    /// 用户反馈过两次「频道都没有了」，两次原因都不同。
    private var emptyReason: String {
        if session.loading { return "加载中…" }
        if session.channels.isEmpty { return "还没有频道，先去管理台导入直播源" }
        if !keyword.trimmingCharacters(in: .whitespaces).isEmpty { return "搜索没有匹配的频道" }
        if onlyFav { return "收藏里还没有频道" }
        return "当前分组没有频道（分组名可能变了）"
    }

    private var list: some View {
        List {
            if filtered.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text(emptyReason)
                        .foregroundColor(.gray)
                    // 把三个关键值直接摊开：下次再出现白屏，一眼就知道卡在哪
                    Text("总频道 \(session.channels.count) · 分组 \(session.groups.count) · 选中 \(selectedGroup ?? "全部")")
                        .font(.caption2)
                        .foregroundColor(.gray.opacity(0.7))
                    if !keyword.trimmingCharacters(in: .whitespaces).isEmpty {
                        Button("清除搜索") { keyword = "" }
                            .font(.caption)
                    }
                    if onlyFav {
                        Button("看全部频道") { onlyFav = false }
                            .font(.caption)
                    } else if !session.channels.isEmpty {
                        Button("看全部频道") { selectedGroup = nil }
                            .font(.caption)
                    }
                }
                .listRowBackground(Color.clear)
            }
            ForEach(filtered) { ch in
                // 不在这里直接 push PlayerView：TabView 会同时保留
                // 「播放」tab 和这里推入的播放器，导致两个画面同时出声。
                // 改成发一个播放请求，由「播放」tab 独占唯一播放器。
                Button {
                    session.requestPlay(ch)
                } label: {
                    row(ch)
                        // 整行可点：默认只能点图标和文字，右侧大片空白没反应（用户实测）。
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .listRowBackground(Color.white.opacity(0.04))
                .swipeActions(edge: .trailing) {
                    Button {
                        session.toggleFavorite(ch)
                    } label: {
                        Label("收藏", systemImage: "star")
                    }
                    .tint(.orange)
                }
            }
        }
        .listStyle(.plain)
        .overlay {
            if session.loading && session.channels.isEmpty {
                ProgressView()
            }
        }
    }

    private func row(_ ch: Channel) -> some View {
        HStack(spacing: 12) {
            // 台号：和 Android 端一致，方便按数字找台
            if ch.number > 0 {
                Text("\(ch.number)")
                    .font(.system(size: 12, weight: .medium))
                    .monospacedDigit()
                    .foregroundColor(Color(red: 0.176, green: 0.831, blue: 0.749))
                    .frame(minWidth: 26, alignment: .trailing)
            }

            AsyncImage(url: URL(string: ch.logo)) { img in
                img.resizable().scaledToFit()
            } placeholder: {
                Color.clear
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 2) {
                Text(ch.name)
                    .foregroundColor(.white)
                    .font(.system(size: 16))
                if let now = session.nowTitles[ch.id], !now.isEmpty {
                    Text(now)
                        .foregroundColor(.gray)
                        .font(.system(size: 12))
                        .lineLimit(1)
                }
            }
            Spacer()
            if session.favorites.contains(ch.id) {
                Image(systemName: "star.fill").foregroundColor(.orange)
            }
        }
        .padding(.vertical, 4)
    }
}

/// 设置页。
struct SettingsView: View {
    /// 从 Info.plist 读真实版本号（project.yml 里注入）。
    /// 解码方式的显示文案。
    static func decodeLabel(_ mode: String) -> String {
        switch mode {
        case "hard": return "强制硬件解码"
        case "soft": return "强制软件解码"
        default: return "自动"
        }
    }

    /// 播放内核的显示文案。
    ///
    /// VLCKit 没装时必须把话说清楚（选了也用不上），否则用户会以为设置没生效——
    /// 这个仓库里已经因为「改了设置但什么都没变」排查过好几轮。
    static func engineLabel(_ mode: String, vlcAvailable: Bool) -> String {
        switch mode {
        case "vlc":
            return vlcAvailable ? "强制 VLCKit" : "VLCKit 未安装（只能用 AVPlayer）"
        case "avplayer":
            return "强制 AVPlayer"
        default:
            return vlcAvailable ? "自动（优先 VLCKit）" : "自动（AVPlayer）"
        }
    }

    static var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
        return "\(v) (\(b))"
    }

    @EnvironmentObject var session: Session
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            List {
                Section("设备") {
                    // 部署目标已上调到 iOS 16，`LabeledContent` 现在可用；
                    // 这里仍用 HStack —— 与下面几行写法统一，也方便加自定义副文本。
                    HStack {
                        Text("服务器")
                        Spacer()
                        Text(session.serverBase).foregroundColor(.gray).lineLimit(1)
                    }
                    HStack {
                        Text("名称")
                        Spacer()
                        Text(session.deviceName.isEmpty ? "iPhone" : session.deviceName)
                            .foregroundColor(.gray)
                    }
                }
                Section("播放") {
                    // 播放内核：对齐 Android 设置页的「播放内核」。
                    // 自动 = VLCKit 优先、AVPlayer 兜底；强制某一项用于排查
                    // 「同一个台换个内核还能不能播」——没有这个开关时，
                    // iOS 只能靠卸 Pod 才能见到 AVPlayer 路径。
                    Button {
                        let opts = ["auto", "vlc", "avplayer"]
                        let i = opts.firstIndex(of: session.engineMode) ?? 0
                        session.engineMode = opts[(i + 1) % opts.count]
                    } label: {
                        HStack {
                            Text("播放内核").foregroundColor(.primary)
                            Spacer()
                            Text(Self.engineLabel(session.engineMode,
                                                  vlcAvailable: VLCPlayerView.isAvailable))
                                .foregroundColor(.gray)
                        }
                    }
                    // 解码方式：老设备硬解会花屏/卡住，需要能手动切软解。
                    // 用 HStack + 点击循环切换，和「关于」的写法保持一致。
                    Button {
                        switch session.decodeMode {
                        case "auto": session.decodeMode = "hard"
                        case "hard": session.decodeMode = "soft"
                        default: session.decodeMode = "auto"
                        }
                    } label: {
                        HStack {
                            Text("解码方式").foregroundColor(.primary)
                            Spacer()
                            Text(Self.decodeLabel(session.decodeMode))
                                .foregroundColor(.gray)
                        }
                    }
                    // 缓冲 / 直播延迟：以前在 VLCPlayerView 里写死 1500ms，
                    // 弱网或源站抖动时只能干等。档位与 Android 设置页对齐。
                    Button {
                        let opts = [1000, 1500, 2000, 3000, 5000, 8000]
                        let i = opts.firstIndex(of: session.bufferMs) ?? 0
                        session.bufferMs = opts[(i + 1) % opts.count]
                    } label: {
                        HStack {
                            Text("网络缓冲").foregroundColor(.primary)
                            Spacer()
                            Text("\(session.bufferMs / 1000) 秒").foregroundColor(.gray)
                        }
                    }
                    Button {
                        let opts = [0, 2000, 4000, 6000, 8000, 10000]
                        let i = opts.firstIndex(of: session.liveDelayMs) ?? 0
                        session.liveDelayMs = opts[(i + 1) % opts.count]
                    } label: {
                        HStack {
                            Text("直播延迟").foregroundColor(.primary)
                            Spacer()
                            Text(session.liveDelayMs == 0
                                 ? "不额外延迟"
                                 : "\(session.liveDelayMs / 1000) 秒")
                                .foregroundColor(.gray)
                        }
                    }
                }

                Section {
                    Button(role: .destructive) {
                        session.unbind()
                        dismiss()
                    } label: {
                        Text("解绑本机")
                    }
                } footer: {
                    Text("解绑后需要在管理台重新批准")
                }
                Section("关于") {
                    HStack {
                        Text("版本")
                        Spacer()
                        // 以前这里硬编码成 "1.0.0"，不管装的是哪个版本都显示 1.0.0。
                        // 改为读 Info.plist（由 project.yml 的 CFBundleShortVersionString 注入）。
                        Text(SettingsView.appVersion).foregroundColor(.gray)
                    }
                    Text("本 App 不提供任何直播源")
                        .font(.caption).foregroundColor(.gray)
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}
