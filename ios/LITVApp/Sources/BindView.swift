import SwiftUI

/// 绑定页：iOS 上唯一需要手输的页面。
struct BindView: View {
    @EnvironmentObject var session: Session
    @State private var server = ""
    @State private var code = ""
    @State private var busy = false
    @State private var pending = false

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Text("LITV")
                .font(.system(size: 40, weight: .bold))
                .foregroundColor(.white)
            Text("直播多终端 · 私有部署")
                .font(.subheadline)
                .foregroundColor(.gray)

            VStack(spacing: 12) {
                TextField("服务器地址，如 192.168.1.10:9527", text: $server)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .padding()
                    .background(Color.white.opacity(0.08))
                    .cornerRadius(10)

                TextField("绑定码（可留空，去管理台批准）", text: $code)
                    .keyboardType(.numberPad)
                    .padding()
                    .background(Color.white.opacity(0.08))
                    .cornerRadius(10)
            }
            .padding(.horizontal, 28)

            Button(action: bind) {
                HStack {
                    if busy { ProgressView().padding(.trailing, 6) }
                    Text(busy ? "处理中…" : (pending ? "等待批准" : "绑定"))
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(Color(red: 0.176, green: 0.831, blue: 0.749))
                .foregroundColor(.black)
                .cornerRadius(10)
            }
            .disabled(busy)
            .padding(.horizontal, 28)

            if let err = session.errorText {
                Text(err).foregroundColor(.red).font(.footnote)
                    .multilineTextAlignment(.center).padding(.horizontal, 28)
            }
            if !session.statusText.isEmpty {
                Text(session.statusText).foregroundColor(.gray).font(.footnote)
                    .multilineTextAlignment(.center).padding(.horizontal, 28)
            }

            Spacer()
            Text("本 App 不提供任何直播源，请自行导入并确保来源合法")
                .font(.caption2).foregroundColor(.gray.opacity(0.7))
                .multilineTextAlignment(.center).padding(.horizontal, 40).padding(.bottom, 20)
        }
        .onAppear { server = session.serverBase }
    }

    private func bind() {
        busy = true
        session.errorText = nil
        Task {
            guard await session.probe(server) else { busy = false; return }
            let bound = await session.register(code: code.isEmpty ? nil : code)
            busy = false
            if !bound {
                pending = true
                await session.waitForApproval()
                pending = false
            }
        }
    }
}
