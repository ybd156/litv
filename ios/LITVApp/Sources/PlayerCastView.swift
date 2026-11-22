import Foundation
import SwiftUI

// 播放页投屏选择弹层。2026-11-23 从 PlayerView.swift 逐字搬出，行为零变化。
//
// 为什么无状态：只读 castLoading / renderers / castTip；重新搜索与发起投屏
// 以闭包注入；关闭弹层用 @Binding（写回父视图同一个 Bool）。

struct PlayerCastSheet: View {
    let castLoading: Bool
    let renderers: [DlnaRenderer]
    let castTip: String?
    @Binding var showCast: Bool
    let onReload: () async -> Void
    let onCast: (DlnaRenderer) async -> Void

    var body: some View {
        NavigationView {
            List {
                if castLoading {
                    Text("正在搜索可投屏设备…").foregroundColor(.gray)
                } else if renderers.isEmpty {
                    Text("没找到可投屏的设备\n确认电视/盒子已开机，且和 NAS 在同一个局域网")
                        .foregroundColor(.gray)
                        .font(.system(size: 13))
                }
                ForEach(renderers) { r in
                    Button {
                        Task { await onCast(r) }
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.name).foregroundColor(.primary)
                            if !r.model.isEmpty {
                                Text(r.model).font(.caption).foregroundColor(.gray)
                            }
                        }
                    }
                }
                if let tip = castTip {
                    Text(tip).font(.caption).foregroundColor(.orange)
                }
            }
            .navigationTitle("投屏到")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        Task { await onReload() }
                    } label: { Image(systemName: "arrow.clockwise") }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") { showCast = false }
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}
