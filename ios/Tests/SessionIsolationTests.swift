import Foundation
import XCTest

final class SessionIsolationTests: XCTestCase {
    @MainActor
    func testCandidateProbeNeverPairsNewOriginWithOldBearer() async throws {
        let f = SessionFixture()
        defer { f.close() }
        let health = f.router.hold("server-b.invalid", "/api/v1/health")
        let probe = Task { await f.session.probe(SessionFixture.b) }
        await fulfillment(of: [health.started], timeout: 5)

        // 探测尚未返回时就发起真实业务 API；不得形成 B + A 的 bearer。
        _ = try? await f.session.api.bootstrap()
        XCTAssertEqual(f.session.serverBase, SessionFixture.a)
        assertNoOldBearerAtB(f)
        assertHealthIsAnonymous(f)
        health.fail()
        _ = await probe.value
        XCTAssertEqual(f.session.serverBase, SessionFixture.a)
        XCTAssertEqual(f.defaults.string(forKey: "server"), SessionFixture.a)
        XCTAssertEqual(f.session.token, SessionFixture.tokenA)
    }

    @MainActor
    func testSleepingApprovalCannotMoveToCandidateOrigin() async throws {
        let f = SessionFixture()
        defer { f.close() }
        let progress = OnceSignal("poll ended or actual bootstrap request observed")
        f.router.observe { request in
            if request.url?.path == "/api/v1/tv/bootstrap" { progress.fire() }
        }
        let poll = Task {
            await f.session.waitForApproval()
            progress.fire()
        }
        let paused = await f.gate.firstPause
        await fulfillment(of: [paused], timeout: 5)
        let health = f.router.hold("server-b.invalid", "/api/v1/health")
        let probe = Task { await f.session.probe(SessionFixture.b) }
        await fulfillment(of: [health.started], timeout: 5)
        await f.gate.resume()
        await fulfillment(of: [progress.expectation], timeout: 5)
        assertNoOldBearerAtB(f)
        assertHealthIsAnonymous(f)
        poll.cancel()
        await poll.value
        health.fail()
        _ = await probe.value
    }

    @MainActor
    func testNonzeroHealthKeepsActiveAndPersistedServer() async throws {
        let f = SessionFixture()
        defer { f.close() }
        f.router.respond("server-b.invalid", "/api/v1/health", #"{"code":500,"msg":"not LITV"}"#)
        let accepted = await f.session.probe(SessionFixture.b)
        XCTAssertFalse(accepted)
        XCTAssertEqual(f.session.serverBase, SessionFixture.a)
        XCTAssertEqual(f.defaults.string(forKey: "server"), SessionFixture.a)
        XCTAssertEqual(f.credentials.token, SessionFixture.tokenA)
        assertHealthIsAnonymous(f)
    }

    @MainActor
    func testCancelledProbeCannotCommitCandidateOrError() async throws {
        let f = SessionFixture()
        defer { f.close() }
        let health = f.router.hold("server-b.invalid", "/api/v1/health")
        let probe = Task { await f.session.probe(SessionFixture.b) }
        await fulfillment(of: [health.started], timeout: 5)
        probe.cancel()
        let accepted = await probe.value
        XCTAssertFalse(accepted)
        XCTAssertEqual(f.session.serverBase, SessionFixture.a)
        XCTAssertEqual(f.credentials.token, SessionFixture.tokenA)
        XCTAssertNil(f.session.errorText)
    }

    @MainActor
    func testLateRegistrationCannotReplaceNewServerCredentials() async throws {
        let f = SessionFixture()
        defer { f.close() }
        let old = f.router.hold("server-a.invalid", "/api/v1/device/register")
        let registration = Task { await f.session.register(code: nil) }
        await fulfillment(of: [old.started], timeout: 5)
        _ = await f.session.probe(SessionFixture.b)
        f.router.respond("server-b.invalid", "/api/v1/device/register", SessionFixture.pendingB)
        _ = await f.session.register(code: nil)
        old.reply(SessionFixture.boundA)
        _ = await registration.value
        XCTAssertEqual(f.session.serverBase, SessionFixture.b)
        XCTAssertEqual(f.session.token, SessionFixture.tokenB)
        XCTAssertEqual(f.credentials.token, SessionFixture.tokenB)
        XCTAssertFalse(f.session.isBound)
        XCTAssertFalse(f.credentials.writes.contains(SessionFixture.tokenA))
    }

    @MainActor
    func testLateRegistrationCannotReviveUnboundSession() async throws {
        let f = SessionFixture()
        defer { f.close() }
        let old = f.router.hold("server-a.invalid", "/api/v1/device/register")
        let registration = Task { await f.session.register(code: nil) }
        await fulfillment(of: [old.started], timeout: 5)
        let deviceKey = f.session.deviceKey
        f.session.unbind()
        old.reply(SessionFixture.boundA)
        _ = await registration.value
        XCTAssertEqual(f.session.token, "")
        XCTAssertEqual(f.credentials.token, "")
        XCTAssertFalse(f.session.isBound)
        XCTAssertFalse(f.defaults.bool(forKey: "isBound"))
        XCTAssertEqual(f.session.deviceKey, deviceKey)
        XCTAssertNil(f.session.errorText)
    }

    @MainActor
    func testSameServerReregistrationInvalidatesOlderGeneration() async throws {
        let f = SessionFixture()
        defer { f.close() }
        let old = f.router.hold("server-a.invalid", "/api/v1/device/register")
        let registration = Task { await f.session.register(code: nil) }
        await fulfillment(of: [old.started], timeout: 5)
        f.router.respond("server-a.invalid", "/api/v1/device/register", SessionFixture.pendingB)
        _ = await f.session.register(code: nil)
        old.reply(SessionFixture.boundA)
        _ = await registration.value
        XCTAssertEqual(f.session.token, SessionFixture.tokenB)
        XCTAssertEqual(f.credentials.token, SessionFixture.tokenB)
        XCTAssertFalse(f.session.isBound)
    }

    @MainActor
    func testLateBootstrapCannotOverwriteNewDataOrLoading() async throws {
        let f = SessionFixture(bound: true)
        defer { f.close() }
        let old = f.router.hold("server-a.invalid", "/api/v1/tv/bootstrap")
        f.session.bootstrap()
        let oldTask = try XCTUnwrap(f.session.bootstrapTask)
        await fulfillment(of: [old.started], timeout: 5)
        _ = await f.session.probe(SessionFixture.b)
        f.router.respond("server-b.invalid", "/api/v1/device/register", SessionFixture.boundB)
        _ = await f.session.register(code: nil)
        let fresh = f.router.hold("server-b.invalid", "/api/v1/tv/bootstrap")
        f.session.bootstrap()
        let freshTask = try XCTUnwrap(f.session.bootstrapTask)
        await fulfillment(of: [fresh.started], timeout: 5)
        f.session.channels = [Channel(id: 99)]
        f.session.statusText = "new session fixture"
        old.reply(SessionFixture.bootstrap)
        await oldTask.value
        XCTAssertEqual(f.session.channels.map(\.id), [99])
        XCTAssertEqual(f.session.statusText, "new session fixture")
        XCTAssertTrue(f.session.loading, "old completion must not stop the new request's spinner")
        fresh.reply(SessionFixture.bootstrap)
        await freshTask.value
    }

    @MainActor
    func testLateBootstrapErrorCannotOverwriteNewErrorOrLoading() async throws {
        let f = SessionFixture(bound: true)
        defer { f.close() }
        let old = f.router.hold("server-a.invalid", "/api/v1/tv/bootstrap")
        f.session.bootstrap()
        let oldTask = try XCTUnwrap(f.session.bootstrapTask)
        await fulfillment(of: [old.started], timeout: 5)
        f.session.unbind()
        f.session.errorText = "new session error fixture"
        f.session.loading = true
        old.fail()
        await oldTask.value
        XCTAssertEqual(f.session.errorText, "new session error fixture")
        XCTAssertTrue(f.session.loading)
    }

    @MainActor
    func testCancelledApprovalDoesNotReportTimeoutOrRetry() async throws {
        let f = SessionFixture()
        defer { f.close() }
        let poll = Task { await f.session.waitForApproval() }
        let paused = await f.gate.firstPause
        await fulfillment(of: [paused], timeout: 5)
        poll.cancel()
        await poll.value
        XCTAssertNil(f.session.errorText)
        XCTAssertFalse(f.session.isBound)
        XCTAssertTrue(f.router.requests.isEmpty, "cancellation at sleep must not send bootstrap")
    }

    @MainActor
    func testLateApprovalCannotReviveUnboundSession() async throws {
        let f = SessionFixture()
        defer { f.close() }
        let old = f.router.hold("server-a.invalid", "/api/v1/tv/bootstrap")
        let poll = Task { await f.session.waitForApproval() }
        let paused = await f.gate.firstPause
        await fulfillment(of: [paused], timeout: 5)
        await f.gate.resume()
        await fulfillment(of: [old.started], timeout: 5)
        f.session.unbind()
        old.reply(SessionFixture.bootstrap)
        await poll.value
        XCTAssertFalse(f.session.isBound)
        XCTAssertFalse(f.defaults.bool(forKey: "isBound"))
        XCTAssertTrue(f.session.channels.isEmpty)
        XCTAssertNil(f.session.errorText)
    }

    @MainActor
    func testOutOfOrderProbesCannotPersistOlderServer() async throws {
        let f = SessionFixture()
        defer { f.close() }
        let bHealth = f.router.hold("server-b.invalid", "/api/v1/health")
        let bProbe = Task { await f.session.probe(SessionFixture.b) }
        await fulfillment(of: [bHealth.started], timeout: 5)
        let cHealth = f.router.hold("server-c.invalid", "/api/v1/health")
        let cProbe = Task { await f.session.probe(SessionFixture.c) }
        await fulfillment(of: [cHealth.started], timeout: 5)
        cHealth.reply(#"{"code":0,"msg":"ok"}"#)
        let cAccepted = await cProbe.value
        bHealth.reply(#"{"code":0,"msg":"ok"}"#)
        let bAccepted = await bProbe.value
        XCTAssertTrue(cAccepted)
        XCTAssertFalse(bAccepted)
        XCTAssertEqual(f.session.serverBase, SessionFixture.c)
        XCTAssertEqual(f.defaults.string(forKey: "server"), SessionFixture.c)
    }

    @MainActor
    func testSwitchClearsEveryServerScopedValueButKeepsDeviceKey() async throws {
        let f = SessionFixture(bound: true)
        defer { f.close() }
        let channel = Channel(id: 7)
        f.session.channels = [channel]
        f.session.groups = [ChannelGroup(name: "old", count: 1)]
        f.session.favorites = [7]
        f.session.nowTitles = [7: "old"]
        f.session.requestPlay(channel)
        let key = f.session.deviceKey
        _ = await f.session.probe(SessionFixture.b)
        XCTAssertEqual(f.session.token, "")
        XCTAssertEqual(f.credentials.token, "")
        XCTAssertFalse(f.session.isBound)
        XCTAssertTrue(f.session.channels.isEmpty)
        XCTAssertTrue(f.session.groups.isEmpty)
        XCTAssertTrue(f.session.favorites.isEmpty)
        XCTAssertTrue(f.session.nowTitles.isEmpty)
        XCTAssertNil(f.session.lastChannel)
        XCTAssertNil(f.session.playRequest)
        XCTAssertEqual(f.session.deviceKey, key)
    }

    @MainActor
    func testUnknownOriginalServerCannotReuseRetainedToken() async throws {
        let f = SessionFixture(server: "", token: SessionFixture.tokenA)
        defer { f.close() }
        _ = await f.session.probe(SessionFixture.b)
        f.router.respond("server-b.invalid", "/api/v1/device/register", SessionFixture.pendingB)
        _ = await f.session.register(code: nil)
        assertNoOldBearerAtB(f)
        assertHealthIsAnonymous(f)
        XCTAssertEqual(f.session.token, SessionFixture.tokenB)
        XCTAssertFalse(f.session.isBound)
    }

    @MainActor
    func testCredentialWriteFailureIsUnboundAndDoesNotPoll() async throws {
        let f = SessionFixture(bound: true)
        defer { f.close() }
        f.credentials.writable = false
        f.router.respond("server-a.invalid", "/api/v1/device/register", SessionFixture.boundA)
        _ = await f.session.register(code: nil)
        XCTAssertEqual(f.session.token, "")
        XCTAssertFalse(f.session.isBound)
        XCTAssertFalse(f.defaults.bool(forKey: "isBound"))
        XCTAssertNotNil(f.session.errorText)
        XCTAssertEqual(f.router.requests.filter { $0.url?.path == "/api/v1/tv/bootstrap" }.count, 0)
    }

    @MainActor
    func testValidApprovalAppliesTheReturnedBootstrapExactlyOnce() async throws {
        let f = SessionFixture()
        defer { f.close() }
        f.router.respond("server-a.invalid", "/api/v1/tv/bootstrap", SessionFixture.bootstrap)
        let poll = Task { await f.session.waitForApproval() }
        let paused = await f.gate.firstPause
        await fulfillment(of: [paused], timeout: 5)
        await f.gate.resume()
        await poll.value
        if let extra = f.session.bootstrapTask { await extra.value }
        XCTAssertTrue(f.session.isBound)
        XCTAssertEqual(f.session.channels.map(\.id), [7])
        XCTAssertEqual(f.session.groups.map(\.name), ["fixture group"])
        XCTAssertEqual(f.session.favorites, [7])
        XCTAssertEqual(f.session.nowTitles[7], "fixture title")
        XCTAssertNil(f.session.errorText)
        let requests = f.router.requests.filter { $0.url?.path == "/api/v1/tv/bootstrap" }
        XCTAssertEqual(requests.count, 1)
        XCTAssertTrue(requests.allSatisfy {
            $0.url?.host == "server-a.invalid" &&
                $0.value(forHTTPHeaderField: "Authorization") == "Bearer " + SessionFixture.tokenA
        })
    }

    @MainActor
    private func assertNoOldBearerAtB(_ f: SessionFixture, file: StaticString = #filePath, line: UInt = #line) {
        let leaked = f.router.requests.contains {
            $0.url?.host == "server-b.invalid" &&
                $0.value(forHTTPHeaderField: "Authorization") == "Bearer " + SessionFixture.tokenA
        }
        XCTAssertFalse(leaked, "old credentials reached the candidate origin", file: file, line: line)
    }

    @MainActor
    private func assertHealthIsAnonymous(_ f: SessionFixture, file: StaticString = #filePath, line: UInt = #line) {
        let health = f.router.requests.filter { $0.url?.path == "/api/v1/health" }
        XCTAssertFalse(health.isEmpty, file: file, line: line)
        XCTAssertTrue(health.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == nil },
                      file: file, line: line)
    }
}
