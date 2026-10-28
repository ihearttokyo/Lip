import Foundation

/// Metadata facts, not editor contents or a graphical-unlocked assertion.
struct HistoryEnvironmentFacts: Equatable {
    var protectedDataAvailable: Bool?
    var onConsole: Bool?
    var loginDone: Bool?
    var currentUserMatches: Bool?
    var frontPID: Int32?
    var eligible: Bool { protectedDataAvailable == true && onConsole == true && loginDone == true && currentUserMatches == true && (frontPID ?? 0) > 0 }
}
struct HistoryEnvironmentFrame {
    let epoch: UUID
    let facts: HistoryEnvironmentFacts
    let ready: Bool
    let sampledAt: Double
}
final class HistoryEnvironmentFeed {
    private let lock = NSLock()
    private var frame = HistoryEnvironmentFrame(epoch: UUID(), facts: HistoryEnvironmentFacts(), ready: false, sampledAt: 0)
    func latest() -> HistoryEnvironmentFrame { lock.lock(); defer { lock.unlock() }; return frame }
    // File-private and CPU-only: environment revoke and per-lease claim linearize
    // together. Never place IPC, callbacks or filesystem work in this scope.
    fileprivate func inspect<T>(_ body: (HistoryEnvironmentFrame) throws -> T) rethrows -> T {
        lock.lock(); defer { lock.unlock() }; return try body(frame)
    }
    @discardableResult func publish(_ facts: HistoryEnvironmentFacts, at time: Double, expectedEpoch: UUID? = nil) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if let expectedEpoch, expectedEpoch != frame.epoch { return false }
        guard time.isFinite, time >= 0, !frame.sampledAt.isFinite || time >= frame.sampledAt else {
            frame = HistoryEnvironmentFrame(epoch: UUID(), facts: HistoryEnvironmentFacts(), ready: false, sampledAt: time); return false
        }
        let epoch = facts == frame.facts && frame.ready ? frame.epoch : UUID()
        frame = HistoryEnvironmentFrame(epoch: epoch, facts: facts, ready: true, sampledAt: time)
        return true
    }
    func revoke(at time: Double) {
        lock.lock(); defer { lock.unlock() }
        frame = HistoryEnvironmentFrame(epoch: UUID(), facts: HistoryEnvironmentFacts(), ready: false, sampledAt: time)
    }
}
struct HistoryFieldMetadata: Equatable {
    let pid: Int32
    let identity: UUID
    let role: String?
    let subrole: String?
    let protectedContent: Bool?
    let enabled: Bool?
    let editable: Bool?
    var eligible: Bool {
        guard let role, let subrole, role.utf8.count <= 64, subrole.utf8.count <= 64 else { return false }
        return ["AXTextField", "AXTextArea", "AXComboBox"].contains(role) &&
            ["", "AXUnknown", "AXSearchField"].contains(subrole) &&
            protectedContent == false && enabled == true && editable == true && pid > 0
    }
}
enum HistoryPrivacyFailure: Error { case denied }

/// Memory-only latching guard. No provider call, IPC or disk under its locks.
final class HistoryPrivacyLease {
    enum State: Equatable { case pending, eligible, revoked, claimed }
    private let lock = NSLock()
    private let feed: HistoryEnvironmentFeed
    private let maximumAge: Double
    private let startEpoch: UUID
    private var state: State = .pending
    private var field: HistoryFieldMetadata?
    private var environmentEpoch: UUID?
    private var sampledAt: Double = 0
    init(feed: HistoryEnvironmentFeed, maximumAge: Double = 0.5) {
        precondition(maximumAge.isFinite && maximumAge > 0); self.feed = feed; self.maximumAge = maximumAge; self.startEpoch = feed.latest().epoch
    }
    private func fresh(_ sample: Double, now: Double) -> Bool { sample.isFinite && now.isFinite && sample >= 0 && now >= sample && now - sample <= maximumAge }
    private func environment(_ env: HistoryEnvironmentFrame, field: HistoryFieldMetadata, now: Double) -> Bool {
        env.ready && env.facts.eligible && env.facts.frontPID == field.pid && fresh(env.sampledAt, now: now)
    }
    @discardableResult func arm(_ metadata: HistoryFieldMetadata?, sampledAt time: Double, now: Double, observationReady: Bool) -> Bool {
        return feed.inspect { env in
        lock.lock(); defer { lock.unlock() }
        guard state == .pending else { return false }
        guard observationReady, let metadata, metadata.eligible, env.epoch == startEpoch, fresh(time, now: now), environment(env, field: metadata, now: now) else { state = .revoked; return false }
        field = metadata; environmentEpoch = env.epoch; sampledAt = time; state = .eligible; return true
        }
    }
    func observe(_ metadata: HistoryFieldMetadata?, sampledAt time: Double, now: Double) {
        feed.inspect { env in
        lock.lock(); defer { lock.unlock() }
        guard state == .eligible else { return }
        guard let metadata, metadata.eligible, metadata == field, time >= sampledAt, fresh(sampledAt, now: now),
            fresh(time, now: now), environment(env, field: metadata, now: now), env.epoch == environmentEpoch else { state = .revoked; return }
        sampledAt = time
        }
    }
    func revoke() { lock.lock(); defer { lock.unlock() }; if state != .claimed { state = .revoked } }
    func snapshot(now: Double) -> State {
        return feed.inspect { env in
        lock.lock(); defer { lock.unlock() }
        if state == .eligible, let field,
            (!fresh(sampledAt, now: now) || !environment(env, field: field, now: now) || env.epoch != environmentEpoch) { state = .revoked }
        return state
        }
    }
    func claim(now: Double) throws {
        try feed.inspect { env in
        lock.lock(); defer { lock.unlock() }
        guard state == .eligible, let field, fresh(sampledAt, now: now), environment(env, field: field, now: now), env.epoch == environmentEpoch else {
            if state != .claimed { state = .revoked }; throw HistoryPrivacyFailure.denied
        }
        state = .claimed
        }
    }
}

struct HistoryAutomaticTicket {
    let capture: UUID
    let settingRequest: UUID
    let storage: HistoryTicket
    let privacy: HistoryPrivacyLease
}
struct HistorySaveEvent {
    let capture: UUID
    let settingRequest: UUID
    let record: UUID
    let saved: Bool
    let message: String
}
/// UI memory epoch. Source callers invalidate on new capture/privacy/lock loss;
/// settings-request identity must also still match at the main-actor callback.
final class HistoryRecordStatusGate {
    private let lock = NSLock()
    private var capture: UUID?
    func begin(_ value: UUID) { lock.lock(); defer { lock.unlock() }; capture = value }
    func invalidate() { lock.lock(); defer { lock.unlock() }; capture = nil }
    func accepts(_ event: HistorySaveEvent, currentSettingRequest: UUID) -> Bool {
        lock.lock(); defer { lock.unlock() }; return capture == event.capture && event.settingRequest == currentSettingRequest
    }
}

/// Timestamp before reads and reject an old sample after an intervening loss.
/// Sampling executes outside all admission locks, on the platform-required actor.
enum HistoryEnvironmentSampler {
    @discardableResult static func publish(to feed: HistoryEnvironmentFeed, clock: () -> Double, sample: () -> HistoryEnvironmentFacts) -> Bool {
        let epoch = feed.latest().epoch, started = clock()
        let facts = sample()
        return feed.publish(facts, at: started, expectedEpoch: epoch)
    }
}

/// Bounds ACTIVE + canceled-but-undrained observation threads. Capacity is released
/// only after the operation's callback/source teardown has actually returned.
final class HistoryObservationCapacity {
    static let shared = HistoryObservationCapacity(maximum: 1)
    private let lock = NSLock()
    private let maximum: Int
    private var occupied = 0
    init(maximum: Int) { precondition(maximum > 0); self.maximum = maximum }
    @discardableResult func launch(_ work: @escaping () -> Void) -> Bool {
        lock.lock()
        guard occupied < maximum else { lock.unlock(); return false }
        occupied += 1; lock.unlock()
        Thread { [self] in
            defer { lock.lock(); occupied -= 1; lock.unlock() }
            work()
        }.start()
        return true
    }
    var active: Int { lock.lock(); defer { lock.unlock() }; return occupied }
}
