import Foundation

/// 本地节目单：下载 xmltv → 解析 → 缓存到 Caches。
///
/// 为什么客户端自己做：服务端只下发 `epg_url`（edge 版不存节目数据，D1 按行计费），
/// 一次全量节目单几万条，放在客户端解析并缓存最省。Android 端 `XmltvEpg` 是同一套做法。
final class EpgStore {
    static let shared = EpgStore()

    private var byChannel: [String: [EpgProgram]] = [:]
    private var loadedUrl = ""
    private var loading = false
    private let lock = NSLock()

    /// 缓存多久算过期（与 Android 一致：24 小时）
    private let ttl: TimeInterval = 24 * 60 * 60

    var isReady: Bool {
        lock.lock(); defer { lock.unlock() }
        return !byChannel.isEmpty
    }

    func programs(epgId: String) -> [EpgProgram]? {
        guard !epgId.isEmpty else { return nil }
        lock.lock(); defer { lock.unlock() }
        return byChannel[epgId]
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
    func ensure(url: String) {
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
                let data = try await self.loadData(url: url)
                let parsed = try EpgParser.parse(data: data)
                self.lock.lock()
                self.byChannel = parsed
                self.loadedUrl = url
                self.lock.unlock()
                NSLog("EpgStore: 节目单就绪 \(parsed.count) 个频道")
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

    static func parse(data: Data) throws -> [String: [EpgProgram]] {
        let p = EpgParser()
        let parser = XMLParser(data: data)
        parser.delegate = p
        guard parser.parse() else {
            throw NSError(domain: "epg", code: 2, userInfo: [NSLocalizedDescriptionKey: "xml 解析失败"])
        }
        return p.out
    }

    func parser(_ parser: XMLParser, didStartElement name: String,
                namespaceURI: String?, qualifiedName: String?,
                attributes: [String: String] = [:]) {
        switch name {
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
