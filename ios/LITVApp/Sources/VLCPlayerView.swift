import SwiftUI
import UIKit

/// VLC 播放器的状态（放在条件编译外面，两种实现共用同一个类型）。
enum VLCPlaybackState {
    case playing
    case buffering
    case failed(String)
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
    var onState: ((VLCPlaybackState) -> Void)?

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black

        let player = VLCMediaPlayer()
        player.drawable = view

        let media = VLCMedia(url: url)
        // 直播低延迟参数：不丢帧、网络缓冲给足
        // 注意：VLCKit 3.5 的 addOptions 收的是 NSDictionary，
        // 逐个用 addOption(_:) 更稳妥（不同版本签名不一样）。
        media.addOption("--no-drop-late-frames")
        media.addOption("--no-skip-frames")
        media.addOption("--network-caching=1500")
        media.addOption("--live-caching=1500")
        media.addOption("--avcodec-hw=any")
        player.media = media

        context.coordinator.player = player
        context.coordinator.onState = onState

        NotificationCenter.default.addObserver(
            context.coordinator,
            selector: #selector(Coordinator.stateChanged(_:)),
            name: NSNotification.Name(rawValue: VLCMediaPlayerStateChanged),
            object: player
        )

        if autoplay { player.play() }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.player?.stop()
        coordinator.player?.drawable = nil
        NotificationCenter.default.removeObserver(coordinator)
    }

    final class Coordinator: NSObject {
        var player: VLCMediaPlayer?
        var onState: ((VLCPlaybackState) -> Void)?

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
struct VLCPlayerView: View {
    let url: URL
    var autoplay: Bool = true
    var onState: ((VLCPlaybackState) -> Void)?

    var body: some View {
        Color.black
    }

    static var isAvailable: Bool { false }
}

#endif
