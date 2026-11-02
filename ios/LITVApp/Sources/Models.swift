import Foundation

// 与服务端 docs/design/01-API.md 严格对齐。
// 注意：服务端用 snake_case，这里统一用 CodingKeys 映射，不改服务端。
//
// R07：Swift **合成**的 Decodable 对非可选属性仍然要求 key 存在。
// 属性上写 `= 默认值` 只是 Swift 内部的初始化值，不是 JSON 缺省解码规则：
// 少一个键就直接 throw，整个响应在 ApiClient.send 里变成"解析失败"。
// 所以每个 DTO 都写了显式 `init(from:)`，逐字段 decodeIfPresent 回落到默认值。
//
// 这些 `init(from:)` 一律放在 extension 里。写进结构体本体的话，编译器不再合成
// 成员初始化器，`ServerInfo()` / `DeviceInfo()` / `PlayInfo.PlayChannel()` 这类
// 默认构造调用会一起编译不过（Bootstrap 的属性默认值就用了它们）。

// MARK: - 缺省字段解码

private extension KeyedDecodingContainerProtocol {
    /// 取可缺省字段：键不存在或显式 null 时回落到默认值。
    ///
    /// 只放宽"键不存在/null"，不放宽类型：类型不匹配照样 throw，
    /// 免得把真正的协议不一致（服务端把数字改成字符串之类）静默吞掉。
    ///
    /// 调用处一律给**写明类型**的回落值（`Int64(0)`、`[Channel]()`）而不是裸字面量：
    /// 裸 `0` 在无上下文时会默认推断成 `Int`，赋给 `Int64` 属性就编译不过。
    func litv<T: Decodable>(_ key: Key, _ fallback: T) throws -> T {
        try decodeIfPresent(T.self, forKey: key) ?? fallback
    }
}

// MARK: - 频道

/// 服务端来源：server/internal/store/models.go 的 Channel（json tag 与下面一致），
/// 由 handlers_client.go 的 tvBootstrap / tvEPG / tvChannels 下发。
struct Channel: Codable, Identifiable, Hashable {
    /// 主键。服务端一定会带，缺了就退化成 0
    ///（宁可列表里 id 重复，也不要整页解析失败导致进不去首页）。
    let id: Int64
    var number: Int = 0
    var name: String = ""
    var group: String = ""
    var logo: String = ""
    var tvgId: String = ""
    var epgId: String = ""
    var enabled: Bool = true
    var sort: Int = 0
    var streamCount: Int = 0

    enum CodingKeys: String, CodingKey {
        case id, number, name, group, logo, enabled, sort
        case tvgId = "tvg_id"
        case epgId = "epg_id"
        case streamCount = "stream_count"
    }
}

extension Channel {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.litv(.id, Int64(0))
        number = try c.litv(.number, Int(0))
        name = try c.litv(.name, "")
        group = try c.litv(.group, "")
        logo = try c.litv(.logo, "")
        tvgId = try c.litv(.tvgId, "")
        epgId = try c.litv(.epgId, "")
        enabled = try c.litv(.enabled, true)
        sort = try c.litv(.sort, Int(0))
        streamCount = try c.litv(.streamCount, Int(0))
    }
}

/// 服务端来源：store.ListGroups（repo.go）直接返回 map{"name","count"}。
struct ChannelGroup: Codable, Identifiable, Hashable {
    var id: String { name }
    var name: String
    var count: Int

    enum CodingKeys: String, CodingKey {
        case name, count
    }
}

extension ChannelGroup {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.litv(.name, "")
        count = try c.litv(.count, Int(0))
    }
}

// MARK: - 节目单

/// 服务端来源：server/internal/store/models.go 的 EPGProgram，
/// 由 handlers_client.go 的 tvEPG 放进 {"channel":…,"programs":[…]} 的 programs 里。
struct EpgProgram: Codable, Identifiable, Hashable {
    let id: Int64
    var epgChannelId: Int64 = 0
    var startAt: Int64 = 0
    var endAt: Int64 = 0
    var title: String = ""
    var description: String = ""
    var category: String = ""

    enum CodingKeys: String, CodingKey {
        case id, title, description, category
        case epgChannelId = "epg_channel_id"
        case startAt = "start_at"
        case endAt = "end_at"
    }

    var isNow: Bool {
        let now = Int64(Date().timeIntervalSince1970)
        return startAt <= now && endAt > now
    }

    var timeText: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        let s = f.string(from: Date(timeIntervalSince1970: TimeInterval(startAt)))
        return s
    }
}

extension EpgProgram {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.litv(.id, Int64(0))
        epgChannelId = try c.litv(.epgChannelId, Int64(0))
        startAt = try c.litv(.startAt, Int64(0))
        endAt = try c.litv(.endAt, Int64(0))
        title = try c.litv(.title, "")
        description = try c.litv(.description, "")
        category = try c.litv(.category, "")
    }
}

/// `/api/v1/tv/epg/{id}` 的真实外壳。
///
/// 服务端 handlers_client.go 的 tvEPG 有两个分支，data 都是**对象**而不是数组：
///   频道未关联 EPG：{"channel":{…},"programs":[],"note":"该频道未关联 EPG"}
///   正常：         {"channel":{…},"programs":[{…}]}
/// 历史上 iOS 按 `[EpgProgram]` 解数组，形状对不上，节目单永远是空。
struct EpgResponse: Codable {
    /// 节目单所属频道，服务端给的是完整的 store.Channel。
    var channel: Channel?
    /// 当天节目。ProgramsByXmltvID 返回 `[]EPGProgram{}`，所以通常是 [] 而不是 null。
    var programs: [EpgProgram] = []
    /// 仅"未关联 EPG"分支才有，用来把"没配 EPG"和"当天没数据"区分开。
    var note: String = ""

    enum CodingKeys: String, CodingKey {
        case channel, programs, note
    }
}

extension EpgResponse {
    init(from decoder: Decoder) throws {
        // 先试对象外壳。取不到容器说明 data 整个不是对象（旧文档写的裸数组），
        // 走下面的兜底。
        if let c = try? decoder.container(keyedBy: CodingKeys.self) {
            channel = try c.decodeIfPresent(Channel.self, forKey: .channel)
            programs = try c.litv(.programs, [EpgProgram]())
            note = try c.litv(.note, "")
            return
        }
        // 兼容旧形状：docs/design/01-API.md 里还写着 data 是
        // `[{start,end,title,desc}]` 裸数组（文档没跟上 handler 的改动）。
        // 只有"data 不是对象"才会走到这里，不是在放宽未知字段校验。
        let one = try decoder.singleValueContainer()
        programs = try one.decode([EpgProgram].self)
        channel = nil
        note = ""
    }
}

// MARK: - 首页 / 设备

/// 服务端来源：tvBootstrap 的 now 数组（map{"channel_id","title","start","end"}）。
struct NowPlaying: Codable {
    var channelId: Int64 = 0
    var title: String = ""
    var start: Int64 = 0
    var end: Int64 = 0

    enum CodingKeys: String, CodingKey {
        case title, start, end
        case channelId = "channel_id"
    }
}

extension NowPlaying {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        channelId = try c.litv(.channelId, Int64(0))
        title = try c.litv(.title, "")
        start = try c.litv(.start, Int64(0))
        end = try c.litv(.end, Int64(0))
    }
}

/// 设备配置。服务端来源：devices.config_json 反序列化后的 map。
///
/// 关键：它是**原样透传的 map**（handlers_client.go tvBootstrap 里 `var devCfg map[string]any`）。
/// 设备还没下发配置时 map 是 nil→JSON `null`，配置存在但没填字段时是 `{}`。
/// 两种情况下每个键都可能缺，所以必须逐字段给默认值。
struct DeviceConfig: Codable {
    var decode: String = "auto"
    var bufferMs: Int = 3000
    var aspect: String = "auto"
    var autoplay: Bool = true
    var engine: String = "auto"

    enum CodingKeys: String, CodingKey {
        case decode, aspect, autoplay, engine
        case bufferMs = "buffer_ms"
    }
}

extension DeviceConfig {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        decode = try c.litv(.decode, "auto")
        bufferMs = try c.litv(.bufferMs, Int(3000))
        aspect = try c.litv(.aspect, "auto")
        autoplay = try c.litv(.autoplay, true)
        engine = try c.litv(.engine, "auto")
    }
}

struct DeviceInfo: Codable {
    var id: Int64 = 0
    var name: String = ""
    /// `null`：设备还没下发配置；`{}`：配置是空 map。
    /// 前者解成 nil，后者解成"全默认值的 DeviceConfig"——两者都不是错误。
    var config: DeviceConfig?

    enum CodingKeys: String, CodingKey {
        case id, name, config
    }
}

extension DeviceInfo {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.litv(.id, Int64(0))
        name = try c.litv(.name, "")
        config = try c.decodeIfPresent(DeviceConfig.self, forKey: .config)
    }
}

struct ServerInfo: Codable {
    var version: String = ""
    var name: String = "LITV"

    enum CodingKeys: String, CodingKey {
        case version, name
    }
}

extension ServerInfo {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.litv(.version, "")
        name = try c.litv(.name, "LITV")
    }
}

/// 服务端来源：handlers_client.go 的 tvBootstrap。
/// 服务端还多给一个 sources（源列表，只有 id/name），iOS 用不到，
/// 按未知键忽略即可（解码不会因为多键报错）。
struct Bootstrap: Codable {
    var server: ServerInfo = ServerInfo()
    var device: DeviceInfo = DeviceInfo()
    var groups: [ChannelGroup] = []
    var channels: [Channel] = []
    var favorites: [Int64] = []
    var now: [NowPlaying] = []

    enum CodingKeys: String, CodingKey {
        case server, device, groups, channels, favorites, now
    }
}

extension Bootstrap {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        server = try c.litv(.server, ServerInfo())
        device = try c.litv(.device, DeviceInfo())
        groups = try c.litv(.groups, [ChannelGroup]())
        channels = try c.litv(.channels, [Channel]())
        favorites = try c.litv(.favorites, [Int64]())
        now = try c.litv(.now, [NowPlaying]())
    }
}

// MARK: - 播放地址

/// 服务端来源：handlers_client.go 的 tvPlay。
///
/// relay 分支：{"channel":…,"kind":…,"mode":"relay","url":…,"expires":…,"stream_id":…,"ua":…}
/// direct 分支：同上，另外多一个 headers（源站请求头）和 stream_id。
/// kind / mode / expires / ua / channel 都可能缺，所以全部给默认值。
struct PlayInfo: Codable {
    /// "ts" 裸 TS / "hls" 上游本来就是 HLS / "remux" 服务端转封装出来的 HLS
    var kind: String = "ts"
    var url: String = ""
    /// 签名过期时间（unix 秒）。目前 tvPlay 两个分支都会给，但不能当必存在。
    var expires: Int64 = 0
    var channel: PlayChannel = PlayChannel()
    /// 这条线路专属的 User-Agent（来自源的配置）。空 = 用默认。
    /// 源站校验 UA 时不带会 403。
    var ua: String = ""
    /// direct = 客户端直连源站；relay = 走服务端中转
    var mode: String = "relay"
    /// 这条线路的完整请求头（服务端已清洗，剔除了 Authorization / Cookie / X-Trim-*）。
    ///
    /// R09：以前只解 ua。要求 Referer / Origin 校验的源，
    /// direct 直连只带 UA 必然 403，而 relay 因为服务端带头反而正常
    /// ——表现就是"直连打不开、中转能看"，用户完全猜不到原因。
    var headers: [String: String] = [:]

    enum CodingKeys: String, CodingKey {
        case kind, url, expires, channel, ua, mode, headers
    }

    /// 交给播放内核的最终请求头。
    ///
    /// ua 是历史字段；headers 里已有 User-Agent 时以 headers 为准。
    /// 含换行的值一律丢弃，避免头注入。
    func playHeaders(defaultUA: String) -> [String: String] {
        var out = headers.filter { !$0.key.isEmpty && !$0.value.isEmpty
            && !$0.value.contains("\n") && !$0.value.contains("\r") }
        if !out.keys.contains(where: { $0.caseInsensitiveCompare("User-Agent") == .orderedSame }) {
            out["User-Agent"] = ua.isEmpty ? defaultUA : ua
        }
        return out
    }

    struct PlayChannel: Codable {
        var id: Int64 = 0
        var name: String = ""
        var logo: String = ""

        enum CodingKeys: String, CodingKey {
            case id, name, logo
        }
    }
}

extension PlayInfo {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = try c.litv(.kind, "ts")
        url = try c.litv(.url, "")
        expires = try c.litv(.expires, Int64(0))
        channel = try c.litv(.channel, PlayChannel())
        ua = try c.litv(.ua, "")
        mode = try c.litv(.mode, "relay")
        headers = try c.litv(.headers, [:])
    }
}

extension PlayInfo.PlayChannel {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.litv(.id, Int64(0))
        name = try c.litv(.name, "")
        logo = try c.litv(.logo, "")
    }
}

/// 服务端来源：handlers_client.go 的 tvStreams（map，字段名与下面一致）。
struct TvStream: Codable, Identifiable {
    let id: Int64
    var label: String = ""
    var quality: String = ""
    var priority: Int = 0
    /// 1 健康 / 0 失效 / -1 未探测
    var health: Int = -1
    var latencyMs: Int = 0

    enum CodingKeys: String, CodingKey {
        case id, label, quality, priority, health
        case latencyMs = "latency_ms"
    }

    var healthText: String {
        switch health {
        case 1: return "正常"
        case 0: return "失效"
        default: return "未检测"
        }
    }
}

extension TvStream {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.litv(.id, Int64(0))
        label = try c.litv(.label, "")
        quality = try c.litv(.quality, "")
        priority = try c.litv(.priority, Int(0))
        health = try c.litv(.health, Int(-1))
        latencyMs = try c.litv(.latencyMs, Int(0))
    }
}

// MARK: - 绑定 / 心跳 / 时移

/// 服务端来源：handlers_client.go 的 deviceRegister。
struct RegisterResult: Codable {
    var deviceId: Int64 = 0
    var token: String = ""
    var status: String = "pending"
    var hint: String = ""

    enum CodingKeys: String, CodingKey {
        case token, status, hint
        case deviceId = "device_id"
    }
}

extension RegisterResult {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try c.litv(.deviceId, Int64(0))
        // token 是设备凭据，缺了就回落成空串
        //（Session 里空 token 不会被当成"已登录"，见 Session.isBound 的注释）。
        token = try c.litv(.token, "")
        status = try c.litv(.status, "pending")
        hint = try c.litv(.hint, "")
    }
}

/// 服务端来源：handlers_client.go 的 tvTimeshift。
/// 时移未开启时只回 {"available_seconds":0,"enabled":false} —— **没有 url 键**。
/// 可被遥控的设备（GET /tv/remote/targets）。
struct RemoteTarget: Codable, Identifiable, Hashable {
    var id: Int64 = 0
    var name: String = ""
    var platform: String = ""
    var model: String = ""
    var online: Bool = false
}

struct TimeshiftInfo: Codable {
    var enabled: Bool = false
    var availableSeconds: Int = 0
    var url: String = ""

    enum CodingKeys: String, CodingKey {
        case enabled, url
        case availableSeconds = "available_seconds"
    }
}

extension TimeshiftInfo {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try c.litv(.enabled, false)
        availableSeconds = try c.litv(.availableSeconds, Int(0))
        url = try c.litv(.url, "")
    }
}
