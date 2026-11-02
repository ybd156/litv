"""iOS 播放控件加文字说明，和 Android 对齐。

Android 已经改成「图标 + 文字」（上一台/暂停/下一台/静音/全屏），
iOS 这边还是纯 SF Symbol，同样认不出来。
"""
import io

p = "ios/LITVApp/Sources/PlayerView.swift"
s = io.open(p, encoding="utf-8").read()

old = """    private var controls: some View {
        HStack(spacing: 22) {
            Button { switchBy(-1) } label: {
                Image(systemName: "backward.end.fill").font(.system(size: 20))
            }
            Button { togglePause() } label: {
                Image(systemName: paused ? "play.fill" : "pause.fill").font(.system(size: 26))
            }
            Button { switchBy(+1) } label: {
                Image(systemName: "forward.end.fill").font(.system(size: 20))
            }
            Button { toggleMute() } label: {
                Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.system(size: 20))
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 22)
        .padding(.vertical, 12)
        .background(.ultraThinMaterial)
        .cornerRadius(24)
        .padding(.bottom, 18)
    }"""

new = """    private var controls: some View {
        HStack(spacing: 18) {
            ctlButton("backward.end.fill", "上一台", size: 18) { switchBy(-1) }
            ctlButton(paused ? "play.fill" : "pause.fill", paused ? "播放" : "暂停", size: 22) { togglePause() }
            ctlButton("forward.end.fill", "下一台", size: 18) { switchBy(+1) }
            ctlButton(muted ? "speaker.slash.fill" : "speaker.wave.2.fill", muted ? "取消静音" : "静音", size: 18) { toggleMute() }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
        .cornerRadius(20)
        .padding(.bottom, 18)
    }

    /// 图标 + 文字两行。只有图标的话不看说明书不知道哪个是换台、哪个是静音。
    private func ctlButton(_ icon: String, _ label: String, size: CGFloat,
                           _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: size))
                Text(label).font(.system(size: 10))
            }
            .frame(minWidth: 52)
        }
    }"""

assert old in s, "找不到 iOS controls"
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
print("iOS PlayerView: 控件已加文字")
