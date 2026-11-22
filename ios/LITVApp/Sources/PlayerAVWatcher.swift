import AVKit
import Foundation

// AVPlayer 失败观察器。2026-11-23 从 PlayerView.swift 整段搬出。
// 纯引用类型：不依赖任何 SwiftUI 状态，只持有 KVO / 通知 token。

/// AVPlayer 的失败观察器。
///
/// 为什么要单独一个引用类型：SwiftUI 的 View 是 struct，KVO token 与
/// NotificationCenter 的观察必须**成对**摘掉，放在 @State 里的临时值很容易漏。
/// 这里由引用对象持有，stop() 与 deinit 都会摘干净（重复调用是安全的）。
///
/// 覆盖两种真实故障：
///   1. item.status == .failed —— 源站 403/404、HLS 播放列表解析失败；
///   2. AVPlayerItemFailedToPlayToEndTime —— 直播中途连接断开。
final class AVPlayerWatcher {

    private var statusObs: NSKeyValueObservation?
    private var endObs: NSObjectProtocol?

    /// 开始观察。onFail 一律在主队列回调。
    func watch(_ player: AVPlayer, onFail: @escaping (String) -> Void) {
        stop()
        guard let item = player.currentItem else { return }

        statusObs = item.observe(\.status, options: [.new, .initial]) { observed, _ in
            guard observed.status == .failed else { return }
            let why = observed.error?.localizedDescription ?? "未知错误"
            DispatchQueue.main.async { onFail("播放失败：\(why)") }
        }

        endObs = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { note in
            let err = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            onFail("播放中断：\(err?.localizedDescription ?? "连接已断开")")
        }
    }

    /// 摘掉所有观察（幂等）。必须成对调用，否则 item 释放后再回调会崩。
    func stop() {
        statusObs?.invalidate()
        statusObs = nil
        if let obs = endObs {
            NotificationCenter.default.removeObserver(obs)
            endObs = nil
        }
    }

    deinit { stop() }
}
