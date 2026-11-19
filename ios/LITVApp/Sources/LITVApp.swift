import SwiftUI

@main
struct LITVApp: App {
    @StateObject private var session = Session()

    init() {
        // 台标（tvg-logo）由 AsyncImage 加载，走的是系统 URLCache。
        // 默认磁盘只有 10MB，几百张台标很容易被挤掉 —— 表现是「台标一会有一会没有」。
        // 调到 32MB 内存 / 256MB 磁盘后，台标基本一次加载就能长期命中。
        //
        // 注意：这**不影响播放器**。播放器的「不吃缓存」是在请求头里显式带
        // `Cache-Control: no-cache`（见 ExoEngine / PlayerView.startAVPlayer），
        // 和 URLCache 是两套东西。节目单另有自己的文件缓存（EpgStore，24 小时）。
        URLCache.shared = URLCache(
            memoryCapacity: 32 * 1024 * 1024,
            diskCapacity: 256 * 1024 * 1024,
            diskPath: "litv_http_cache"
        )
    }

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
            //
            // 但反过来必须看 token：**重签安装会让 Keychain 里的旧 token 读不到**
            // （Sideloadly 换签名后访问组变了），而 isBound 存在 UserDefaults 会保留下来，
            // 于是进了主界面却什么都拉不到（「总频道 0」）。
            // 凭据没了就回绑定页重新绑，别让用户对着空列表猜。
            if session.isBound && !session.token.isEmpty {
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
