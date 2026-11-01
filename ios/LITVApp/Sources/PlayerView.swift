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
    @State private var usingVLC = false
    @State private var errorText: String?
    @State private var loading = true
    @State private var programs: [EpgProgram] = []
    @State private var showEpg = false
    @State private var streamId: Int64 = 0
    @State private var streams: [TvStream] = []

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // VLCKit 可用就用它（能解 AC3/裸 TS），否则退回 AVPlayer
            if usingVLC, let u = playURL {
                VLCPlayerView(url: u) { st in
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
                osd
            }
        }
        .navigationTitle(channel.name)
        .navigationBarTitleDisplayMode(.inline)
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

    private var osd: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(channel.name).font(.headline).foregroundColor(.white)
                Spacer()
                Text(channel.group).font(.caption).foregroundColor(.gray)
            }
            if let now = programs.first(where: { $0.isNow }) {
                Text("\(now.timeText) \(now.title)")
                    .font(.caption).foregroundColor(.gray).lineLimit(1)
            }
        }
        .padding(12)
        .background(.ultraThinMaterial)
        .cornerRadius(10)
        .padding(12)
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
            let info = try await session.api.play(channelId: channel.id, streamId: streamId)
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
            // 有 VLCKit 就用它（能解 AC3/裸 TS），没有就走 AVPlayer
            if VLCPlayerView.isAvailable {
                usingVLC = true
                player = nil
            } else {
                startAVPlayer(u)
            }

            // heartbeat 是 async 的，必须 await——Swift 里 async 函数不能"发了不管"
            await session.api.heartbeat(channelId: channel.id)
            programs = (try? await session.api.epg(channelId: channel.id)) ?? []
        } catch {
            errorText = error.localizedDescription
            loading = false
        }
    }

    /// AVPlayer 兜底路径（也是 VLCKit 不可用时的唯一路径）。
    private func startAVPlayer(_ u: URL) {
        let item = AVPlayerItem(url: u)
        let p = AVPlayer(playerItem: item)
        p.play()
        player = p
        loading = false
    }

    private func loadStreams() async {
        streams = (try? await session.api.streams(channelId: channel.id)) ?? []
        // 简单轮换：每次点刷新切到下一条线路
        if streams.count > 1 {
            let idx = streams.firstIndex(where: { $0.id == streamId }) ?? 0
            streamId = streams[(idx + 1) % streams.count].id
        }
        await start()
    }
}
