import Foundation
import SwiftUI
import UIKit

/// 全局状态。用 ObservableObject 而不是 TCA/Redux：
/// 这个 App 的状态很浅（频道列表 + 当前设备），引框架是负担。
@MainActor
final class Session: ObservableObject {

    // 绑定相关
    @Published var serverBase: String
    /// 设备 bearer token。只存 Keychain，不回退到 UserDefaults 明文。
    @Published var token: String
    /// 注册后的 pending token 不能被当成已批准。
    @Published var isBound: Bool
    @Published var deviceName: String

    // 数据
    @Published var channels: [Channel] = []
    @Published var groups: [ChannelGroup] = []
    @Published var favorites: Set<Int64> = []
    @Published var nowTitles: [Int64: String] = [:]
    @Published var lastChannel: Channel?
    @Published var playRequest: Channel?

    @Published var decodeMode: String {
        didSet { defaults.set(decodeMode, forKey: "decodeMode") }
    }

    // 状态
    @Published var loading = false
    @Published var statusText = ""
    @Published var errorText: String?

    /// 默认依赖仍是真实 Keychain / URLSession；测试只替换依赖，不复制业务实现。
    private let defaults: UserDefaults
    private let transport: URLSession
    private let saveCredential: (String) -> Bool
    private let clearCredential: () -> Void
    private let loadDeviceKey: () -> String
    private let approvalPause: @Sendable () async throws -> Void

    /// 保留实际任务句柄，让回归测试能等到异步回写结束。
    private(set) var bootstrapTask: Task<Void, Never>?

    init(
        defaults: UserDefaults = .standard,
        transport: URLSession = .shared,
        loadToken: () -> String = CredentialStore.loadToken,
        saveToken: @escaping (String) -> Bool = CredentialStore.saveToken,
        clearToken: @escaping () -> Void = CredentialStore.clearToken,
        loadDeviceKey: @escaping () -> String = CredentialStore.loadDeviceKey,
        approvalPause: @escaping @Sendable () async throws -> Void = {
            try await Task.sleep(nanoseconds: 3_000_000_000)
        }
    ) {
        self.defaults = defaults
        self.transport = transport
        self.saveCredential = saveToken
        self.clearCredential = clearToken
        self.loadDeviceKey = loadDeviceKey
        self.approvalPause = approvalPause
        serverBase = defaults.string(forKey: "server") ?? ""
        token = loadToken()
        isBound = defaults.bool(forKey: "isBound")
        deviceName = defaults.string(forKey: "deviceName") ?? ""
        decodeMode = defaults.string(forKey: "decodeMode") ?? "auto"
    }

    /// 设备 key 进程内恒定；解绑和换服务器不删除这个身份。
    private lazy var cachedDeviceKey: String = loadDeviceKey()
    var deviceKey: String { cachedDeviceKey }
    var api: ApiClient { ApiClient(base: serverBase, token: token, transport: transport) }

    func requestPlay(_ ch: Channel) {
        lastChannel = ch
        playRequest = ch
    }

    // ---------------------------------------------------------------- 绑定

    /// 探测地址是不是 LITV 服务端。
    func probe(_ input: String) async -> Bool {
        var b = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !b.lowercased().hasPrefix("http") { b = "http://" + b }
        b = b.hasSuffix("/") ? String(b.dropLast()) : b
        let previous = serverBase
        serverBase = b
        do {
            let h = try await ApiClient(base: b, token: "", transport: transport).health()
            let ok = (h["code"] as? Int) == 0
            if ok {
                if !previous.isEmpty && previous != b { dropCredentials() }
                defaults.set(b, forKey: "server")
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
            let r = try await api.register(deviceKey: deviceKey,
                                           name: deviceName.isEmpty ? "iPhone" : deviceName,
                                           model: UIDevice.current.model,
                                           code: code)
            setToken(r.token)
            if token.isEmpty {
                errorText = "系统安全存储不可用，无法保存绑定凭据，请重试"
                return false
            }
            isBound = (r.status == "bound")
            defaults.set(isBound, forKey: "isBound")
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
            try? await approvalPause()
            if (try? await api.bootstrap()) != nil {
                isBound = true
                defaults.set(true, forKey: "isBound")
                bootstrap()
                return
            }
        }
        errorText = "等待批准超时，请在管理台确认这台设备"
    }

    // ---------------------------------------------------------------- 数据

    func bootstrap() {
        guard !token.isEmpty, !serverBase.isEmpty else { return }
        loading = true
        bootstrapTask = Task {
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
        defaults.set(false, forKey: "isBound")
        channels = []
        favorites = []
    }

    // ---------------------------------------------------------------- 凭据

    /// 写安全存储失败时同步清空内存，不假装登录成功。
    private func setToken(_ value: String) {
        if value.isEmpty || !saveCredential(value) {
            clearCredential()
            token = ""
        } else {
            token = value
        }
    }

    private func dropCredentials() {
        setToken("")
        isBound = false
        defaults.set(false, forKey: "isBound")
    }

    func channels(in group: String?) -> [Channel] {
        guard let g = group else { return channels }
        return channels.filter { $0.group == g }
    }
}
