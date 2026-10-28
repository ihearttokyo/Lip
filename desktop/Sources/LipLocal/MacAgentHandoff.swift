import Foundation
import CoreFoundation

/// Desired UI identifiers, not provider parameters, credentials or admission.
enum MacAgentRoute: String, CaseIterable, Codable {
    case none = "NONE", chatGPT = "CHATGPT_APP", claude = "CLAUDE_APP", pairedCodex = "PAIRED_CODEX"
    var title: String { switch self {
    case .none: return "None"
    case .chatGPT: return "ChatGPT — unavailable"
    case .claude: return "Claude — unavailable"
    case .pairedCodex: return "Paired Codex — unavailable"
    } }
}
enum MacAgentEffort: String, CaseIterable, Codable {
    case instant = "INSTANT", medium = "MEDIUM", high = "HIGH", extraHigh = "EXTRA_HIGH", pro = "PRO"
    var title: String { switch self {
    case .instant: return "Instant"; case .medium: return "Medium"; case .high: return "High"
    case .extraHigh: return "Extra High"; case .pro: return "Pro"
    } }
}
struct MacAgentPreferences: Equatable, Codable {
    var version = 1
    var route: MacAgentRoute = .none
    var effort: MacAgentEffort = .medium
    var optionFn = true
    static func decode(_ data: Data?) -> Self {
        guard let data, let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            Set(object.keys) == ["version", "route", "effort", "optionFn"],
            let version = object["version"] as? NSNumber, CFGetTypeID(version) != CFBooleanGetTypeID(), version == 1, !["d", "f"].contains(String(cString: version.objCType)),
            let route = object["route"] as? String, let effort = object["effort"] as? String,
            let enabled = object["optionFn"] as? NSNumber, CFGetTypeID(enabled) == CFBooleanGetTypeID(),
            let target = MacAgentRoute(rawValue: route), let mode = MacAgentEffort(rawValue: effort) else { return Self() }
        return Self(route: target, effort: mode, optionFn: enabled.boolValue)
    }
}

/// Confirmed process snapshot. Equal successful saves advance opaque identity;
/// failed writes do not. Defaults persist desired modes only, never query/account.
final class MacAgentPreferencesStore {
    final class Snapshot { let value: MacAgentPreferences; let revision = UUID(); init(_ value: MacAgentPreferences) { self.value = value } }
    static let shared = MacAgentPreferencesStore(read: { UserDefaults.standard.data(forKey: "agent.handoff.ui.v1") }, write: { data in
        UserDefaults.standard.set(data, forKey: "agent.handoff.ui.v1")
        return UserDefaults.standard.data(forKey: "agent.handoff.ui.v1") == data // Confirmed memory, not an fsync claim.
    })
    private let lock = NSLock()
    private let writer = NSLock()
    private let write: (Data) throws -> Bool
    private var confirmed: Snapshot
    private var observers: [UUID: () -> Void] = [:]
    init(read: () throws -> Data?, write: @escaping (Data) throws -> Bool) {
        self.write = write; confirmed = Snapshot(MacAgentPreferences.decode((try? read()) ?? nil))
    }
    func snapshot() -> Snapshot { lock.lock(); defer { lock.unlock() }; return confirmed }
    @discardableResult func observe(_ callback: @escaping () -> Void) -> UUID { lock.lock(); defer { lock.unlock() }; let id = UUID(); observers[id] = callback; return id }
    func unobserve(_ id: UUID) { lock.lock(); observers[id] = nil; lock.unlock() }
    func save(_ value: MacAgentPreferences) -> Bool {
        guard value.version == 1, let bytes = try? JSONEncoder().encode(value) else { return false }
        writer.lock()
        guard (try? write(bytes)) == true else { writer.unlock(); return false }
        lock.lock(); confirmed = Snapshot(value); let callbacks = Array(observers.values); lock.unlock()
        writer.unlock()
        callbacks.forEach { $0() } // No consumer callback under the preference lock.
        return true
    }
}

/// Future admission must supply its own verified, independent privacy lease.
/// It must not contend with, consume or silently reuse history's claim lease.
final class MacAgentPrivacyLease {
    private let lock = NSLock(); private var active = true
    private let validate: () -> Bool
    init(validate: @escaping () -> Bool) { self.validate = validate }
    func revoke() { lock.lock(); active = false; lock.unlock() }
    func isActive() -> Bool { lock.lock(); defer { lock.unlock() }; return active }
    func isCurrent() -> Bool {
        lock.lock(); let was = active; lock.unlock()
        guard was, validate() else { revoke(); return false }
        lock.lock(); defer { lock.unlock() }; return active
    }
}
final class MacAgentDestination: CustomStringConvertible {
    let route: MacAgentRoute
    let provider: String, accountLabel: String, accountIdentity: String
    let efforts: Set<MacAgentEffort>
    let maximumTextBytes: Int
    var description: String { "AgentDestination(redacted)" }
    var valid: Bool {
        route != .none && (1...16_777_216).contains(maximumTextBytes) &&
        [(provider,64),(accountLabel,320),(accountIdentity,512)].allSatisfy { value, limit in
            !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && value.utf8.count <= limit &&
                !value.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) }
        }
    }
    init(route: MacAgentRoute, provider: String, accountLabel: String, accountIdentity: String, efforts: Set<MacAgentEffort>, maximumTextBytes: Int) {
        self.route = route; self.provider = provider; self.accountLabel = accountLabel; self.accountIdentity = accountIdentity; self.efforts = efforts; self.maximumTextBytes = maximumTextBytes
    }
}
enum MacAgentOutcome: Equatable { case unavailable, stale, unsupportedEffort, alreadyAttempted, dispatched, ambiguous, refused
    var message: String { switch self {
    case .unavailable: return "Agent route unavailable; nothing sent. Transcript kept in Review."
    case .stale: return "Agent handoff canceled by context/settings change; nothing sent. Transcript kept in Review."
    case .unsupportedEffort: return "Desired effort unsupported by this route; nothing sent. Transcript kept in Review."
    case .alreadyAttempted: return "This completed dictation was already attempted. Check the destination; no retry."
    case .dispatched: return "Agent action dispatched, not recipient acceptance. Transcript kept in Review."
    case .ambiguous: return "Agent outcome unconfirmed. Check the destination; no retry or fallback. Transcript kept in Review."
    case .refused: return "Agent action refused; no retry or fallback. Transcript kept in Review."
    } }
}
struct MacAgentPayload: CustomStringConvertible {
    let text: String, language: String, modelID: String
    let destination: MacAgentDestination; let effort: MacAgentEffort
    var description: String { "MacAgentPayload(redacted)" }
}
/// Adapter results describe an invoked action only. Coordinator-only preflight
/// states (and their "nothing sent" wording) cannot be returned after invocation.
enum MacAgentDispatchOutcome { case dispatched, ambiguous, refused }
protocol MacAgentAdapter: AnyObject {
    var destination: MacAgentDestination { get }
    func dispatch(_ payload: MacAgentPayload) throws -> MacAgentDispatchOutcome
}

/// RAM-only successful-producer provenance and local at-most-once invocation.
/// No transport implementation, editor, clipboard, history or auth APIs exist here.
final class MacAgentCoordinator {
    final class Attempt: CustomStringConvertible {
        let language: String, modelID: String
        fileprivate let privacy: MacAgentPrivacyLease?, adapter: MacAgentAdapter?, destination: MacAgentDestination?
        fileprivate var completed = false
        fileprivate init(language: String, modelID: String, privacy: MacAgentPrivacyLease?, adapter: MacAgentAdapter?, destination: MacAgentDestination?) {
            self.language = language; self.modelID = modelID; self.privacy = privacy; self.adapter = adapter; self.destination = destination
        }
        var description: String { "AgentAttempt(redacted)" }
    }
    final class Ticket: CustomStringConvertible {
        fileprivate let attempt: Attempt; fileprivate let preferences: MacAgentPreferencesStore.Snapshot
        fileprivate init(_ attempt: Attempt, _ preferences: MacAgentPreferencesStore.Snapshot) { self.attempt = attempt; self.preferences = preferences }
        var description: String { "AgentTicket(redacted)" }
    }
    private final class Record {
        let attempt: Attempt; let text: String; var attempted = false
        init(_ attempt: Attempt, _ text: String) { self.attempt = attempt; self.text = text }
    }
    static let productionAdmittedRoutes: [MacAgentRoute] = []
    private let lock = NSLock()
    private let settings: MacAgentPreferencesStore
    private let resolve: (MacAgentRoute) -> MacAgentAdapter?
    private let privacy: () -> MacAgentPrivacyLease?
    private var attempt: Attempt?, record: Record?
    private var epoch = UUID()
    private var busy = false
    private var settingsObserver: UUID?
    init(settings: MacAgentPreferencesStore, resolve: @escaping (MacAgentRoute) -> MacAgentAdapter? = { _ in nil }, privacy: @escaping () -> MacAgentPrivacyLease? = { nil }) {
        self.settings = settings; self.resolve = resolve; self.privacy = privacy
        settingsObserver = settings.observe { [weak self] in self?.revoke() }
    }
    deinit { if let settingsObserver { settings.unobserve(settingsObserver) } }
    func revoke() { lock.lock(); let old = attempt; attempt = nil; record = nil; epoch = UUID(); lock.unlock(); old?.privacy?.revoke() }
    /// Saved desired route is NOT admission. No adapter -> no privacy callback or AX observer.
    func begin(language: String, modelID: String) -> Attempt {
        revoke()
        let preference = settings.snapshot()
        lock.lock(); let generation = epoch; lock.unlock()
        let adapter = preference.value.route == .none ? nil : resolve(preference.value.route)
        let destination = adapter?.destination
        let eligible = destination?.route == preference.value.route && destination?.valid == true && adapter != nil &&
            !language.isEmpty && !modelID.isEmpty && language.utf8.count <= 128 && modelID.utf8.count <= 128
        let candidate = eligible ? privacy() : nil
        let lease = candidate?.isCurrent() == true ? candidate : nil // No private/unknown capture may be upgraded later.
        let value = Attempt(language: language, modelID: modelID, privacy: lease, adapter: eligible ? adapter : nil, destination: eligible ? destination : nil)
        let fresh = settings.snapshot().revision == preference.revision
        lock.lock(); let accepted = fresh && epoch == generation
        if accepted { attempt = value }; lock.unlock()
        if !accepted { lease?.revoke() }
        return value
    }
    func finishForAgent(_ owner: Attempt) -> Ticket? {
        let preference = settings.snapshot()
        lock.lock(); defer { lock.unlock() }; return attempt === owner ? Ticket(owner, preference) : nil
    }
    @discardableResult func complete(_ owner: Attempt, text: String) -> Bool {
        lock.lock(); let current = attempt === owner && !owner.completed; lock.unlock()
        guard current else { return false }
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, owner.privacy?.isCurrent() == true else {
            lock.lock(); if attempt === owner { attempt = nil; record = nil; epoch = UUID() }; lock.unlock()
            owner.privacy?.revoke(); return false
        }
        lock.lock(); defer { lock.unlock() }
        guard attempt === owner, !owner.completed, owner.adapter != nil else { return false }
        owner.completed = true; record = Record(owner, text); return true
    }
    func dispatch(_ ticket: Ticket) -> MacAgentOutcome {
        let preference = settings.snapshot()
        guard preference.revision == ticket.preferences.revision else { return .stale }
        let owner = ticket.attempt
        guard let adapter = owner.adapter, let destination = owner.destination else { return .unavailable }
        guard let latest = resolve(preference.value.route), latest === adapter, latest.destination === destination else { return .stale }
        guard destination.valid else { return .unavailable }
        guard destination.efforts.contains(preference.value.effort) else { return .unsupportedEffort }
        guard owner.privacy?.isCurrent() == true else { revoke(); return .stale }
        lock.lock()
        guard attempt === owner, let record, record.attempt === owner, !busy else { lock.unlock(); return .stale }
        guard !record.attempted else { lock.unlock(); return .alreadyAttempted }
        guard record.text.utf8.count <= destination.maximumTextBytes else { lock.unlock(); return .refused }
        record.attempted = true; busy = true; lock.unlock() // Consume BEFORE any adapter/validation callback.
        defer { lock.lock(); busy = false; lock.unlock() }
        guard owner.privacy?.isCurrent() == true, settings.snapshot().revision == preference.revision else { return .stale }
        // Freshness callbacks are external/reentrant: an account/capability
        // descriptor may have changed during them. Resolve exact identity again
        // AFTER those callbacks, then only CPU state checks precede invocation.
        guard let finalAdapter = resolve(preference.value.route), finalAdapter === adapter,
            finalAdapter.destination === destination else { return .stale }
        let preferencesCurrent = settings.snapshot().revision == preference.revision
        let privacyActive = owner.privacy?.isActive() == true
        lock.lock(); let current = attempt === owner && self.record === record; lock.unlock()
        guard current && preferencesCurrent && privacyActive else { return .stale }
        do {
            switch try adapter.dispatch(MacAgentPayload(text: record.text, language: owner.language, modelID: owner.modelID, destination: destination, effort: preference.value.effort)) {
            case .dispatched: return .dispatched
            case .ambiguous: return .ambiguous
            case .refused: return .refused
            }
        } catch { return .ambiguous }
    }
}
