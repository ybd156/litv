import AVKit
import SwiftUI
import UIKit

// `techText` 要读 `VLCMediaPlayer.videoSize` 拿分辨率，所以本文件也需要这个模块。
// 它只在装了 Pod 时才存在，故用条件 import（与 VLCPlayerView.swift 一致）。
// 2026-11-18 教训：光写 `#if canImport(MobileVLCKit)` 判断是不够的 —— CI 里 Pod 已装、
// canImport 为真，守卫内的 `VLCMediaPlayer` 就会被编译；本文件没 import 就直接报
// `cannot find type 'VLCMediaPlayer' in scope`（iOS Build Check #88 红）。
#if canImport(MobileVLCKit)
import MobileVLCKit
#endif

/// 播放页。
///
/// 双内核策略：
///   - **VLCKit 优先**：底层 FFmpeg，AC3/E-AC3/DTS/裸 TS/AVS3(部分) 全都能解，
///     正好补上 AVPlayer 的短板（Apple 不授权第三方解 Dolby）
///   - **AVPlayer 兜底**：VLCKit 没装上时退回系统播放器，保证 App 永远能播 HLS
///
/// 服务端配合：iOS 请求播放地址时带 `fmt=hls`，裸 TS 源会被服务端转封装成 HLS。
struct PlayerView: View {
    @EnvironmentObject var session: Session
    let channel: Channel

    @State private var player: AVPlayer?
    @State private var playURL: URL?
    /// 源配置里指定的 UA（空 = 用默认）
    @State private var playUA = ""
    /// R09：这条线路的完整请求头（服务端已清洗）。
    /// VLC 映射 UA/Referer，AVPlayer 整份塞进 AVURLAssetHTTPHeaderFieldsKey。
    @State private var playHeaders: [String: String] = [:]
    @State private var usingVLC = false
    @State private var errorText: String?
    @State private var loading = true
    @State private var programs: [EpgProgram] = []
    @State private var showEpg = false
    /// 节目单当前看的是第几天：0=今天，-1=昨天，+1=明天
    @State private var epgDay = 0
    @State private var epgTip: String?
    /// 手势提示（亮度/音量百分比）
    @State private var gestureTip: String?
    // 投屏
    @State private var showCast = false
    @State private var renderers: [DlnaRenderer] = []
    @State private var castLoading = false
    @State private var castTip: String?
    @State private var dragStartBrightness: CGFloat?
    @State private var dragStartVolume: Float?
    @State private var streamId: Int64 = 0
    @State private var streams: [TvStream] = []
    /// VLC 失败降级正在进行。VLCKit 一次失败可能连发两个状态回调
    /// （error + ended），不加锁会并发起两次降级请求、开两个 AVPlayer。
    @State private var fallbackInFlight = false
    /// VLC 状态回调里只置位，真正取地址的 async 工作交给下面的 onChange 做。
    /// 回调本身不是 async 上下文，直接在里面 await 网络请求编译不过。
    @State private var fallbackRequested = false

    // 控制条状态
    @State private var paused = false
    /// 转圈超时兜底任务（见 start() 里的说明）
    @State private var loadingWatchdog: Task<Void, Never>?

    /// AVPlayer 失败观察（item.status KVO + 播放到尾失败通知）。
    ///
    /// 没有它的时候：HLS 403 / 断网 → AVPlayer 什么都不说，界面黑屏、转圈还已经收起来了，
    /// 而 errorText 仍是 nil → 连「重试」按钮都不会出现。用户只能杀进程。
    @State private var avWatcher: AVPlayerWatcher?

    /// 本次起播有没有收到过播放器**任何**状态回调。
    ///
    /// 只用来区分"彻底没反应"（VLCKit 连 .error 都不发）和"还在慢慢起播"
    /// （收到过 .buffering）。后者报错就是误伤。
    @State private var sawPlaybackState = false

    /// 控件浮层是否可见。和 Android 手机端一致：
    /// 点画面呼出、4 秒自动收起，不常驻遮挡画面。
    @State private var controlsVisible = true

    /// 手机横过来时自动全屏；也可以点控制条上的「全屏」按钮强制全屏。
    @Environment(\.verticalSizeClass) private var vSize
    @Environment(\.scenePhase) private var scenePhase

    /// 是不是因为退到后台才暂停的（回来要接着播）。
    @State private var pausedByBackground = false

    /// QoE：本次起播的起点 / 已经上报过 start 没有。
    @AppStorage("litv.aspect") private var aspectMode = "auto"

    /// 播放页当前分组（nil = 全部）。与 Android 手机端的 chip 一致：
    /// 切台时只在这个分组的频道里循环。
    @State private var playGroup: String? = nil

    /// 时移（回看）地址。非空时 start() 直接播它，不再请求 /tv/play。
    @State private var timeshiftURL: URL?

    @State private var playStartedAt: Date?
    @State private var startReported = false
    @State private var forceFullscreen = false
    @State private var controlsHideTask: Task<Void, Never>?
    @State private var muted = false
    @StateObject private var vlc = VLCControl()
    /// 换台用：切了台就换这个，nil 表示还是进来时那个
    @State private var switched: Channel?
    /// direct / relay，用于 OSD 显示
    @State private var playMode = ""
    /// 播放封装：ts / hls / remux。取服务端 PlayInfo.kind。
    ///
    /// **这是 QoE 上报里 `path` 字段的唯一合法来源。**
    /// 曾经这里把 `playMode`（direct/relay/timeshift）当 path 发上去，
    /// 于是服务端的 `by_path` 分组全被污染，按封装看起播 P90 变得不可比，
    /// 而且不报错——path 是自由文本，服务端只做 32 字节截断。
    /// 与 `playMode` 是**正交两维**，不能互相顶替。
    /// 客户端解析模式（address 来自本地源表）拿不到服务端的 kind，留空
    /// —— 空值不会下发，比猜一个 "ts" 诚实。
    @State private var playKind = ""

    /// 当前实际在播的频道
    private var active: Channel { switched ?? channel }

    /// 是否处于全屏：手机横过来（verticalSizeClass == .compact）自动全屏，
    /// 或者用户点了「全屏」按钮。
    private var isFullscreen: Bool { vSize == .compact || forceFullscreen }

    /// 本次起播是否走 VLCKit。
    ///
    /// 设置里的「播放内核」只决定**首选**，两条边界必须守住：
    ///   - VLCKit 没装时，auto / vlc 都只能走 AVPlayer（`isAvailable` 为假）；
    ///   - 强制 avplayer 时**绝不**建 VLC 播放器，否则这个开关等于没做
    ///     （排查「VLC 有声音没画面」时就失去唯一确定的对照组）。
    /// 强制 vlc 失败后仍允许回退 AVPlayer——与 Android 的 `EngineChain.chainFor`
    /// 一致：强制内核打头，但不砍掉兜底。
    private var preferVLC: Bool {
        if session.engineMode == "avplayer" { return false }
        // 开了「直播列表强制刷新」就必须走 AVPlayer：VLCKit 只有 UA/Referer，
        // 带不了 Cache-Control，会一直被 CDN 的过期 m3u8 喂住（分片 0 字节）。
        // 裸 TS 是例外——AVPlayer 解不了，只能交给 VLC，那种源也就吃不到这个开关。
        if session.hlsNoCache && playKind != "ts" { return false }
        return VLCPlayerView.isAvailable
    }

    var body: some View {
        VStack(spacing: 0) {
            // 竖屏布局：频道名在上，16:9 画面在中间，节目信息在下。
            if !isFullscreen {
                osdHeader
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 10)
            }

            // 分组 chip 只在「频道」页出现，播放页不重复（用户圈出来过）
            videoArea

            if !isFullscreen {
                osdDetails
                    .padding(.horizontal, 16)
                    .padding(.top, 10)
                // 节目单常驻在视频下方（与 Android 手机端一致：不再是弹层）
                liveEpgPanel
            }
        }
        .background(Color.black.ignoresSafeArea())
        // 全屏时收起系统栏：状态栏 + 底部标签栏（安卓那边是 setSystemBarsHidden）。
        // 部署目标已上调到 iOS 16，`.toolbar(.hidden, for: .tabBar)` 可用；
        // iOS 15 时代没有对应 API，只能整体改 fullScreenCover 呈现。
        .statusBar(hidden: isFullscreen)
        .toolbar(isFullscreen ? .hidden : .visible, for: .tabBar)
        .navigationTitle(active.name)
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: switched?.id) { _ in
            // 换台：重新起播
            Task { await start() }
        }
        .onChange(of: fallbackRequested) { want in
            // VLC 判定失败后由这里接手降级（回调里不能直接 await）
            guard want else { return }
            Task { await fallbackToAVPlayer() }
        }
        .onChange(of: session.epgVersion) { _ in
            // EPG 后台下完了：立刻重算当前台当天的节目单。
            // 不补这一下，表现就是「首次打开没有节目单，换台才有」。
            Task { await loadEpg(day: epgDay) }
        }
        .onChange(of: session.engineMode) { _ in
            // 设置里换了播放内核就重起当前台。
            // Android 靠 `onResume` 比对 `appliedEngine` 触发 `restartCurrent()`,
            // iOS 没有对应的回前台时机（设置是 sheet/页内切换），直接盯这个值更可靠。
            // 起播进行中不打断：那次 start() 已经按旧内核在跑了，让它自己走完。
            guard !loading else { return }
            Task { await start() }
        }
        // ★ 原来的四个入口（比例 / 节目单 / 时移 / 换线路）挂在这里的 `.toolbar` 上，
        //   但 PlayerView 由 PlayTab 直接返回、**不在 NavigationView 里**，
        //   没有导航栏承载 → 四个 `.toolbar` 项全都不呈现，用户根本点不到
        //   （2026-11-18 审计发现：连画面比例都改不了）。
        //   现在改为放进控制条的「更多」菜单（见 controls），不依赖导航容器，
        //   竖屏与全屏都能点到；`.navigationTitle` 留着——将来若给 PlayTab 包上
        //   NavigationView，它会自然生效，不冲突。
        .sheet(isPresented: $showEpg) { epgSheet }
        .sheet(isPresented: $showCast) { castSheet }
        .onAppear {
            showControls()
            Task { await start() }
        }
        .onChange(of: loading) { v in
            // QoE：首帧出来（loading 收起且没有错误）时上报起播耗时
            guard v == false, !startReported, errorText == nil else { return }
            startReported = true
            let ms = playStartedAt.map { Int(Date().timeIntervalSince($0) * 1000) } ?? 0
            let ch = active.id
            let p = playKind, e = usingVLC ? "vlc" : "avplayer"
            Task { await session.api.reportStats(
                channelId: ch, event: "start", startupMs: ms, path: p, engine: e) }
        }
        .onChange(of: scenePhase) { phase in
            // App 退到后台时 onDisappear **不会**触发，音频会一直响。
            // 后台一律停播；回到前台重建播放器再播（和 Android 端一致：
            // 唤醒后解码器常常出不了帧，续播容易变成黑屏）。
            if phase == .active {
                if pausedByBackground {
                    pausedByBackground = false
                    Task { await start() }
                }
            } else if player != nil {
                pausedByBackground = true
                player?.pause()
            }
        }
        .onDisappear {
            // QoE：离开播放页补一条 stop（带观看时长）
            if startReported, let t0 = playStartedAt {
                let ms = Int(Date().timeIntervalSince(t0) * 1000)
                let ch = active.id
                let p = playKind, e = usingVLC ? "vlc" : "avplayer"
                Task { await session.api.reportStats(
                    channelId: ch, event: "stop", watchMs: ms, path: p, engine: e) }
            }
            player?.pause()
            player = nil
            // KVO / 通知必须成对摘掉，否则 item 释放后再回调就是崩。
            avWatcher?.stop()
            avWatcher = nil
            loadingWatchdog?.cancel()
            loadingWatchdog = nil
            controlsHideTask?.cancel()
            controlsHideTask = nil
        }
    }

    // ---------------------------------------------------------------- 布局

    @ViewBuilder
    private var videoArea: some View {
        let content = ZStack {
            Color.black

            videoContent

            if loading {
                ProgressView().tint(.white)
            }

            if let tip = gestureTip {
                Text(tip)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.black.opacity(0.6))
                    .cornerRadius(10)
            }

            if let err = errorText {
                errorOverlay(err)
            }

            // 点画面呼出/收起浮层。出错时不铺这层，否则会盖住「重试」按钮。
            if errorText == nil {
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture { toggleControls() }
                    // 上下滑动：左半边调亮度，右半边调音量。
                    // 用 minimumDistance 区分点击和滑动，两者不冲突。
                    .gesture(
                        DragGesture(minimumDistance: 25)
                            .onChanged { v in
                                // 横向滑动留给切台，不要同时去调亮度/音量
                                guard abs(v.translation.height) >= abs(v.translation.width) else { return }
                                handleVerticalDrag(v)
                            }
                            .onEnded { v in
                                // 左右滑切台（与 Android 手机端一致：OSD 不再放上一台/下一台）
                                if abs(v.translation.width) > 60,
                                   abs(v.translation.width) > abs(v.translation.height) {
                                    switchBy(v.translation.width < 0 ? +1 : -1)
                                }
                                dragStartBrightness = nil
                                dragStartVolume = nil
                                let tip = gestureTip
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                                    if gestureTip == tip { gestureTip = nil }
                                }
                            }
                    )

                if controlsVisible {
                    VStack {
                        // 竖屏时信息卡已经在画面下方常显，这里不再重复
                        if isFullscreen {
                            osd
                        }
                        Spacer()
                        controls
                    }
                    .transition(.opacity)
                }
            }
        }

        if isFullscreen {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .ignoresSafeArea()
        } else {
            // 竖屏固定 16:9，画面在顶部，下面留给信息区
            content
                .aspectRatio(16.0 / 9.0, contentMode: .fit)
        }
    }

    @ViewBuilder
    private var videoContent: some View {
        // VLCKit 可用就用它（能解 AC3/裸 TS），否则退回 AVPlayer
        if usingVLC, let u = playURL {
            // 参数顺序必须与 VLCPlayerView 的属性声明顺序一致
            // （Swift 的逐成员初始化器按声明顺序传参）
            VLCPlayerView(url: u, decodeMode: session.decodeMode,
                          bufferMs: session.bufferMs,
                          liveDelayMs: session.liveDelayMs,
                          userAgent: playUA, headers: playHeaders,
                          control: vlc, aspect: aspectMode) { st in
                // VLCKit 的状态通知是在它自己的线程上发出的，直接改 @State
                // 属于跨线程写 UI 状态；统一切回主线程再处理。
                Task { @MainActor in
                    switch st {
                    case .playing:
                        // 只有真正出过画面才算"起播过"：以前这句放在 switch 之前，
                        // 于是第一次 .buffering 就把它置 true，下面那条判断等于失效。
                        sawPlaybackState = true
                        loading = false
                        // 起播成功必须撤掉超时兜底，否则 8 秒后它会判定"还没起播"
                        // 并弹出一个假超时（正常起播被它盖住）。
                        loadingWatchdog?.cancel()
                    case .buffering:
                        // 直播流在分片边界会反复 buffering。已经出过画面就不要再转圈，
                        // 否则用户看到的就是「画面在播，中间一直有个加载圈」（实测反馈）。
                        if !sawPlaybackState { loading = true }
                    case .failed:
                        // 交给 AVPlayer 兜底，转圈改由那条路径控制
                        loadingWatchdog?.cancel()
                        // R08：VLC 起不来就退回 AVPlayer，别让用户卡死。
                        // 这里只置位，真正的取地址在 onChange 里跑。
                        fallbackRequested = true
                    }
                }
            }
            // 必须显式撑满：UIViewRepresentable 默认按 UIView 的理想尺寸布局，
            // 而裸 UIView 没有 intrinsic size，结果画面缩成一小块。
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let p = player {
            PlayerLayerView(player: p, aspect: aspectMode)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func errorOverlay(_ err: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 36)).foregroundColor(.orange)
            Text(err)
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            // 降级全失败了给一条"再试一次"的路，否则用户只能杀进程
            if !loading {
                Button("重试") {
                    Task { await start() }
                }
                .foregroundColor(Color(red: 0.176, green: 0.831, blue: 0.749))
            }
        }
    }

    /// 切换全屏：既改布局，也尽量真的转屏。
    ///
    /// 部署目标已上调到 iOS 16，`requestGeometryUpdate` 可直接用
    /// （iOS 15 时代只能走 UIDevice 的 KVC 绕法，那段已删掉）。
    /// 转屏失败也不影响——forceFullscreen 已经让布局变成全屏了。
    private func toggleFullscreen() {
        forceFullscreen.toggle()
        let wantLandscape = forceFullscreen
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            scene.requestGeometryUpdate(
                .iOS(interfaceOrientations: wantLandscape ? .landscapeRight : .portrait))
        }
    }

    private var routeText: String {
        let mode = playMode == "direct" ? "直连源站" : "服务端中转"
        let ua = playUA.isEmpty ? "默认 UA" : "UA: " + String(playUA.prefix(30))
        return mode + " · " + ua
    }

    /// 标题栏：频道名 + 分组。竖屏时放在画面**上方**。
    /// 技术信息：分辨率 + 引擎。
    ///
    /// 安卓 TV 端 OSD 早就有（FHD/HD/SD + 编码），iOS 这边一直是空的，
    /// 排查"能播没声 / 花屏 / 是不是回落成 AVPlayer 了"时没有任何抓手。
    /// VLCKit 只暴露 `videoSize`，**不暴露音频编解码名**（`audioTrackNames` 只是
    /// "Track 1"），所以这里只报分辨率与引擎，不硬编一个假的音频编码。
    private var techText: String? {
        var parts: [String] = []
        if usingVLC {
            #if canImport(MobileVLCKit)
            if let p = vlc.player as? VLCMediaPlayer {
                let size = p.videoSize
                if size.width > 0 && size.height > 0 {
                    let w = Int(size.width)
                    let h = Int(size.height)
                    parts.append("\(w)×\(h)")
                    parts.append(h >= 1080 ? "FHD" : (h >= 720 ? "HD" : "SD"))
                }
            }
            #endif
            // 设置里强制了这个内核就标出来：排查时「到底是不是我选的那个」
            // 必须一眼可见，否则又会绕回「改了设置没变化」的老坑。
            parts.append(session.engineMode == "vlc" ? "VLC（强制）" : "VLC")
        } else {
            parts.append(session.engineMode == "avplayer" ? "AVPlayer（强制）" : "AVPlayer")
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private var osdHeader: some View {
        HStack(spacing: 8) {
            Text(active.name).font(.headline).foregroundColor(.white)
            // 与 Android 一致：频道名后面跟「● 直播」角标
            Text(timeshiftURL == nil ? "● 直播" : "◀ 回放")
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(timeshiftURL == nil ? .red : .orange)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(Capsule().fill(Color.black.opacity(0.45)))
            Spacer()
            // 与 Android 一致：右上角显示**引擎/解码**（Android 是「系统硬解 (Media3)」），
            // 不再是分组名 —— 分组名在频道页已经有了，播放时更想知道走的哪条链路。
            Text(techText ?? active.group)
                .font(.caption)
                .foregroundColor(.gray)
        }
    }

    /// 节目信息：当前节目 + 进度 + 下一档 + 播放链路。竖屏时放在画面**下方**。
    private var osdDetails: some View {
        VStack(alignment: .leading, spacing: 4) {
            // 直播 / 回放角标 + 技术信息（分辨率 · 引擎）。
            // 安卓手机端有「● 直播 / ◀ 回放」角标，iOS 之前完全没有——
            // 用户点了回看之后看不出自己在看回放还是直播。
            HStack(spacing: 8) {
                Text(timeshiftURL == nil ? "● 直播" : "◀ 回放")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(timeshiftURL == nil ? .red : .orange)
                // techText（分辨率/引擎）已在顶部 osdHeader 显示，这里不重复
                Spacer(minLength: 0)
            }
            if let now = programs.first(where: { $0.isNow }) {
                Text("\(now.timeText) \(now.title)")
                    .font(.caption).foregroundColor(.gray).lineLimit(1)
                // 当前节目进度：和 Android 端一致，一眼看出还剩多久
                if now.endAt > now.startAt {
                    let nowTS = Int64(Date().timeIntervalSince1970)
                    let total = Double(now.endAt - now.startAt)
                    let done = Double(max(0, min(now.endAt - now.startAt, nowTS - now.startAt)))
                    ProgressView(value: done, total: total)
                        .tint(Color(red: 0.176, green: 0.831, blue: 0.749))
                }
            }
            if programs.isEmpty {
                // 空着什么都不显示，用户会以为"坏了"。
                // 明确告诉他大概率是没关联 EPG，好去管理台处理。
                Text("没有节目单（该频道可能未关联 EPG）")
                    .font(.caption2).foregroundColor(.gray.opacity(0.8))
            }
            // 下一档节目：Android 的 OSD 有这一行
            if let next = programs.first(where: { $0.startAt > Int64(Date().timeIntervalSince1970) }) {
                Text("下一档 \(next.timeText) \(next.title)")
                    .font(.caption2).foregroundColor(.gray.opacity(0.85)).lineLimit(1)
            }
            // 播放链路 + 实际用的 UA：排查源站校验时一眼能看到
            Text(routeText)
                .font(.system(size: 10)).foregroundColor(.gray.opacity(0.8)).lineLimit(1)
        }
    }

    /// 浮层里用的完整信息卡（横屏时用；竖屏已拆到画面上/下）。
    private var osd: some View {
        VStack(alignment: .leading, spacing: 6) {
            osdHeader
            osdDetails
        }
        .padding(12)
        // 与 Android 一致：**纯文字浮在画面上**，不加半透明卡片底和圆角。
        // 之前这里是 .ultraThinMaterial + cornerRadius(10)，用户要求去掉。
        // 顶部额外留 40pt：全屏时这个浮层紧贴屏幕顶端，会被 iPhone 的灵动岛/
        // 圆角遮住（用户实测「没适配全面屏」就是指这个）。
        .padding(.top, 40)
        .padding(12)
    }

    /// 播放控制条：上一台 / 播放暂停 / 下一台 / 静音。
    ///
    /// 之前只有 OSD 显示信息，没有任何操作控件——用户只能靠手势，
    /// 而且不知道能干什么。这里补齐成常规播放器的样子。
    /// 播放控制层：与 Android 手机端同构（2026-11-19 按用户要求对齐）。
    ///
    /// 布局：**中央大播放键 + 左右边缘换台 + 底部左（比例/内核/解码）右（静音/全屏/投屏/更多）**。
    /// 之前是底部一条横向胶囊，和 Android 的分散式布局完全不是一回事。
    private var controls: some View {
        ZStack {
            // 中央：播放 / 暂停（72pt，与 Android 的 btnPlayPause 一致）
            // Android 那边是 **无底色** 的半透明图标（alpha 0.85），这里不能加圆底，
            // 否则在蓝色画面上会变成一个显眼的蓝圆（用户实测指出）。
            Button(action: togglePause) {
                Image(systemName: paused ? "play.fill" : "pause.fill")
                    .font(.system(size: 34))
                    .foregroundColor(.white)
                    .frame(width: 72, height: 72)
                    .opacity(0.85)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // 左右边缘：上一台 / 下一台
            HStack {
                ctlButton("backward.end.fill", "上一台", size: 20) { switchBy(-1) }
                Spacer()
                ctlButton("forward.end.fill", "下一台", size: 20) { switchBy(1) }
            }
            .padding(.horizontal, 10)

            // 右上角：投屏（Android 的 btnCast 是 top|end）
            VStack {
                HStack {
                    Spacer()
                    ctlButton("airplayvideo", "投屏", size: 17) {
                        showCast = true
                        Task { await loadRenderers() }
                    }
                }
                .padding(.trailing, 10)
                .padding(.top, 8)
                Spacer()
            }

            // 底部：左（全屏时才显示比例/内核/解码）+ 右（静音/全屏/更多）
            VStack {
                Spacer()
                HStack(spacing: 8) {
                    // Android 的 fsTools 只在全屏出现（visibility=gone）
                    if isFullscreen {
                        pillButton("比例 \(aspectLabel)") { cycleAspect() }
                        pillButton("内核 \(engineLabel)") { cycleEngine() }
                        pillButton("解码 \(decodeLabel)") { cycleDecode() }
                    }
                    Spacer(minLength: 4)
                    ctlButton(muted ? "speaker.slash.fill" : "speaker.wave.2.fill", muted ? "取消静音" : "静音", size: 18) { toggleMute() }
                    ctlButton(isFullscreen
                              ? "arrow.down.right.and.arrow.up.left"
                              : "arrow.up.left.and.arrow.down.right",
                              isFullscreen ? "退出全屏" : "全屏", size: 17) { toggleFullscreen() }
                    // 「更多」：节目单 / 时移 / 换线路
                    Menu {
                        Button("比例 自动") { aspectMode = "auto" }
                        Button("比例 16:9") { aspectMode = "16:9" }
                        Button("比例 4:3") { aspectMode = "4:3" }
                        Button("比例 拉伸") { aspectMode = "fill" }
                        Button("比例 裁切") { aspectMode = "zoom" }
                        Divider()
                        Button("内核 自动") { session.engineMode = "auto" }
                        Button("内核 VLC") { session.engineMode = "vlc" }
                        Button("内核 AVPlayer") { session.engineMode = "avplayer" }
                        Button("解码 自动") { session.decodeMode = "auto" }
                        Button("解码 硬解") { session.decodeMode = "hard" }
                        Button("解码 软解") { session.decodeMode = "soft" }
                        Divider()
                        Button("节目单") { showEpg = true }
                        Button("时移回看") { Task { await startTimeshift() } }
                        Button("换线路") { Task { await loadStreams() } }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 20))
                            .foregroundColor(.white)
                            .frame(minWidth: 40, minHeight: 40)
                    }
                    .accessibilityLabel("更多")
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 20)
            }
        }
    }

    /// 底部小胶囊（比例 / 内核 / 解码），与 Android 的 `PhoneCtlPill` 同款。
    private func pillButton(_ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 11))
                .foregroundColor(.white)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)  // 竖屏窄，不能让「比例 自动」换行
                .padding(.horizontal, 9)
                .frame(height: 30)
        }
        .buttonStyle(.plain)
    }

    /// 画面比例的中文标签（与 Android 的 `比例 自动` 一致）
    private var aspectLabel: String {
        switch aspectMode {
        case "16:9": return "16:9"
        case "4:3": return "4:3"
        case "fill": return "拉伸"
        case "zoom": return "裁切"
        default: return "自动"
        }
    }

    private func cycleAspect() {
        let order = ["auto", "16:9", "4:3", "fill", "zoom"]
        let i = order.firstIndex(of: aspectMode) ?? 0
        aspectMode = order[(i + 1) % order.count]
    }

    // ---------------------------------------------------------------- 投屏

    private var castSheet: some View {
        NavigationView {
            List {
                if castLoading {
                    Text("正在搜索可投屏设备…").foregroundColor(.gray)
                } else if renderers.isEmpty {
                    Text("没找到可投屏的设备\n确认电视/盒子已开机，且和 NAS 在同一个局域网")
                        .foregroundColor(.gray)
                        .font(.system(size: 13))
                }
                ForEach(renderers) { r in
                    Button {
                        Task { await doCast(r) }
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.name).foregroundColor(.primary)
                            if !r.model.isEmpty {
                                Text(r.model).font(.caption).foregroundColor(.gray)
                            }
                        }
                    }
                }
                if let tip = castTip {
                    Text(tip).font(.caption).foregroundColor(.orange)
                }
            }
            .navigationTitle("投屏到")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        Task { await loadRenderers() }
                    } label: { Image(systemName: "arrow.clockwise") }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") { showCast = false }
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func loadRenderers() async {
        castLoading = true
        castTip = nil
        defer { castLoading = false }
        do {
            renderers = try await session.api.dlnaRenderers()
            if renderers.isEmpty {
                castTip = "局域网里没找到 DLNA 设备"
            }
        } catch {
            renderers = []
            castTip = "搜索失败：\(error.localizedDescription)"
        }
    }

    private func doCast(_ r: DlnaRenderer) async {
        do {
            try await session.api.dlnaCast(
                channelId: active.id, controlUrl: r.controlUrl, name: r.name)
            castTip = "已投屏到 \(r.name)"
        } catch {
            castTip = "投屏失败：\(error.localizedDescription)"
        }
    }

    /// 图标 + 文字两行。
    ///
    /// 2026-11-19：**用 SF Symbols，不用 Unicode 符号**。
    /// `⏮ ⏸ ⏭ ⇪` 这些在 iOS 上会被渲染成 **emoji**（自带蓝色方块底），
    /// 看起来就像给按钮加了圆底——这正是用户圈出来的「有颜色」。
    /// SF Symbols 是单色矢量，`.foregroundColor(.white)` 后就是纯白。
    private func ctlButton(_ symbol: String, _ label: String, size: CGFloat,
                           _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                // 必须显式给白色：Image 不给颜色会继承 App 的 accentColor（这里是绿色），
                // 用户实测指出「图标怎么是绿的」。
                Image(systemName: symbol)
                    .font(.system(size: size))
                    .foregroundColor(.white)
                Text(label).font(.system(size: 10)).foregroundColor(.white)
            }
            .frame(minWidth: 46)
        }
        .buttonStyle(.plain)
    }

    // ---------------------------------------------------------------- 内核 / 解码

    private var engineLabel: String {
        switch session.engineMode {
        case "vlc": return "VLC"
        case "avplayer": return "AV"
        default: return "自动"
        }
    }

    private var decodeLabel: String {
        switch session.decodeMode {
        case "hard": return "硬解"
        case "soft": return "软解"
        default: return "自动"
        }
    }

    /// 点一下切到下一个（与 Android 全屏工具栏的「内核 自动 / 解码 自动」一致）。
    /// 改的是 session 的 @Published 字段，现有的 onChange 会自动重起当前台。
    private func cycleEngine() {
        let order = ["auto", "vlc", "avplayer"]
        let i = order.firstIndex(of: session.engineMode) ?? 0
        session.engineMode = order[(i + 1) % order.count]
    }

    private func cycleDecode() {
        let order = ["auto", "hard", "soft"]
        let i = order.firstIndex(of: session.decodeMode) ?? 0
        session.decodeMode = order[(i + 1) % order.count]
    }

    // ---------------------------------------------------------------- 控件浮层

    private func showControls() {
        controlsVisible = true
        controlsHideTask?.cancel()
        controlsHideTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled { controlsVisible = false }
        }
    }

    private func toggleControls() {
        if controlsVisible {
            controlsHideTask?.cancel()
            controlsVisible = false
        } else {
            showControls()
        }
    }

    private func togglePause() {
        if let p = player {
            if paused { p.play() } else { p.pause() }
        } else {
            vlc.togglePause()
        }
        paused.toggle()
    }

    private func toggleMute() {
        muted.toggle()
        if let p = player {
            p.isMuted = muted
        } else {
            vlc.setMuted(muted)
        }
    }

    /// 播放页分组 chip（与 Android 手机端一致）。
    private var groupChips: some View {
        let groups = Array(Set(session.channels.map { $0.group }.filter { !$0.isEmpty })).sorted()
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip("全部", on: playGroup == nil) { playGroup = nil }
                ForEach(groups, id: \.self) { g in
                    chip(g, on: playGroup == g) { playGroup = g }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
    }

    private func chip(_ title: String, on: Bool, tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            Text(title)
                .font(.caption)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(on ? Color.accentColor.opacity(0.25) : Color.white.opacity(0.08))
                .foregroundColor(on ? .accentColor : .gray)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    /// 从节目单某一档发起回看。
    ///
    /// 与 Android 的 `EpgActions.replay` 一致：服务端的时移接口是**按整台**给地址
    /// （`/api/v1/tv/timeshift/{id}`，不带节目时间），所以这里等于"从这档节目处
    /// 发起回看"，实际播放的是该台时移缓冲的起点，不按节目精确定位。
    private func replayProgram(_ p: EpgProgram) async {
        gestureTip = "回看：\(p.title)"
        await startTimeshift()
    }

    /// 开始回看（时移）。服务端没开时移时给一句提示，不改播放状态。
    private func startTimeshift() async {
        do {
            let info = try await session.api.timeshift(channelId: active.id)
            guard info.enabled, let u = session.api.url(info.url) else {
                gestureTip = "这个台没有时移（服务端未开启或频道不支持）"
                return
            }
            timeshiftURL = u
            await start()
        } catch {
            gestureTip = "时移失败：\(error.localizedDescription)"
        }
    }

    private func switchBy(_ delta: Int) {
        // 只在当前分组内循环（未选分组 = 全部），与 Android 手机端一致
        var list = session.channels
        if let g = playGroup {
            let inGroup = list.filter { $0.group == g }
            if !inGroup.isEmpty { list = inGroup }
        }
        guard !list.isEmpty else { return }
        let i = list.firstIndex(where: { $0.id == active.id }) ?? 0
        let n = ((i + delta) % list.count + list.count) % list.count
        player?.pause()
        player = nil
        avWatcher?.stop()
        avWatcher = nil
        // 换台就不再是回看
        timeshiftURL = nil
        // 复用同一个页面：改 switched 后由 onChange 重新起播
        switched = list[n]
    }

    /// 常驻节目单：视频下方直接铺开，按天切换。
    ///
    /// 与 Android 手机端对齐——那边节目单是常驻面板，不是按钮弹出来的。
    /// 全屏时仍然用 sheet（画面优先）。
    private var liveEpgPanel: some View {
        VStack(spacing: 0) {
            // 与 Android 一致：**6 天日期行**（今天 ±3），点哪个看哪天。
            // 之前只有「< 今天 >」左右箭头，看不到有哪些日期可选。
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 18) {
                    ForEach([-3, -2, -1, 0, 1, 2], id: \.self) { d in
                        Button { Task { await loadEpg(day: d) } } label: {
                            Text(dayLabel(d))
                                .font(.system(size: 14, weight: epgDay == d ? .semibold : .regular))
                                .foregroundColor(epgDay == d
                                                 ? Color(red: 0.176, green: 0.831, blue: 0.749)
                                                 : .gray)
                        }
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.vertical, 8)

            if let tip = epgTip {
                Text(tip)
                    .font(.caption)
                    .foregroundColor(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
            }

            if programs.isEmpty {
                Text("暂无节目单")
                    .font(.caption)
                    .foregroundColor(.gray)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 24)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(programs) { p in
                            HStack {
                                Text(p.timeText)
                                    .foregroundColor(.gray)
                                    .font(.caption)
                                    .frame(width: 44, alignment: .leading)
                                Text(p.title)
                                    .foregroundColor(p.isNow ? .green : .primary)
                                Spacer()
                                // 已经播完的节目给一个「回看」入口。
                                // 安卓从节目单行就能直接回看，iOS 之前只能点顶栏整台时移。
                                if p.endAt <= Int64(Date().timeIntervalSince1970) {
                                    Button {
                                        Task { await replayProgram(p) }
                                    } label: {
                                        Text("回看")
                                            .font(.caption2)
                                            .foregroundColor(Color(red: 0.176, green: 0.831, blue: 0.749))
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    /// 节目单弹层：支持按天切换，长按节目可以设开播提醒 / 预约录制。
    private var epgSheet: some View {
        NavigationView {
            VStack(spacing: 0) {
                // 按天切换。IPTV 的节目单一般只给前后几天，
                // 所以这里不限范围，拉不到就是空列表。
                // 与 Android 一致：6 天日期行
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 18) {
                        ForEach([-3, -2, -1, 0, 1, 2], id: \.self) { d in
                            Button { Task { await loadEpg(day: d) } } label: {
                                Text(dayLabel(d))
                                    .font(.system(size: 14, weight: epgDay == d ? .semibold : .regular))
                                    .foregroundColor(epgDay == d
                                                     ? Color(red: 0.176, green: 0.831, blue: 0.749)
                                                     : .gray)
                            }
                        }
                    }
                    .padding(.horizontal, 20)
                }
                .padding(.vertical, 10)

                if let tip = epgTip {
                    Text(tip)
                        .font(.caption)
                        .foregroundColor(.orange)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 6)
                }

                List(programs) { p in
                    HStack {
                        Text(p.timeText)
                            .foregroundColor(.gray)
                            .font(.caption)
                            .frame(width: 44, alignment: .leading)
                        Text(p.title)
                            .foregroundColor(p.isNow ? .green : .primary)
                        Spacer()
                    }
                    .contentShape(Rectangle())
                    // 长按出菜单：只有还没播完的节目才给提醒/录制
                    .contextMenu {
                        if p.endAt > Int64(Date().timeIntervalSince1970) {
                            Button {
                                Task { await addReminder(p) }
                            } label: { Label("提醒我", systemImage: "bell") }

                            Button {
                                Task { await addRecording(p) }
                            } label: { Label("预约录制", systemImage: "record.circle") }
                        }
                    }
                }
                .listStyle(.plain)
            }
            .navigationTitle(active.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") { showEpg = false }
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    /// 上下滑动：左半边调屏幕亮度，右半边调播放器音量。
    ///
    /// 为什么用 VLC 的音量而不是系统音量：系统音量要 MPVolumeView 那套私有手法，
    /// 而且会和用户的系统音量设置互相打架；VLC 自己的音量只影响这个播放器。
    /// 代价是 AVPlayer 兜底路径没有音量手势（那条路只认系统音量）。
    private func handleVerticalDrag(_ v: DragGesture.Value) {
        let screen = UIScreen.main.bounds
        guard screen.height > 0 else { return }
        // 上滑为正
        let delta = -v.translation.height / screen.height

        if v.startLocation.x < screen.width / 2 {
            if dragStartBrightness == nil { dragStartBrightness = UIScreen.main.brightness }
            let b = min(1.0, max(0.0, (dragStartBrightness ?? 0.5) + delta))
            UIScreen.main.brightness = b
            gestureTip = "亮度 \(Int(b * 100))%"
        } else {
            if dragStartVolume == nil { dragStartVolume = vlc.volume() }
            let vol = min(200, max(0, (dragStartVolume ?? 100) + Float(delta) * 200))
            vlc.setVolume(vol)
            gestureTip = "音量 \(Int(vol))%"
        }
    }

    private func dayLabel(_ d: Int) -> String {
        switch d {
        case 0: return "今天"
        case -1: return "昨天"
        case 1: return "明天"
        default: return d > 0 ? "\(d) 天后" : "\(-d) 天前"
        }
    }

    /// 按天重新拉节目单。day 是相对今天的偏移。
    private func loadEpg(day: Int) async {
        epgTip = nil
        // 本地节目单优先（服务端只给 xmltv 地址，epg_programs 在 edge 上是空的）
        let local = EpgStore.shared.programs(channel: active, dayOffset: day) ?? []
        let list = local.isEmpty
            ? ((try? await session.api.epg(channelId: active.id, day: day)) ?? [])
            : local
        programs = list
        epgDay = day
        if list.isEmpty {
            epgTip = "这一天没有节目单（该频道可能未关联 EPG）"
        }
    }

    private func addReminder(_ p: EpgProgram) async {
        do {
            try await session.api.createReminder(
                channelId: active.id, title: p.title, startAt: p.startAt)
            epgTip = "已设置提醒：\(p.title)"
        } catch {
            epgTip = "设置提醒失败：\(error.localizedDescription)"
        }
    }

    private func addRecording(_ p: EpgProgram) async {
        // 时长取节目本身的长度，夹在 1 分钟 ~ 8 小时之间（服务端同样有限制）
        let dur = Int(max(60, min(8 * 3600, p.endAt - p.startAt)))
        do {
            try await session.api.createRecording(
                channelId: active.id, name: p.title, startAt: p.startAt, durationSec: dur)
            epgTip = "已预约录制：\(p.title)"
        } catch {
            epgTip = "预约录制失败：\(error.localizedDescription)"
        }
    }

    // ---------------------------------------------------------------- 播放

    private func start() async {
        // 记录当前台：底部「播放」tab 下次打开时直接续上
        session.lastChannel = active
        loading = true
        errorText = nil
        // QoE：每次起播重新计时
        playStartedAt = Date()
        startReported = false
        fallbackInFlight = false
        // 换台/重试时清掉上一次的降级请求，否则新旧两个标志会互相打架
        fallbackRequested = false
        // 换台就重新计"有没有收到过状态回调"，并摘掉上一个 item 的观察
        sawPlaybackState = false
        avWatcher?.stop()
        avWatcher = nil
        // 节目单先拉：它和播放是两件独立的事。
        // 之前放在播放成功之后，一旦取流失败（或 VLC 卡住不回调），
        // 节目单就永远是空的——用户看到的就是"视频里没有节目单"。
        epgDay = 0
        let localNow = EpgStore.shared.programs(channel: active, dayOffset: 0) ?? []
        programs = localNow.isEmpty
            ? ((try? await session.api.epg(channelId: active.id)) ?? [])
            : localNow

        // 转圈兜底：VLC 的 .playing 回调在个别源/机型上可能一直不来，
        // 那样中间的圈会永远转下去。这里加一个超时，到点无论如何都收起。
        //
        // 注意判据（改过一次的地方）：只有"**收到过状态回调**"或"已经起播"才允许静默收圈；
        // 彻底没有回调说明播放器根本没反应，这时候必须给出原因 + 重试入口，
        // 否则用户看到的就是永远黑屏、连按钮都没有。
        loadingWatchdog?.cancel()
        loadingWatchdog = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard !Task.isCancelled else { return }
            guard loading else { return }   // 已经起播（.playing 会 cancel，这里再兜一层）
            loading = false
            if errorText == nil && !sawPlaybackState && player == nil {
                errorText = "起播超时：8 秒内播放器没有任何响应。\n"
                    + "可能是源站不可达、线路已失效，或服务端未启用转封装。"
            }
        }

        do {
            // R08：能选内核的是"实际能力"，不是请求参数。
            //   有 VLCKit -> 尝试直连（省掉 NAS 中转），裸 TS 也能解；
            //   没有 VLCKit -> 直接要 relay + HLS，AVPlayer 只认 HLS。
            // 时移（回看）：地址由 /tv/timeshift 下发，直接播，不再请求 /tv/play
            if let ts = timeshiftURL {
                playURL = ts
                playUA = ""
                playHeaders = ["User-Agent": "LITV/1.0 (iOS)"]
                playMode = "timeshift"
                // /live/{id}/timeshift.ts 是裸 TS，所以封装就是 ts
                playKind = "ts"
                if preferVLC {
                    usingVLC = true
                    player = nil
                } else if session.engineMode == "avplayer" {
                    // 强制 AVPlayer 时别把裸 TS 丢给它：那是一个永远转圈的黑屏，
                    // 用户根本看不出是设置选错了。
                    errorText = "时移（回看）是裸 TS 封装，AVPlayer 无法播放。\n"
                        + "请在设置里把「播放内核」改回「自动」。"
                    loading = false
                    return
                } else {
                    usingVLC = false
                    startAVPlayer(ts)
                }
                return
            }

            // 客户端解析模式：地址就是本机解析出来的源地址，不请求 /tv/play。
            // 服务端那边 D1 已经没有频道表了，走它只会拿到 404 或旧签名。
            if session.clientParse {
                // client 模式下服务端存的地址是构建时抓的旧域名（可能已被污染），
                // 本地表还没就绪时**不能**静默回落到 /tv/play —— 那只会抛一个
                // 「A server with the specified hostname could not be found」，
                // 让人以为是网络问题，其实是频道 id 对不上。
                guard let direct = ChannelStore.shared.url(of: active.id),
                      let directURL = URL(string: direct) else {
                    // 诊断：把 id、名字、本地表条目数摊出来。
                    // 列表有频道但这里查不到，只有两种可能：
                    //   ① 本地表还没就绪（urlCount = -1）
                    //   ② active 是旧对象，id 与本地表对不上（urlCount > 0 但缺这个 id）
                    errorText = "本地频道表未就绪\nid=\(active.id) · \(active.name)\n本地表 \(ChannelStore.shared.urlCount()) 条地址"
                    loading = false
                    return
                }
                let h = ChannelStore.shared.headers(of: active.id)
                playURL = directURL
                playUA = h["User-Agent"] ?? ""
                playHeaders = h.isEmpty ? ["User-Agent": "LITV/1.0 (iOS)"] : h
                playMode = "direct"
                // 本地源表只给地址，没有服务端的 kind。留空 = 不上报 path，
                // 不要猜（猜错的 path 会污染 by_path 分组，比没有更难查）。
                playKind = ""
                if preferVLC {
                    usingVLC = true
                    player = nil
                } else {
                    usingVLC = false
                    startAVPlayer(directURL)
                }
                await session.api.heartbeat(channelId: active.id)
                return
            }

            let viaVLC = preferVLC
            let info = try await session.api.play(
                channelId: active.id, streamId: streamId, direct: viaVLC)
            guard let u = session.api.url(info.url) else {
                errorText = "播放地址无效"
                loading = false
                return
            }

            // R08 的关键分支：只有**走 AVPlayer 这条路**才拒绝裸 TS。
            if viaVLC {
                // VLC 路径：裸 TS 完全合法，不再在选内核之前拦一道。
                // 服务端仍可能给 kind == "remux"（上游是裸 TS 且服务端配了 ffmpeg），
                // 那只是表示 url 指向 remux.m3u8，VLC 一样能播，不需要区别对待。
                playURL = u
                playUA = info.ua
                playHeaders = info.playHeaders(defaultUA: "LITV/1.0 (iOS)")
                playMode = info.mode
                playKind = info.kind
                usingVLC = true
                player = nil
            } else {
                // AVPlayer 路径：只接受 HLS。
                // 走到这里说明已经带了 fmt=hls，服务端还是回 "ts"，
                // 那就是 NAS 上没有可用的 ffmpeg（转封装不可用），这不是客户端能修的。
                if info.kind == "ts" {
                    errorText = "该频道是裸 TS 源，而服务端未启用转封装（ffmpeg）。\n请管理员在 config.yaml 里配置 transcode.ffmpeg 后重启服务。"
                    loading = false
                    return
                }
                playURL = u
                playUA = info.ua
                playHeaders = info.playHeaders(defaultUA: "LITV/1.0 (iOS)")
                playMode = info.mode
                playKind = info.kind
                usingVLC = false
                startAVPlayer(u)
            }

            // heartbeat 是 async 的，必须 await——Swift 里 async 函数不能"发了不管"
            await session.api.heartbeat(channelId: active.id)
        } catch {
            errorText = error.localizedDescription
            loading = false
        }
    }

    /// VLC 起播失败 -> 降级到 AVPlayer。
    ///
    /// 必须重新向服务端要一次地址（relay + HLS），**不能复用** VLC 拿到的那个 URL：
    /// 那个 URL 很可能是 direct 模式下的上游裸 TS 地址，
    /// AVPlayer 既解不了裸 TS，也拿不到服务端的转封装。
    private func fallbackToAVPlayer() async {
        if fallbackInFlight { return }
        fallbackInFlight = true
        usingVLC = false
        player?.pause()
        player = nil
        loading = true
        errorText = nil

        do {
            let info = try await session.api.playForAVPlayer(
                channelId: active.id, streamId: streamId)
            guard let u = session.api.url(info.url) else {
                errorText = "播放地址无效"
                loading = false
                return
            }
            // 服务端没配 ffmpeg 时 relay 也只能给裸 TS，这时候降级没有意义，
            // 给出和主路径一致的原因，别让用户以为换个内核就能好。
            if info.kind == "ts" {
                errorText = "VLC 播放失败，且服务端未启用转封装（ffmpeg），无法降级到 AVPlayer。\n请管理员在 config.yaml 里配置 transcode.ffmpeg 后重启服务。"
                loading = false
                return
            }
            playURL = u
            playUA = info.ua
            playHeaders = info.playHeaders(defaultUA: "LITV/1.0 (iOS)")
            playMode = info.mode
            playKind = info.kind
            startAVPlayer(u)
        } catch {
            errorText = "VLC 播放失败，降级 AVPlayer 也没拿到地址：" + error.localizedDescription
            loading = false
        }
    }

    /// AVPlayer 兜底路径（也是 VLCKit 不可用时的唯一路径）。
    private func startAVPlayer(_ u: URL) {
        let item: AVPlayerItem
        // R09：AVPlayer 支持一次带多个自定义请求头。
        // 以前只带 User-Agent，遇到校验 Referer / Origin 的源就 403。
        // AVURLAssetHTTPHeaderFieldsKey 是 AVFoundation 未公开但长期稳定的用法。
        var hdrs = playHeaders.filter { k, v in
            !k.isEmpty && !v.isEmpty && !v.contains("\n") && !v.contains("\r")
        }
        // 直播列表强制刷新：与 Android 的 prefs.hlsNoCache 对齐。
        // 源 m3u8 带 max-age=86400、分片签名却几分钟就过期，CDN 会把过期列表
        // 反复喂回来，分片请求拿到 0 字节 →「播一下卡一下」。
        // 只有 AVPlayer 这条路能带自定义头；VLCKit 只有 UA/Referer。
        if session.hlsNoCache { hdrs["Cache-Control"] = "no-cache" }
        if hdrs.isEmpty {
            item = AVPlayerItem(url: u)
        } else {
            let asset = AVURLAsset(url: u, options: ["AVURLAssetHTTPHeaderFieldsKey": hdrs])
            item = AVPlayerItem(asset: asset)
        }
        let p = AVPlayer(playerItem: item)
        // 失败必须能被看到：item.status 变 .failed（403/404、HLS 解析失败）和
        // failedToPlayToEndTime（中途断开）两条路都要接住，否则就是"黑屏且无提示"。
        let watcher = AVPlayerWatcher()
        watcher.watch(p) { reason in
            errorText = reason
            // 报错时一定要把转圈收掉：errorOverlay 的「重试」按钮条件是 if !loading，
            // 忘了这一步等于报错但没有任何出口。
            loading = false
            loadingWatchdog?.cancel()
        }
        avWatcher = watcher
        p.play()
        player = p
        loading = false
    }

    private func loadStreams() async {
        streams = (try? await session.api.streams(channelId: active.id)) ?? []
        // 简单轮换：每次点刷新切到下一条线路
        if streams.count > 1 {
            let idx = streams.firstIndex(where: { $0.id == streamId }) ?? 0
            streamId = streams[(idx + 1) % streams.count].id
        }
        await start()
    }
}

/// AVPlayer 的失败观察器。
///
/// 为什么要单独一个引用类型：SwiftUI 的 View 是 struct，KVO token 与
/// NotificationCenter 的观察必须**成对**摘掉，放在 @State 里的临时值很容易漏。
/// 这里由引用对象持有，stop() 与 deinit 都会摘干净（重复调用是安全的）。
///
/// 覆盖两种真实故障：
///   1. item.status == .failed —— 源站 403/404、HLS 播放列表解析失败；
///   2. AVPlayerItemFailedToPlayToEndTime —— 直播中途连接断开。
final class AVPlayerWatcher {

    private var statusObs: NSKeyValueObservation?
    private var endObs: NSObjectProtocol?

    /// 开始观察。onFail 一律在主队列回调。
    func watch(_ player: AVPlayer, onFail: @escaping (String) -> Void) {
        stop()
        guard let item = player.currentItem else { return }

        statusObs = item.observe(\.status, options: [.new, .initial]) { observed, _ in
            guard observed.status == .failed else { return }
            let why = observed.error?.localizedDescription ?? "未知错误"
            DispatchQueue.main.async { onFail("播放失败：\(why)") }
        }

        endObs = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { note in
            let err = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            onFail("播放中断：\(err?.localizedDescription ?? "连接已断开")")
        }
    }

    /// 摘掉所有观察（幂等）。必须成对调用，否则 item 释放后再回调会崩。
    func stop() {
        statusObs?.invalidate()
        statusObs = nil
        if let obs = endObs {
            NotificationCenter.default.removeObserver(obs)
            endObs = nil
        }
    }

    deinit { stop() }
}
