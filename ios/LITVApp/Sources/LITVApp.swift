import SwiftUI

@main
struct LITVApp: App {
    @StateObject private var session = Session()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
                .preferredColorScheme(.dark)
                .onAppear { session.bootstrap() }
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
                HomeView()
            } else {
                BindView()
            }
        }
        .background(Color(red: 0.043, green: 0.055, blue: 0.067).ignoresSafeArea())
    }
}
