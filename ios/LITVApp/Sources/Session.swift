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

    /// 网络缓冲（毫秒）。0 表示交给 VLCKit 默认。对齐 Android 的 1/2/3/5/8 秒档。
    @Published var bufferMs: Int {
        didSet { defaults.set(bufferMs, forKey: "bufferMs") }
    }

    /// 直播主动延迟（毫秒，0 = 不额外落后）。对齐 Android 的 2/4/6/8/10 秒档。
    @Published var liveDelayMs: Int {
        didSet { defaults.set(liveDelayMs, forKey: "liveDelayMs") }
    }

    /// 播放内核：auto（默认）/ vlc / avplayer。与 Android 设置页的同一档语义。
    ///
    /// auto = VLCKit 可用就用它（能解 AC3/裸 TS），不可用退回 AVPlayer。
    /// 强制 avplayer 时**绝不**建 VLC 播放器：排查「VLC 有声音没画面 / 起播卡住」
    /// 这类问题时需要一个确定的对照。强制 vlc 只决定首选，失败后仍允许回退到
    /// AVPlayer（与 Android 的 `EngineChain.chainFor` 一致：强制内核打头，不砍掉兜底）。
    @Published var engineMode: String {
        didSet { defaults.set(engineMode, forKey: "engineMode") }
    }

    /// 直播列表强制不吃缓存（AVPlayer 路径会带 `Cache-Control: no-cache`）。
    ///
    /// 有些源的 m3u8 带 `max-age=86400`，但里面分片 URL 的签名只有几分钟有效：
    /// CDN 会把过期列表反复喂给播放器，分片就 0 字节 —— 表现「播一下卡一下」。
    /// 只对 AVPlayer 生效（VLCKit 只有 UA/Referer，没有自定义头的接口）。
    @Published var hlsNoCache: Bool {
        didSet { defaults.set(hlsNoCache, forKey: "hlsNoCache") }
    }

    /// 节目单每就绪一次 +1。
    ///
    /// 播放页的节目单只在「起播」和「切日期」时算，EPG 是后台下载的，
    /// 下完了没人通知它 —— 表现就是「首次打开没有节目单，换台才有」。
    /// PlayerView 盯这个值重算。
    @Published var epgVersion: Int = 0

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
        engineMode = defaults.string(forKey: "engineMode") ?? "auto"
        hlsNoCache = (defaults.object(forKey: "hlsNoCache") as? Bool) ?? true
        bufferMs = (defaults.object(forKey: "bufferMs") as? Int) ?? 1500
        liveDelayMs = (defaults.object(forKey: "liveDelayMs") as? Int) ?? 0
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
                // 凭据写不进安全存储就等于没登录：连"已绑定"状态也要一起撤掉，
                // 否则界面显示已登录、实际没有凭据，下一次请求必然 401。
                isBound = false
                defaults.set(false, forKey: "isBound")
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

    /// 服务端下发的 xmltv 地址（客户端自己下载解析，本地缓存）
    private(set) var epgUrl: String = ""
    /// 服务端是否客户端解析模式（bootstrap 的 client_parse，0/1）
    private(set) var clientParse: Bool = false

    private func applyBootstrap(_ b: Bootstrap) {
        epgUrl = b.epgUrl
        // 客户端解析模式：频道列表由客户端自己拉源解析（服务端 D1 不存频道表）
        clientParse = b.clientParse != 0
        // 拿到地址就后台拉一次：不阻塞界面，解析完本地就有节目单了。
        // 就绪后重算「正在播」——client 模式下服务端不下发 b.now，不补就是空的。
        EpgStore.shared.ensure(url: b.epgUrl) { [weak self] in
            guard let self else { return }
            self.refreshNowFromEpg()
            // 通知播放页重算节目单（它不会自己发现 EPG 好了）
            self.epgVersion += 1
        }
        channels = b.channels
        // 分组从**频道自己**算，不用服务端 b.groups：client 模式下 D1 里没有频道表，
        // 那份 groups 是过期数据（新分组不会出现、数量也不准）。Android 也是这么算的。
        rebuildGroups()
        favorites = Set(b.favorites)
        var m: [Int64: String] = [:]
        for n in b.now { m[n.channelId] = n.title }
        nowTitles = m
        statusText = "\(b.channels.count) 个频道"
        // 客户端解析模式：用自己的解析结果替换服务端给的（可能为空的）频道表。
        // iOS 目前没有设备级 dl_key，服务端会下发明文信封（v=0），decrypt 直接返回。
        if clientParse {
            ChannelStore.shared.load(session: self, dlKey: "") { [weak self] parsed in
                guard let self else { return }
                guard let p = parsed else {
                    // 失败必须说出来：以前静默 return，表现是
                    // 「列表有频道、点播报本地表未就绪」，完全看不出原因。
                    self.statusText = "客户端解析失败：\(ChannelStore.shared.lastError ?? "未知原因")"
                    return
                }
                self.channels = p.channels
                self.statusText = "客户端解析 \(p.channels.count) 个频道"
                self.rebuildGroups()
                // 频道换成本地的了：按本地节目单重新算一遍「正在播」
                self.refreshNowFromEpg()
            }
        }
        errorText = nil
    }

    /// 从频道列表重算分组：顺序按首次出现，count 按实际频道数。
    private func rebuildGroups() {
        var order: [String] = []
        var counts: [String: Int] = [:]
        for ch in channels {
            let g = ch.group
            guard !g.isEmpty else { continue }
            if counts[g] == nil { order.append(g) }
            counts[g, default: 0] += 1
        }
        groups = order.map { ChannelGroup(name: $0, count: counts[$0] ?? 0) }
    }

    /// 用本地节目单补「正在播」。
    /// client 模式下服务端不下发 `b.now`，不自己算的话频道列表右侧永远空着。
    /// 匹配规则与 Android 一致：epgId 优先，空了按频道名反查。
    func refreshNowFromEpg() {
        guard !channels.isEmpty else { return }
        let now = Int64(Date().timeIntervalSince1970)
        var m = nowTitles
        for ch in channels {
            guard let list = EpgStore.shared.programs(channel: ch, dayOffset: 0) else { continue }
            if let cur = list.first(where: { $0.startAt <= now && $0.endAt > now }) {
                m[ch.id] = cur.title
            }
        }
        if !m.isEmpty { nowTitles = m }
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
        let hit = channels.filter { $0.group == g }
        // 分组名对不上时**不要返回空**：从「服务端分组」切到「本地解析分组」
        // 后名字会有细微差异，而 selectedGroup 还留着旧值 ——
        // 那会表现成「频道都没有了」（2026-11-19 实际踩到）。
        return hit.isEmpty ? channels : hit
    }
}
