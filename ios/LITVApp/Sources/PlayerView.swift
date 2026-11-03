import AVKit
import SwiftUI
import UIKit

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

    /// 控件浮层是否可见。和 Android 手机端一致：
    /// 点画面呼出、4 秒自动收起，不常驻遮挡画面。
    @State private var controlsVisible = true

    /// 手机横过来时自动全屏；也可以点控制条上的「全屏」按钮强制全屏。
    @Environment(\.verticalSizeClass) private var vSize
    @State private var forceFullscreen = false
    @State private var controlsHideTask: Task<Void, Never>?
    @State private var muted = false
    @StateObject private var vlc = VLCControl()
    /// 换台用：切了台就换这个，nil 表示还是进来时那个
    @State private var switched: Channel?
    /// direct / relay，用于 OSD 显示
    @State private var playMode = ""

    /// 当前实际在播的频道
    private var active: Channel { switched ?? channel }

    /// 是否处于全屏：手机横过来（verticalSizeClass == .compact）自动全屏，
    /// 或者用户点了「全屏」按钮。
    private var isFullscreen: Bool { vSize == .compact || forceFullscreen }

    var body: some View {
        VStack(spacing: 0) {
            // 竖屏布局：频道名在上，16:9 画面在中间，节目信息在下。
            if !isFullscreen {
                osdHeader
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 10)
            }

            videoArea

            if !isFullscreen {
                osdDetails
                    .padding(.horizontal, 16)
                    .padding(.top, 10)
                Spacer(minLength: 0)
            }
        }
        .background(Color.black.ignoresSafeArea())
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
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button {
                    showEpg = true
                } label: {
                    Image(systemName: "list.bullet.rectangle")
                }
                Button {
                    Task { await loadStreams() }
                } label: {
                    Image(systemName: "arrow.triangle.2.circlepath")
                }
            }
        }
        .sheet(isPresented: $showEpg) { epgSheet }
        .onAppear {
            showControls()
            Task { await start() }
        }
        .onDisappear {
            player?.pause()
            player = nil
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
                            .onChanged { v in handleVerticalDrag(v) }
                            .onEnded { _ in
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
            VLCPlayerView(url: u, userAgent: playUA, headers: playHeaders,
                          decodeMode: session.decodeMode, control: vlc) { st in
                // VLCKit 的状态通知是在它自己的线程上发出的，直接改 @State
                // 属于跨线程写 UI 状态；统一切回主线程再处理。
                Task { @MainActor in
                    switch st {
                    case .playing:
                        loading = false
                    case .buffering:
                        loading = true
                    case .failed:
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
            VideoPlayer(player: p)
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
    /// iOS 15 没有 requestGeometryUpdate，只能走 UIDevice 的 KVC 办法；
    /// 转屏失败也不影响——forceFullscreen 已经让布局变成全屏了。
    private func toggleFullscreen() {
        forceFullscreen.toggle()
        let wantLandscape = forceFullscreen
        if #available(iOS 16.0, *) {
            if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
                scene.requestGeometryUpdate(
                    .iOS(interfaceOrientations: wantLandscape ? .landscapeRight : .portrait))
            }
        } else {
            UIDevice.current.setValue(
                wantLandscape ? UIInterfaceOrientation.landscapeRight.rawValue
                              : UIInterfaceOrientation.portrait.rawValue,
                forKey: "orientation")
        }
    }

    private var routeText: String {
        let mode = playMode == "direct" ? "直连源站" : "服务端中转"
        let ua = playUA.isEmpty ? "默认 UA" : "UA: " + String(playUA.prefix(30))
        return mode + " · " + ua
    }

    /// 标题栏：频道名 + 分组。竖屏时放在画面**上方**。
    private var osdHeader: some View {
        HStack {
            Text(active.name).font(.headline).foregroundColor(.white)
            Spacer()
            Text(active.group).font(.caption).foregroundColor(.gray)
        }
    }

    /// 节目信息：当前节目 + 进度 + 下一档 + 播放链路。竖屏时放在画面**下方**。
    private var osdDetails: some View {
        VStack(alignment: .leading, spacing: 4) {
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
        .background(.ultraThinMaterial)
        .cornerRadius(10)
        .padding(12)
    }

    /// 播放控制条：上一台 / 播放暂停 / 下一台 / 静音。
    ///
    /// 之前只有 OSD 显示信息，没有任何操作控件——用户只能靠手势，
    /// 而且不知道能干什么。这里补齐成常规播放器的样子。
    private var controls: some View {
        HStack(spacing: 12) {
            ctlButton("backward.end.fill", "上一台", size: 18) { switchBy(-1) }
            ctlButton(paused ? "play.fill" : "pause.fill", paused ? "播放" : "暂停", size: 22) { togglePause() }
            ctlButton("forward.end.fill", "下一台", size: 18) { switchBy(+1) }
            ctlButton(muted ? "speaker.slash.fill" : "speaker.wave.2.fill", muted ? "取消静音" : "静音", size: 18) { toggleMute() }
            // 这两个原来只在 toolbar 上。PlayTab 里没有 NavigationView，
            // toolbar 不会被渲染，用户就找不到节目单和线路了。
            ctlButton("list.bullet.rectangle", "节目单", size: 18) { showEpg = true }
            ctlButton("arrow.triangle.2.circlepath", "线路", size: 18) {
                Task { await loadStreams() }
            }
            // 竖屏时手动切全屏（横过来也会自动全屏）
            ctlButton(isFullscreen
                        ? "arrow.down.right.and.arrow.up.left"
                        : "arrow.up.left.and.arrow.down.right",
                      isFullscreen ? "退出全屏" : "全屏", size: 18) { toggleFullscreen() }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
        .cornerRadius(20)
        .padding(.bottom, 18)
    }

    /// 图标 + 文字两行。只有图标的话不看说明书不知道哪个是换台、哪个是静音。
    private func ctlButton(_ icon: String, _ label: String, size: CGFloat,
                           _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: size))
                Text(label).font(.system(size: 10))
            }
            .frame(minWidth: 52)
        }
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

    private func switchBy(_ delta: Int) {
        let list = session.channels
        guard !list.isEmpty else { return }
        let i = list.firstIndex(where: { $0.id == active.id }) ?? 0
        let n = ((i + delta) % list.count + list.count) % list.count
        player?.pause()
        player = nil
        // 复用同一个页面：改 switched 后由 onChange 重新起播
        switched = list[n]
    }

    /// 节目单弹层：支持按天切换，长按节目可以设开播提醒 / 预约录制。
    private var epgSheet: some View {
        NavigationView {
            VStack(spacing: 0) {
                // 按天切换。IPTV 的节目单一般只给前后几天，
                // 所以这里不限范围，拉不到就是空列表。
                HStack(spacing: 18) {
                    Button {
                        Task { await loadEpg(day: epgDay - 1) }
                    } label: { Image(systemName: "chevron.left") }

                    Text(dayLabel(epgDay))
                        .font(.subheadline)
                        .frame(maxWidth: .infinity)

                    Button {
                        Task { await loadEpg(day: epgDay + 1) }
                    } label: { Image(systemName: "chevron.right") }
                }
                .padding(.horizontal, 20)
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
        let list = (try? await session.api.epg(channelId: active.id, day: day)) ?? []
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
        fallbackInFlight = false
        // 换台/重试时清掉上一次的降级请求，否则新旧两个标志会互相打架
        fallbackRequested = false
        // 节目单先拉：它和播放是两件独立的事。
        // 之前放在播放成功之后，一旦取流失败（或 VLC 卡住不回调），
        // 节目单就永远是空的——用户看到的就是"视频里没有节目单"。
        epgDay = 0
        programs = (try? await session.api.epg(channelId: active.id)) ?? []

        // 转圈兜底：VLC 的 .playing 回调在个别源/机型上可能一直不来，
        // 那样中间的圈会永远转下去。这里加一个超时，到点无论如何都收起。
        loadingWatchdog?.cancel()
        loadingWatchdog = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            if !Task.isCancelled { loading = false }
        }

        do {
            // R08：能选内核的是"实际能力"，不是请求参数。
            //   有 VLCKit -> 尝试直连（省掉 NAS 中转），裸 TS 也能解；
            //   没有 VLCKit -> 直接要 relay + HLS，AVPlayer 只认 HLS。
            let viaVLC = VLCPlayerView.isAvailable
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
        let hdrs = playHeaders.filter { k, v in
            !k.isEmpty && !v.isEmpty && !v.contains("\n") && !v.contains("\r")
        }
        if hdrs.isEmpty {
            item = AVPlayerItem(url: u)
        } else {
            let asset = AVURLAsset(url: u, options: ["AVURLAssetHTTPHeaderFieldsKey": hdrs])
            item = AVPlayerItem(asset: asset)
        }
        let p = AVPlayer(playerItem: item)
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
