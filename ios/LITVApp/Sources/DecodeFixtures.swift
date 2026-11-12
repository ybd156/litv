import Foundation

// R07 回归 fixture。
//
// 这里的 JSON 全部照抄真实 handler 的输出形状（字段名逐个对照 Go 侧
// store 模型的 json tag 和 handlers_client.go 里 map[string]any 的键），
// 不是手写的"理想响应"。用途是把"缺键即 throw"这个老毛病钉住：
// 每条 fixture 都跑一次 JSONDecoder，任何一条解不开就把它收集成失败项。
//
// 为什么不做成 XCTest target：ios/project.yml 里没有 test target，
// 加一个要改工程描述文件（超出本任务的写范围，也改不动 CI）。
// 因此这里提供 `check()`，由 LITVApp 在 DEBUG 下开局跑一次并把失败打成日志。
// 函数体**不加** #if DEBUG，所以 Release 编译也会编到它，
// 至少保证这些解码路径和 fixture 本身能过 Swift 编译器。

/// 服务端响应 fixture 集合。
enum DecodeFixtures {

    // MARK: - 来源：handlers_client.go tvBootstrap

    /// 新设备、设备配置还没下发（devices.config_json 默认 `'{}'` → devCfg 是空 map）。
    /// 服务端还会给 `sources`，iOS 用不到，顺便验证多余键被忽略而不是报错。
    static let bootstrapEmptyConfig = #"""
    {"code":0,"msg":"ok","data":{
      "server":{"version":"0.1.2","name":"LITV"},
      "device":{"id":7,"name":"iPhone","config":{}},
      "groups":[{"name":"央视","count":2}],
      "channels":[
        {"id":1,"number":1,"name":"CCTV-1","group":"央视","logo":"","tvg_id":"cctv1",
         "epg_id":"cctv1","enabled":true,"sort":1,"source_id":3,"stream_count":4,"created_at":1700000000}
      ],
      "favorites":[1],
      "now":[{"channel_id":1,"title":"新闻联播","start":1700000000,"end":1700001800}],
      "sources":[{"id":3,"name":"自建源"}]
    }}
    """#

    /// 同一 handler 的最瘦形态：config 是 null，groups / favorites / now 都没给。
    /// tvBootstrap 里 `var devCfg map[string]any` 未赋值时 JSON 就是 null。
    static let bootstrapSparse = #"""
    {"code":0,"msg":"ok","data":{
      "server":{"version":"0.1.2"},
      "device":{"id":9,"name":"iPad","config":null},
      "channels":[]
    }}
    """#

    // MARK: - 来源：handlers_client.go tvPlay

    /// direct 分支：url 是上游原始地址，多带 headers，这里故意是裸 TS。
    static let playDirectTs = #"""
    {"code":0,"msg":"ok","data":{
      "channel":{"id":1,"name":"CCTV-1","logo":""},
      "kind":"ts","mode":"direct",
      "url":"http://upstream.example/live/1.ts",
      "ua":"VLC/3.0.18 LibVLC/3.0.18",
      "headers":{"User-Agent":"VLC/3.0.18 LibVLC/3.0.18"},
      "expires":1700000000,"stream_id":9
    }}
    """#

    /// relay 分支：上游裸 TS + 服务端配了 ffmpeg → kind 变 remux，url 是相对路径。
    static let playRelayRemux = #"""
    {"code":0,"msg":"ok","data":{
      "channel":{"id":1,"name":"CCTV-1","logo":""},
      "kind":"remux","mode":"relay",
      "url":"/api/live/1/remux.m3u8?sig=abc&exp=1700000000&dk=devkey",
      "expires":1700000000,"stream_id":9,"ua":""
    }}
    """#

    /// 精简形态：没有 expires / ua / mode / channel。
    /// 老 PlayInfo 把 expires 当必存在，这种响应会直接 throw 掉整条播放链路。
    static let playMinimal = #"""
    {"code":0,"msg":"ok","data":{"kind":"ts","url":"http://upstream.example/live/1.ts"}}
    """#

    // MARK: - 来源：handlers_client.go tvEPG

    /// 正常分支：`ok(w, map[string]any{"channel": ch, "programs": progs})`。
    static let epgNormal = #"""
    {"code":0,"msg":"ok","data":{
      "channel":{"id":1,"number":1,"name":"CCTV-1","group":"央视","logo":"","tvg_id":"cctv1",
                 "epg_id":"cctv1","enabled":true,"sort":1,"source_id":3,"created_at":1700000000},
      "programs":[
        {"id":11,"epg_channel_id":5,"start_at":1699999200,"end_at":1700002800,
         "title":"新闻联播","description":"","category":"新闻"},
        {"id":12,"epg_channel_id":5,"start_at":1700002800,"end_at":1700006400,
         "title":"焦点访谈","description":"","category":"新闻"}
      ]
    }}
    """#

    /// 未关联 EPG 分支：多一个 note，programs 是空数组（不是 null）。
    static let epgNoMapping = #"""
    {"code":0,"msg":"ok","data":{
      "channel":{"id":2,"number":2,"name":"卫视","group":"卫视","logo":"","tvg_id":"",
                 "epg_id":"","enabled":true,"sort":2,"source_id":3,"created_at":1700000000},
      "programs":[],"note":"该频道未关联 EPG"
    }}
    """#

    /// 兼容分支：docs/design/01-API.md 里还写着 data 是 `[{start,end,title,desc}]` 裸数组。
    /// 服务端早已改成对象，保留一条旧形状 fixture 确认兜底分支没坏。
    static let epgLegacyArray = #"""
    {"code":0,"msg":"ok","data":[
      {"id":11,"epg_channel_id":5,"start_at":1699999200,"end_at":1700002800,
       "title":"新闻联播","description":"","category":"新闻"}
    ]}
    """#

    // MARK: - 来源：handlers_client.go tvTimeshift / tvStreams

    /// 时移未开启：只有 enabled=false 和 available_seconds=0，**没有 url 键**。
    static let timeshiftDisabled = #"""
    {"code":0,"msg":"ok","data":{"available_seconds":0,"enabled":false}}
    """#

    /// 线路列表：label 可能为默认"线路 N"，health=-1 表示未探测。
    static let streamsList = #"""
    {"code":0,"msg":"ok","data":[
      {"id":9,"label":"高清","quality":"1080p","priority":1,"health":1,"latency_ms":38},
      {"id":10,"label":"线路 2","quality":"","priority":5,"health":-1}
    ]}
    """#

    // MARK: - 自检

    /// 跑一遍全部 fixture。返回空数组表示全部通过。
    static func check() -> [String] {
        var fails: [String] = []

        /// 剥外壳 → 解码 → 用 verify 做字段断言。
        /// verify 返回 nil 表示这条 fixture 通过，返回字符串则是失败原因。
        func report<T: Decodable>(_ name: String, _ json: String,
                                  as type: T.Type, _ verify: (T) -> String?) {
            let payload: Data
            do {
                payload = try envelopeData(json)
            } catch {
                fails.append("\(name)：外壳剥离失败 \(error)")
                return
            }
            do {
                let v = try JSONDecoder().decode(type, from: payload)
                if let bad = verify(v) { fails.append("\(name)：\(bad)") }
            } catch {
                fails.append("\(name)：解码失败 \(error)")
            }
        }

        report("bootstrap/空配置", bootstrapEmptyConfig, as: Bootstrap.self) { b in
            if b.channels.count != 1 { return "channels 应该是 1 条，实得 \(b.channels.count)" }
            let ch = b.channels[0]
            if ch.id != 1 || ch.name != "CCTV-1" { return "channel 字段没解对：\(ch)" }
            if ch.streamCount != 4 { return "stream_count 没解对：\(ch.streamCount)" }
            if b.device.config == nil { return "config={} 应该解成 DeviceConfig 而不是 nil" }
            if b.device.config?.bufferMs != 3000 { return "buffer_ms 缺省应该回落成 3000" }
            if b.favorites != [1] { return "favorites 应该是 [1]" }
            if b.now.first?.title != "新闻联播" { return "now 没解出来" }
            return nil
        }

        report("bootstrap/最瘦形态", bootstrapSparse, as: Bootstrap.self) { b in
            if b.device.config?.decode != "auto" { return "config=null 时应拿到全默认的 DeviceConfig" }
            if !b.groups.isEmpty || !b.favorites.isEmpty || !b.now.isEmpty {
                return "缺省的 groups/favorites/now 应该是空集合"
            }
            if b.server.name != "LITV" { return "缺省 name 应该回落成 LITV" }
            return nil
        }

        report("play/direct 裸 TS", playDirectTs, as: PlayInfo.self) { p in
            if p.kind != "ts" { return "kind 应该是 ts，实得 \(p.kind)" }
            if p.mode != "direct" { return "mode 应该是 direct，实得 \(p.mode)" }
            if p.expires != 1700000000 { return "expires 没解对" }
            if p.ua.isEmpty { return "ua 没解对" }
            if p.channel.id != 1 { return "channel.id 没解对" }
            return nil
        }

        report("play/relay remux", playRelayRemux, as: PlayInfo.self) { p in
            if p.kind != "remux" { return "kind 应该是 remux" }
            if p.mode != "relay" { return "mode 应该是 relay" }
            if !p.url.hasSuffix("dk=devkey") { return "url 没解对：\(p.url)" }
            if p.ua != "" { return "ua 缺省应该是空串" }
            return nil
        }

        report("play/缺 expires", playMinimal, as: PlayInfo.self) { p in
            if p.expires != 0 { return "缺 expires 应该回落成 0" }
            if p.mode != "relay" { return "缺 mode 应该回落成 relay" }
            if p.channel.id != 0 { return "缺 channel 应该回落成空 PlayChannel" }
            return nil
        }

        report("epg/正常", epgNormal, as: EpgResponse.self) { r in
            if r.programs.count != 2 { return "programs 应该是 2 条，实得 \(r.programs.count)" }
            if r.programs[0].title != "新闻联播" { return "programs[0].title 没解对" }
            if r.programs[0].epgChannelId != 5 { return "epg_channel_id 没解对" }
            if r.channel?.id != 1 { return "channel 没解对" }
            if r.note != "" { return "正常分支不该有 note" }
            return nil
        }

        report("epg/未关联", epgNoMapping, as: EpgResponse.self) { r in
            if !r.programs.isEmpty { return "未关联 EPG 时 programs 应该是空" }
            if r.note != "该频道未关联 EPG" { return "note 没解对：\(r.note)" }
            return nil
        }

        report("epg/旧数组形状", epgLegacyArray, as: EpgResponse.self) { r in
            if r.programs.count != 1 { return "旧数组兜底没解出 1 条" }
            if r.channel != nil { return "旧数组形状下 channel 应该是 nil" }
            return nil
        }

        report("timeshift/未开启", timeshiftDisabled, as: TimeshiftInfo.self) { t in
            if t.enabled { return "enabled 应该 false" }
            if t.availableSeconds != 0 { return "available_seconds 应该是 0" }
            if t.url != "" { return "缺 url 应该回落成空串" }
            return nil
        }

        report("streams/线路列表", streamsList, as: [TvStream].self) { list in
            if list.count != 2 { return "应该 2 条线路，实得 \(list.count)" }
            if list[1].health != -1 { return "缺 health 应该回落成 -1（未检测）" }
            if list[1].latencyMs != 0 { return "缺 latency_ms 应该回落成 0" }
            if list[1].label != "线路 2" { return "label 没解对" }
            return nil
        }

        return fails
    }

    /// 把 `{"code":0,"msg":"ok","data":…}` 剥出 data 的 JSON 片段。
    ///
    /// fixture 故意保留服务端原样（含外壳），所以这里重新走一次 JSONSerialization。
    private static func envelopeData(_ json: String) throws -> Data {
        guard let raw = json.data(using: .utf8) else {
            throw FixtureError("不是合法 UTF-8")
        }
        let obj = try JSONSerialization.jsonObject(with: raw)
        guard let env = obj as? [String: Any] else {
            throw FixtureError("外壳不是对象")
        }
        guard let code = env["code"] as? Int, code == 0 else {
            throw FixtureError("fixture 的 code 应该是 0")
        }
        guard let payload = env["data"] else {
            throw FixtureError("外壳没有 data")
        }
        return try JSONSerialization.data(withJSONObject: payload)
    }

    private struct FixtureError: LocalizedError {
        let message: String
        init(_ m: String) { message = m }
        var errorDescription: String? { "fixture 错误：\(message)" }
    }
}
