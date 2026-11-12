import SwiftUI

/// 遥控器：列出同一服务端下可被遥控的设备，选中后用按键面板控制。
///
/// 对应服务端 GET /tv/remote/targets 与 POST /tv/remote/{id}。
/// 之前 iOS 完全没有这个能力，和 Android 差了一个功能块。
struct RemoteView: View {
    @EnvironmentObject var session: Session

    @State private var targets: [RemoteTarget] = []
    @State private var selected: RemoteTarget?
    @State private var loading = false
    @State private var tip = ""
    @State private var errorText: String?

    var body: some View {
        NavigationView {
            Group {
                if let t = selected {
                    pad(for: t)
                } else {
                    deviceList
                }
            }
            .navigationTitle(selected == nil ? "遥控器" : (selected?.name ?? "遥控器"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // 注意：不能在 toolbar 里写 `if`——那会用到 buildIf，
                // 只有 iOS 16+ 才有，而本项目最低支持 iOS 15（CI 会直接编译失败）。
                // 所以按钮始终存在，靠 disabled + opacity 控制是否可见可点。
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("设备") { selected = nil }
                        .disabled(selected == nil)
                        .opacity(selected == nil ? 0 : 1)
                }
            }
        }
        .navigationViewStyle(.stack)
        .onAppear { Task { await load() } }
    }

    private var deviceList: some View {
        List {
            if let e = errorText {
                Text(e)
                    .foregroundColor(.orange)
                    .font(.system(size: 12))
                    .listRowBackground(Color.clear)
            }
            if targets.isEmpty {
                Text(loading ? "加载中…" : "没有可遥控的设备（需要先在别的设备上绑定同一个服务端）")
                    .foregroundColor(.gray)
                    .font(.system(size: 13))
                    .listRowBackground(Color.clear)
            }
            ForEach(targets) { t in
                Button {
                    if t.online {
                        selected = t
                        tip = "点按发送按键"
                    } else {
                        tip = "\(t.name) 不在线"
                    }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: t.platform.contains("tv") ? "tv" : "iphone")
                            .foregroundColor(.gray)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.name).foregroundColor(.white)
                            Text(t.online ? "在线" : "离线")
                                .font(.system(size: 11))
                                .foregroundColor(t.online ? .green : .gray)
                        }
                        Spacer()
                        if t.online {
                            Image(systemName: "chevron.right").foregroundColor(.gray)
                        }
                    }
                }
                .listRowBackground(Color.white.opacity(0.04))
            }
        }
        .listStyle(.plain)
        .refreshable { await load() }
    }

    private func pad(for t: RemoteTarget) -> some View {
        VStack(spacing: 18) {
            Text(tip.isEmpty ? "点按发送按键" : tip)
                .font(.system(size: 12))
                .foregroundColor(.gray)

            VStack(spacing: 8) {
                keyButton(t, "up", "chevron.up")
                HStack(spacing: 8) {
                    keyButton(t, "left", "chevron.left")
                    keyButton(t, "ok", "checkmark")
                    keyButton(t, "right", "chevron.right")
                }
                keyButton(t, "down", "chevron.down")
            }

            HStack(spacing: 12) {
                keyButton(t, "back", "arrow.uturn.backward")
                keyButton(t, "menu", "line.3.horizontal")
                keyButton(t, "home", "house")
            }

            HStack(spacing: 12) {
                keyButton(t, "volume_down", "speaker.minus")
                keyButton(t, "volume_up", "speaker.plus")
            }

            Spacer()
        }
        .padding(.top, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(red: 0.043, green: 0.055, blue: 0.067))
    }

    private func keyButton(_ t: RemoteTarget, _ key: String, _ icon: String) -> some View {
        Button {
            Task { await send(t, key) }
        } label: {
            Image(systemName: icon)
                .font(.system(size: 20))
                .frame(width: 66, height: 54)
                .background(Color.white.opacity(0.08))
                .cornerRadius(12)
                .foregroundColor(.white)
        }
    }

    private func send(_ t: RemoteTarget, _ key: String) async {
        do {
            try await session.api.remoteKey(deviceId: t.id, key: key)
            tip = "已发送：\(key)"
        } catch {
            tip = "发送失败：\(error.localizedDescription)"
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            let list = try await session.api.remoteTargets()
            targets = list
            errorText = nil
            // 选中的设备可能已经离线/解绑，及时退回列表
            if let s = selected {
                if let fresh = list.first(where: { $0.id == s.id }), fresh.online {
                    selected = fresh
                } else {
                    selected = nil
                    tip = "\(s.name) 已离线"
                }
            }
        } catch {
            errorText = error.localizedDescription
        }
    }
}
