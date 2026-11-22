import Foundation
import SwiftUI

// 播放页节目单视图。2026-11-23 从 PlayerView.swift 逐字搬出，行为零变化。
//
// 为什么无状态：两个 View 只读 epgDay / epgTip / programs 这些展示数据；
// 需要改状态的动作（按天拉节目单、回看、设提醒、预约录制）全部以闭包注入；
// 关闭弹层用 @Binding，写回的还是父视图同一个 Bool。
// 自身不持有任何 @State/@StateObject/@Environment。

/// 天偏移 → 中文标签（纯格式化，不读任何视图状态）。
/// 原为 PlayerView.dayLabel，逻辑逐字相同，只去掉 4 空格缩进。
func dayLabel(_ d: Int) -> String {
    switch d {
    case 0: return "今天"
    case -1: return "昨天"
    case 1: return "明天"
    default: return d > 0 ? "\(d) 天后" : "\(-d) 天前"
    }
}

/// 常驻节目单：视频下方直接铺开，按天切换。
///
/// 与 Android 手机端对齐——那边节目单是常驻面板，不是按钮弹出来的。
/// 全屏时仍然用 sheet（画面优先）。
struct PlayerLiveEpgPanel: View {
    let epgDay: Int
    let epgTip: String?
    let programs: [EpgProgram]
    let onLoadEpg: (Int) async -> Void
    let onReplay: (EpgProgram) async -> Void

    var body: some View {
        VStack(spacing: 0) {
            // 与 Android 一致：**6 天日期行**（今天 ±3），点哪个看哪天。
            // 之前只有「< 今天 >」左右箭头，看不到有哪些日期可选。
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 18) {
                    ForEach([-3, -2, -1, 0, 1, 2], id: \.self) { d in
                        Button { Task { await onLoadEpg(d) } } label: {
                            Text(dayLabel(d))
                                .font(.system(size: 14, weight: epgDay == d ? .semibold : .regular))
                                .foregroundColor(epgDay == d
                                                 ? Color(red: 0.176, green: 0.831, blue: 0.749)
                                                 : .gray)
                        }
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.vertical, 8)

            if let tip = epgTip {
                Text(tip)
                    .font(.caption)
                    .foregroundColor(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
            }

            if programs.isEmpty {
                Text("暂无节目单")
                    .font(.caption)
                    .foregroundColor(.gray)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 24)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(programs) { p in
                            HStack {
                                Text(p.timeText)
                                    .foregroundColor(.gray)
                                    .font(.caption)
                                    .frame(width: 44, alignment: .leading)
                                Text(p.title)
                                    .foregroundColor(p.isNow ? .green : .primary)
                                Spacer()
                                // 已经播完的节目给一个「回看」入口。
                                // 安卓从节目单行就能直接回看，iOS 之前只能点顶栏整台时移。
                                if p.endAt <= Int64(Date().timeIntervalSince1970) {
                                    Button {
                                        Task { await onReplay(p) }
                                    } label: {
                                        Text("回看")
                                            .font(.caption2)
                                            .foregroundColor(Color(red: 0.176, green: 0.831, blue: 0.749))
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

/// 节目单弹层：支持按天切换，长按节目可以设开播提醒 / 预约录制。
struct PlayerEpgSheet: View {
    let active: Channel
    let epgDay: Int
    let epgTip: String?
    let programs: [EpgProgram]
    @Binding var showEpg: Bool
    let onLoadEpg: (Int) async -> Void
    let onAddReminder: (EpgProgram) async -> Void
    let onAddRecording: (EpgProgram) async -> Void

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // 按天切换。IPTV 的节目单一般只给前后几天，
                // 所以这里不限范围，拉不到就是空列表。
                // 与 Android 一致：6 天日期行
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 18) {
                        ForEach([-3, -2, -1, 0, 1, 2], id: \.self) { d in
                            Button { Task { await onLoadEpg(d) } } label: {
                                Text(dayLabel(d))
                                    .font(.system(size: 14, weight: epgDay == d ? .semibold : .regular))
                                    .foregroundColor(epgDay == d
                                                     ? Color(red: 0.176, green: 0.831, blue: 0.749)
                                                     : .gray)
                            }
                        }
                    }
                    .padding(.horizontal, 20)
                }
                .padding(.vertical, 10)

                if let tip = epgTip {
                    Text(tip)
                        .font(.caption)
                        .foregroundColor(.orange)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 6)
                }

                List(programs) { p in
                    HStack {
                        Text(p.timeText)
                            .foregroundColor(.gray)
                            .font(.caption)
                            .frame(width: 44, alignment: .leading)
                        Text(p.title)
                            .foregroundColor(p.isNow ? .green : .primary)
                        Spacer()
                    }
                    .contentShape(Rectangle())
                    // 长按出菜单：只有还没播完的节目才给提醒/录制
                    .contextMenu {
                        if p.endAt > Int64(Date().timeIntervalSince1970) {
                            Button {
                                Task { await onAddReminder(p) }
                            } label: { Label("提醒我", systemImage: "bell") }

                            Button {
                                Task { await onAddRecording(p) }
                            } label: { Label("预约录制", systemImage: "record.circle") }
                        }
                    }
                }
                .listStyle(.plain)
            }
            .navigationTitle(active.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") { showEpg = false }
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}
