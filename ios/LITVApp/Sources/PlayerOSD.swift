import Foundation
import SwiftUI

// 播放页 OSD 展示层。2026-11-23 从 PlayerView.swift 逐字搬出，行为零变化。
//
// 为什么无状态：两个 View 只读"已经算好的展示数据"——当前频道、时移地址、
// 技术信息文案、播放链路文案——全部以 let 传入；不持有任何
// @State/@StateObject/@Environment，也没有动作闭包（不写回父视图）。
// 属性名刻意与父视图成员同名，因此搬过来的 body 一行都不用改。

struct PlayerOSDHeader: View {
    let active: Channel
    let timeshiftURL: URL?
    let techText: String?

    var body: some View {
        HStack(spacing: 8) {
            Text(active.name).font(.headline).foregroundColor(.white)
            // 与 Android 一致：频道名后面跟「● 直播」角标
            Text(timeshiftURL == nil ? "● 直播" : "◀ 回放")
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(timeshiftURL == nil ? .red : .orange)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(Capsule().fill(Color.black.opacity(0.45)))
            Spacer()
            // 与 Android 一致：右上角显示**引擎/解码**（Android 是「系统硬解 (Media3)」），
            // 不再是分组名 —— 分组名在频道页已经有了，播放时更想知道走的哪条链路。
            Text(techText ?? active.group)
                .font(.caption)
                .foregroundColor(.gray)
        }
    }
}

/// 节目信息：当前节目 + 进度 + 下一档 + 播放链路。竖屏时放在画面**下方**。
struct PlayerOSDDetails: View {
    let timeshiftURL: URL?
    let programs: [EpgProgram]
    let routeText: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // 直播 / 回放角标 + 技术信息（分辨率 · 引擎）。
            // 安卓手机端有「● 直播 / ◀ 回放」角标，iOS 之前完全没有——
            // 用户点了回看之后看不出自己在看回放还是直播。
            HStack(spacing: 8) {
                Text(timeshiftURL == nil ? "● 直播" : "◀ 回放")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(timeshiftURL == nil ? .red : .orange)
                // techText（分辨率/引擎）已在顶部 osdHeader 显示，这里不重复
                Spacer(minLength: 0)
            }
            if let now = programs.first(where: { $0.isNow }) {
                Text("\(now.timeText) \(now.title)")
                    .font(.caption).foregroundColor(.gray).lineLimit(1)
                // 当前节目进度：和 Android 端一致，一眼看出还剩多久
                if now.endAt > now.startAt {
                    let nowTS = Int64(Date().timeIntervalSince1970)
                    let total = Double(now.endAt - now.startAt)
                    let done = Double(max(0, min(now.endAt - now.startAt, nowTS - now.startAt)))
                    ProgressView(value: done, total: total)
                        .tint(Color(red: 0.176, green: 0.831, blue: 0.749))
                }
            }
            if programs.isEmpty {
                // 空着什么都不显示，用户会以为"坏了"。
                // 明确告诉他大概率是没关联 EPG，好去管理台处理。
                Text("没有节目单（该频道可能未关联 EPG）")
                    .font(.caption2).foregroundColor(.gray.opacity(0.8))
            }
            // 下一档节目：Android 的 OSD 有这一行
            if let next = programs.first(where: { $0.startAt > Int64(Date().timeIntervalSince1970) }) {
                Text("下一档 \(next.timeText) \(next.title)")
                    .font(.caption2).foregroundColor(.gray.opacity(0.85)).lineLimit(1)
            }
            // 播放链路 + 实际用的 UA：排查源站校验时一眼能看到
            Text(routeText)
                .font(.system(size: 10)).foregroundColor(.gray.opacity(0.8)).lineLimit(1)
        }
    }
}
