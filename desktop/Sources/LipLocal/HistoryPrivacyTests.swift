import Foundation
import CryptoKit
import Darwin

/// Synthetic metadata/transport and injected crypto only. No platform monitor,
/// NSApplication, CGSession, AX, Keychain, editor, microphone or permission query.
func testHistoryPrivacy() {
    var checks = 0
    func check(_ ok: @autoclosure () -> Bool, _ label: String) {
        guard ok() else { fputs("FAIL history privacy check: \(label)\n", stderr); exit(1) }; checks += 1
    }
    func denied(_ label: String, _ action: () throws -> Void) { do { try action(); preconditionFailure(label) } catch { checks += 1 } }
    func facts() -> HistoryEnvironmentFacts { HistoryEnvironmentFacts(protectedDataAvailable: true, onConsole: true, loginDone: true, currentUserMatches: true, frontPID: 7) }
    func feed() -> HistoryEnvironmentFeed { let f = HistoryEnvironmentFeed(); f.publish(facts(), at: 100); return f }
    let id = UUID()
    func field(role: String? = "AXTextArea", subrole: String? = "AXUnknown", protected: Bool? = false, enabled: Bool? = true, editable: Bool? = true, pid: Int32 = 7, identity: UUID? = nil) -> HistoryFieldMetadata {
        HistoryFieldMetadata(pid: pid, identity: identity ?? id, role: role, subrole: subrole, protectedContent: protected, enabled: enabled, editable: editable)
    }
    let normal = field()
    let invalid = [field(role: nil), field(role: "AXButton"), field(role: String(repeating: "x", count: 65)), field(subrole: nil), field(subrole: "AXSecureTextField"), field(subrole: "unrecognized"), field(protected: true), field(protected: nil), field(enabled: false), field(enabled: nil), field(editable: false), field(editable: nil), field(pid: 0), field(pid: 8)]
    for (index, metadata) in invalid.enumerated() {
        let lease = HistoryPrivacyLease(feed: feed())
        check(!lease.arm(metadata, sampledAt: 100, now: 100, observationReady: true), "Invalid/unknown field rejected \(index)")
        denied("Invalid field cannot claim") { try lease.claim(now: 100) }
    }
    for mutate in [
        { (f: inout HistoryEnvironmentFacts) in f.protectedDataAvailable = nil },
        { (f: inout HistoryEnvironmentFacts) in f.protectedDataAvailable = false },
        { (f: inout HistoryEnvironmentFacts) in f.onConsole = nil },
        { (f: inout HistoryEnvironmentFacts) in f.onConsole = false },
        { (f: inout HistoryEnvironmentFacts) in f.loginDone = false },
        { (f: inout HistoryEnvironmentFacts) in f.loginDone = nil },
        { (f: inout HistoryEnvironmentFacts) in f.currentUserMatches = false },
        { (f: inout HistoryEnvironmentFacts) in f.currentUserMatches = nil },
        { (f: inout HistoryEnvironmentFacts) in f.frontPID = nil },
        { (f: inout HistoryEnvironmentFacts) in f.frontPID = 0 }
    ] {
        let f = feed(); var value = facts(); mutate(&value); f.publish(value, at: 100.1)
        let lease = HistoryPrivacyLease(feed: f)
        check(!lease.arm(normal, sampledAt: 100.1, now: 100.1, observationReady: true), "Unknown/unavailable environment denies")
    }
    let noCoverage = HistoryPrivacyLease(feed: feed())
    check(!noCoverage.arm(normal, sampledAt: 100, now: 100, observationReady: false), "Unsupported subscriptions deny")
    let unknown = HistoryPrivacyLease(feed: feed())
    check(!unknown.arm(nil, sampledAt: 100, now: 100, observationReady: true), "Unknown destination denies")
    for time in [-1.0, .nan, .infinity, 99.0, 101.0] {
        let lease = HistoryPrivacyLease(feed: feed())
        check(!lease.arm(normal, sampledAt: time, now: 100, observationReady: true), "Malformed/stale/future stamp denies")
    }
    for changed in [field(protected: true), field(identity: UUID()), field(pid: 8), field(enabled: false), field(editable: nil)] {
        let lease = HistoryPrivacyLease(feed: feed())
        check(lease.arm(normal, sampledAt: 100, now: 100, observationReady: true), "Ordinary arm")
        lease.observe(changed, sampledAt: 100.1, now: 100.1)
        lease.observe(normal, sampledAt: 100.2, now: 100.2)
        check(lease.snapshot(now: 100.2) == .revoked, "Observed loss cannot revive on return")
        denied("Revoked field cannot claim") { try lease.claim(now: 100.2) }
    }
    let epochLease = HistoryPrivacyLease(feed: feed())
    check(epochLease.arm(normal, sampledAt: 100, now: 100, observationReady: true), "Initial ordinary context")
    epochLease.revoke(); check(!epochLease.arm(normal, sampledAt: 100.1, now: 100.1, observationReady: true), "Teardown/cancel cannot rearm")
    let lifecycle = feed(), old = HistoryPrivacyLease(feed: lifecycle)
    check(old.arm(normal, sampledAt: 100, now: 100, observationReady: true), "Lifecycle arm")
    lifecycle.revoke(at: 100.1); lifecycle.publish(facts(), at: 100.2)
    old.observe(normal, sampledAt: 100.3, now: 100.3)
    check(old.snapshot(now: 100.3) == .revoked, "Loss/recovery epoch prevents old lease revival even if intermediate loss not polled")
    let late = HistoryPrivacyLease(feed: lifecycle)
    lifecycle.revoke(at: 100.31); lifecycle.publish(facts(), at: 100.32)
    check(!late.arm(normal, sampledAt: 100.33, now: 100.33, observationReady: true), "Stale setup reply from old environment epoch cannot arm")
    let fresh = HistoryPrivacyLease(feed: lifecycle)
    check(fresh.arm(normal, sampledAt: 100.33, now: 100.33, observationReady: true), "Recovery allows only new lease")
    try! fresh.claim(now: 100.34); denied("One-shot privacy claim") { try fresh.claim(now: 100.34) }
    let gapFeed = feed(), gap = HistoryPrivacyLease(feed: gapFeed)
    check(gap.arm(normal, sampledAt: 100, now: 100, observationReady: true), "Gap arm")
    gapFeed.publish(facts(), at: 101); gap.observe(normal, sampledAt: 101, now: 101)
    check(gap.snapshot(now: 101) == .revoked, "Observation gap cannot silently renew")
    let backwardFeed = feed(), backwards = HistoryPrivacyLease(feed: backwardFeed)
    check(backwards.arm(normal, sampledAt: 100, now: 100, observationReady: true), "Backward clock arm")
    backwardFeed.publish(facts(), at: 99); backwardFeed.publish(facts(), at: 100.1)
    check(backwards.snapshot(now: 100.1) == .revoked, "Backwards platform sample cannot preserve old epoch")
    let permission = HistoryPrivacyLease(feed: feed())
    check(permission.arm(normal, sampledAt: 100, now: 100, observationReady: true), "Permission arm")
    permission.observe(nil, sampledAt: 100.1, now: 100.1)
    check(permission.snapshot(now: 100.1) == .revoked, "Permission/read failure revokes")

    // Capability-limited transport works independently of insertion; it has NO
    // content, selection, range, clipboard or CursorInsertion entry point.
    let access = HistoryPrivacyFakeAccess()
    let reader = HistoryMetadataReader(access: access)
    let readA = reader.read()!, readA2 = reader.read()!
    check(readA.identity == readA2.identity && readA.eligible, "Same element identity stable")
    check(Set(access.trace).isSubset(of: ["trusted", "focus", "pid", "equal", "role", "subrole", "protected", "enabled", "settable-metadata"]), "Insertion OFF reader uses only allowlisted metadata")
    access.element = 2; let readB = reader.read()!; check(readB.identity != readA.identity, "Different AX identity not inferred from PID/hash")
    access.permission = false; access.trace = []; check(reader.read() == nil && access.trace == ["trusted"], "Denied permission performs no field query")
    access.permission = true
    let stopped = HistoryPrivacyFlag(); access.afterRole = { stopped.mark() }
    let cancelReader = HistoryMetadataReader(access: access, cancelled: { stopped.value })
    access.trace = []; check(cancelReader.read() == nil, "Canceled provider reply rejected")
    check(access.trace.last == "role", "No follow-up queries after observed cancel")

    // Block actual fake-provider IPC; revocation/admission remain CPU-only.
    let entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0), drained = DispatchSemaphore(value: 0)
    let blockedAccess = HistoryPrivacyFakeAccess(), cancelled = HistoryPrivacyFlag()
    blockedAccess.afterRole = { entered.signal(); release.wait() }
    let blockedReader = HistoryMetadataReader(access: blockedAccess, cancelled: { cancelled.value })
    let blockedLease = HistoryPrivacyLease(feed: feed())
    check(blockedLease.arm(normal, sampledAt: 100, now: 100, observationReady: true), "Blocked IPC lease arm")
    DispatchQueue.global().async { _ = blockedReader.read(); drained.signal() }
    check(entered.wait(timeout: .now() + 5) == .success, "Provider entered blocked role query")
    blockedLease.revoke(); cancelled.mark()
    check(blockedLease.snapshot(now: 100.1) == .revoked, "Revoke returns while provider stays blocked")
    release.signal(); check(drained.wait(timeout: .now() + 5) == .success, "Late provider drains")
    check(blockedLease.snapshot(now: 100.2) == .revoked, "Late reply cannot revive revoked lease")

    // P2 regressions: sampling time precedes reads; lifecycle loss rejects late facts.
    let slowFeed = feed(); var sampleClock = 100.0
    check(HistoryEnvironmentSampler.publish(to: slowFeed, clock: { sampleClock }) { sampleClock = 100.7; return facts() }, "Slow environment sample publishes original timestamp")
    check(slowFeed.latest().sampledAt == 100, "Slow read never relabels old facts as fresh")
    let slowLease = HistoryPrivacyLease(feed: slowFeed)
    check(!slowLease.arm(normal, sampledAt: 100.7, now: 100.7, observationReady: true), "Slow environment sample cannot arm lease")
    let lostFeed = feed()
    check(!HistoryEnvironmentSampler.publish(to: lostFeed, clock: { 100 }) { lostFeed.revoke(at: 100.1); return facts() }, "Late pre-loss sample cannot publish")
    check(!lostFeed.latest().ready, "Late sample leaves revoked environment unavailable")

    // Canceled work retains capacity until provider AND teardown actually finish.
    let capacity = HistoryObservationCapacity(maximum: 1)
    let capEntered = DispatchSemaphore(value: 0), capRelease = DispatchSemaphore(value: 0)
    let teardownEntered = DispatchSemaphore(value: 0), teardownRelease = DispatchSemaphore(value: 0)
    let capCancelled = HistoryPrivacyFlag()
    check(capacity.launch { capEntered.signal(); capRelease.wait(); teardownEntered.signal(); teardownRelease.wait() }, "First observation reserves capacity")
    check(capEntered.wait(timeout: .now() + 5) == .success, "Bounded worker entered provider")
    capCancelled.mark()
    for _ in 0..<25 { check(!capacity.launch { preconditionFailure("Over-capacity observation launched") }, "Cancel cannot free blocked worker capacity") }
    check(capCancelled.value && capacity.active == 1, "Canceled blocked worker remains counted")
    capRelease.signal()
    check(teardownEntered.wait(timeout: .now() + 5) == .success, "Provider returned into teardown")
    check(!capacity.launch { preconditionFailure("Teardown capacity escaped") }, "Teardown retains capacity")
    teardownRelease.signal()
    let capacityDeadline = Date(timeIntervalSinceNow: 5)
    while capacity.active != 0 && Date() < capacityDeadline { Thread.sleep(forTimeInterval: 0.01) }
    check(capacity.active == 0, "Capacity releases only after teardown")
    let resumed = DispatchSemaphore(value: 0)
    check(capacity.launch { resumed.signal() }, "Drained capacity accepts new observation")
    check(resumed.wait(timeout: .now() + 5) == .success, "Replacement worker actually runs")

    // Discovery is never admission: subscribe/drain then reread same live field.
    for variant in 0..<7 {
        let setupAccess = HistoryPrivacyFakeAccess(), setupFeed = feed(), setupCancel = HistoryPrivacyFlag()
        let setupLease = HistoryPrivacyLease(feed: setupFeed)
        let setupReader = HistoryMetadataReader(access: setupAccess, cancelled: { setupCancel.value })
        let armed = HistoryObservationSetup.arm(reader: setupReader, lease: setupLease, clock: { 100 }, subscribeDestruction: { _ in
            setupAccess.trace.append("subscribe")
            switch variant {
            case 1: setupAccess.element = 2
            case 2: setupAccess.protected = true
            case 3: setupAccess.protected = nil
            case 4: setupAccess.permission = false
            case 5: setupCancel.mark()
            default: break
            }
            return variant != 6
        }, drainPending: { setupAccess.trace.append("drain") }, cancelled: { setupCancel.value })
        check(armed == (variant == 0), "Only fresh subscribed same-identity ordinary field arms \(variant)")
        if variant == 0 {
            let subscription = setupAccess.trace.firstIndex(of: "subscribe")!
            check(setupAccess.trace.filter { $0 == "focus" }.count == 2 && setupAccess.trace[subscription + 1] == "drain" && setupAccess.trace.last == "settable-metadata", "Subscription and drain precede second complete metadata read")
        } else {
            check(setupLease.snapshot(now: 100) == .revoked, "Rejected setup latches revoked \(variant)")
        }
    }

    let root = (try! HistoryVault.canonicalAnchor(FileManager.default.temporaryDirectory)).appendingPathComponent("LipHistoryPrivacyTests-" + UUID().uuidString)
    try! FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    let key = SymmetricKey(data: Data(repeating: 0x52, count: 32))
    func until(_ label: String, _ predicate: () -> Bool) {
        let deadline = Date(timeIntervalSinceNow: 5)
        while !predicate() && Date() < deadline { _ = RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.01)) }
        check(predicate(), label)
    }
    func enable(_ service: HistoryService) {
        var reply: HistorySettingEvent?; service.setEnabled(true) { reply = $0 }
        until("Synthetic writer ready") { reply != nil }; try! reply!.result.get()
    }
    func record() -> HistoryRecord { HistoryRecord(id: UUID(), created: Date(timeIntervalSince1970: 1700000000), profile: "clean", original: "synthetic privacy fixture 10 mg", output: "Synthetic privacy fixture 10 mg.", modelDetails: "synthetic") }
    let f = feed(), eligible = HistoryPrivacyLease(feed: f)
    check(eligible.arm(normal, sampledAt: 100, now: 100, observationReady: true), "Eligible save arm")
    let vault = HistoryVault(root: root.appendingPathComponent("eligible"), key: { _ in key })
    let service = HistoryService(defaults: UserDefaults(suiteName: "dev.lip.privacy-test." + UUID().uuidString)!, vault: vault)
    enable(service)
    let capture = UUID(), a = record(), automatic = service.automaticTicket(capture: capture, privacy: eligible, now: 100)!
    var outcome: HistorySaveEvent?
    service.saveAutomatically(a, ticket: automatic, clock: { 100 }) { outcome = $0 }
    until("Eligible automatic save callback") { outcome != nil }
    check(outcome!.saved && outcome!.capture == capture && outcome!.record == a.id && (try! vault.list().records == [a]), "Eligible exact record committed")
    var duplicate: HistorySaveEvent?; service.saveAutomatically(record(), ticket: automatic, clock: { 100 }) { duplicate = $0 }
    until("Duplicate privacy claim callback") { duplicate != nil }; check(!duplicate!.saved && (try! vault.list().records == [a]), "Automatic ticket one shot")
    let raceFeed = feed(), raceLease = HistoryPrivacyLease(feed: raceFeed)
    check(raceLease.arm(normal, sampledAt: 100, now: 100, observationReady: true), "Preclaim-race arm")
    let raceVault = HistoryVault(root: root.appendingPathComponent("revoked-before-admission"), beforeAdmission: {
        raceLease.observe(field(protected: true), sampledAt: 100.1, now: 100.1)
        raceLease.observe(normal, sampledAt: 100.2, now: 100.2)
    }, key: { _ in key })
    let raceService = HistoryService(defaults: UserDefaults(suiteName: "dev.lip.privacy-race." + UUID().uuidString)!, vault: raceVault)
    enable(raceService)
    let raceTicket = raceService.automaticTicket(capture: UUID(), privacy: raceLease, now: 100)!
    var refused: HistorySaveEvent?
    raceService.saveAutomatically(record(), ticket: raceTicket, clock: { 100.2 }) { refused = $0 }
    until("Preclaim privacy loss callback") { refused != nil }
    check(!refused!.saved && (try! raceVault.list().records.isEmpty), "Privacy loss at final admission never publishes record")
    let badLease = HistoryPrivacyLease(feed: feed()); _ = badLease.arm(field(protected: true), sampledAt: 100, now: 100, observationReady: true)
    check(service.automaticTicket(capture: UUID(), privacy: badLease, now: 100) == nil, "Protected/unknown auto ticket cannot be issued")
    let gate = HistoryRecordStatusGate(); gate.begin(capture)
    check(gate.accepts(outcome!, currentSettingRequest: automatic.settingRequest), "Current record status accepted")
    gate.begin(UUID()); check(!gate.accepts(outcome!, currentSettingRequest: automatic.settingRequest), "Old record cannot overwrite new capture status")
    gate.begin(capture); check(!gate.accepts(outcome!, currentSettingRequest: UUID()), "Old consent callback cannot overwrite new setting status")
    gate.invalidate(); check(!gate.accepts(outcome!, currentSettingRequest: automatic.settingRequest), "Revoked status epoch cannot revive")
    print("PASS \(checks) history observed-context policy/transport/storage/status checks; SYNTHETIC only, no real AX/CGSession/AppKit state/Keychain/UI/lock qualification")
    print("Synthetic privacy fixtures retained at \(root.path)")
}
private final class HistoryPrivacyFlag {
    private let lock = NSLock(); private var state = false
    func mark() { lock.lock(); defer { lock.unlock() }; state = true }
    var value: Bool { lock.lock(); defer { lock.unlock() }; return state }
}
private final class HistoryPrivacyFakeAccess: HistoryMetadataAccess {
    var permission = true, element = 1
    var protected: Bool? = false
    var trace: [String] = []
    var afterRole: (() -> Void)?
    func trusted() -> Bool { trace.append("trusted"); return permission }
    func focused() -> Int? { trace.append("focus"); return element }
    func pid(_ element: Int) -> Int32? { trace.append("pid"); return 7 }
    func equal(_ lhs: Int, _ rhs: Int) -> Bool { trace.append("equal"); return lhs == rhs }
    func string(_ element: Int, _ attribute: HistoryMetadataAttribute) -> String? {
        switch attribute { case .role: trace.append("role"); afterRole?(); return "AXTextArea"
        case .subrole: trace.append("subrole"); return "AXUnknown"
        default: preconditionFailure("Unexpected metadata method") }
    }
    func boolean(_ element: Int, _ attribute: HistoryMetadataAttribute) -> Bool? {
        switch attribute { case .protectedContent: trace.append("protected"); return protected
        case .enabled: trace.append("enabled"); return true
        default: preconditionFailure("Unexpected metadata method") }
    }
    func valueSettable(_ element: Int) -> Bool? { trace.append("settable-metadata"); return true }
}
