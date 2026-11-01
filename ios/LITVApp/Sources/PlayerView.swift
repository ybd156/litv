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
    @State private var usingVLC = false
    @State private var errorText: String?
    @State private var loading = true
    @State private var programs: [EpgProgram] = []
    @State private var showEpg = false
    @State private var streamId: Int64 = 0
    @State private var streams: [TvStream] = []

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
                VLCPlayerView(url: u, userAgent: playUA, control: vlc) { st in
                    switch st {
                    case .playing:
                        loading = false
                    case .buffering:
                        loading = true
                    case .failed(let msg):
                        // VLC 起不来就退回 AVPlayer，别让用户卡死
                        errorText = nil
                        usingVLC = false
                        startAVPlayer(u)
                        _ = msg
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
        HStack(spacing: 22) {
            Button { switchBy(-1) } label: {
                Image(systemName: "backward.end.fill").font(.system(size: 20))
            }
            Button { togglePause() } label: {
                Image(systemName: paused ? "play.fill" : "pause.fill").font(.system(size: 26))
            }
            Button { switchBy(+1) } label: {
                Image(systemName: "forward.end.fill").font(.system(size: 20))
            }
            Button { toggleMute() } label: {
                Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.system(size: 20))
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 22)
        .padding(.vertical, 12)
        .background(.ultraThinMaterial)
        .cornerRadius(24)
        .padding(.bottom, 18)
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
        // 复用同一个页面：改 current 后由 onChange 重新起播
        onSwitch?(list[n])
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
        loading = true
        errorText = nil
        do {
            // 装了 VLCKit 就尝试直连（省掉 NAS 中转）；服务端不允许时会自动回落
            let info = try await session.api.play(
                channelId: active.id, streamId: streamId, direct: VLCPlayerView.isAvailable)
            guard let u = session.api.url(info.url) else {
                errorText = "播放地址无效"
                loading = false
                return
            }
            if info.kind == "ts" {
                // 理论上不该出现：客户端已经带了 fmt=hls，服务端应该给 remux。
                // 走到这里说明服务端没配 ffmpeg（转封装不可用）。
                errorText = "该频道是裸 TS 源，而服务端未启用转封装（ffmpeg）。\n请管理员在 config.yaml 里配置 transcode.ffmpeg 后重启服务。"
                loading = false
                return
            }
            playURL = u
            playUA = info.ua
            playMode = info.mode
            // 有 VLCKit 就用它（能解 AC3/裸 TS），没有就走 AVPlayer
            if VLCPlayerView.isAvailable {
                usingVLC = true
                player = nil
            } else {
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

    /// AVPlayer 兜底路径（也是 VLCKit 不可用时的唯一路径）。
    private func startAVPlayer(_ u: URL) {
        let item: AVPlayerItem
        if playUA.isEmpty {
            item = AVPlayerItem(url: u)
        } else {
            // 源站校验 UA 时，AVPlayer 也得带上，否则 403。
            // AVURLAssetHTTPHeaderFieldsKey 是 AVFoundation 未公开但长期稳定的用法。
            let asset = AVURLAsset(
                url: u,
                options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": playUA]]
            )
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
