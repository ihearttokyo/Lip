import Foundation
import CryptoKit
import Security
import Darwin

/// Explicit CLI only; known fixture key. No Keychain calls, microphone, app/window or real text.
func testTranscriptHistory() {
    var checks = 0
    func check(_ value: @autoclosure () -> Bool, _ name: String) { precondition(value(), name); checks += 1 }
    func refuses(_ name: String, _ block: () throws -> Void) { do { try block(); preconditionFailure(name) } catch { checks += 1 } }
    func until(_ name: String, _ predicate: () -> Bool) {
        let deadline = Date(timeIntervalSinceNow: 5)
        while !predicate() && Date() < deadline { _ = RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.01)) }
        check(predicate(), name)
    }
    func setting(_ service: HistoryService, _ value: Bool) -> HistorySettingEvent {
        var reply: HistorySettingEvent?
        let id = service.setEnabled(value) { event in
            check(Thread.isMainThread, "Setting callback on main thread"); reply = event
        }
        until("Setting callback arrived") { reply != nil }
        check(reply!.request == id, "Setting callback request identity")
        return reply!
    }
    let root = (try! HistoryVault.canonicalAnchor(FileManager.default.temporaryDirectory)).appendingPathComponent("LipHistoryTests-" + UUID().uuidString, isDirectory: true)
    try! FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    let attributes = HistoryKeychain.attributes(), addition = HistoryKeychain.addition(Data(repeating: 0x42, count: 32))
    check(attributes[kSecUseDataProtectionKeychain as String] as? Bool == true && addition[kSecUseDataProtectionKeychain as String] as? Bool == true, "Data Protection Keychain lookup/create")
    check(attributes[kSecAttrSynchronizable as String] as? Bool == false && addition[kSecAttrAccessible as String] as? String == kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String, "Device-local unlocked key metadata")
    let fixtureKey = SymmetricKey(data: Data(repeating: 0x42, count: 32))
    var calls: [Bool] = []
    func vault(_ name: String, limits: HistoryLimits = HistoryLimits()) -> HistoryVault {
        HistoryVault(root: root.appendingPathComponent(name), limits: limits, key: { create in calls.append(create); return fixtureKey })
    }
    let defaults = UserDefaults(suiteName: "dev.lip.history-tests." + UUID().uuidString)!
    let unused = vault("disabled")
    let service = HistoryService(defaults: defaults, vault: unused)
    check(!service.enabled && service.ticket() == nil, "History default must be off")
    service.saveForExplicitUserRequest(HistoryRecord(id: UUID(), created: Date(timeIntervalSince1970: 1700000000), profile: "clean", original: "synthetic", output: "Synthetic.", modelDetails: "test"), ticket: nil) { _ in preconditionFailure("Disabled callback") }
    check(calls.isEmpty && !FileManager.default.fileExists(atPath: unused.root.path), "Off must not access filesystem/key")
    func record(_ text: String = "synthetic café 東京 👍🏾 10 mg") -> HistoryRecord {
        HistoryRecord(id: UUID(), created: Date(timeIntervalSince1970: 1700000000), profile: "clean", original: text, output: text + ".", modelDetails: "synthetic native timings")
    }
    let a = record(); let store = vault("roundtrip")
    check(try! store.list().records.isEmpty, "Empty read")
    check(calls.isEmpty && !FileManager.default.fileExists(atPath: store.root.path), "Read-empty must not create key/dir")
    try! store.save(a)
    check(try! store.list().records == [a], "Exact roundtrip")
    check(calls == [true, false], "Key creation only first save/read is readonly")
    let path = store.root.appendingPathComponent(a.id.uuidString + ".json")
    let data = try! Data(contentsOf: path)
    check(!String(decoding: data, as: UTF8.self).contains(a.original), "No plaintext on disk")
    let mode = try! FileManager.default.attributesOfItem(atPath: path.path)[.posixPermissions] as! NSNumber
    check(mode.intValue == 0o600, "Record permissions")
    refuses("Duplicate immutable ID must not overwrite") { try store.save(a) }
    check(try! Data(contentsOf: path) == data, "Existing bytes survive duplicate")
    var damaged = try! JSONDecoder().decode(HistoryVault.Envelope.self, from: data).sealed; damaged[damaged.count - 1] ^= 1
    try! JSONEncoder().encode(HistoryVault.Envelope(version: 1, id: a.id, sealed: damaged)).write(to: path)
    let listing = try! store.list(); check(listing.records.isEmpty && listing.unreadable == 1, "Tampered tag refused/preserved")
    refuses("Tampered record must not be deleted") { try store.delete(a.id) }
    check(FileManager.default.fileExists(atPath: path.path), "Corrupt record preserved")
    try! data.write(to: path)
    let renamed = store.root.appendingPathComponent(UUID().uuidString + ".json"); try! FileManager.default.moveItem(at: path, to: renamed)
    check(try! store.list().unreadable == 1, "Authenticated filename ID binding")
    try! FileManager.default.moveItem(at: renamed, to: path)
    let missing = HistoryVault(root: store.root, key: { create in check(!create, "Never create a replacement for existing ciphertext"); throw HistoryFailure.keyUnavailable })
    refuses("Missing key must not replace") { try missing.save(record()) }
    check(try! Data(contentsOf: path) == data, "Lost key preserves records")
    let wrong = HistoryVault(root: store.root, key: { _ in SymmetricKey(data: Data(repeating: 0x43, count: 32)) })
    check(try! wrong.list().unreadable == 1, "Wrong key refused")
    refuses("Wrong key cannot append to existing vault") { try wrong.save(record()) }
    check(try! Data(contentsOf: path) == data, "Wrong key leaves original bytes intact")
    let cap = vault("count", limits: HistoryLimits(bytes: 10000, records: 1, payload: 4096, file: 8192))
    try! cap.save(a); refuses("Count cap preserves old") { try cap.save(record()) }; check(try! cap.list().records == [a], "Count cap no eviction")
    let byteCap = vault("bytes", limits: HistoryLimits(bytes: 20, records: 20, payload: 4096, file: 8192))
    refuses("Byte cap") { try byteCap.save(a) }; check(try! byteCap.list().records.isEmpty, "Byte cap no record")
    let payloadCap = vault("payload", limits: HistoryLimits(bytes: 10000, records: 20, payload: 8, file: 8192))
    refuses("Payload cap") { try payloadCap.save(a) }; check(!FileManager.default.fileExists(atPath: payloadCap.root.path), "Oversized input no IO")
    let revoked = vault("revoked")
    refuses("Commit revoked") { try revoked.save(a) { _ in throw HistoryFailure.revoked } }
    check(try! revoked.list().records.isEmpty, "Revoked commit never publishes")
    let consent = HistoryConsent(enabled: false); check(consent.ticket() == nil, "No start ticket while off")
    consent.set(true); let old = consent.ticket()!; consent.set(false)
    refuses("Disable fences old ticket") { try consent.withCommit(old) {} }
    consent.set(true); refuses("Reenable cannot resurrect old ticket") { try consent.withCommit(old) {} }
    let ticket = consent.ticket()!; ticket.lease.cancel()
    refuses("Explicit session cancellation") { try consent.withCommit(ticket) {} }
    let once = consent.ticket()!; var committed = 0
    try! consent.withCommit(once) { committed += 1 }
    refuses("One-shot session") { try consent.withCommit(once) { committed += 1 } }; check(committed == 1, "One-shot count")
    // Actual worker paused at commit gate, with consent revoked while it is queued.
    let reached = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0), done = DispatchSemaphore(value: 0)
    let raceVault = vault("race"); let raceTicket = consent.ticket()!; let result = HistoryTestBox()
    DispatchQueue.global().async {
        defer { done.signal() }
        do { try raceVault.save(a) { action in reached.signal(); release.wait(); try consent.withCommit(raceTicket, action) }; result.saved = true } catch { result.saved = false }
    }
    check(reached.wait(timeout: .now() + 5) == .success, "Worker reached commit gate"); consent.set(false); release.signal()
    check(done.wait(timeout: .now() + 5) == .success && !result.saved, "Disable wins queued commit")
    check(try! raceVault.list().records.isEmpty, "No active record after revoked race")
    let orphanVault = vault("orphan-cap", limits: HistoryLimits(bytes: 10000, records: 1, payload: 4096, file: 8192))
    try! FileManager.default.createDirectory(at: orphanVault.root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    let orphan = orphanVault.root.appendingPathComponent(".pending-synthetic")
    let orphanData = Data([1,2,3,4]); try! orphanData.write(to: orphan)
    refuses("Orphan counts toward new-entry admission") { try orphanVault.save(record()) }
    check(try! Data(contentsOf: orphan) == orphanData && orphanVault.list().records.isEmpty, "Orphan preserved and list still accessible")
    let elsewhere = root.appendingPathComponent("elsewhere"); try! FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: false)
    let parentLink = root.appendingPathComponent("linked-parent"); try! FileManager.default.createSymbolicLink(at: parentLink, withDestinationURL: elsewhere)
    let linked = HistoryVault(root: parentLink.appendingPathComponent("vault"), key: { _ in preconditionFailure("Key touched through linked ancestor") })
    refuses("Linked ancestor before creation") { try linked.save(record()) }
    check(!FileManager.default.fileExists(atPath: elsewhere.appendingPathComponent("vault").path), "Linked ancestor destination untouched")
    let sharedDefaults = UserDefaults(suiteName: "dev.lip.history-multi-tests." + UUID().uuidString)!
    let shared = vault("multi-instance")
    let first = HistoryService(defaults: sharedDefaults, vault: shared)
    try! setting(first, true).result.get(); check(first.ticket() != nil, "Authorized writer ticket")
    let second = HistoryService(defaults: sharedDefaults, vault: shared)
    check(second.ticket() == nil, "Second service cannot issue ticket")
    refuses("Second instance cannot silently change consent") { try setting(second, false).result.get() }
    try! setting(first, false).result.get(); check(second.ticket() == nil, "Owner disable blocks stale service")
    // Independent vault object / same path models another process's advisory lock.
    let external = HistoryService(defaults: sharedDefaults, vault: HistoryVault(root: shared.root, key: { _ in fixtureKey }))
    refuses("Independent writer lock refused") { try setting(external, true).result.get() }
    // A real storage worker is paused BEFORE acquisition. Recording ticket and
    // disable must return while that worker remains blocked, not merely finish later.
    let pausedRoot = vault("paused-readiness")
    let pausedDefaults = UserDefaults(suiteName: "dev.lip.history-paused." + UUID().uuidString)!
    let entered = DispatchSemaphore(value: 0), unblock = DispatchSemaphore(value: 0)
    let hookState = HistoryHookState()
    let paused = HistoryService(defaults: pausedDefaults, vault: pausedRoot, beforeAcquire: {
        hookState.recordWorker()
        if hookState.first() { entered.signal(); unblock.wait(); hookState.markReleased() }
    })
    var staleReply: HistorySettingEvent?, offReply: HistorySettingEvent?
    let enableID = paused.setEnabled(true) { staleReply = $0 }
    check(entered.wait(timeout: .now() + 5) == .success, "Writer reached paused setup")
    check(!hookState.wasMain && !hookState.released, "Setup runs off-main and remains paused")
    check(paused.ticket() == nil && !FileManager.default.fileExists(atPath: pausedRoot.root.path), "Preparing ticket is cheap/failclosed/no disk")
    let offID = paused.setEnabled(false) { offReply = $0 }
    check(!paused.enabled && paused.ticket() == nil && !hookState.released, "Disable admission returns before blocked setup releases")
    unblock.signal()
    until("Both request completions arrived") { staleReply != nil && offReply != nil }
    check(staleReply!.request == enableID && offReply!.request == offID, "Obsolete callback is explicitly identifiable")
    refuses("Old preparation cannot become ready") { try staleReply!.result.get() }
    try! offReply!.result.get(); check(!paused.snapshot().ready && !pausedDefaults.bool(forKey: "history.enabled.v1"), "Disable persisted and ready stays false")
    try! setting(paused, true).result.get(); let readyTicket = paused.ticket()
    check(readyTicket != nil && paused.snapshot().ready && paused.enabled, "Enable ready only after completed worker")
    let invalidateID = paused.setEnabled(false)
    check(paused.ticket() == nil && !paused.snapshot().ready && paused.snapshot().request == invalidateID, "Ready ticket revoked immediately")
    var saveReply: String?
    paused.saveForExplicitUserRequest(a, ticket: readyTicket) { saveReply = $0 }
    until("Old queued save completion") { saveReply != nil }
    check(try! pausedRoot.list().records.isEmpty, "No commit from old ready ticket after disable")
    try! setting(paused, true).result.get()
    paused.saveForExplicitUserRequest(a, ticket: readyTicket) { saveReply = $0 }
    // Queue a list after the save to observe its completed, rejected dispatch.
    var listed = false
    paused.list { result in check((try? result.get().records.isEmpty) == true, "Reenable does not revive old recording ticket"); listed = true }
    until("Reenable old-ticket check drained") { listed }
    let startupDefaults = UserDefaults(suiteName: "dev.lip.history-startup." + UUID().uuidString)!
    startupDefaults.set(true, forKey: "history.enabled.v1")
    let startupHook = HistoryHookState()
    let startup = HistoryService(defaults: startupDefaults, vault: vault("persisted-startup"), beforeAcquire: { startupHook.recordWorker() })
    until("Persisted-on startup becomes ready asynchronously") { startup.snapshot().ready }
    check(startup.ticket() != nil && !startupHook.wasMain, "Persisted-on startup writer runs off-main")
    // Discriminator for the earlier lock-sharing bug: pause INSIDE an admitted
    // filesystem action, after global + one-shot authorization has been claimed.
    let publishReached = DispatchSemaphore(value: 0), publishRelease = DispatchSemaphore(value: 0)
    let publishState = HistoryHookState()
    let publishVault = HistoryVault(root: root.appendingPathComponent("claimed-publish"), beforePublish: {
        publishState.recordWorker(); publishReached.signal(); publishRelease.wait(); publishState.markReleased()
    }, key: { _ in fixtureKey })
    let publishDefaults = UserDefaults(suiteName: "dev.lip.history-claimed." + UUID().uuidString)!
    let publishing = HistoryService(defaults: publishDefaults, vault: publishVault)
    try! setting(publishing, true).result.get(); let publishingTicket = publishing.ticket()!
    var publishMessage: String?, publishOff: HistorySettingEvent?
    publishing.saveForExplicitUserRequest(a, ticket: publishingTicket) { publishMessage = $0 }
    check(publishReached.wait(timeout: .now() + 5) == .success, "Worker paused inside authorized filesystem action")
    check(!publishState.wasMain && !publishState.released, "Claimed filesystem work remains off-main and paused")
    let uiReturned = HistoryFlag()
    // Explicit bounded CLI fault discriminator, not an observation timeout or
    // device latency benchmark. A modeled old lock would deadlock this caller.
    DispatchQueue.global().asyncAfter(deadline: .now() + 5) {
        if !uiReturned.value { fputs("FAIL synthetic admission blocked inside already-claimed IO (5s discriminator)\n", stderr); _exit(78) }
    }
    let unclaimed = publishing.ticket()
    check(unclaimed != nil && publishing.snapshot().ready && !publishState.released, "Fn ticket/snapshot return during blocked claimed IO")
    let publishOffID = publishing.setEnabled(false) { publishOff = $0 }
    check(!publishing.enabled && !publishing.snapshot().ready && publishing.snapshot().preparing && publishing.ticket() == nil && !publishState.released, "Disable revokes without waiting for claimed IO")
    check(publishOff == nil, "Disable persistence not falsely confirmed before drain")
    uiReturned.mark()
    publishRelease.signal()
    until("Claimed publish and disable drain complete") { publishMessage != nil && publishOff != nil }
    try! publishOff!.result.get()
    check(publishOff!.request == publishOffID && !publishing.snapshot().preparing && !publishing.snapshot().ready, "Off confirmation follows drain")
    check(try! publishVault.list().records == [a], "Already-claimed pre-disable record may finish; not falsely erased")
    var laterMessage: String?
    publishing.saveForExplicitUserRequest(record(), ticket: unclaimed) { laterMessage = $0 }
    until("Unclaimed old recording rejected after confirmed Off") { laterMessage != nil }
    check(try! publishVault.list().records == [a], "No unclaimed publication after confirmed Off")
    let exported = root.appendingPathComponent("selected.json"); try! HistoryExport.write(a, to: exported)
    let json = try! JSONSerialization.jsonObject(with: Data(contentsOf: exported)) as! [String: Any]
    let transfer = json["record"] as! [String: Any]
    check(json["schema"] as? String == "dev.lip.dictation-history" && json["version"] as? Int == 1 && json["sourcePlatform"] as? String == "macos", "Shared export schema/source")
    check(transfer["original"] as? String == a.original && transfer["output"] as? String == a.output && transfer["createdAtUnixMs"] as? Int64 == Int64(a.created.timeIntervalSince1970 * 1000), "Export selected exact text/date")
    check(transfer["language"] as? String == "en-US" && transfer["model"] as? String == a.profile && transfer["legacyCloudCleanup"] as? Bool == false, "Export recognition metadata not invented cloud usage")
    let unicode = HistoryRecord(id: UUID(), created: Date(timeIntervalSince1970: 1700000000.125), profile: "clean", original: "か\u{3099} 你好\n🎙️", output: "が 你好\n🎙️", modelDetails: "synthetic")
    let unicodeJSON = try! JSONSerialization.jsonObject(with: HistoryVault.export(unicode)) as! [String: Any]
    let unicodeRecord = unicodeJSON["record"] as! [String: Any]
    check(unicodeRecord["original"] as? String == unicode.original && unicodeRecord["output"] as? String == unicode.output && unicodeRecord["createdAtUnixMs"] as? Int64 == 1700000000125, "Shared export exact Unicode and milliseconds")
    refuses("Export refuses invalid timestamp") { try HistoryVault.export(HistoryRecord(id: UUID(), created: Date(timeIntervalSince1970: -1), profile: "clean", original: "x", output: "x", modelDetails: "")) }
    refuses("Export refuses large metadata") { try HistoryVault.export(HistoryRecord(id: UUID(), created: Date(), profile: String(repeating: "x", count: 4097), original: "x", output: "x", modelDetails: "")) }
    refuses("Export refuses oversize without truncation") { try HistoryVault.export(HistoryRecord(id: UUID(), created: Date(), profile: "clean", original: String(repeating: "x", count: 4*1024*1024), output: "x", modelDetails: "")) }
    refuses("Export refuses existing file") { try HistoryExport.write(record(), to: exported) }
    check(try! JSONSerialization.jsonObject(with: Data(contentsOf: exported)) as! NSDictionary == json as NSDictionary, "Export existing destination preserved")
    let link = store.root.appendingPathComponent(UUID().uuidString + ".json"); try! FileManager.default.createSymbolicLink(at: link, withDestinationURL: exported)
    refuses("Symlink vault entry") { _ = try store.list() }
    try! FileManager.default.removeItem(at: link) // Exact synthetic link only; never destination.
    try! store.delete(a.id); check(try! store.list().records.isEmpty, "Delete selected exact record")
    print("PASS \(checks) encrypted-history CLI checks; synthetic data/injected key only; no Keychain/app/UI/microphone/installed evidence")
    print("Synthetic fixtures retained at \(root.path)")
}
private final class HistoryTestBox { var saved = false }

private final class HistoryHookState {
    private let lock = NSLock()
    private var count = 0
    private var onMain = false
    private var didRelease = false
    func recordWorker() { lock.lock(); defer { lock.unlock() }; onMain = onMain || Thread.isMainThread }
    func first() -> Bool { lock.lock(); defer { lock.unlock() }; count += 1; return count == 1 }
    func markReleased() { lock.lock(); defer { lock.unlock() }; didRelease = true }
    var wasMain: Bool { lock.lock(); defer { lock.unlock() }; return onMain }
    var released: Bool { lock.lock(); defer { lock.unlock() }; return didRelease }
}

private final class HistoryFlag {
    private let lock = NSLock()
    private var state = false
    var value: Bool { lock.lock(); defer { lock.unlock() }; return state }
    func mark() { lock.lock(); defer { lock.unlock() }; state = true }
}
