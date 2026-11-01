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
    var base: String
    var token: String

    private var decoder: JSONDecoder { JSONDecoder() }

    func url(_ path: String) -> URL? {
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

    func register(deviceKey: String, name: String, code: String?) async throws -> RegisterResult {
        var body: [String: Any] = [
            "device_key": deviceKey,
            "name": name,
            "platform": "ios",
            "model": UIDevice.current.model,
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

    func play(channelId: Int64, streamId: Int64 = 0) async throws -> PlayInfo {
        var path = "/api/v1/tv/play/\(channelId)"
        if streamId > 0 { path += "?stream_id=\(streamId)" }
        guard let req = request(path, method: "GET", body: nil) else {
            throw ApiError(message: "地址无效")
        }
        return try await send(req, as: PlayInfo.self)
    }

    func streams(channelId: Int64) async throws -> [TvStream] {
        guard let req = request("/api/v1/tv/streams/\(channelId)", method: "GET", body: nil) else {
            throw ApiError(message: "地址无效")
        }
        return try await send(req, as: [TvStream].self)
    }

    func epg(channelId: Int64, day: Int = 0) async throws -> [EpgProgram] {
        guard let req = request("/api/v1/tv/epg/\(channelId)?day=\(day)", method: "GET", body: nil) else {
            throw ApiError(message: "地址无效")
        }
        return try await send(req, as: [EpgProgram].self)
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
                                body: ["playing_channel": channelId, "state": "playing"]) else { return }
        _ = try? await send(req, as: HeartbeatResult.self)
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
