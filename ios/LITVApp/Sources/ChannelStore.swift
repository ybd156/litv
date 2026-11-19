import CryptoKit
import Foundation

/// 客户端自己解析频道（与 Android 的 `M3uClient` 同策略）。
///
/// 服务端只下发「源 m3u 地址 + 显示规则」的加密信封（绑定设备、60 秒有效），
/// 客户端解开后自己拉 m3u、解析、按规则过滤，并把结果连同版本号缓存到磁盘。
/// 版本没变就不重下；服务端换源时 bump channels_version，这才触发更新。
///
/// 解析规则必须与 `edge/scripts/build.mjs` 一致，否则同一个台在管理台和客户端
/// 是两个 stable_key，屏蔽/改名就对不上。
final class ChannelStore {
    static let shared = ChannelStore()

    struct Parsed {
        var version: String
        var channels: [Channel]
        /// 频道 id → 直连地址（客户端模式下不再走 /tv/play）
        var urlById: [Int64: String]
        /// 频道 id → 播放头（UA / Referer）
        var headersById: [Int64: [String: String]]
    }

    private(set) var current: Parsed?
    /// 最近一次解析失败的原因。以前失败只回调 nil，界面上完全看不出来
    /// （表现是「列表有频道但点播报本地表未就绪」），排查只能靠猜。
    private(set) var lastError: String?
    private var loading = false
    private let lock = NSLock()

    func url(of channelId: Int64) -> String? {
        lock.lock(); defer { lock.unlock() }
        return current?.urlById[channelId]
    }

    func headers(of channelId: Int64) -> [String: String] {
        lock.lock(); defer { lock.unlock() }
        return current?.headersById[channelId] ?? [:]
    }

    private func cacheURL() -> URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent("channels_client.json")
    }

    /// 入口：拉信封 → 解密 → 版本比对 → 拉源解析 → 缓存。
    /// 任何一步失败都回调 nil，调用方回落到服务端下发的频道。
    func load(session: Session, dlKey: String, done: @escaping (Parsed?) -> Void) {
        lock.lock()
        if loading {
            let cur = current
            lock.unlock()
            done(cur); return
        }
        loading = true
        lock.unlock()

        Task.detached(priority: .utility) { [weak self] in
            guard let self else { return }
            let result = await self.build(session: session, dlKey: dlKey)
            self.lock.lock()
            self.loading = false
            if let r = result { self.current = r }
            self.lock.unlock()
            if let r = result { NSLog("ChannelStore: 就绪 \(r.channels.count) 个 / 版本 \(r.version)") }
            else { NSLog("ChannelStore: 失败，回落服务端频道") }
            await MainActor.run { done(result) }
        }
    }

    // MARK: - 内部

    private func build(session: Session, dlKey: String) async -> Parsed? {
        guard let env = try? await session.api.channelSrc() else {
            lastError = "拉取源配置失败（/tv/channel-src 请求出错）"
            return readCache()
        }
        guard let payload = decrypt(env: env, dlKey: dlKey) else {
            lastError = "解密源配置失败"
            return readCache()
        }

        let version = payload["version"] as? String ?? "0"
        if let cached = readCache(), cached.version == version { return cached }

        guard let urls = payload["urls"] as? [[String: Any]] else {
            lastError = "源配置里没有 urls 字段"
            return nil
        }
        let policy = payload["policy"] as? [String: Any]
        let hiddenGroups = Set((policy?["groups_hidden"] as? [String]) ?? [])
        let hiddenChannels = Set((policy?["channels_hidden"] as? [String]) ?? [])

        var merged: [String: Channel] = [:]
        var urlById: [Int64: String] = [:]
        var headersById: [Int64: [String: String]] = [:]
        var fetchFailed = 0

        for u in urls {
            guard let urlStr = u["url"] as? String else { continue }
            guard let text = try? await fetchText(urlStr,
                                                 ua: u["ua"] as? String ?? "",
                                                 referer: u["referer"] as? String ?? "")
            else { fetchFailed += 1; continue }
            Self.merge(text: text, into: &merged, urlById: &urlById, headersById: &headersById,
                       defaultUA: u["ua"] as? String ?? "",
                       defaultReferer: u["referer"] as? String ?? "")
        }

        let kept = merged.values.filter {
            !hiddenGroups.contains($0.group) && !hiddenChannels.contains($0.name)
        }
        if kept.isEmpty {
            lastError = "解析后频道为空（拉源失败 \(fetchFailed)/\(urls.count)，或全被屏蔽规则滤掉）"
            return readCache()
        }
        lastError = nil

        let keepIds = Set(kept.map { $0.id })
        urlById = urlById.filter { keepIds.contains($0.key) }
        headersById = headersById.filter { keepIds.contains($0.key) }

        let parsed = Parsed(version: version, channels: kept.sorted { $0.number < $1.number },
                            urlById: urlById, headersById: headersById)
        writeCache(parsed)
        return parsed
    }

    /// 解服务端信封：`enc` = 密文||16 字节 tag，`iv` 12 字节。
    private func decrypt(env: [String: Any], dlKey: String) -> [String: Any]? {
        if (env["v"] as? Int ?? 0) != 1 { return env }   // v=0 就是明文
        guard let ivB64 = env["iv"] as? String, let encB64 = env["enc"] as? String,
              let iv = Data(base64Encoded: ivB64), let enc = Data(base64Encoded: encB64),
              let key = Data(base64Encoded: dlKey), key.count == 32, iv.count == 12,
              enc.count > 16 else { return nil }
        let ct = enc.prefix(enc.count - 16)
        let tag = enc.suffix(16)
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv),
                                            ciphertext: ct, tag: tag)
            let plain = try AES.GCM.open(box, using: SymmetricKey(data: key))
            return try JSONSerialization.jsonObject(with: plain) as? [String: Any]
        } catch {
            NSLog("ChannelStore: 解密失败 \(error.localizedDescription)")
            return nil
        }
    }

    private func fetchText(_ url: String, ua: String, referer: String) async throws -> String {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var req = URLRequest(url: u)
        req.timeoutInterval = 20
        if !ua.isEmpty { req.setValue(ua, forHTTPHeaderField: "User-Agent") }
        if !referer.isEmpty { req.setValue(referer, forHTTPHeaderField: "Referer") }
        let (data, _) = try await URLSession.shared.data(for: req)
        return String(data: data, encoding: .utf8) ?? ""
    }

    // MARK: - 解析

    static func normalizeName(_ name: String) -> String {
        var out = ""
        for ch in name.lowercased() {
            guard let c = ch.unicodeScalars.first?.value else { continue }
            if c <= 32 || c == 92 || c == 47 || c == 124 || c == 183 { continue }
            if ch == "-" || ch == "_" || ch == "." { continue }
            out.append(ch)
        }
        return out
    }

    /// 频道 id 必须**跨进程稳定**。
    ///
    /// 以前这里是 `Int64(abs(key.hashValue) % 2_000_000_000) + 1`，而 Swift 的
    /// `hashValue` 每次进程启动都带随机种子 —— 二次启动时缓存里的 id 与本次解析
    /// 结果对不上，`url(of:)` 返回 nil，播放就回落到 `/tv/play`（那里是构建时
    /// 抓的旧域名；域名被污染时表现为「A server with the specified hostname
    /// could not be found」）。
    ///
    /// FNV-1a：同一个 key 永远得到同一个 id。
    static func stableId(_ key: String) -> Int64 {
        var h: UInt32 = 2_166_136_261
        for b in key.utf8 {
            h ^= UInt32(b)
            h = h &* 16_777_619
        }
        return Int64(h % 2_000_000_000) + 1
    }

    /// 缓存里的 id 是按哪套算法算出来的。算法一变就不认旧缓存，
    /// 否则会拿到一批永远对不上号的 id。
    /// 2 → 稳定 id；3 → 频道 headers 加入源级 UA/Referer 兜底。
    static let cacheAlgo = 3

    static func merge(text: String, into merged: inout [String: Channel],
                      urlById: inout [Int64: String],
                      headersById: inout [Int64: [String: String]],
                      defaultUA: String = "",
                      defaultReferer: String = "") {
        var name = "", tvgId = "", logo = "", group = "", ua = "", referer = ""
        var pending = false

        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#EXTM3U") { continue }
            if line.hasPrefix("#EXTINF") {
                let (head, nm) = splitExtinf(line)
                name = nm.trimmingCharacters(in: .whitespaces)
                tvgId = attr(head, "tvg-id")
                logo = attr(head, "tvg-logo")
                group = attr(head, "group-title")
                ua = ""; referer = ""
                pending = true
                continue
            }
            if line.hasPrefix("#EXTGRP:") {
                if pending && group.isEmpty {
                    group = String(line.dropFirst("#EXTGRP:".count)).trimmingCharacters(in: .whitespaces)
                }
                continue
            }
            if line.hasPrefix("#EXTVLCOPT:") {
                let opt = String(line.dropFirst("#EXTVLCOPT:".count))
                if pending, let eq = opt.firstIndex(of: "=") {
                    let k = opt[..<eq].trimmingCharacters(in: .whitespaces).lowercased()
                    let v = opt[opt.index(after: eq)...].trimmingCharacters(in: .whitespaces)
                    if k == "http-user-agent" { ua = v }
                    else if k == "http-referrer" || k == "http-referer" { referer = v }
                }
                continue
            }
            if line.hasPrefix("#") { continue }
            if !pending { continue }

            let key = tvgId.isEmpty ? "name:" + normalizeName(name) : "id:" + tvgId
            let id = Self.stableId(key)
            if merged[key] == nil {
                var ch = Channel(id: id, number: merged.count + 1, name: name,
                                 group: group.isEmpty ? "未分组" : group,
                                 logo: logo, tvgId: tvgId, epgId: tvgId, enabled: true)
                ch.number = merged.count + 1
                merged[key] = ch
                urlById[id] = line
                var h: [String: String] = [:]
                // 频道自己没写 #EXTVLCOPT 时，用**源级别**的 UA/Referer 兜底。
                // 服务端 build 也是这么做的（把源 UA 继承给每条 stream）——
                // 少了这一步，同一条流在 edge 能播、在客户端却是「默认 UA」播不了。
                let effUA = ua.isEmpty ? defaultUA : ua
                let effRef = referer.isEmpty ? defaultReferer : referer
                if !effUA.isEmpty { h["User-Agent"] = effUA }
                if !effRef.isEmpty { h["Referer"] = effRef }
                if !h.isEmpty { headersById[id] = h }
            }
            pending = false
        }
    }

    private static func splitExtinf(_ line: String) -> (String, String) {
        var inQuote = false
        var idx = line.startIndex
        while idx < line.endIndex {
            let c = line[idx]
            if c == "\"" { inQuote.toggle() }
            else if c == ",", !inQuote {
                return (String(line[..<idx]), String(line[line.index(after: idx)...]))
            }
            idx = line.index(after: idx)
        }
        return (line, "")
    }

    private static func attr(_ head: String, _ key: String) -> String {
        var rest = Substring(head)
        while let eq = rest.firstIndex(of: "=") {
            let name = rest[..<eq].trimmingCharacters(in: .whitespaces)
            var after = rest[rest.index(after: eq)...]
            guard after.hasPrefix("\"") else { rest = after; continue }
            after = after.dropFirst()
            guard let close = after.firstIndex(of: "\"") else { break }
            let value = String(after[..<close])
            if name.lowercased() == key { return value }
            rest = after[after.index(after: close)...]
        }
        return ""
    }

    private func writeCache(_ p: Parsed) {
        var arr: [[String: Any]] = []
        for ch in p.channels {
            var o: [String: Any] = ["id": ch.id, "n": ch.number, "name": ch.name,
                                    "g": ch.group, "logo": ch.logo, "tvg": ch.tvgId,
                                    "epg": ch.epgId, "url": p.urlById[ch.id] ?? ""]
            if let h = p.headersById[ch.id] { o["h"] = h }
            arr.append(o)
        }
        let root: [String: Any] = ["version": p.version, "algo": Self.cacheAlgo, "channels": arr]
        guard let url = cacheURL(),
              let data = try? JSONSerialization.data(withJSONObject: root) else { return }
        try? data.write(to: url)
    }

    private func readCache() -> Parsed? {
        guard let url = cacheURL(), let data = try? Data(contentsOf: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (root["algo"] as? Int) == Self.cacheAlgo,
              let arr = root["channels"] as? [[String: Any]], !arr.isEmpty else { return nil }
        var list: [Channel] = []
        var urlById: [Int64: String] = [:]
        var headersById: [Int64: [String: String]] = [:]
        for o in arr {
            let idNum = (o["id"] as? NSNumber)?.int64Value ?? 0
            if idNum == 0 { continue }
            list.append(Channel(id: idNum, number: (o["n"] as? NSNumber)?.intValue ?? 0,
                                name: o["name"] as? String ?? "",
                                group: o["g"] as? String ?? "未分组",
                                logo: o["logo"] as? String ?? "",
                                tvgId: o["tvg"] as? String ?? "",
                                epgId: o["epg"] as? String ?? "", enabled: true))
            if let u = o["url"] as? String, !u.isEmpty { urlById[idNum] = u }
            if let h = o["h"] as? [String: String] { headersById[idNum] = h }
        }
        return list.isEmpty ? nil
            : Parsed(version: root["version"] as? String ?? "0",
                     channels: list, urlById: urlById, headersById: headersById)
    }
}
