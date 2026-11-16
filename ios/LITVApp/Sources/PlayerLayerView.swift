import SwiftUI
import AVFoundation

/// 纯渲染层：把 AVPlayer 的画面按「画面比例」设置显示出来。
/// 不用 SwiftUI 的 `VideoPlayer`：它是 AVKit 黑盒，不暴露 videoGravity。
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

    static func gravity(_ mode: String) -> AVLayerVideoGravity {
        switch mode {
        case "fill": return .resize
        case "zoom": return .resizeAspectFill
        default: return .resizeAspect
        }
    }
}

final class PlayerLayerUIView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}
