import Foundation
import UIKit

/// 服务端统一响应外壳：{"code":0,"msg":"ok","data":...}
private struct Envelope<T: Decodable>: Decodable {
    let code: Int
    let msg: String
    let data: T?
}

struct ApiError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// LITV 终端 API 客户端。
///
/// 用 async/await + URLSession，不引第三方库：
/// iOS 端依赖越少，侧载后出问题的概率越低。
struct ApiClient {
    /// 客户端版本号（读 Info.plist，由 project.yml 注入）。
    /// 只用于上报给服务端做展示，不参与任何逻辑判断。
    static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }

    var base: String
    var token: String

    private var decoder: JSONDecoder { JSONDecoder() }

    func url(_ path: String) -> URL? {
        // 直连模式下服务端给的是**上游绝对地址**，不能再拼 base，
        // 否则会拼成 http://nas:9527/http://源站/xxx.m3u8
        if path.hasPrefix("http://") || path.hasPrefix("https://") {
            return URL(string: path)
        }
        let b = base.hasSuffix("/") ? String(base.dropLast()) : base
        let p = path.hasPrefix("/") ? path : "/" + path
        return URL(string: b + p)
    }

    private func request(_ path: String, method: String, body: [String: Any]?) -> URLRequest? {
        guard let u = url(path) else { return nil }
        var req = URLRequest(url: u)
        req.httpMethod = method
        req.timeoutInterval = 15
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if !token.isEmpty {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body = body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return req
    }

    /// 解包 Envelope 并返回 data。
    private func send<T: Decodable>(_ req: URLRequest, as: T.Type) async throws -> T {
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw ApiError(message: "无响应")
        }
        // 先按外壳解，业务错误码也在这里拦
        let env: Envelope<T>
        do {
            env = try decoder.decode(Envelope<T>.self, from: data)
        } catch {
            throw ApiError(message: "解析失败（HTTP \(http.statusCode)）")
        }
        if env.code != 0 {
            throw ApiError(message: env.msg.isEmpty ? "错误码 \(env.code)" : env.msg)
        }
        guard let d = env.data else {
            throw ApiError(message: "空数据")
        }
        return d
    }

    // ---------------- 业务接口 ----------------

    func health() async throws -> [String: Any] {
        guard let req = request("/api/v1/health", method: "GET", body: nil) else {
            throw ApiError(message: "地址无效")
        }
        let (data, _) = try await URLSession.shared.data(for: req)
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return obj ?? [:]
    }

    /// 注册设备。
    ///
    /// model 由调用方传进来，而不是这里读 UIDevice.current：
    /// Swift 6 里 UIDevice.current 是 @MainActor 隔离的，
    /// 在这个非隔离的 async 方法里直接读会报
    /// "expression is 'async' but is not marked with 'await'"。
    func register(deviceKey: String, name: String, model: String, code: String?) async throws -> RegisterResult {
        var body: [String: Any] = [
            "device_key": deviceKey,
            "name": name,
            "platform": "ios",
            "model": model,
            // 版本号只用于管理台展示"这台设备装的是哪版"
            "app_version": ApiClient.appVersion,
        ]
        if let code = code, !code.isEmpty { body["code"] = code }
        guard let req = request("/api/v1/device/register", method: "POST", body: body) else {
            throw ApiError(message: "地址无效")
        }
        return try await send(req, as: RegisterResult.self)
    }

    func bootstrap() async throws -> Bootstrap {
        guard let req = request("/api/v1/tv/bootstrap", method: "GET", body: nil) else {
            throw ApiError(message: "地址无效")
        }
        return try await send(req, as: Bootstrap.self)
    }

    /// 取播放地址。
    ///
    /// `fmt=hls` 的语义是"我不想要裸 TS，请服务端转封装成 HLS"：
    /// AVPlayer 播不了裸 MPEG-TS，服务端收到这个参数后会用 ffmpeg 把裸 TS 源
    /// 转封装成 HLS（只转封装不转码）。
    ///
    /// - Parameter direct: 让服务端把**上游原始地址**直接给回来，客户端自己连源站。
    ///   省掉 NAS 的带宽和 CPU。注意：AVPlayer 播不了裸 TS，所以只有装了 VLCKit
    ///   才敢要直连；否则仍然要求服务端转封装成 HLS。
    func play(channelId: Int64, streamId: Int64 = 0, direct: Bool = false) async throws -> PlayInfo {
        var q: [String] = []
        // R08：只有 AVPlayer 路径必须 HLS。VLCKit 底层是 FFmpeg，裸 TS 能直接解。
        // 所以"直连 + 有 VLCKit"这一种组合才不带 fmt=hls。
        if !direct || !VLCPlayerView.isAvailable {
            q.append("fmt=hls")
        }
        if streamId > 0 { q.append("stream_id=\(streamId)") }
        if direct { q.append("direct=1") }
        let path = "/api/v1/tv/play/\(channelId)?" + q.joined(separator: "&")
        guard let req = request(path, method: "GET", body: nil) else {
            throw ApiError(message: "地址无效")
        }
        return try await send(req, as: PlayInfo.self)
    }

    /// 专门给 AVPlayer 用的地址：**强制 relay + HLS**。
    ///
    /// VLC 起播失败降级到 AVPlayer 时必须走这里重新取一次地址：
    /// 手里那个 VLC 用的地址可能是直连源站的裸 TS，
    /// 直接喂给 AVPlayer 只会黑屏，而且我们已经绕过了服务端的转封装。
    /// 这里 `direct: false` 会稳定地带 `fmt=hls`，服务端据此转封装。
    ///
    /// 注意：服务端没配 ffmpeg 时 `kind` 仍然是 "ts"（见 handlers_client.go 的 tvPlay），
    /// 调用方必须自己检查 `kind`，不能假定这里一定拿到 HLS。
    func playForAVPlayer(channelId: Int64, streamId: Int64 = 0) async throws -> PlayInfo {
        try await play(channelId: channelId, streamId: streamId, direct: false)
    }

    func streams(channelId: Int64) async throws -> [TvStream] {
        guard let req = request("/api/v1/tv/streams/\(channelId)", method: "GET", body: nil) else {
            throw ApiError(message: "地址无效")
        }
        return try await send(req, as: [TvStream].self)
    }

    /// 取当天节目单。
    ///
    /// 服务端 data 是对象不是数组（handlers_client.go 的 tvEPG）：
    ///   {"channel":{…},"programs":[{…}]}                       正常
    ///   {"channel":{…},"programs":[],"note":"该频道未关联 EPG"}  未关联 EPG
    /// 所以按 EpgResponse 解外壳，再把 programs 摊平成数组给界面用。
    /// 未关联 EPG 或当天没节目时返回空数组（不是错误）。
    func epg(channelId: Int64, day: Int = 0) async throws -> [EpgProgram] {
        guard let req = request("/api/v1/tv/epg/\(channelId)?day=\(day)", method: "GET", body: nil) else {
            throw ApiError(message: "地址无效")
        }
        let r = try await send(req, as: EpgResponse.self)
        return r.programs
    }

    func favorite(channelId: Int64, on: Bool) async throws {
        guard let req = request("/api/v1/tv/favorite", method: "POST",
                                body: ["channel_id": channelId, "on": on]) else {
            throw ApiError(message: "地址无效")
        }
        _ = try await send(req, as: EmptyResponse.self)
    }

    func heartbeat(channelId: Int64) async {
        guard let req = request("/api/v1/tv/heartbeat", method: "POST",
                                body: ["playing_channel": channelId, "state": "playing",
                                       "app_version": ApiClient.appVersion]) else { return }
        _ = try? await send(req, as: HeartbeatResult.self)
    }

    /// 可被遥控的设备列表（不含自己）。
    func remoteTargets() async throws -> [RemoteTarget] {
        guard let req = request("/api/v1/tv/remote/targets", method: "GET", body: nil) else {
            throw ApiError(message: "地址无效")
        }
        return try await send(req, as: [RemoteTarget].self)
    }

    /// 发一个按键指令给目标设备。
    ///
    /// key 取值见服务端 remote.Command：up/down/left/right/ok/back/menu/home/
    /// volume_up/volume_down，以及数字 "0"…"9"。
    func remoteKey(deviceId: Int64, key: String) async throws {
        guard let req = request("/api/v1/tv/remote/\(deviceId)", method: "POST",
                                body: ["type": "key", "key": key]) else {
            throw ApiError(message: "地址无效")
        }
        _ = try await send(req, as: EmptyResponse.self)
    }

    /// 让目标设备直接播某个频道。
    func remotePlay(deviceId: Int64, channelId: Int64) async throws {
        guard let req = request("/api/v1/tv/remote/\(deviceId)", method: "POST",
                                body: ["type": "play", "channel_id": channelId]) else {
            throw ApiError(message: "地址无效")
        }
        _ = try await send(req, as: EmptyResponse.self)
    }

    func timeshift(channelId: Int64) async throws -> TimeshiftInfo {
        guard let req = request("/api/v1/tv/timeshift/\(channelId)", method: "GET", body: nil) else {
            throw ApiError(message: "地址无效")
        }
        return try await send(req, as: TimeshiftInfo.self)
    }
}

/// 服务端有些接口只返回 {"saved":true} 之类，这里用空结构接住。
struct EmptyResponse: Codable {}

struct HeartbeatResult: Codable {
    var configVersion: Int64 = 0
    var serverTime: Int64 = 0

    enum CodingKeys: String, CodingKey {
        case configVersion = "config_version"
        case serverTime = "server_time"
    }
}
