import Foundation
import SwiftUI
import UIKit

/// 全局状态。用 ObservableObject 而不是 TCA/Redux：
/// 这个 App 的状态很浅（频道列表 + 当前设备），引框架是负担。
@MainActor
final class Session: ObservableObject {

    // 绑定相关
    @Published var serverBase: String = UserDefaults.standard.string(forKey: "server") ?? ""
    @Published var token: String = UserDefaults.standard.string(forKey: "token") ?? ""
    /// 是否已被管理员批准。
    ///
    /// 不能用 `token.isEmpty` 当"已登录"：设备注册后服务端立刻返回 token，
    /// 但状态还是 pending，拿它调接口一律 2003。
    @Published var isBound: Bool = UserDefaults.standard.bool(forKey: "isBound")
    @Published var deviceName: String = UserDefaults.standard.string(forKey: "deviceName") ?? ""

    // 数据
    @Published var channels: [Channel] = []
    @Published var groups: [ChannelGroup] = []
    @Published var favorites: Set<Int64> = []
    @Published var nowTitles: [Int64: String] = [:]

    // 状态
    @Published var loading = false
    @Published var statusText = ""
    @Published var errorText: String?

    /// 设备标识：装一次生成一次，重装会变（和 Android 端行为一致）
    var deviceKey: String {
        if let k = UserDefaults.standard.string(forKey: "deviceKey"), !k.isEmpty { return k }
        let k = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        UserDefaults.standard.set(k, forKey: "deviceKey")
        return k
    }

    var api: ApiClient { ApiClient(base: serverBase, token: token) }

    // ---------------------------------------------------------------- 绑定

    /// 探测地址是不是 LITV 服务端。
    func probe(_ input: String) async -> Bool {
        var b = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !b.lowercased().hasPrefix("http") { b = "http://" + b }
        b = b.hasSuffix("/") ? String(b.dropLast()) : b
        serverBase = b
        do {
            let h = try await ApiClient(base: b, token: "").health()
            let ok = (h["code"] as? Int) == 0
            if ok { UserDefaults.standard.set(b, forKey: "server") }
            return ok
        } catch {
            errorText = "连不上：\(error.localizedDescription)"
            return false
        }
    }

    /// 注册设备。返回 true 表示已直接绑定，false 表示待管理员批准。
    func register(code: String?) async -> Bool {
        do {
            // Session 是 @MainActor，在这里读 UIDevice 是安全的
            let r = try await api.register(deviceKey: deviceKey,
                                           name: deviceName.isEmpty ? "iPhone" : deviceName,
                                           model: UIDevice.current.model,
                                           code: code)
            token = r.token
            UserDefaults.standard.set(r.token, forKey: "token")
            // 只有 bound 才算真的进去了；pending 时 token 存着用于轮询，
            // 但不能让界面以为已登录（否则没批准也能进首页）。
            isBound = (r.status == "bound")
            UserDefaults.standard.set(isBound, forKey: "isBound")
            statusText = r.hint
            return isBound
        } catch {
            errorText = error.localizedDescription
            return false
        }
    }

    /// 待批准时轮询：能拉到 bootstrap 就说明放行了。
    func waitForApproval() async {
        for _ in 0..<60 {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if (try? await api.bootstrap()) != nil {
                isBound = true
                UserDefaults.standard.set(true, forKey: "isBound")
                await bootstrap()
                return
            }
        }
        errorText = "等待批准超时，请在管理台确认这台设备"
    }

    // ---------------------------------------------------------------- 数据

    func bootstrap() {
        guard !token.isEmpty, !serverBase.isEmpty else { return }
        loading = true
        Task {
            do {
                let b = try await api.bootstrap()
                channels = b.channels
                groups = b.groups
                favorites = Set(b.favorites)
                var m: [Int64: String] = [:]
                for n in b.now { m[n.channelId] = n.title }
                nowTitles = m
                statusText = "\(b.channels.count) 个频道"
                errorText = nil
            } catch {
                errorText = error.localizedDescription
            }
            loading = false
        }
    }

    func toggleFavorite(_ ch: Channel) {
        let on = !favorites.contains(ch.id)
        Task {
            do {
                try await api.favorite(channelId: ch.id, on: on)
                if on { favorites.insert(ch.id) } else { favorites.remove(ch.id) }
            } catch {
                errorText = "收藏失败：\(error.localizedDescription)"
            }
        }
    }

    func unbind() {
        token = ""
        isBound = false
        UserDefaults.standard.removeObject(forKey: "token")
        UserDefaults.standard.set(false, forKey: "isBound")
        channels = []
        favorites = []
    }

    /// 按分组筛选（nil = 全部）。
    func channels(in group: String?) -> [Channel] {
        guard let g = group else { return channels }
        return channels.filter { $0.group == g }
    }
}
