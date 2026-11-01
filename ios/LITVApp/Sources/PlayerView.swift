import AVKit
import SwiftUI

/// 播放页。
///
/// iOS 的解码现实（调研已确认）：
///   - AVPlayer 对 HLS（.m3u8）是系统级最优实现，功耗低、起播快
///   - AVPlayer **不支持裸 MPEG-TS**，也不支持 AC3/E-AC3
/// 所以服务端对 HLS 上游返回 .m3u8，对裸 TS 上游返回 .ts —— 后者 iOS 播不了。
/// 这不是客户端能绕过的，必须服务端转封装（未来加 ffmpeg 转 HLS）。
struct PlayerView: View {
    @EnvironmentObject var session: Session
    let channel: Channel

    @State private var player: AVPlayer?
    @State private var errorText: String?
    @State private var loading = true
    @State private var programs: [EpgProgram] = []
    @State private var showEpg = false
    @State private var streamId: Int64 = 0
    @State private var streams: [TvStream] = []

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let p = player {
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
            let item = AVPlayerItem(url: u)
            let p = AVPlayer(playerItem: item)
            p.play()
            player = p
            loading = false

            // heartbeat 是 async 的，必须 await——Swift 里 async 函数不能"发了不管"
            await session.api.heartbeat(channelId: channel.id)
            programs = (try? await session.api.epg(channelId: channel.id)) ?? []
        } catch {
            errorText = error.localizedDescription
            loading = false
        }
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
