import SwiftUI
import UIKit
import QuartzCore
import AVFoundation

/// 纯渲染层：把 AVPlayer 的画面按「画面比例」设置显示出来。
/// 不用 SwiftUI 的 `VideoPlayer`：它是 AVKit 黑盒，不暴露 videoGravity。
///
/// 五种档位分两类：
///   - `fill`（拉伸铺满）/ `zoom`（裁切铺满）改的是 **videoGravity**，图层不变形；
///   - `16:9` / `4:3` 改的是 **图层本身的形状**（裁成该比例、居中、留黑边），
///     再把画面填满这个图层。
///
/// 2026-11-18 修：原来后两档落到 `default: .resizeAspect`，也就是「按源比例
/// 适配容器」——和「自动」完全一样，所以 iOS 上选 16:9/4:3 画面没有任何变化。
/// VLC 那条路走的是 `videoAspectRatio`，所以看起来"只有 VLC 生效"。
/// `AVLayerVideoGravity` 做不到"强制显示比例"（它永远尊重源比例），只能改图层尺寸。
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    /// auto / fill / zoom / 16:9 / 4:3
    let aspect: String

    func makeUIView(context: Context) -> PlayerLayerUIView {
        let v = PlayerLayerUIView()
        v.backgroundColor = .black
        v.playerLayer.player = player
        apply(aspect, to: v)
        return v
    }

    func updateUIView(_ uiView: PlayerLayerUIView, context: Context) {
        uiView.playerLayer.player = player
        apply(aspect, to: uiView)
    }

    private func apply(_ mode: String, to v: PlayerLayerUIView) {
        v.playerLayer.videoGravity = Self.gravity(mode)
        v.forcedAspect = Self.forcedAspect(mode)
        // 档位换了要重新排一次：forcedAspect 只在布局时生效
        v.setNeedsLayout()
    }

    static func gravity(_ mode: String) -> AVLayerVideoGravity {
        switch mode {
        case "fill":
            // 拉伸铺满：不保比例，直接拉满图层
            return .resize
        case "zoom":
            return .resizeAspectFill
        case "16:9", "4:3":
            // 图层已经被裁成目标比例，这里让它填满这个图层：
            // 源是变形帧（比如标称 4:3 的 H.264）时也会被拉成 16:9，
            // 语义与 VLC 的 `videoAspectRatio = "16:9"` 一致。
            return .resize
        default:
            return .resizeAspect
        }
    }

    /// 需要强制显示的比例；nil = 跟随源（auto / fill / zoom）。
    static func forcedAspect(_ mode: String) -> CGFloat? {
        switch mode {
        case "16:9": return 16.0 / 9.0
        case "4:3": return 4.0 / 3.0
        default: return nil
        }
    }
}

final class PlayerLayerUIView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }

    /// 非 nil 时把播放图层裁成这个宽高比并居中；四周的黑边由本视图背景补上。
    var forcedAspect: CGFloat?

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 0, bounds.height > 0 else { return }
        // 直接改 frame 会带上隐式动画，切比例时画面会「滑」过去一段
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = Self.fit(forcedAspect, in: bounds)
        CATransaction.commit()
    }

    /// 在 `rect` 里按 `aspect` 居中放一个最大的矩形。
    ///
    /// 单独拆出来是为了能直接测（`ios/Tests/PlayerAspectTests.swift`）——
    /// 比例算错的表现是「画面偏左/偏上」，和渲染管线的问题长得一模一样，
    /// 有纯函数可测就别靠肉眼看真机。
    static func fit(_ aspect: CGFloat?, in rect: CGRect) -> CGRect {
        guard let a = aspect, a > 0, rect.width > 0, rect.height > 0 else { return rect }
        if rect.width / rect.height > a {
            // 容器比目标宽 → 高度顶满，左右各留一条黑边
            let w = rect.height * a
            return CGRect(x: rect.minX + (rect.width - w) / 2, y: rect.minY,
                          width: w, height: rect.height)
        } else {
            // 容器比目标高 → 宽度顶满，上下各留一条黑边
            let h = rect.width / a
            return CGRect(x: rect.minX, y: rect.minY + (rect.height - h) / 2,
                          width: rect.width, height: h)
        }
    }
}
