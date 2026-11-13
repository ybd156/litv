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

    /// 会话代际。换服务端、解绑、重新注册都会 +1。
    ///
    /// 所有异步操作在入口记下当时的代际，`await` 回来后先比对；
    /// 不一致就丢弃结果。这是「迟到的响应不得写回新会话」的唯一机制——
    /// 没有它，慢网下连点两次绑定就能把新 token 换回旧的。
    private var epoch = 0

    /// 探测代际。乱序探测时只有最后发起的那次能提交。
    private var probeEpoch = 0

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
    ///
    /// **探测期间不改任何全局状态。** 候选地址只放在局部变量里，
    /// 健康检查也显式用空 token 发出；只有确认是 LITV 之后才原子提交。
    ///
    /// 反过来做的后果是真实发生过的：探测失败后 `serverBase` 停在候选地址，
    /// 而 token 还是原服务器的，下一次 bootstrap 就把旧 bearer 发给了第三方。
    func probe(_ input: String) async -> Bool {
        var b = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !b.lowercased().hasPrefix("http") { b = "http://" + b }
        b = b.hasSuffix("/") ? String(b.dropLast()) : b

        probeEpoch += 1
        let mine = probeEpoch
        let previous = serverBase

        do {
            // token 显式留空：健康检查绝不能带凭据去一个还没确认的地址。
            let h = try await ApiClient(base: b, token: "", transport: transport).health()
            // 取消，或被更晚发起的探测取代 → 不提交，也不改任何状态。
            if Task.isCancelled || mine != probeEpoch { return false }
            guard (h["code"] as? Int) == 0 else { return false }
            commitServerSwitch(to: b, from: previous)
            return true
        } catch {
            if Task.isCancelled || mine != probeEpoch { return false }
            errorText = "连不上：\(error.localizedDescription)"
            return false
        }
    }

    /// 提交换服务器：先让所有在途结果失效，再一次性改掉地址与凭据。
    private func commitServerSwitch(to b: String, from previous: String) {
        if b == previous {
            defaults.set(b, forKey: "server")
            return
        }
        // 先 +1：此刻起所有旧会话的异步结果都算过期。
        epoch += 1
        serverBase = b
        defaults.set(b, forKey: "server")
        // previous 为空表示「不知道原来连的是谁」，留存 token 同样不可信。
        dropCredentials()
        clearSessionData()
    }

    /// 注册设备。返回 true 表示已直接绑定，false 表示待管理员批准。
    func register(code: String?) async -> Bool {
        epoch += 1
        let mine = epoch
        // 快照本次请求的地址与凭据：await 期间 sessionBase/token 可能被改。
        let client = api
        do {
            let r = try await client.register(deviceKey: deviceKey,
                                              name: deviceName.isEmpty ? "iPhone" : deviceName,
                                              model: UIDevice.current.model,
                                              code: code)
            // 迟到的响应（期间换过服务器 / 解绑过 / 又注册了一次）一律丢弃。
            guard mine == epoch, !Task.isCancelled else { return false }
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
            guard mine == epoch else { return false }
            errorText = error.localizedDescription
            return false
        }
    }

    /// 待批准时轮询：能拉到 bootstrap 就说明放行了。
    ///
    /// 只发一次 bootstrap 并直接应用结果——先探活再取数据会多一次请求，
    /// 而且两次之间状态可能已经变了。
    func waitForApproval() async {
        for _ in 0..<60 {
            do {
                try await approvalPause()
            } catch {
                // 取消（用户离开绑定页 / 切 tab）不是超时，静默退出，不发请求也不报错。
                return
            }
            if Task.isCancelled { return }

            epoch += 1
            let mine = epoch
            do {
                let b = try await api.bootstrap()
                guard mine == epoch, !Task.isCancelled else { return }
                isBound = true
                defaults.set(true, forKey: "isBound")
                applyBootstrap(b)
                return
            } catch {
                // 2003 = 还没批准，继续轮询；期间会话已变则直接放弃。
                guard mine == epoch, !Task.isCancelled else { return }
                continue
            }
        }
        errorText = "等待批准超时，请在管理台确认这台设备"
    }

    // ---------------------------------------------------------------- 数据

    func bootstrap() {
        guard !token.isEmpty, !serverBase.isEmpty else { return }
        epoch += 1
        let mine = epoch
        loading = true
        bootstrapTask = Task {
            do {
                let b = try await api.bootstrap()
                // 旧代际的返回不得覆盖新会话的数据，也不得把新的 loading 关掉。
                guard mine == epoch else { return }
                applyBootstrap(b)
            } catch {
                guard mine == epoch else { return }
                errorText = error.localizedDescription
            }
            if mine == epoch { loading = false }
        }
    }

    private func applyBootstrap(_ b: Bootstrap) {
        channels = b.channels
        groups = b.groups
        favorites = Set(b.favorites)
        var m: [Int64: String] = [:]
        for n in b.now { m[n.channelId] = n.title }
        nowTitles = m
        statusText = "\(b.channels.count) 个频道"
        errorText = nil
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
        epoch += 1
        setToken("")
        isBound = false
        defaults.set(false, forKey: "isBound")
        clearSessionData()
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

    /// 彻底丢弃本机凭据（换服务端 / 解绑时用）。
    private func dropCredentials() {
        setToken("")
        isBound = false
        defaults.set(false, forKey: "isBound")
    }

    /// 清掉所有「属于某个服务器」的数据。换源后旧频道列表必须消失，
    /// 否则界面会显示上一个服务器的台，点进去必然失败。
    /// `deviceKey` 不在其中：设备身份跨服务器保留。
    private func clearSessionData() {
        channels = []
        groups = []
        favorites = []
        nowTitles = [:]
        lastChannel = nil
        playRequest = nil
    }

    func channels(in group: String?) -> [Channel] {
        guard let g = group else { return channels }
        return channels.filter { $0.group == g }
    }
}
