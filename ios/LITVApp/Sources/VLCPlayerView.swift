import SwiftUI
import UIKit

/// VLC 播放器的状态（放在条件编译外面，两种实现共用同一个类型）。
enum VLCPlaybackState {
    case playing
    case buffering
    case failed(String)
}

/// 把 VLCMediaPlayer 的控制能力暴露给外面的控制条。
///
/// SwiftUI 的按钮拿不到 UIViewRepresentable 内部创建的播放器实例，
/// 所以用一个共享的引用对象转一手。
final class VLCControl: ObservableObject {
    weak var player: AnyObject?

    func togglePause() {
        #if canImport(MobileVLCKit)
        guard let p = player as? VLCMediaPlayer else { return }
        if p.isPlaying { p.pause() } else { p.play() }
        #endif
    }

    func setMuted(_ muted: Bool) {
        #if canImport(MobileVLCKit)
        guard let p = player as? VLCMediaPlayer else { return }
        p.audio?.volume = muted ? 0 : 100
        #endif
    }
}

#if canImport(MobileVLCKit)
import MobileVLCKit

/// 基于 libVLC 的播放器视图。
///
/// 为什么需要它：AVPlayer 只认 HLS，且**不支持 AC3/E-AC3**（Apple 未授权第三方解码）。
/// 国内 IPTV 有大量 AC3 音轨和裸 TS 封装，AVPlayer 遇到就是"有画面没声音"或者直接黑屏。
/// VLC 底层是 FFmpeg，这些都能解。
struct VLCPlayerView: UIViewRepresentable {

    let url: URL
    var autoplay: Bool = true
    /// 源配置里指定的 User-Agent（空 = 用 VLC 默认）
    var userAgent: String = ""
    /// 这条线路的完整请求头（R09）。VLCKit 只对 UA / Referer 提供专用 option，
    /// 其余头没有通用入口，所以这里映射这两类最常见的防盗链校验项。
    var headers: [String: String] = [:]
    /// 控制条通过它拿到底层播放器
    var control: VLCControl? = nil
    var onState: ((VLCPlaybackState) -> Void)?

    /// 大小写不敏感地在 headers 里找一个头。
    private func headerValue(_ name: String) -> String {
        for (k, v) in headers where k.caseInsensitiveCompare(name) == .orderedSame {
            if !v.isEmpty { return v }
        }
        return ""
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black

        let player = VLCMediaPlayer()
        player.drawable = view

        player.media = buildMedia()
        context.coordinator.currentURL = url
        context.coordinator.player = player
        context.coordinator.onState = onState
        // 控制条要能操作这个播放器
        control?.player = player

        NotificationCenter.default.addObserver(
            context.coordinator,
            selector: #selector(Coordinator.stateChanged(_:)),
            name: NSNotification.Name(rawValue: VLCMediaPlayerStateChanged),
            object: player
        )

        if autoplay { player.play() }
        return view
    }

    /// 按当前 url / headers 造一份 VLCMedia。
    ///
    /// makeUIView 与 updateUIView 共用同一份构造逻辑，
    /// 保证换台时直播低延迟参数和请求头完全一致——两处各写一遍迟早会漂移。
    private func buildMedia() -> VLCMedia {
        let media = VLCMedia(url: url)
        // 直播低延迟参数：不丢帧、网络缓冲给足
        // 注意：VLCKit 3.5 的 addOptions 收的是 NSDictionary，
        // 逐个用 addOption(_:) 更稳妥（不同版本签名不一样）。
        media.addOption("--no-drop-late-frames")
        media.addOption("--no-skip-frames")
        media.addOption("--network-caching=1500")
        media.addOption("--live-caching=1500")
        media.addOption("--avcodec-hw=any")
        // 源站校验 UA 时必须带，否则 403。headers 里的优先级高于历史 ua 字段。
        let ua = headerValue("User-Agent")
        if !ua.isEmpty {
            media.addOption(":http-user-agent=\(ua)")
        } else if !userAgent.isEmpty {
            media.addOption(":http-user-agent=\(userAgent)")
        }
        // R09：Referer 也是常见的防盗链校验项，VLCKit 有专用 option。
        let ref = headerValue("Referer")
        if !ref.isEmpty {
            media.addOption(":http-referrer=\(ref)")
        }
        return media
    }

    /// 换台时必须重建 media。
    ///
    /// SwiftUI 的 UIViewRepresentable 在参数变化时只调 updateUIView，
    /// **不会**重新走 makeUIView。以前这里是空实现，于是 url 变了
    /// 播放器还在放旧流（或首帧之后再也不出画面）——
    /// 用户看到的就是「点频道换台黑屏、没有任何提示」。
    func updateUIView(_ uiView: UIView, context: Context) {
        guard context.coordinator.currentURL != url else { return }
        context.coordinator.currentURL = url
        guard let player = context.coordinator.player else { return }
        player.stop()
        player.media = buildMedia()
        if autoplay { player.play() }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.player?.stop()
        coordinator.player?.drawable = nil
        NotificationCenter.default.removeObserver(coordinator)
    }

    final class Coordinator: NSObject {
        var player: VLCMediaPlayer?
        var onState: ((VLCPlaybackState) -> Void)?
        /// 当前已加载的 URL。updateUIView 靠它判断是否需要换台重建 media。
        var currentURL: URL?

        @objc func stateChanged(_ note: Notification) {
            guard let player = player else { return }
            // 只区分三种我们关心的状态，其余忽略：
            // 不同 VLCKit 版本的 VLCMediaPlayerState 枚举成员不完全一致，
            // 写太细会在某个版本上编译不过。
            switch player.state {
            case .playing:
                onState?(.playing)
            case .error:
                onState?(.failed("VLC 播放失败（源不可达或格式不支持）"))
            case .ended:
                onState?(.failed("流已结束"))
            default:
                onState?(.buffering)
            }
        }
    }

    /// 运行期判断 VLCKit 是否真的可用。
    static var isAvailable: Bool { true }
}

#else

/// 没装 VLCKit 时的占位实现：PlayerView 会据此退回 AVPlayer。
/// 构造参数必须与 VLCKit 版本保持完全一致，否则 PlayerView 里那处调用
/// 在"没有 Pod"的构建下编译不过。
struct VLCPlayerView: View {
    let url: URL
    var autoplay: Bool = true
    /// 源配置里指定的 User-Agent（空 = 用 VLC 默认）
    var userAgent: String = ""
    /// 这条线路的完整请求头（R09），占位实现不使用，仅为保持签名一致。
    var headers: [String: String] = [:]
    /// 控制条通过它拿到底层播放器
    var control: VLCControl? = nil
    var onState: ((VLCPlaybackState) -> Void)?

    var body: some View {
        Color.black
    }

    static var isAvailable: Bool { false }
}

#endif
