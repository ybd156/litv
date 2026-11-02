import SwiftUI

/// 底部四 tab 根视图，对齐 Android 端的 播放 / 频道 / 遥控器 / 设置。
///
/// 播放器**只由「播放」tab 持有**。频道页点台不直接 push 播放器，
/// 而是发一个播放请求并切到这个 tab——否则 TabView 会同时保留两个
/// PlayerView，两个画面一起出声。
struct RootTabs: View {
    @EnvironmentObject var session: Session
    @State private var selection = 0

    var body: some View {
        TabView(selection: $selection) {
            PlayTab(isActive: selection == 0)
                .tabItem { Label("播放", systemImage: "play.rectangle") }
                .tag(0)

            HomeView()
                .tabItem { Label("频道", systemImage: "list.bullet") }
                .tag(1)

            RemoteView()
                .tabItem { Label("遥控器", systemImage: "av.remote") }
                .tag(2)

            SettingsView()
                .tabItem { Label("设置", systemImage: "gearshape") }
                .tag(3)
        }
        .tint(Color(red: 0.176, green: 0.831, blue: 0.749))
        .onChange(of: session.playRequest) { req in
            guard req != nil else { return }
            // 先切 tab，再清请求：清空会再触发一次 onChange，被上面的 guard 挡掉
            selection = 0
            session.playRequest = nil
        }
    }
}

/// 「播放」tab：只有在**本 tab 处于选中状态**时才真正创建播放器。
///
/// 这是"两个画面同时出声"的关键修复点：TabView 会把非当前 tab 的视图
/// 保留在树里，如果无条件渲染 PlayerView，切到频道页再选台就会出现
/// 两个播放器同时在播。
private struct PlayTab: View {
    let isActive: Bool
    @EnvironmentObject var session: Session

    var body: some View {
        Group {
            if isActive, let ch = session.lastChannel {
                // .id 强制按频道重建：PlayerView 的 channel 是 let，
                // 复用同一个视图不会更新频道。
                PlayerView(channel: ch).id(ch.id)
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "play.rectangle")
                        .font(.system(size: 42))
                        .foregroundColor(.gray)
                    Text(session.lastChannel == nil ? "还没有在看的频道" : "已暂停")
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
