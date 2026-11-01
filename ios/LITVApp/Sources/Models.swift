import Foundation

// 与服务端 docs/design/01-API.md 严格对齐。
// 注意：服务端用 snake_case，这里统一用 CodingKeys 映射，不改服务端。

struct Channel: Codable, Identifiable, Hashable {
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

struct ChannelGroup: Codable, Identifiable, Hashable {
    var id: String { name }
    var name: String
    var count: Int
}

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

struct DeviceInfo: Codable {
    var id: Int64 = 0
    var name: String = ""
    var config: DeviceConfig?
}

struct ServerInfo: Codable {
    var version: String = ""
    var name: String = "LITV"
}

struct Bootstrap: Codable {
    var server: ServerInfo = ServerInfo()
    var device: DeviceInfo = DeviceInfo()
    var groups: [ChannelGroup] = []
    var channels: [Channel] = []
    var favorites: [Int64] = []
    var now: [NowPlaying] = []
}

struct PlayInfo: Codable {
    var kind: String = "ts"
    var url: String = ""
    var expires: Int64 = 0
    var channel: PlayChannel = PlayChannel()

    struct PlayChannel: Codable {
        var id: Int64 = 0
        var name: String = ""
        var logo: String = ""
    }
}

struct TvStream: Codable, Identifiable {
    let id: Int64
    var label: String = ""
    var quality: String = ""
    var priority: Int = 0
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

struct TimeshiftInfo: Codable {
    var enabled: Bool = false
    var availableSeconds: Int = 0
    var url: String = ""

    enum CodingKeys: String, CodingKey {
        case enabled, url
        case availableSeconds = "available_seconds"
    }
}
