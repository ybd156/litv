import Foundation

/// 本地节目单：下载 xmltv → 解析 → 缓存到 Caches。
///
/// 为什么客户端自己做：服务端只下发 `epg_url`（edge 版不存节目数据，D1 按行计费），
/// 一次全量节目单几万条，放在客户端解析并缓存最省。Android 端 `XmltvEpg` 是同一套做法。
final class EpgStore {
    static let shared = EpgStore()

    private var byChannel: [String: [EpgProgram]] = [:]
    /// xmltv `<channel><display-name>` 归一化名 → id。
    ///
    /// 客户端解析模式下频道来自 m3u，很多源不写 `tvg-id`，于是 epgId 为空、
    /// 节目单永远是「暂无」。服务端入库时会拿频道名对 `<display-name>` 算 epg_id，
    /// Android 的 `XmltvEpg.idByName` 也走同一条路，这里要一致。
    private var byName: [String: String] = [:]
    private var loadedUrl = ""
    private var loading = false
    private let lock = NSLock()

    /// 缓存多久算过期（与 Android 一致：24 小时）
    private let ttl: TimeInterval = 24 * 60 * 60

    var isReady: Bool {
        lock.lock(); defer { lock.unlock() }
        return !byChannel.isEmpty
    }

    /// 按频道名反查 xmltv 频道 id：先精确匹配，再退到「最长前缀」。
    /// 例：`CCTV1综合` → `CCTV1`；`CCTV-10科教` 不会被 `CCTV1` 抢走（长度优先）。
    func idByName(_ name: String) -> String {
        let n = Self.normalizeName(name)
        guard !n.isEmpty else { return "" }
        lock.lock(); defer { lock.unlock() }
        if let hit = byName[n] { return hit }
        var bestId = ""
        var bestLen = 0
        for (k, v) in byName where !k.isEmpty && k.count > bestLen && n.hasPrefix(k) {
            bestId = v
            bestLen = k.count
        }
        return bestId
    }

    /// 频道名归一化（与 ChannelStore 同一套规则）
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

    func programs(epgId: String) -> [EpgProgram]? {
        guard !epgId.isEmpty else { return nil }
        lock.lock(); defer { lock.unlock() }
        return byChannel[epgId]
    }

    /// 按频道取某天的节目：`epgId` 优先，为空时按频道名反查。
    ///
    /// 客户端解析模式下频道来自 m3u，很多源不写 `tvg-id` —— 不反查的话
    /// 节目单永远是「该频道可能未关联 EPG」。
    func programs(channel: Channel, dayOffset: Int) -> [EpgProgram]? {
        let id = channel.epgId.isEmpty ? idByName(channel.name) : channel.epgId
        return programs(epgId: id, dayOffset: dayOffset)
    }

    /// 某一天的节目（dayOffset: 0=今天，-1=昨天）。
    func programs(epgId: String, dayOffset: Int) -> [EpgProgram]? {
        guard let all = programs(epgId: epgId) else { return nil }
        let cal = Calendar.current
        guard let day = cal.date(byAdding: .day, value: dayOffset, to: Date()) else { return all }
        let start = Int64(cal.startOfDay(for: day).timeIntervalSince1970)
        let end = start + 24 * 60 * 60
        return all.filter { $0.endAt > start && $0.startAt < end }
    }

    /// 按需加载（同一个地址只下一次；24 小时内优先用缓存）。
    /// `completion` 在数据真正就绪后于主线程回调 —— 「正在播」要靠它来重算。
    func ensure(url: String, completion: (() -> Void)? = nil) {
        guard !url.isEmpty else { return }
        lock.lock()
        if loading || url == loadedUrl {
            lock.unlock(); return
        }
        loading = true
        lock.unlock()

        Task.detached(priority: .utility) { [weak self] in
            guard let self else { return }
            defer {
                self.lock.lock(); self.loading = false; self.lock.unlock()
            }
            do {
                // ① 先试**解析结果缓存**：源 XML 有 6 MB，每次冷启动解析要几百毫秒到数秒，
                //    用户感知就是「节目单打开要加载一会」。把 parse 的结果（Codable）存成 JSON，
                //    下次直接 decode，跳过整个 XML 解析。
                var loaded: (programs: [String: [EpgProgram]], nameToId: [String: String])?
                if let j = self.parsedCacheFile(),
                   let attrs = try? FileManager.default.attributesOfItem(atPath: j.path),
                   let mtime = attrs[.modificationDate] as? Date,
                   Date().timeIntervalSince(mtime) < self.ttl,
                   let d = try? Data(contentsOf: j),
                   let cached = try? JSONDecoder().decode(ParsedCache.self, from: d) {
                    loaded = (cached.byChannel, cached.byName)
                    NSLog("EpgStore: 命中解析缓存 \(cached.byChannel.count) 个频道")
                }

                // ② 没有可用缓存才走 XML（loadData 内部还有一层 24h 的原始 XML 缓存）
                if loaded == nil {
                    let data = try await self.loadData(url: url)
                    let parsed = try EpgParser.parse(data: data)
                    loaded = parsed
                    if let j = self.parsedCacheFile(),
                       let d = try? JSONEncoder().encode(
                           ParsedCache(byChannel: parsed.programs, byName: parsed.nameToId)) {
                        try? d.write(to: j)
                    }
                }

                guard let result = loaded else { return }
                self.lock.lock()
                self.byChannel = result.programs
                self.byName = result.nameToId
                self.loadedUrl = url
                self.lock.unlock()
                NSLog("EpgStore: 节目单就绪 \(result.programs.count) 个频道 / \(result.nameToId.count) 个频道名")
                if let completion {
                    await MainActor.run { completion() }
                }
            } catch {
                NSLog("EpgStore: 加载失败 \(error.localizedDescription)")
            }
        }
    }

    // MARK: - 私有

    private func cacheFile() -> URL? {
        guard let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        else { return nil }
        return dir.appendingPathComponent("epg.xml")
    }

    /// 解析结果的缓存文件（跳过 6 MB XML 的解析）。
    private func parsedCacheFile() -> URL? {
        guard let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        else { return nil }
        return dir.appendingPathComponent("epg-parsed.json")
    }

    /// 解析结果的落盘格式。EpgProgram 本身已经是 Codable（带 CodingKeys）。
    private struct ParsedCache: Codable {
        let byChannel: [String: [EpgProgram]]
        let byName: [String: String]
    }

    private func loadData(url: String) async throws -> Data {
        if let f = cacheFile(),
           let attrs = try? FileManager.default.attributesOfItem(atPath: f.path),
           let mtime = attrs[.modificationDate] as? Date,
           Date().timeIntervalSince(mtime) < ttl,
           let cached = try? Data(contentsOf: f), cached.count > 1024 {
            return cached
        }
        guard let u = URL(string: url) else { throw NSError(domain: "epg", code: 1) }
        var req = URLRequest(url: u)
        req.timeoutInterval = 20
        let (data, _) = try await URLSession.shared.data(for: req)
        if let f = cacheFile(), data.count > 1024 {
            try? data.write(to: f)
        }
        return data
    }
}

/// 极简 xmltv 解析：只取 programme 的起止、标题、简介、分类。
private final class EpgParser: NSObject, XMLParserDelegate {
    private var out: [String: [EpgProgram]] = [:]
    private var nextId: Int64 = 1
    private var curChannel = ""
    private var curStart: Int64 = 0
    private var curEnd: Int64 = 0
    private var curTitle = ""
    private var curDesc = ""
    private var curCategory = ""
    private var inElement = ""
    private var buf = ""
    /// 当前 `<channel>` 的 id，以及「归一化频道名 → id」。
    /// 客户端解析模式下频道多半没有 tvg-id，只能靠这张表反查。
    private var curChanId = ""
    var nameToId: [String: String] = [:]

    static func parse(data: Data) throws -> (programs: [String: [EpgProgram]], nameToId: [String: String]) {
        let p = EpgParser()
        let parser = XMLParser(data: data)
        parser.delegate = p
        guard parser.parse() else {
            throw NSError(domain: "epg", code: 2, userInfo: [NSLocalizedDescriptionKey: "xml 解析失败"])
        }
        return (p.out, p.nameToId)
    }

    func parser(_ parser: XMLParser, didStartElement name: String,
                namespaceURI: String?, qualifiedName: String?,
                attributes: [String: String] = [:]) {
        switch name {
        case "channel":
            curChanId = attributes["id"] ?? ""
        case "display-name":
            inElement = name; buf = ""
        case "programme":
            curChannel = attributes["channel"] ?? ""
            curStart = Self.epoch(attributes["start"])
            curEnd = Self.epoch(attributes["stop"])
            curTitle = ""; curDesc = ""; curCategory = ""
        case "title", "desc", "category":
            inElement = name; buf = ""
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if !inElement.isEmpty { buf += string }
    }

    func parser(_ parser: XMLParser, didEndElement name: String,
                namespaceURI: String?, qualifiedName: String?) {
        switch name {
        case "display-name":
            let nm = EpgStore.normalizeName(buf)
            if !curChanId.isEmpty && !nm.isEmpty { nameToId[nm] = curChanId }
        case "title": curTitle = buf.trimmingCharacters(in: .whitespacesAndNewlines)
        case "desc": curDesc = buf.trimmingCharacters(in: .whitespacesAndNewlines)
        case "category": curCategory = buf.trimmingCharacters(in: .whitespacesAndNewlines)
        case "programme":
            if !curChannel.isEmpty && curEnd > curStart {
                let p = EpgProgram(
                    id: nextId, epgChannelId: 0,
                    startAt: curStart, endAt: curEnd,
                    title: curTitle, description: curDesc, category: curCategory)
                nextId += 1
                out[curChannel, default: []].append(p)
            }
        default: break
        }
        inElement = ""
    }

    /// xmltv 时间：`20261115160000 +0800`
    private static let fmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMddHHmmss Z"
        return f
    }()

    private static func epoch(_ s: String?) -> Int64 {
        guard let s, !s.isEmpty else { return 0 }
        if let d = fmt.date(from: s) { return Int64(d.timeIntervalSince1970) }
        // 容错：只给 yyyyMMddHHmmss（无时区）时按本地时区算
        let f2 = DateFormatter()
        f2.locale = Locale(identifier: "en_US_POSIX")
        f2.dateFormat = "yyyyMMddHHmmss"
        if let d = f2.date(from: s) { return Int64(d.timeIntervalSince1970) }
        return 0
    }
}
