import SwiftUI

@main
struct LITVApp: App {
    @StateObject private var session = Session()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
                .preferredColorScheme(.dark)
                .onAppear {
                    #if DEBUG
                    // R07 回归：DEBUG 下开局跑一遍服务端响应 fixture，
                    // 解码一旦回归就在控制台立刻看到，不用等真机发现节目单空白。
                    let fails = DecodeFixtures.check()
                    if fails.isEmpty {
                        print("[LITV] R07 fixture 全部通过")
                    } else {
                        for f in fails { print("[LITV] R07 fixture 失败: \(f)") }
                    }
                    #endif
                    session.bootstrap()
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var session: Session

    var body: some View {
        Group {
            // 注意：不能用 token 是否为空来判断"已登录"。
            // 设备注册后服务端**立刻返回 token**，但状态还是 pending——
            // 拿这个 token 调接口一律被拒（2003）。必须等管理员批准。
            if session.isBound {
                // 底部四 tab，与 Android 端保持一致：
                // 播放 / 频道 / 遥控器 / 设置
                RootTabs()
            } else {
                BindView()
            }
        }
        .background(Color(red: 0.043, green: 0.055, blue: 0.067).ignoresSafeArea())
    }
}
