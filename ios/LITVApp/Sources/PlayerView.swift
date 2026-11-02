import AVKit
import SwiftUI

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
    @State private var muted = false
    @StateObject private var vlc = VLCControl()
    /// 换台用：切了台就换这个，nil 表示还是进来时那个
    @State private var switched: Channel?
    /// direct / relay，用于 OSD 显示
    @State private var playMode = ""

    /// 当前实际在播的频道
    private var active: Channel { switched ?? channel }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // VLCKit 可用就用它（能解 AC3/裸 TS），否则退回 AVPlayer
            if usingVLC, let u = playURL {
                VLCPlayerView(url: u, userAgent: playUA, headers: playHeaders, control: vlc) { st in
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
                            // 这里只置位，真正的取地址在 onChange 里跑（见上面的说明）。
                            fallbackRequested = true
                        }
                    }
                }
                .ignoresSafeArea()
            } else if let p = player {
                VideoPlayer(player: p)
                    .ignoresSafeArea()
            }

            if loading {
                ProgressView().tint(.white)
            }

            if let err = errorText {
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

            VStack {
                Spacer()
                // 控制条贴着底部，OSD 信息在它上面
                controls
                osd
            }
        }
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
        .onAppear { Task { await start() } }
        .onDisappear { player?.pause(); player = nil }
    }

    private var routeText: String {
        let mode = playMode == "direct" ? "直连源站" : "服务端中转"
        let ua = playUA.isEmpty ? "默认 UA" : "UA: " + String(playUA.prefix(30))
        return mode + " · " + ua
    }

    private var osd: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(active.name).font(.headline).foregroundColor(.white)
                Spacer()
                Text(active.group).font(.caption).foregroundColor(.gray)
            }
            if let now = programs.first(where: { $0.isNow }) {
                Text("\(now.timeText) \(now.title)")
                    .font(.caption).foregroundColor(.gray).lineLimit(1)
            }
            // 播放链路 + 实际用的 UA：排查源站校验时一眼能看到
            Text(routeText)
                .font(.system(size: 10)).foregroundColor(.gray.opacity(0.8)).lineLimit(1)
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
        HStack(spacing: 18) {
            ctlButton("backward.end.fill", "上一台", size: 18) { switchBy(-1) }
            ctlButton(paused ? "play.fill" : "pause.fill", paused ? "播放" : "暂停", size: 22) { togglePause() }
            ctlButton("forward.end.fill", "下一台", size: 18) { switchBy(+1) }
            ctlButton(muted ? "speaker.slash.fill" : "speaker.wave.2.fill", muted ? "取消静音" : "静音", size: 18) { toggleMute() }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 18)
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

    private var epgSheet: some View {
        NavigationView {
            List(programs) { p in
                HStack {
                    Text(p.timeText).foregroundColor(.gray).font(.caption)
                        .frame(width: 44, alignment: .leading)
                    Text(p.title).foregroundColor(p.isNow ? .green : .primary)
                }
            }
            .navigationTitle("节目单")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") { showEpg = false }
                }
            }
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
            programs = (try? await session.api.epg(channelId: active.id)) ?? []
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
