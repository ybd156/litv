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

    /// 明确暂停（幂等）：只在正在播时暂停。
    ///
    /// 退后台必须用这个、不能用 `togglePause`——后者是"切换"语义，
    /// 一旦当前已暂停就会把音频**打开**（表现是"切出去反而开始响"）。
    func pause() {
        #if canImport(MobileVLCKit)
        guard let p = player as? VLCMediaPlayer else { return }
        if p.isPlaying { p.pause() }
        #endif
    }

    func setMuted(_ muted: Bool) {
        #if canImport(MobileVLCKit)
        guard let p = player as? VLCMediaPlayer else { return }
        p.audio?.volume = muted ? 0 : 100
        #endif
    }

    /// 当前音量（0~200，100 为原始音量）。拿不到时返回 100。
    func volume() -> Float {
        #if canImport(MobileVLCKit)
        if let p = player as? VLCMediaPlayer {
            return Float(p.audio?.volume ?? 100)
        }
        #endif
        return 100
    }

    /// 设置音量，自动夹在 0~200。
    func setVolume(_ v: Float) {
        #if canImport(MobileVLCKit)
        guard let p = player as? VLCMediaPlayer else { return }
        p.audio?.volume = Int32(min(200, max(0, v)))
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
    /// 解码方式：auto（默认，让 VLC 自己选）/ hard（强制硬件）/ soft（强制软件）
    var decodeMode: String = "auto"
    /// 网络缓冲（毫秒），0 = 不覆盖（交给 VLCKit 默认）。对齐 Android 的 1/2/3/5/8 秒档。
    var bufferMs: Int = 1500
    /// 直播主动延迟（毫秒），0 = 不额外落后。对齐 Android 的 2/4/6/8/10 秒档。
    var liveDelayMs: Int = 0
    /// 源配置里指定的 User-Agent（空 = 用 VLC 默认）
    var userAgent: String = ""
    /// 这条线路的完整请求头（R09）。VLCKit 只对 UA / Referer 提供专用 option，
    /// 其余头没有通用入口，所以这里映射这两类最常见的防盗链校验项。
    var headers: [String: String] = [:]
    /// 控制条通过它拿到底层播放器
    var control: VLCControl? = nil
    /// 画面比例：auto / fill / zoom / 16:9 / 4:3
    var aspect: String = "auto"
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
        // 让 UIView 跟着 SwiftUI 给的尺寸走。不设这个的话，
        // 画面会保持一个很小的初始尺寸，看起来"播放框缩在角落/下方"。
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // 显式给一个初始 frame：裸 UIView 没有 intrinsic size，
        // 只靠 autoresizingMask 时在部分容器里会缩成一小块
        // （AGENTS §6 要求 frame 与 autoresizingMask 两者都有）。
        view.frame = CGRect(x: 0, y: 0, width: 1, height: 1)

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
        // 缓冲与直播延迟：以前写死 1500，弱网/源站抖动时用户只能干等。
        // 现在跟设置里联动（0 表示不覆盖，用 VLCKit 默认）。
        if bufferMs > 0 {
            media.addOption("--network-caching=\(bufferMs)")
        }
        let liveCaching = max(bufferMs, liveDelayMs)
        if liveCaching > 0 {
            media.addOption("--live-caching=\(liveCaching)")
        }
        // 解码方式：auto 不传参（VLC 自己挑），hard 强制硬解，soft 强制软解。
        // 有些老设备硬解会花屏或卡住，用户需要能手动切软解。
        switch decodeMode {
        case "hard":
            media.addOption("--avcodec-hw=any")
        case "soft":
            media.addOption("--avcodec-hw=none")
        default:
            break
        }
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
        #if canImport(MobileVLCKit)
        if let p = context.coordinator.player as? VLCMediaPlayer {
            // VLCKit 的 videoAspectRatio 是 C 字符串（char *），不是 Swift String，
            // 必须转成 UnsafeMutablePointer<CChar>；传 nil 表示"跟随源"。
            switch aspect {
            case "16:9": p.videoAspectRatio = strdup("16:9")
            case "4:3": p.videoAspectRatio = strdup("4:3")
            case "fill":
                // 拉伸铺满：libVLC 只有「显示比例」一个入口，没有 Android 的
                // ScaleType.SURFACE_FILL。把显示比例设成**容器**自己的比例，
                // 等价于拉满容器（画面按容器比例拉伸）。
                // 注意：容器尺寸在这里取的是当前 bounds；转屏会重走 updateUIView
                // （sizeClass 变化 → 外层 body 重算），所以不会长期停在旧比例。
                let b = uiView.bounds.size
                if b.width >= 2, b.height >= 2 {
                    p.videoAspectRatio = strdup(String(format: "%.0f:%.0f", b.width, b.height))
                } else {
                    p.videoAspectRatio = nil
                }
            default:
                // auto / zoom：VLCKit 3.5 没有暴露裁剪几何（Android 的
                // ScaleType.SURFACE_ZOOM 用不上），「裁切铺满」在这条路只能是
                // 按源比例适配。AVPlayer 那条路支持完整的裁切铺满。
                p.videoAspectRatio = nil
            }
        }
        #endif
        // 解码方式是在 buildMedia() 里烘进 media 的选项（--avcodec-hw=any/none），
        // 改它必须重建 media。这里以前只看 URL，于是"设置里切硬解/软解"永远不生效，
        // 得换台才会变——和 Android 端当初那个 bug 一样。
        let decodeChanged = context.coordinator.currentDecode != decodeMode
        guard context.coordinator.currentURL != url || decodeChanged else { return }
        context.coordinator.currentURL = url
        context.coordinator.currentDecode = decodeMode
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
        /// 当前 media 用的解码方式。改解码要重建 media，只有 URL 变才重建是不够的。
        var currentDecode: String = ""

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
    /// 解码方式：占位实现不使用，仅为与 VLCKit 版的签名保持一致。
    var decodeMode: String = "auto"
    /// 网络缓冲（毫秒）：占位实现不使用，仅为签名一致。
    var bufferMs: Int = 1500
    /// 直播主动延迟（毫秒）：占位实现不使用，仅为签名一致。
    var liveDelayMs: Int = 0
    /// 源配置里指定的 User-Agent（空 = 用 VLC 默认）
    var userAgent: String = ""
    /// 这条线路的完整请求头（R09），占位实现不使用，仅为保持签名一致。
    var headers: [String: String] = [:]
    /// 控制条通过它拿到底层播放器
    var control: VLCControl? = nil
    /// 画面比例：占位实现不使用，仅为签名一致。
    var aspect: String = "auto"
    var onState: ((VLCPlaybackState) -> Void)?

    var body: some View {
        Color.black
    }

    static var isAvailable: Bool { false }
}

#endif
