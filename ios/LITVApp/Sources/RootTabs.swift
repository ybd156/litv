import SwiftUI

/// 底部四 tab 根视图，对齐 Android 端的 播放 / 频道 / 遥控器 / 设置。
///
/// 之前 iOS 只有「频道列表 + 齿轮进设置」这一层，
/// 和 Android 的结构差得比较远，用户两边切换会觉得不是同一个应用。
struct RootTabs: View {
    @EnvironmentObject var session: Session

    var body: some View {
        TabView {
            PlayTab()
                .tabItem { Label("播放", systemImage: "play.rectangle") }

            HomeView()
                .tabItem { Label("频道", systemImage: "list.bullet") }

            RemoteTab()
                .tabItem { Label("遥控器", systemImage: "av.remote") }

            SettingsView()
                .tabItem { Label("设置", systemImage: "gearshape") }
        }
        .tint(Color(red: 0.176, green: 0.831, blue: 0.749))
    }
}

/// 「播放」tab：有上次看的台就续上，没有就引导去频道页。
private struct PlayTab: View {
    @EnvironmentObject var session: Session

    var body: some View {
        Group {
            if let ch = session.lastChannel {
                PlayerView(channel: ch)
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "play.rectangle")
                        .font(.system(size: 42))
                        .foregroundColor(.gray)
                    Text("还没有在看的频道")
                        .foregroundColor(.gray)
                    Text("到「频道」页选一个台")
                        .font(.system(size: 12))
                        .foregroundColor(.gray.opacity(0.75))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(red: 0.043, green: 0.055, blue: 0.067))
            }
        }
    }
}

/// 「遥控器」tab。
///
/// 真正的遥控实现（设备列表 + 按键转发）在下一步补，
/// 这里先给出入口，避免四个 tab 里少一个。
private struct RemoteTab: View {
    var body: some View {
        NavigationView {
            VStack(spacing: 10) {
                Image(systemName: "av.remote")
                    .font(.system(size: 42))
                    .foregroundColor(.gray)
                Text("遥控器")
                    .foregroundColor(.gray)
                Text("在同一个 Wi-Fi 下控制其他设备")
                    .font(.system(size: 12))
                    .foregroundColor(.gray.opacity(0.75))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("遥控器")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
    }
}
