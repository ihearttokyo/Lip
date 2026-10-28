import Foundation

/// Pure, public synthetic fixtures only. No GUI/Keychain/model/microphone/provider.
func testMacAgentShortcut(contract: Data) {
    var checks = 0
    func check(_ value: @autoclosure () -> Bool, _ label: String) { precondition(value(), label); checks += 1 }
    final class Fake: MacAgentAdapter {
        var destination = MacAgentDestination(route: .pairedCodex, provider: "Synthetic", accountLabel: "Owned fixture", accountIdentity: "synthetic", efforts: [.medium, .high], maximumTextBytes: 65536)
        var calls = 0; var text = ""; var effort: MacAgentEffort?
        var outcome = MacAgentDispatchOutcome.dispatched; var callback: (() -> Void)?; var throwing = false
        func dispatch(_ payload: MacAgentPayload) throws -> MacAgentDispatchOutcome {
            calls += 1; text = payload.text; effort = payload.effort; callback?()
            if throwing { throw NSError(domain: "synthetic", code: 1) }
            return outcome
        }
    }
    var initial = MacAgentPreferences(route: .pairedCodex, effort: .high)
    let settings = MacAgentPreferencesStore(read: { try JSONEncoder().encode(initial) }, write: { _ in true })
    let adapter = Fake(); var privateContext = false
    let coordinator = MacAgentCoordinator(settings: settings, resolve: { _ in adapter }, privacy: { MacAgentPrivacyLease(validate: { !privateContext }) })
    let first = coordinator.begin(language: "en", modelID: "clean")
    let ticket = coordinator.finishForAgent(first)!
    check(coordinator.dispatch(ticket) == .stale && adapter.calls == 0, "Unfinished audio never supplies a previous query")
    check(coordinator.complete(first, text: "Public synthetic Friday query."), "Successful producer publishes")
    check(coordinator.dispatch(ticket) == .dispatched && adapter.calls == 1, "One explicit completed-only invocation")
    check(adapter.text == "Public synthetic Friday query." && adapter.effort == .high, "Exact output and frozen desired effort")
    check(coordinator.dispatch(ticket) == .alreadyAttempted && adapter.calls == 1, "No duplicate invocation")
    let equal = settings.snapshot(); check(settings.save(equal.value), "Equal settings save succeeds")
    check(equal.revision != settings.snapshot().revision, "Settings ABA identity advances even when equal")
    check(coordinator.dispatch(ticket) == .stale, "Settings change revokes old ticket")
    let old = coordinator.begin(language: "en", modelID: "pro")
    let fresh = coordinator.begin(language: "en", modelID: "clean")
    check(!coordinator.complete(old, text: "Old result"), "Late old producer rejected")
    check(coordinator.complete(fresh, text: "Current result"), "Late old producer cannot revoke newer attempt")
    let current = coordinator.finishForAgent(fresh)!
    privateContext = true
    check(coordinator.dispatch(current) == .stale && adapter.calls == 1, "Privacy epoch revokes completed query")
    privateContext = false
    privateContext = true
    let beganPrivate = coordinator.begin(language: "en", modelID: "clean")
    let beganPrivateTicket = coordinator.finishForAgent(beganPrivate)!
    privateContext = false
    check(!coordinator.complete(beganPrivate, text: "Must remain private"), "Ineligible capture cannot upgrade after context becomes public")
    check(coordinator.dispatch(beganPrivateTicket) != .dispatched && adapter.calls == 1, "No late privacy eligibility upgrade")
    let canceled = coordinator.begin(language: "en", modelID: "clean"); let canceledTicket = coordinator.finishForAgent(canceled)!
    coordinator.revoke()
    check(!coordinator.complete(canceled, text: "Canceled output") && coordinator.dispatch(canceledTicket) == .stale, "Cancel/sleep keeps late work revoked")
    let failed = coordinator.begin(language: "en", modelID: "pro"); let failedTicket = coordinator.finishForAgent(failed)!
    check(!coordinator.complete(failed, text: " \n "), "Empty recognition produces no record")
    check(coordinator.dispatch(failedTicket) == .stale && adapter.calls == 1, "Empty decode never reuses older successful query")
    let ambiguous = coordinator.begin(language: "en", modelID: "clean"); let ambiguousTicket = coordinator.finishForAgent(ambiguous)!
    check(coordinator.complete(ambiguous, text: "Ambiguous public query"), "Fresh success after failure")
    adapter.throwing = true
    check(coordinator.dispatch(ambiguousTicket) == .ambiguous, "Thrown/unknown adapter outcome is ambiguous")
    check(coordinator.dispatch(ambiguousTicket) == .alreadyAttempted && adapter.calls == 2, "No retry/fallback after ambiguity")
    adapter.throwing = false
    initial.effort = .pro; check(settings.save(initial), "Desired unsupported mode may be saved without claiming support")
    let unsupported = coordinator.begin(language: "en", modelID: "clean"); let unsupportedTicket = coordinator.finishForAgent(unsupported)!
    check(coordinator.complete(unsupported, text: "Unsupported public mode"), "Synthetic producer still succeeds")
    check(coordinator.dispatch(unsupportedTicket) == .unsupportedEffort && adapter.calls == 2, "No effort downgrade/fallback")
    initial.effort = .medium; _ = settings.save(initial)
    let reentrant = coordinator.begin(language: "en", modelID: "clean"); let reentrantTicket = coordinator.finishForAgent(reentrant)!
    _ = coordinator.complete(reentrant, text: "Reentrant public query")
    adapter.callback = { check(coordinator.dispatch(reentrantTicket) != .dispatched, "Reentrant duplicate never invokes adapter"); coordinator.revoke() }
    check(coordinator.dispatch(reentrantTicket) == .dispatched && adapter.calls == 3, "Already-invoked action cannot be undone by reentrant revoke")
    var privacyCalls = 0
    let unadmitted = MacAgentCoordinator(settings: settings, privacy: { privacyCalls += 1; return MacAgentPrivacyLease(validate: { true }) })
    let notConnected = unadmitted.begin(language: "en", modelID: "clean"); let unavailable = unadmitted.finishForAgent(notConnected)!
    check(!unadmitted.complete(notConnected, text: "Local Review only"), "No unadmitted eligible source")
    check(unadmitted.dispatch(unavailable) == .unavailable && privacyCalls == 0, "Saved provider does not start privacy IO or admit transport")
    var saves = false
    let failedStore = MacAgentPreferencesStore(read: { nil }, write: { _ in saves })
    let confirmed = failedStore.snapshot()
    check(!failedStore.save(initial) && failedStore.snapshot().revision == confirmed.revision && failedStore.snapshot().value == MacAgentPreferences(), "Failed save preserves confirmed settings")
    saves = true; check(failedStore.save(initial) && failedStore.snapshot().revision != confirmed.revision, "Successful save advances identity")
    check(MacAgentPreferences.decode(Data("{\"version\":2}".utf8)) == MacAgentPreferences(), "Unknown/partial settings fail closed")
    check(MacAgentPreferences.decode(Data("{\"version\":true,\"route\":\"PAIRED_CODEX\",\"effort\":\"HIGH\",\"optionFn\":true}".utf8)) == MacAgentPreferences(), "Boolean is not a schema version")
    let refusingAdapter = Fake(); refusingAdapter.outcome = .refused
    let refusalSettings = MacAgentPreferencesStore(read: { try JSONEncoder().encode(MacAgentPreferences(route: .pairedCodex)) }, write: { _ in true })
    let refusalCoordinator = MacAgentCoordinator(settings: refusalSettings, resolve: { _ in refusingAdapter }, privacy: { MacAgentPrivacyLease(validate: { true }) })
    let refused = refusalCoordinator.begin(language: "en", modelID: "clean")
    _ = refusalCoordinator.complete(refused, text: "Refused public fixture")
    let refusalTicket = refusalCoordinator.finishForAgent(refused)!
    check(refusalCoordinator.dispatch(refusalTicket) == .refused && refusingAdapter.calls == 1, "Invoked adapter returns only dispatch-state outcome, never coordinator-only nothing-sent state")
    check(refusalCoordinator.dispatch(refusalTicket) == .alreadyAttempted && refusingAdapter.calls == 1, "Adapter refusal remains consumed without retry")
    let accountAdapter = Fake(); var changeAccount = false
    let accountSettings = MacAgentPreferencesStore(read: { try JSONEncoder().encode(MacAgentPreferences(route: .pairedCodex)) }, write: { _ in true })
    let accountCoordinator = MacAgentCoordinator(settings: accountSettings, resolve: { _ in accountAdapter }, privacy: {
        MacAgentPrivacyLease(validate: {
            if changeAccount {
                changeAccount = false
                accountAdapter.destination = MacAgentDestination(route: .pairedCodex, provider: "Synthetic", accountLabel: "Different fixture account", accountIdentity: "different", efforts: [.medium], maximumTextBytes: 65536)
            }
            return true
        })
    })
    let accountAttempt = accountCoordinator.begin(language: "en", modelID: "clean")
    _ = accountCoordinator.complete(accountAttempt, text: "Account-bound public fixture")
    let accountTicket = accountCoordinator.finishForAgent(accountAttempt)!
    changeAccount = true
    check(accountCoordinator.dispatch(accountTicket) == .stale && accountAdapter.calls == 0, "Privacy callback account swap cannot invoke old destination")
    var callbackStore: MacAgentPreferencesStore?
    let reentrantPreferences = MacAgentPreferencesStore(read: { nil }, write: { _ in _ = callbackStore?.snapshot(); return true })
    callbackStore = reentrantPreferences
    check(reentrantPreferences.save(MacAgentPreferences()), "Preference writer can inspect snapshot without state-lock deadlock")
    check(MacAgentPreferences.decode(Data("{\"version\":1.0,\"route\":\"PAIRED_CODEX\",\"effort\":\"HIGH\",\"optionFn\":true}".utf8)) == MacAgentPreferences(), "Floating schema version fails closed")
    let fixture = try! JSONSerialization.jsonObject(with: contract) as! [String: Any]
    check(fixture["schema"] as? String == "lip-agent-desired-ui-modes-v1", "Shared contract identity")
    check(fixture["routes"] as? [String] == MacAgentRoute.allCases.map(\.rawValue), "Actual Swift route IDs match Android fixture")
    let efforts = fixture["efforts"] as! [[String: String]]
    check(efforts.map { $0["id"]! } == MacAgentEffort.allCases.map(\.rawValue) && efforts.map { $0["label"]! } == MacAgentEffort.allCases.map(\.title), "Actual effort IDs/labels match fixture")
    check(fixture["defaultRoute"] as? String == MacAgentPreferences().route.rawValue && fixture["defaultEffort"] as? String == MacAgentPreferences().effort.rawValue, "Shared defaults NONE/MEDIUM")
    check((fixture["productionAdmittedRoutes"] as? [String]) == MacAgentCoordinator.productionAdmittedRoutes.map(\.rawValue), "No production route invented")
    var chord = FnAgentChord()
    check(chord.flags(fn: true, option: true, enabled: true, recording: false, preparing: false, working: false) == .unavailable, "Idle alternate never starts capture")
    check(chord.flags(fn: false, option: true, enabled: true, recording: false, preparing: false, working: false) == .none, "Fn-first release suppressed")
    check(chord.flags(fn: false, option: false, enabled: true, recording: false, preparing: false, working: false) == .none, "Option release suppressed")
    check(chord.flags(fn: false, option: true, enabled: true, recording: true, preparing: false, working: false) == .none, "Option-first alone does nothing")
    check(chord.flags(fn: true, option: true, enabled: true, recording: true, preparing: false, working: false) == .finishAgent, "Option-first/Fn finishes existing recording")
    check(chord.flags(fn: true, option: false, enabled: true, recording: false, preparing: false, working: true) == .none, "Option released first cannot ordinary-stop")
    check(chord.flags(fn: false, option: false, enabled: true, recording: false, preparing: false, working: true) == .none, "Final Fn release cannot start/cancel")
    check(chord.flags(fn: true, option: false, enabled: true, recording: false, preparing: false, working: false) == .ordinary(true), "Ordinary Fn starts unchanged")
    check(chord.flags(fn: true, option: true, enabled: true, recording: true, preparing: false, working: false) == .finishAgent, "Option pressed during held Fn routes agent finish")
    check(chord.flags(fn: true, option: true, enabled: true, recording: false, preparing: false, working: true) == .none, "Repeat ignored")
    chord.reset()
    check(chord.flags(fn: true, option: true, enabled: true, recording: false, preparing: true, working: false) == .cancelPreparing, "Preparation never becomes new alternate recording")
    chord.reset()
    check(chord.flags(fn: true, option: true, enabled: true, recording: false, preparing: false, working: true) == .unavailable, "Working never sends previous transcript")
    chord.reset()
    check(chord.flags(fn: true, option: true, enabled: false, recording: false, preparing: false, working: false) == .ordinary(true), "Disabled chord preserves ordinary Fn")
    chord.reset()
    check(chord.flags(fn: true, option: false, enabled: true, recording: false, preparing: false, working: false, fnEvent: false, optionEvent: false) == .none, "Unrelated modifier event never starts an already-held Fn at listener startup")
    check(chord.flags(fn: true, option: true, enabled: true, recording: false, preparing: false, working: false, fnEvent: false, optionEvent: false) == .none, "Unrelated modifier event never invents a chord")
    testGestures(); testManualRecordingGestures()
    print("Mac agent/shortcut pure tests passed (\(checks) assertions; synthetic adapter only, not actual route/runtime).")
}
