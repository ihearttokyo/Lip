#if !arch(arm64)
#error("Lip requires Apple Silicon and macOS 26 or newer.")
#endif
import AppKit
import AVFoundation
import ApplicationServices

let cliProfile: BarkProfile = CommandLine.arguments.contains("--clean") ? .clean : .pro
let modelRoot = FileManager.default.homeDirectoryForCurrentUser
    .appendingPathComponent("Library/Application Support/LipLocal/BarkPro/coreml")

@MainActor final class LipApp: NSObject, NSApplicationDelegate, NSMenuDelegate {
    var item: NSStatusItem!
    var status: NSMenuItem!
    var copyItem: NSMenuItem!
    var rawCopyItem: NSMenuItem!
    var tap: CFMachPort?
    var tapSource: CFRunLoopSource?
    var timer: Timer?
    var recorder: AVAudioRecorder?
    var audioURL: URL?
    var recordingDeadline: Timer?
    var gesture = FnGesture()
    var modelReady = false
    var lastPermissionCheck = 0.0
    var lastDiagnostics: Data?
    var fnEvents = 0
    var operation: UInt64 = 0
    var inserting = false
    var sessionAutoInsert = true
    var sessionTarget: CursorTarget?
    var sessionTargetIssue = ""
    var lastInsertionStatus = "not_attempted"
    var working = false
    var transcript = ""
    var rawTranscript = ""
    var cleanupNote = ""
    let cleanupSettings = CleanupSettings()
    var sessionCleanup = CleanupOptions()
    var lastModelDetails = ""
    var settingsController: SettingsWindowController?
    var transcriptController: TranscriptWindowController?
    var activeProfile = BarkProfile.selected()
    var engine = BarkEngine(root: BarkProfile.selected().root, profile: BarkProfile.selected())
    var modelPreparation: UInt64 = 0
    let history = HistoryService() // OFF default; initializer does no vault/key IO while off.
    let historyFlow = HistoryCaptureFlow()
    var historyEnvironment: HistoryPlatformEnvironment?
    var historyObservation: HistoryPrivacyMonitor?
    var historyTicket: HistoryAutomaticTicket?
    var historyRecordedAt = Date()
    var historyPreparationTimeout: DispatchWorkItem?
    var historyController: HistoryWindowController?
    var historyStatus: NSMenuItem!
    var historyPreferenceStatus: NSMenuItem!

    func applicationDidFinishLaunching(_ notification: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.title = "Lip"
        let menu = NSMenu()
        status = NSMenuItem(title: "Loading \(activeProfile.title) locally…", action: nil, keyEquivalent: "")
        menu.addItem(status)
        add(menu, "Enable Fn controls / check permissions", #selector(enableControls))
        add(menu, "Enable direct insertion / Accessibility…", #selector(enableInsertion))
        add(menu, "Start / finish recording", #selector(toggleRecording))
        add(menu, "Cancel recording", #selector(cancelFromMenu))
        menu.addItem(.separator())
        copyItem = add(menu, "Copy last transcript", #selector(copyTranscript)); copyItem.isEnabled = false
        rawCopyItem = add(menu, "Copy original transcript", #selector(copyRawTranscript)); rawCopyItem.isEnabled = false
        add(menu, "Review original / formatted transcript…", #selector(showTranscript))
        add(menu, "Settings…", #selector(showSettings)).keyEquivalent = ","
        add(menu, "Local dictation history…", #selector(showHistory))
        historyPreferenceStatus = NSMenuItem(title: HistoryCapturePresentation.preference(history.snapshot()), action: nil, keyEquivalent: "")
        menu.addItem(historyPreferenceStatus)
        historyStatus = NSMenuItem(title: "History: no new record outcome this session", action: nil, keyEquivalent: "")
        menu.addItem(historyStatus)
        menu.delegate = self
        menu.addItem(.separator())
        add(menu, "Quit Lip", #selector(quit))
        item.menu = menu
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(sleeping), name: NSWorkspace.willSleepNotification, object: nil)
        DistributedNotificationCenter.default().addObserver(self, selector: #selector(sleeping), name: NSNotification.Name("com.apple.screenIsLocked"), object: nil)
        timer = Timer.scheduledTimer(withTimeInterval: 0.04, repeats: true) { [weak self] _ in
            guard let self else { return }
            MainActor.assumeIsolated {
                let now = ProcessInfo.processInfo.systemUptime
                self.perform(self.gesture.tick(at: now))
                if now - self.lastPermissionCheck >= 1 {
                    self.lastPermissionCheck = now
                    self.refreshControls()
                }
            }
        }
        if let timer { RunLoop.main.add(timer, forMode: .common) }
        // Do not request permissions or surface windows at startup.
        installTap()
        prepareSelectedModel()
    }
    func prepareSelectedModel() {
        guard !working, recorder == nil, !historyFlow.pending else { return }
        let profile = BarkProfile.selected()
        if activeProfile != profile {
            activeProfile = profile
            engine = BarkEngine(root: profile.root, profile: profile)
        }
        setStatus("Preparing \(profile.title) locally · first compilation may take minutes…")
        modelReady = false; modelPreparation &+= 1
        let generation = modelPreparation; let selectedEngine = engine
        Task {
            do {
                try await selectedEngine.prepare()
                guard generation == modelPreparation else { return }
                modelReady = true; refreshControls(forceStatus: true)
            } catch {
                guard generation == modelPreparation else { return }
                setStatus("Selected model unavailable · download/verify in Settings · \(error)")
            }
        }
    }
    @discardableResult func add(_ menu: NSMenu, _ title: String, _ action: Selector) -> NSMenuItem {
        let entry = NSMenuItem(title: title, action: action, keyEquivalent: ""); entry.target = self; menu.addItem(entry); return entry
    }
    func setStatus(_ text: String) { status.title = text }
    func installTap() {
        guard tap == nil, CGPreflightListenEventAccess() else { return }
        let mask = CGEventMask(1 << CGEventType.flagsChanged.rawValue)
        tap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .listenOnly,
            eventsOfInterest: mask, callback: { _, type, event, context in
                guard let context else { return Unmanaged.passUnretained(event) }
                let app = Unmanaged<LipApp>.fromOpaque(context).takeUnretainedValue()
                if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
                    DispatchQueue.main.async { app.gesture.reset(); app.cancelRecording(); if let tap = app.tap { CGEvent.tapEnable(tap: tap, enable: true) } }
                } else if type == .flagsChanged && event.getIntegerValueField(.keyboardEventKeycode) == 63 {
                    let down = event.flags.contains(.maskSecondaryFn)
                    DispatchQueue.main.async { app.fnEvents += 1; app.perform(app.gesture.key(down: down, at: ProcessInfo.processInfo.systemUptime)); app.writeDiagnostics() }
                }
                return Unmanaged.passUnretained(event)
            }, userInfo: Unmanaged.passUnretained(self).toOpaque())
        if let tap {
            tapSource = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
            CFRunLoopAddSource(CFRunLoopGetMain(), tapSource, .commonModes)
            CGEvent.tapEnable(tap: tap, enable: true)
        }
    }
    func menuWillOpen(_ menu: NSMenu) { refreshHistoryPreference() }
    func refreshHistoryPreference() { historyPreferenceStatus?.title = HistoryCapturePresentation.preference(history.snapshot()) }
    func refreshControls(forceStatus: Bool = false) {
        refreshHistoryPreference()
        let allowed = CGPreflightListenEventAccess()
        if !allowed, let oldTap = tap {
            // Revocation must not leave a recording locked on without a stop key.
            gesture.reset(); cancelRecording()
            if let source = tapSource { CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes) }
            CFMachPortInvalidate(oldTap); tap = nil; tapSource = nil
        }
        if allowed && tap == nil { installTap() }
        let issue = fnControlIssue(inputAllowed: allowed, tapInstalled: tap != nil,
            microphoneAllowed: AVCaptureDevice.authorizationStatus(for: .audio) == .authorized)
            ?? (cleanupSettings.autoInsert && !AXIsProcessTrusted() ? "Direct insertion needs Accessibility · recording still available" : nil)
        if !working && recorder == nil && !historyFlow.pending {
            item.button?.title = issue == nil ? "Lip" : "Lip !"
            if let issue { setStatus(issue) }
            else if modelReady && (forceStatus || lastControlsIssue != nil) { setStatus("Ready · hold Fn or double-tap Fn") }
        }
        lastControlsIssue = issue
        writeDiagnostics()
    }
    private var lastControlsIssue: String?
    func writeDiagnostics() {
        // Local operational state only: no audio, transcript, other keystrokes or editor data.
        let root = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/LipLocal")
        let value: [String: Any] = ["pid": ProcessInfo.processInfo.processIdentifier,
            "inputMonitoringAllowed": CGPreflightListenEventAccess(),
            "microphoneAllowed": AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
            "tapInstalled": tap != nil,
            "tapEnabled": tap.map { CGEvent.tapIsEnabled(tap: $0) } ?? false,
            "fnEvents": fnEvents, "recording": recorder != nil, "transcribing": working && !inserting,
            "inserting": inserting, "insertionStatus": lastInsertionStatus, "cursorTargetCaptured": sessionTarget != nil, "accessibilityAllowed": AXIsProcessTrusted(), "autoInsert": cleanupSettings.autoInsert,
            "cleanupMode": cleanupSettings.options.mode.rawValue, "selectedModel": activeProfile.rawValue,
            "modelReady": modelReady, "computePolicy": "automatic_all",
            "transcriptAvailable": !rawTranscript.isEmpty, "transcriptCharacters": transcript.count]
        if let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), data != lastDiagnostics {
            lastDiagnostics = data
            try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let url = root.appendingPathComponent("fn-diagnostics.json")
            try? data.write(to: url, options: .atomic)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        }
    }
    @objc func enableControls() {
        if !CGPreflightListenEventAccess() { _ = CGRequestListenEventAccess() }
        refreshControls(forceStatus: true)
        AVCaptureDevice.requestAccess(for: .audio) { [weak self] _ in
            DispatchQueue.main.async { self?.refreshControls(forceStatus: true) }
        }
    }
    @objc func enableInsertion() {
        _ = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary)
        refreshControls(forceStatus: true)
    }
    func perform(_ actions: [FnGesture.Action]) {
        for action in actions {
            switch action { case .start: startRecording(); case .stop: finishRecording(); case .cancel: cancelRecording() }
        }
    }
    @objc func toggleRecording() {
        if recorder != nil || historyFlow.pending {
            gesture.reset()
            finishRecording()
        } else if !working {
            gesture.reset()
            startRecording(manual: true)
        }
        writeDiagnostics()
    }
    @objc func cancelFromMenu() {
        gesture.reset()
        cancelRecording()
        writeDiagnostics()
    }
    func startRecording(manual: Bool = false) {
        guard !working, recorder == nil, !historyFlow.pending else { return }
        guard modelReady else { setStatus("Selected model not ready · download/verify in Settings"); return }
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else { setStatus("Enable microphone from Lip menu first"); return }
        let capture = historyFlow.begin(history.snapshot())
        if manual { gesture.manualRecordingStarted() }
        historyPreparationTimeout?.cancel(); historyPreparationTimeout = nil
        historyObservation?.cancel(); historyObservation = nil; historyTicket = nil
        historyStatus.title = "History: new recording not retained yet"
        guard historyFlow.needsObservation(capture) else {
            beginPreparedRecording(capture: capture, lease: nil, manual: manual); return
        }
        // Construct/read public environment only for consented, ready history.
        if historyEnvironment == nil { historyEnvironment = HistoryPlatformEnvironment(app: NSApp) }
        setStatus("Preparing history privacy check · release/tap cancels start…")
        let timeout = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.beginPreparedRecording(capture: capture, lease: nil, manual: manual)
        }
        historyPreparationTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: timeout)
        historyObservation = HistoryPrivacyMonitor(feed: historyEnvironment!.feed) { [weak self] lease in
            MainActor.assumeIsolated { self?.beginPreparedRecording(capture: capture, lease: lease, manual: manual) }
        }
    }
    func beginPreparedRecording(capture: UUID, lease: HistoryPrivacyLease?, manual: Bool) {
        let now = ProcessInfo.processInfo.systemUptime
        guard let admission = historyFlow.resolve(capture, readiness: history.snapshot(), privacyEligible: lease?.snapshot(now: now) == .eligible) else { return }
        historyPreparationTimeout?.cancel(); historyPreparationTimeout = nil
        if admission.retain, let lease {
            historyTicket = history.automaticTicket(capture: capture, privacy: lease, now: now)
            if historyTicket?.settingRequest != admission.settingRequest { historyTicket = nil }
        }
        if historyTicket == nil { historyObservation?.cancel(); historyObservation = nil }
        // No async result can attach history after this point or upgrade this audio.
        guard !working, recorder == nil, modelReady else { cancelHistoryCapture(); return }
        sessionAutoInsert = cleanupSettings.autoInsert
        sessionTarget = nil; sessionTargetIssue = ""; lastInsertionStatus = "capturing_cursor"
        if sessionAutoInsert {
            switch CursorInsertion.capture(frontPID: NSWorkspace.shared.frontmostApplication?.processIdentifier) {
            case .success(let target): sessionTarget = target; lastInsertionStatus = "cursor_captured"
            case .failure(let issue): sessionTargetIssue = issue.message; lastInsertionStatus = "cursor_unavailable"
            }
        }
        do {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("lip-\(UUID().uuidString).wav")
            let value = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: 16000, AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false])
            guard value.prepareToRecord() else { throw Failure("Microphone could not prepare") }
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            guard value.record(forDuration: BarkAudioBudget.seconds) else { throw Failure("Microphone could not start") }
            operation &+= 1
            recorder = value; audioURL = url
            historyRecordedAt = Date()
            historyStatus.title = historyTicket == nil ? "History: memory-only recording (off, unavailable or unverified context)" : "History: checking context; save not yet confirmed"
            sessionCleanup = cleanupSettings.options
            item.button?.title = "Lip ●"; setStatus("Recording locally · release hold / tap to finish locked mode")
            recordingDeadline = Timer.scheduledTimer(withTimeInterval: BarkAudioBudget.seconds, repeats: false) { [weak self] _ in MainActor.assumeIsolated { self?.gesture.reset(); self?.finishRecording() } }
            if let recordingDeadline { RunLoop.main.add(recordingDeadline, forMode: .common) }
        } catch { cancelHistoryCapture(); gesture.reset(); setStatus("Recording failed · \(error)") }
    }
    func finishRecording() {
        if historyFlow.pending {
            cancelHistoryCapture(); setStatus("Canceled before audio started · history check did not finish"); return
        }
        guard let recorder, let url = audioURL else { return }
        recorder.stop(); self.recorder = nil; audioURL = nil
        recordingDeadline?.invalidate(); recordingDeadline = nil
        let generation = operation
        let capture = historyFlow.capture
        let ticket = historyTicket
        let profile = activeProfile.rawValue
        let recordedAt = historyRecordedAt
        let target = sessionTarget
        let targetIssue = sessionTargetIssue
        let wantsInsertion = sessionAutoInsert
        working = true; item.button?.title = "Lip …"; setStatus("Transcribing locally…")
        Task { [self] in
            var historySaveQueued = false
            var transcribed = false
            var sourceWasLimited = false
            defer {
                if !historySaveQueued, historyFlow.capture == capture {
                    historyObservation?.cancel(); historyObservation = nil; historyTicket = nil
                    historyStatus.title = "History: no record saved for this capture"
                }
                if BarkAudioBudget.retainSource(transcribed: transcribed, limited: sourceWasLimited, canceled: generation != operation) {
                    let retained: URL
                    do { retained = try retainRecoveryRecording(url) }
                    catch { retained = url } // Original private temporary file remains recoverable.
                    let reason = sourceWasLimited ? "Audio exceeded the decode limit; its tail was not transcribed." : "No completed transcript was produced."
                    setStatus("\(reason) Audio kept locally at \(retained.path). No upload; remove it after recovery.")
                } else { try? FileManager.default.removeItem(at: url) }
                working = false; inserting = false; sessionTarget = nil
                item.button?.title = "Lip"; writeDiagnostics()
            }
            do {
                let native = try await engine.transcribeDetailed(url, allowRecorderPacketOverrun: true)
                let raw = native.text
                guard generation == operation else { return }
                if raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { setStatus("No speech detected · previous transcript kept") }
                else {
                    transcribed = true
                    sourceWasLimited = (native.sourceAudioSeconds ?? native.audioSeconds) > native.audioSeconds
                    rawTranscript = raw; lastModelDetails = native.details
                    applyCleanup(sessionCleanup)
                    if let capture, let ticket {
                        let record = HistoryRecord(id: UUID(), created: recordedAt, profile: profile, original: raw, output: transcript, modelDetails: native.details)
                        if historyFlow.bind(record: record.id, capture: capture) {
                            historySaveQueued = true
                            history.saveAutomatically(record, ticket: ticket) { [weak self] event in
                                guard let self, self.operation == generation,
                                    self.historyFlow.accepts(event, readiness: self.history.snapshot()) else { return }
                                self.historyStatus.title = "History: " + event.message
                                self.historyObservation?.cancel(); self.historyObservation = nil; self.historyTicket = nil
                            }
                        }
                    } else { historyObservation?.cancel(); historyObservation = nil }
                    if wantsInsertion {
                        if let target {
                            inserting = true; setStatus("Inserting at original cursor…"); writeDiagnostics()
                            let outcome = await CursorInsertion.insert(transcript, into: target) { [self] in
                                await MainActor.run { self.operation == generation && self.cleanupSettings.autoInsert }
                            }
                            if generation == operation {
                                lastInsertionStatus = outcome.verified ? "verified" : (outcome.dispatched ? "unconfirmed_or_partial" : "refused_before_dispatch")
                                setStatus(outcome.message)
                            }
                        } else { lastInsertionStatus = "no_start_cursor"; setStatus("Not inserted · \(targetIssue) Transcript kept in Review.") }
                    }
                }
            } catch { setStatus("Transcription failed · previous transcript kept · \(error)") }
        }
    }
    private func retainRecoveryRecording(_ url: URL,
        root: URL = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/LipLocal/RecoveryAudio", isDirectory: true)) throws -> URL {
        let manager = FileManager.default
        // Never follow a user-created app-directory alias into a cloud/shared path.
        guard root.standardizedFileURL.path == root.resolvingSymlinksInPath().standardizedFileURL.path else { throw Failure("Recovery directory is an alias") }
        try manager.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try manager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: root.path)
        var privateRoot = root
        var flags = URLResourceValues(); flags.isExcludedFromBackup = true
        try privateRoot.setResourceValues(flags)
        try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        let destination = root.appendingPathComponent(url.lastPathComponent)
        try manager.moveItem(at: url, to: destination) // Unique owned name; never replace another recovery.
        return destination
    }

    func cancelHistoryCapture() {
        historyFlow.cancel(); historyPreparationTimeout?.cancel(); historyPreparationTimeout = nil
        historyObservation?.cancel(); historyObservation = nil; historyTicket = nil
        historyStatus?.title = "History: capture canceled / no new save confirmed"
    }
    @objc func cancelRecording() {
        cancelHistoryCapture()
        operation &+= 1; sessionTarget = nil
        recorder?.stop(); recorder = nil
        if let audioURL { try? FileManager.default.removeItem(at: audioURL) }; audioURL = nil
        recordingDeadline?.invalidate(); recordingDeadline = nil
        if !working { item.button?.title = "Lip"; setStatus("Canceled · hold Fn or double-tap Fn") }
        else { setStatus(inserting ? "Insertion canceled; a prefix may already be present. Check the field before retrying." : "Canceled · local decoding finishing; no automatic insertion") }
    }
    @objc func sleeping() { gesture.reset(); cancelRecording() }
    @objc func copyTranscript() {
        guard !transcript.isEmpty else { return }
        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(transcript, forType: .string)
        setStatus("Copied · paste into your intended field")
    }
    @objc func copyRawTranscript() {
        guard !rawTranscript.isEmpty else { return }
        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(rawTranscript, forType: .string)
        setStatus("Original copied · paste into your intended field")
    }
    func applyCleanup(_ options: CleanupOptions) {
        guard !rawTranscript.isEmpty else { return }
        let result = LocalCleanup.apply(rawTranscript, options: options)
        transcript = result.text; cleanupNote = result.note
        copyItem.isEnabled = true; rawCopyItem.isEnabled = true
        transcriptController?.update(raw: rawTranscript, processed: transcript, note: cleanupNote, modelDetails: lastModelDetails)
        setStatus("Transcript ready · \(options.mode.title) · review/copy")
        writeDiagnostics()
    }
    @objc func showHistory() {
        if historyController == nil {
            historyController = HistoryWindowController(history: history) { [weak self] _ in self?.historyConsentChanged() }
        }
        historyController?.showWindow(nil)
        historyController?.window?.makeKeyAndOrderFront(nil)
    }
    func historyConsentChanged() {
        // Called at consent intent, not delayed until storage persistence completes.
        let wasPending = historyFlow.pending
        cancelHistoryCapture()
        historyEnvironment = nil
        refreshHistoryPreference()
        if wasPending { gesture.reset(); setStatus("Recording start canceled because history consent changed · start again") }
    }
    @objc func showSettings() {
        if settingsController == nil {
            settingsController = SettingsWindowController(settings: cleanupSettings, canChangeModel: { [weak self] in
                guard let self else { return false }; return !self.working && self.recorder == nil && !self.historyFlow.pending
            }) { [weak self] in
                guard let self else { return }
                // An in-flight recording keeps its start-time settings snapshot.
                if !self.working && self.recorder == nil && !self.historyFlow.pending {
                    self.applyCleanup(self.cleanupSettings.options)
                    if self.activeProfile != BarkProfile.selected() || !self.modelReady { self.prepareSelectedModel() }
                }
                self.writeDiagnostics()
            }
        }
        settingsController?.showWindow(nil)
        settingsController?.window?.makeKeyAndOrderFront(nil)
    }
    @objc func showTranscript() {
        if transcriptController == nil { transcriptController = TranscriptWindowController() }
        transcriptController?.update(raw: rawTranscript, processed: transcript,
            note: rawTranscript.isEmpty ? "No transcript yet." : cleanupNote, modelDetails: lastModelDetails)
        transcriptController?.showWindow(nil)
        transcriptController?.window?.makeKeyAndOrderFront(nil)
    }
    @objc func quit() { cancelRecording(); NSApp.terminate(nil) }
}

if CommandLine.arguments.contains("--test-history-import-flow") {
    guard CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--test-history-import-flow" else {
        fputs("Invalid synthetic import flow arguments.\n", stderr); exit(64)
    }
    testHistoryImportFlow()
} else if CommandLine.arguments.contains("--test-history-import") {
    guard CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--test-history-import" else {
        fputs("Invalid synthetic import arguments.\n", stderr); exit(64)
    }
    testHistoryImportAdmission()
} else if CommandLine.arguments.contains("--test-history-transfer") {
    guard CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--test-history-transfer" else {
        fputs("Invalid synthetic transfer arguments.\n", stderr); exit(64)
    }
    testHistoryPortable()
} else if CommandLine.arguments.contains("--test-history-transfer-receive") {
    guard CommandLine.arguments.count == 3, CommandLine.arguments[1] == "--test-history-transfer-receive" else {
        fputs("Invalid synthetic transfer arguments.\n", stderr); exit(64)
    }
    do { try testHistoryPortableReceive(CommandLine.arguments[2]) }
    catch { fputs("Synthetic transfer validation failed.\n", stderr); exit(65) }
} else if CommandLine.arguments.contains("--test-history") {
    testTranscriptHistory()
    testHistoryPrivacy()
    testHistoryCapture()
} else if CommandLine.arguments.contains("--self-test") {
    testGestures()
    testControlReadiness()
    testManualRecordingGestures()
    testCleanup()
    testCursorInsertionGuards()
    testNativeBarkPolicy()
    testModelCatalog()
} else if let index = CommandLine.arguments.firstIndex(of: "--install-model"), CommandLine.arguments.count > index + 1, let profile = BarkProfile(rawValue: CommandLine.arguments[index + 1]) {
    Task {
        do { try await BarkInstaller.install(profile) { print($0) }; exit(0) }
        catch { fputs("Model installation failed: \(error)\n", stderr); exit(1) }
    }
    dispatchMain()
} else if CommandLine.arguments.contains("--insertion-fixture") {
    MainActor.assumeIsolated {
        let app = NSApplication.shared
        app.setActivationPolicy(.regular)
        let delegate = InsertionFixtureApp(); app.delegate = delegate
        withExtendedLifetime(delegate) { app.run() }
    }
} else if CommandLine.arguments.contains("--test-cleanup-windows") {
    MainActor.assumeIsolated {
        _ = NSApplication.shared
        NSApp.setActivationPolicy(.accessory)
        testCleanupWindows()
    }
} else if CommandLine.arguments.contains("--test-microphone") {
    Task { @MainActor in exit(await runMicrophoneProbe()) }
    dispatchMain()
} else if let index = CommandLine.arguments.firstIndex(of: "--transcribe-details"), CommandLine.arguments.count > index + 1 {
    let url = URL(fileURLWithPath: CommandLine.arguments[index + 1])
    Task {
        do {
            let result = try await BarkEngine(root: cliProfile.root, profile: cliProfile).transcribeDetailed(url)
            print(String(decoding: try JSONEncoder().encode(result), as: UTF8.self)); exit(0)
        } catch { fputs("\(error)\n", stderr); exit(1) }
    }
    dispatchMain()
} else if let index = CommandLine.arguments.firstIndex(of: "--transcribe"), CommandLine.arguments.count > index + 1 {
    let audio = URL(fileURLWithPath: CommandLine.arguments[index + 1])
    Task {
        do { let text = try await BarkEngine(root: cliProfile.root, profile: cliProfile).transcribe(audio); print(text); exit(0) }
        catch { fputs("\(error)\n", stderr); exit(1) }
    }
    dispatchMain()
} else if CommandLine.arguments.contains("--prepare") {
    Task {
        do { try await BarkEngine(root: cliProfile.root, profile: cliProfile).prepare(); print("Bark Pro loaded locally."); exit(0) }
        catch { fputs("\(error)\n", stderr); exit(1) }
    }
    dispatchMain()
} else {
    MainActor.assumeIsolated {
        let app = NSApplication.shared
        let delegate = LipApp(); app.delegate = delegate
        app.setActivationPolicy(.accessory)
        app.run()
    }
}
