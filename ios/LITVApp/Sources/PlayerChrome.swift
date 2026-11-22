import SwiftUI

// 播放页控件外观构造函数。2026-11-23 从 PlayerView.swift 逐字搬出，行为零变化。
//
// 为什么无状态：这三个函数只依赖入参（符号名 / 文案 / 尺寸 / 动作闭包），
// 不读任何 @State/@StateObject/@Environment，也不写父视图状态，
// 动作一律由调用方以闭包注入。留在 PlayerView 的 extension 里，
// 是为了让调用点一字不改（它们仍是同一类型的成员，只是换了文件）。

extension PlayerView {

    /// 底部小胶囊（比例 / 内核 / 解码），与 Android 的 `PhoneCtlPill` 同款。
    func pillButton(_ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 11))
                .foregroundColor(.white)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)  // 竖屏窄，不能让「比例 自动」换行
                .padding(.horizontal, 9)
                .frame(height: 30)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// 图标 + 文字两行。
    ///
    /// 2026-11-19：**用 SF Symbols，不用 Unicode 符号**。
    /// `⏮ ⏸ ⏭ ⇪` 这些在 iOS 上会被渲染成 **emoji**（自带蓝色方块底），
    /// 看起来就像给按钮加了圆底——这正是用户圈出来的「有颜色」。
    /// SF Symbols 是单色矢量，`.foregroundColor(.white)` 后就是纯白。
    func ctlButton(_ symbol: String, _ label: String, size: CGFloat,
                   _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                // 必须显式给白色：Image 不给颜色会继承 App 的 accentColor（这里是绿色），
                // 用户实测指出「图标怎么是绿的」。
                Image(systemName: symbol)
                    .font(.system(size: size))
                    .foregroundColor(.white)
                Text(label).font(.system(size: 10)).foregroundColor(.white)
            }
            .frame(minWidth: 46, minHeight: 44)
            // 命中区域必须铺满整块：否则点按钮边缘会穿透到底层「点画面 → 隐藏控件」，
            // 表现就是「点那些按钮还点不了，一点就隐藏了」（用户实测）。
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
