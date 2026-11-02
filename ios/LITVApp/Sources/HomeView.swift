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

    private var list: some View {
        List {
            if filtered.isEmpty {
                Text(session.loading ? "加载中…" : "还没有频道，先去管理台导入直播源")
                    .foregroundColor(.gray)
                    .listRowBackground(Color.clear)
            }
            ForEach(filtered) { ch in
                NavigationLink {
                    PlayerView(channel: ch)
                } label: {
                    row(ch)
                }
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
    @EnvironmentObject var session: Session
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            List {
                Section("设备") {
                    // 注意：LabeledContent 是 iOS 16+，这里用 HStack 保持 iOS 15 兼容
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
                        Text("1.0.0").foregroundColor(.gray)
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
