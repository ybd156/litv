import Foundation
import SwiftUI
import UIKit

/// 全局状态。用 ObservableObject 而不是 TCA/Redux：
/// 这个 App 的状态很浅（频道列表 + 当前设备），引框架是负担。
@MainActor
final class Session: ObservableObject {

    // 绑定相关
    @Published var serverBase: String = UserDefaults.standard.string(forKey: "server") ?? ""
    /// 设备 bearer token。**只存 Keychain**（见 CredentialStore），UserDefaults 里不再留明文。
    ///
    /// 初始值会顺带把历史上写在 UserDefaults 的旧 token 迁移进 Keychain 并删掉旧键；
    /// 迁移或 Keychain 不可用都返回空 → 视为未登录，绝不回退明文。
    @Published var token: String = CredentialStore.loadToken()
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

    /// 最近一次播放的频道。底部「播放」tab 靠它决定显示什么：
    /// 有就继续播这个台，没有就提示去「频道」页选一个。
    @Published var lastChannel: Channel?

    /// 解码方式：auto / hard / soft。持久化，重开 App 仍生效。
    @Published var decodeMode: String =
        UserDefaults.standard.string(forKey: "decodeMode") ?? "auto" {
        didSet { UserDefaults.standard.set(decodeMode, forKey: "decodeMode") }
    }

    /// 频道页点台时发出的「请播放这个台」请求。
    ///
    /// 不直接在频道页 push 播放器：TabView 会把「播放」tab 一起保留在视图树里，
    /// 两个播放器同时存在就会**同时出声**。改成请求 → 由「播放」tab 独占播放器。
    @Published var playRequest: Channel?

    func requestPlay(_ ch: Channel) {
        lastChannel = ch
        playRequest = ch
    }

    // 状态
    @Published var loading = false
    @Published var statusText = ""
    @Published var errorText: String?

    /// 设备标识：存 Keychain，**重装/清数据后不变**（AGENTS §3：服务端据此认回原设备）。
    ///
    /// 旧实现是随机 UUID 存 UserDefaults，重装就变，每次重装都要管理员重新批准
    /// （旧注释写「和 Android 端行为一致」是错的：Android 用 ANDROID_ID 派生稳定标识）。
    ///
    /// 这里用 lazy 缓存一次：万一 Keychain 写不进去，也不能让同一个进程里
    /// 两次读拿到两个不同的 key（那会直接注册出两台"设备"）。
    private lazy var cachedDeviceKey: String = CredentialStore.loadDeviceKey()

    var deviceKey: String { cachedDeviceKey }

    var api: ApiClient { ApiClient(base: serverBase, token: token) }

    // ---------------------------------------------------------------- 绑定

    /// 探测地址是不是 LITV 服务端。
    func probe(_ input: String) async -> Bool {
        var b = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !b.lowercased().hasPrefix("http") { b = "http://" + b }
        b = b.hasSuffix("/") ? String(b.dropLast()) : b
        let previous = serverBase
        serverBase = b
        do {
            let h = try await ApiClient(base: b, token: "").health()
            let ok = (h["code"] as? Int) == 0
            if ok {
                // 换了服务端就必须丢掉旧凭据：否则下一次请求会把 A 服务器的
                // bearer token 发给 B 服务器（凭据泄漏给第三方）。
                // Android 端 Prefs.serverBase 的 setter 早就有这条守卫。
                if !previous.isEmpty && previous != b { dropCredentials() }
                UserDefaults.standard.set(b, forKey: "server")
            }
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
            // 写 Keychain；存不进安全存储就等于没登录（不回退明文、不假装成功）。
            setToken(r.token)
            if token.isEmpty {
                errorText = "系统安全存储不可用，无法保存绑定凭据，请重试"
                return false
            }
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
        setToken("")
        isBound = false
        UserDefaults.standard.set(false, forKey: "isBound")
        channels = []
        favorites = []
    }

    // ---------------------------------------------------------------- 凭据

    /// token 的唯一写入口。
    ///
    /// 刻意不用 `didSet`：写 Keychain 可能失败，失败时要把内存里的值也清空
    /// （否则会出现"界面以为已登录、实际没有凭据"的半残状态）。
    private func setToken(_ value: String) {
        if value.isEmpty || !CredentialStore.saveToken(value) {
            CredentialStore.clearToken()
            token = ""
        } else {
            token = value
        }
    }

    /// 彻底丢弃本机凭据（换服务端 / 解绑时用）。
    private func dropCredentials() {
        setToken("")
        isBound = false
        UserDefaults.standard.set(false, forKey: "isBound")
    }

    /// 按分组筛选（nil = 全部）。
    func channels(in group: String?) -> [Channel] {
        guard let g = group else { return channels }
        return channels.filter { $0.group == g }
    }
}
