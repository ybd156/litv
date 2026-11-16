import SwiftUI
import AVFoundation

/// 纯渲染层：把 AVPlayer 的画面按「画面比例」设置显示出来。
///
/// 为什么不用 SwiftUI 的 `VideoPlayer`：它是 AVKit 的黑盒，不暴露
/// `videoGravity`，做不了设置里的 拉伸 / 裁切 / 固定比例。
/// 播放控件由 PlayerView 自己的 OSD 负责，这里只画画面。
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    /// auto / fill / zoom / 16:9 / 4:3
    let aspect: String

    func makeUIView(context: Context) -> PlayerLayerUIView {
        let v = PlayerLayerUIView()
        v.backgroundColor = .black
        v.playerLayer.player = player
        v.playerLayer.videoGravity = Self.gravity(aspect)
        return v
    }

    func updateUIView(_ uiView: PlayerLayerUIView, context: Context) {
        uiView.playerLayer.player = player
        uiView.playerLayer.videoGravity = Self.gravity(aspect)
    }

    /// 16:9 / 4:3 用 resizeAspect：AVPlayerLayer 没有"强制比例"的开关，
    /// 固定比例需要外层容器按比例约束，这里先保证不拉伸。
    static func gravity(_ mode: String) -> AVLayerVideoGravity {
        switch mode {
        case "fill": return .resize           // 拉伸铺满（会变形）
        case "zoom": return .resizeAspectFill // 保持比例裁切铺满
        default: return .resizeAspect         // 保持比例，留黑边
        }
    }
}

/// 只承载一个 AVPlayerLayer 的 UIView。
final class PlayerLayerUIView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}
