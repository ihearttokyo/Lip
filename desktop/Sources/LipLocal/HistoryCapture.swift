import Foundation

/// Recording-intent state, owned by MainActor in production. No IPC/storage/audio.
/// A timeout/denial starts memory-only once; a late result never upgrades it.
final class HistoryCaptureFlow {
    struct Admission {
        let capture: UUID
        let settingRequest: UUID?
        let retain: Bool
    }
    private(set) var capture: UUID?
    private var request: UUID?
    private(set) var pending = false
    private var record: UUID?
    @discardableResult func begin(_ readiness: HistoryReadiness) -> UUID {
        let id = UUID(); capture = id; record = nil; pending = true
        request = readiness.requested && readiness.ready ? readiness.request : nil
        return id
    }
    func needsObservation(_ id: UUID) -> Bool { capture == id && pending && request != nil }
    func resolve(_ id: UUID, readiness: HistoryReadiness, privacyEligible: Bool) -> Admission? {
        guard capture == id, pending else { return nil }
        pending = false
        let retain = privacyEligible && request != nil && request == readiness.request && readiness.requested && readiness.ready
        return Admission(capture: id, settingRequest: retain ? request : nil, retain: retain)
    }
    func bind(record id: UUID, capture expected: UUID) -> Bool {
        guard capture == expected, !pending, record == nil else { return false }; record = id; return true
    }
    func accepts(_ event: HistorySaveEvent, readiness: HistoryReadiness) -> Bool {
        !pending && capture == event.capture && record == event.record && readiness.requested && readiness.ready && readiness.request == event.settingRequest && request == event.settingRequest
    }
    func cancel() { capture = nil; request = nil; pending = false; record = nil }
}

/// Preference and per-record outcome are separate: persisted-on must never look Off.
enum HistoryCapturePresentation {
    static func preference(_ value: HistoryReadiness) -> String {
        if value.preparing {
            return value.requested ? "History preference: on requested · preparing; no new saves yet" : "History preference: off requested · stopping; persistence unconfirmed"
        }
        if value.error != nil {
            return value.requested ? "History preference: on requested · unavailable; no new saves" : "History preference: off requested · persistence unconfirmed"
        }
        if value.requested {
            return value.ready ? "History preference: on · saves require verified context" : "History preference: on requested · not ready; no new saves"
        }
        return "History preference: off · no automatic saves"
    }
}
