import Foundation
import XCTest

/// API 编译依赖的能力位；不复制 Session/API，也不启动播放器。
enum VLCPlayerView {
    static let isAvailable = false
}

final class FixtureURLProtocol: URLProtocol {
    private static let routerLock = NSLock()
    private static var router: RequestRouter?
    private let stateLock = NSLock()
    private var stopped = false

    static func install(_ value: RequestRouter?) {
        routerLock.lock()
        router = value
        routerLock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.routerLock.lock()
        let current = Self.router
        Self.routerLock.unlock()
        guard let current = current else {
            client?.urlProtocol(self, didFailWithError: URLError(.resourceUnavailable))
            return
        }
        current.start(self)
    }

    override func stopLoading() {
        stateLock.lock()
        stopped = true
        stateLock.unlock()
    }

    func reply(_ json: String, status: Int = 200) {
        stateLock.lock()
        let active = !stopped
        stateLock.unlock()
        guard active, let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: status,
                                             httpVersion: "HTTP/1.1",
                                             headerFields: ["Content-Type": "application/json"]) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(json.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    func fail() {
        stateLock.lock()
        let active = !stopped
        stateLock.unlock()
        if active { client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost)) }
    }
}

final class HeldRequest {
    let started = XCTestExpectation(description: "controlled request started")
    private let lock = NSLock()
    private var pending: FixtureURLProtocol?

    func accept(_ request: FixtureURLProtocol) {
        lock.lock()
        pending = request
        lock.unlock()
        started.fulfill()
    }

    func reply(_ json: String) {
        lock.lock()
        let request = pending
        lock.unlock()
        XCTAssertNotNil(request, "reply requires an observed request, not a timing guess")
        request?.reply(json)
    }

    func fail() {
        lock.lock()
        let request = pending
        lock.unlock()
        XCTAssertNotNil(request)
        request?.fail()
    }
}

/// 跨 URLProtocol 回调和 MainActor 只触发一次的进度信号。
final class OnceSignal: @unchecked Sendable {
    let expectation: XCTestExpectation
    private let lock = NSLock()
    private var fired = false

    init(_ description: String) {
        expectation = XCTestExpectation(description: description)
    }

    func fire() {
        lock.lock()
        let first = !fired
        fired = true
        lock.unlock()
        if first { expectation.fulfill() }
    }
}

/// 捕获真实 ApiClient 经 URLSession 发出的请求，不手工伪造请求头。
final class RequestRouter: @unchecked Sendable {
    private struct Route: Hashable {
        let host: String
        let path: String
    }
    private let lock = NSLock()
    private var held: [Route: [HeldRequest]] = [:]
    private var responses: [Route: String] = [:]
    private var log: [URLRequest] = []
    private var observer: (@Sendable (URLRequest) -> Void)?

    func observe(_ value: @escaping @Sendable (URLRequest) -> Void) {
        lock.lock()
        observer = value
        lock.unlock()
    }

    func hold(_ host: String, _ path: String) -> HeldRequest {
        let value = HeldRequest()
        lock.lock()
        held[Route(host: host, path: path), default: []].append(value)
        lock.unlock()
        return value
    }

    func respond(_ host: String, _ path: String, _ json: String) {
        lock.lock()
        responses[Route(host: host, path: path)] = json
        lock.unlock()
    }

    var requests: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return log
    }

    func start(_ value: FixtureURLProtocol) {
        let route = Route(host: value.request.url?.host ?? "", path: value.request.url?.path ?? "")
        lock.lock()
        log.append(value.request)
        var queue = held[route] ?? []
        let waiting = queue.isEmpty ? nil : queue.removeFirst()
        held[route] = queue
        let response = responses[route] ?? Self.defaultResponse(route.path)
        let callback = observer
        lock.unlock()
        callback?(value.request)
        if let waiting = waiting { waiting.accept(value) } else { value.reply(response) }
    }

    private static func defaultResponse(_ path: String) -> String {
        if path == "/api/v1/health" { return #"{"code":0,"msg":"ok","data":{}}"# }
        return #"{"code":2003,"msg":"pending fixture","data":{}}"#
    }
}

/// 可控暂停点；计时只用作测试失败上限，复现顺序由 continuation 决定。
actor ApprovalGate {
    let firstPause = XCTestExpectation(description: "approval poll paused")
    private var count = 0
    private var waiters: [UUID: CheckedContinuation<Void, Error>] = [:]

    func pause() async throws {
        try Task.checkCancellation()
        let id = UUID()
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                waiters[id] = continuation
                count += 1
                if count == 1 { firstPause.fulfill() }
            }
        }, onCancel: {
            Task { await self.cancel(id) }
        })
        try Task.checkCancellation()
    }

    func resume() {
        let pending = waiters
        waiters.removeAll()
        for continuation in pending.values { continuation.resume() }
    }

    private func cancel(_ id: UUID) {
        waiters.removeValue(forKey: id)?.resume(throwing: CancellationError())
    }
}

@MainActor
final class MemoryCredentials {
    var token: String
    var writable = true
    private(set) var writes: [String] = []

    init(_ token: String) { self.token = token }
    func save(_ value: String) -> Bool {
        guard writable else { return false }
        token = value
        writes.append(value)
        return true
    }
    func clear() { token = "" }
}

@MainActor
final class SessionFixture {
    static let a = "https://server-a.invalid"
    static let b = "https://server-b.invalid"
    static let c = "https://server-c.invalid"
    static let tokenA = "fixture-token-a"
    static let tokenB = "fixture-token-b"
    static let bootstrap = #"{"code":0,"msg":"ok","data":{"channels":[{"id":7,"name":"fixture channel"}],"groups":[{"name":"fixture group","count":1}],"favorites":[7],"now":[{"channel_id":7,"title":"fixture title"}]}}"#
    static let pendingB = #"{"code":0,"msg":"ok","data":{"token":"fixture-token-b","status":"pending","hint":"pending fixture"}}"#
    static let boundA = #"{"code":0,"msg":"ok","data":{"token":"fixture-token-a","status":"bound","hint":"bound fixture"}}"#

    let router = RequestRouter()
    let gate = ApprovalGate()
    let credentials: MemoryCredentials
    let defaults: UserDefaults
    let transport: URLSession
    let session: Session
    private let suiteName = "C05Regression." + UUID().uuidString

    init(bound: Bool = false, token: String = SessionFixture.tokenA) {
        credentials = MemoryCredentials(token)
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.set(Self.a, forKey: "server")
        defaults.set(bound, forKey: "isBound")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FixtureURLProtocol.self]
        configuration.urlCache = nil
        transport = URLSession(configuration: configuration)
        let credentials = self.credentials
        let gate = self.gate
        session = Session(defaults: defaults, transport: transport,
                          loadToken: { credentials.token },
                          saveToken: { credentials.save($0) },
                          clearToken: { credentials.clear() },
                          loadDeviceKey: { "fixture-stable-device-key" },
                          approvalPause: { try await gate.pause() })
        FixtureURLProtocol.install(router)
    }

    func close() {
        transport.invalidateAndCancel()
        defaults.removePersistentDomain(forName: suiteName)
        FixtureURLProtocol.install(nil)
    }
}
