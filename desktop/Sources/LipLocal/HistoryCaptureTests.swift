import Foundation

/// Actual caller's memory state machine with synthetic facts; no app/platform/audio.
func testHistoryCapture() {
    var checks = 0
    func check(_ value: @autoclosure () -> Bool, _ label: String) {
        guard value() else { fputs("FAIL history capture: \(label)\n", stderr); exit(1) }; checks += 1
    }
    let request = UUID()
    func state(_ requested: Bool = true, _ ready: Bool = true, _ id: UUID? = nil, preparing: Bool = false) -> HistoryReadiness {
        HistoryReadiness(request: id ?? request, requested: requested, ready: ready, preparing: preparing, error: ready ? nil : .unavailable)
    }
    for readiness in [state(false, false), state(true, false, preparing: true), state(true, false)] {
        let flow = HistoryCaptureFlow(), capture = UUID()
        let id = flow.begin(readiness)
        check(!flow.needsObservation(id), "Off/preparing/error never asks for metadata")
        check(flow.resolve(id, readiness: state(), privacyEligible: true)?.retain == false, "Late ready cannot upgrade old audio")
        check(flow.resolve(id, readiness: state(), privacyEligible: true) == nil, "Immediate start exactly once")
        check(!flow.bind(record: UUID(), capture: capture), "Wrong capture cannot bind record")
    }
    for eligible in [false, true] {
        let flow = HistoryCaptureFlow(), id = flow.begin(state())
        check(flow.needsObservation(id), "Ready future capture prepares before audio")
        let admission = flow.resolve(id, readiness: state(), privacyEligible: eligible)
        check(admission?.capture == id && admission?.retain == eligible, "Timely lease or nil/timeout start as specified")
        check((admission?.settingRequest != nil) == eligible, "Only retained capture has setting identity")
        check(flow.resolve(id, readiness: state(), privacyEligible: true) == nil, "Late eligible reply cannot upgrade timed out capture")
    }
    for readiness in [state(false, false), state(true, false), state(true, true, UUID())] {
        let flow = HistoryCaptureFlow(), id = flow.begin(state())
        check(flow.resolve(id, readiness: readiness, privacyEligible: true)?.retain == false, "Off/failed/replaced request refuses retention")
    }
    for _ in 0..<3 { // Fn release, explicit cancel, consent change share actual cancel operation.
        let flow = HistoryCaptureFlow(), id = flow.begin(state()); flow.cancel()
        check(!flow.pending && flow.capture == nil, "Cancellation invalidates pending start")
        check(flow.resolve(id, readiness: state(), privacyEligible: true) == nil, "Late callback after cancellation cannot start audio")
    }
    let flow = HistoryCaptureFlow(), old = flow.begin(state()), current = flow.begin(state())
    check(flow.resolve(old, readiness: state(), privacyEligible: true) == nil, "Previous capture reply cannot start newer intent")
    check(flow.resolve(current, readiness: state(), privacyEligible: true)?.retain == true, "Current intent starts")
    let record = UUID()
    check(flow.bind(record: record, capture: current), "Current exact record bound")
    check(!flow.bind(record: UUID(), capture: current), "Cannot rebind a duplicate completed transcript")
    func event(capture: UUID? = nil, setting: UUID? = nil, item: UUID? = nil) -> HistorySaveEvent {
        HistorySaveEvent(capture: capture ?? current, settingRequest: setting ?? request, record: item ?? record, saved: true, message: "Synthetic generic status")
    }
    check(flow.accepts(event(), readiness: state()), "Current exact result may update history-only status")
    check(!flow.accepts(event(capture: old), readiness: state()), "Old capture status rejected")
    check(!flow.accepts(event(item: UUID()), readiness: state()), "Wrong record status rejected")
    check(!flow.accepts(event(setting: UUID()), readiness: state()), "Wrong consent status rejected")
    check(!flow.accepts(event(), readiness: state(false, false)), "Off rejects outstanding result")
    check(!flow.accepts(event(), readiness: state(true, true, UUID())), "Off/On supersession rejects old result")
    flow.cancel()
    check(!flow.accepts(event(), readiness: state()), "Cancel/recorder failure rejects late result")
    check(flow.resolve(current, readiness: state(), privacyEligible: true) == nil, "Canceled completed capture cannot restart")
    // Actual Fn state machine drives delayed preparation: menu-start/Fn-stop must
    // lock at intent time, not wait for asynchronous recording-start callback.
    var gesture = FnGesture(); let menu = HistoryCaptureFlow(), menuID = menu.begin(state())
    gesture.manualRecordingStarted()
    check(gesture.key(down: true, at: 1) == [.stop], "Fn tap stops pending manual recording intent")
    menu.cancel(); check(menu.resolve(menuID, readiness: state(), privacyEligible: true) == nil, "Pending menu stop cannot start audio later")
    var hold = FnGesture(); let held = HistoryCaptureFlow(); _ = hold.key(down: true, at: 0)
    let heldID = held.begin(state())
    check(hold.key(down: false, at: 0.23) == [.stop], "Hold release during prepare stops intent")
    held.cancel(); check(held.resolve(heldID, readiness: state(), privacyEligible: true) == nil, "Released hold cannot start audio later")
    check(HistoryCapturePresentation.preference(state(false, false)).contains("persistence unconfirmed"), "Failed Off remains unconfirmed, not falsely persisted")
    let genuinelyOff = HistoryReadiness(request: request, requested: false, ready: false, preparing: false, error: nil)
    check(HistoryCapturePresentation.preference(genuinelyOff) == "History preference: off · no automatic saves", "Off preference explicit")
    check(HistoryCapturePresentation.preference(state(true, false, preparing: true)).contains("on requested · preparing"), "Persisted-on startup shows preparing, not Off")
    check(HistoryCapturePresentation.preference(state()).contains("on · saves require verified context"), "Ready On separate from record result")
    check(HistoryCapturePresentation.preference(state(true, false)).contains("on requested · unavailable"), "Failed On explicit")
    print("PASS \(checks) history capture chronology/status/Fn checks; synthetic caller state only, no audio/platform/UI")
}
