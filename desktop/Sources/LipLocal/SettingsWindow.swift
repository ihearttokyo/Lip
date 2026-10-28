import AppKit
import ApplicationServices

@MainActor final class SettingsWindowController: NSWindowController {
    private let settings: CleanupSettings
    private var vocabularyController: VocabularyWindowController?
    private let changed: () -> Void
    private let mode = NSPopUpButton()
    private let model = NSPopUpButton()
    private let modelStatus = NSTextField(wrappingLabelWithString: "")
    private var download: Task<Void, Never>?
    private let install = NSButton()
    private let canChangeModel: () -> Bool
    private let insert = NSButton(checkboxWithTitle: "Insert completed transcript at the original cursor", target: nil, action: nil)
    private let capital = NSButton(checkboxWithTitle: "Optional app rule: capitalize first word", target: nil, action: nil)
    private let period = NSButton(checkboxWithTitle: "Optional app rule: add a final period", target: nil, action: nil)
    private let hesitation = NSButton(checkboxWithTitle: "Optional app rule: remove um/uh/erm (filler mode only)", target: nil, action: nil)
    private let preview = NSTextField(wrappingLabelWithString: "")

    init(settings: CleanupSettings, canChangeModel: @escaping () -> Bool = { true }, changed: @escaping () -> Void) {
        self.settings = settings; self.changed = changed; self.canChangeModel = canChangeModel
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 580, height: 620),
            styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Lip Settings · Bark Native Features"; window.isReleasedWhenClosed = false
        super.init(window: window)
        // A fixed document in a scroll view avoids content-view/stack sizing cycles.
        let document = NSView(frame: NSRect(x: 0, y: 0, width: 580, height: 1160))
        let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 580, height: 620))
        scroll.hasVerticalScroller = true; scroll.documentView = document
        window.contentView = scroll
        var top: CGFloat = 1138
        func place(_ view: NSView, _ height: CGFloat) {
            view.frame = NSRect(x: 24, y: top - height, width: 526, height: height)
            document.addSubview(view); top -= height + 10
        }
        func label(_ text: String, height: CGFloat, heading: Bool = false) {
            let view = NSTextField(wrappingLabelWithString: text)
            view.font = heading ? .boldSystemFont(ofSize: 18) : .systemFont(ofSize: 13)
            view.textColor = heading ? .labelColor : .secondaryLabelColor
            place(view, height)
        }
        label("On-device speech models", height: 26, heading: true)
        model.addItems(withTitles: BarkProfile.allCases.map(\.title))
        model.selectItem(at: BarkProfile.allCases.firstIndex(of: BarkProfile.selected())!)
        model.target = self; model.action = #selector(selectModel); place(model, 28)
        label("Clean removes fillers, repeated words and false starts in the model itself. Pro is optional and more verbatim. Neither guarantees formatting or Wispr Flow parity. Models download separately; no silent model fallback.", height: 70)
        install.title = "Download / verify selected model"; install.target = self; install.action = #selector(installModel)
        place(install, 30)
        modelStatus.font = .systemFont(ofSize: 12); place(modelStatus, 42)
        refreshModelStatus()
        label("English-only, on-device speech recognition. Native output preserves the model's punctuation, capitalization and numeral choices. There is no extra text model or rewriting stage.", height: 54)
        label("Hardware acceleration is automatic. Core ML can use the Neural Engine, GPU and CPU for supported operations; no device selection is needed.", height: 54)
        label("Automatic short8/full15 encoder selection uses the functions bundled with this same model. The runtime handles longer audio in chunks. Review exposes approximate word timings and uncalibrated token scores—not an accuracy guarantee.", height: 70)
        label("Output handling", height: 24, heading: true)
        mode.addItems(withTitles: CleanupMode.allCases.map(\.title))
        mode.setAccessibilityLabel("Output handling")
        mode.target = self; mode.action = #selector(update)
        place(mode, 28)
        for control in [capital, period, hesitation] {
            control.target = self; control.action = #selector(update); place(control, 24)
        }
        preview.textColor = .secondaryLabelColor; preview.font = .systemFont(ofSize: 12)
        place(preview, 48)
        label("Native mode adds/removes nothing. App-rule modes are optional legacy features, not Bark capabilities. Pro retains fillers; it has no list-formatting, filler-removal, grammar-rewriting, translation or speaker-label switch.", height: 66)
        insert.state = settings.autoInsert ? .on : .off
        insert.target = self; insert.action = #selector(update); place(insert, 24)
        label("Insertion uses no clipboard or paste shortcut. It requires Accessibility and a supported unchanged field/caret; otherwise the transcript stays in Review. No Enter/Send action is generated.", height: 54)
        place(NSButton(title: "Enable Accessibility for direct insertion…", target: self, action: #selector(requestAccessibility)), 30)
        place(NSButton(title: "Portable vocabulary: import / export…", target: self, action: #selector(openVocabulary)), 30)
        label("Prepared JSON only. Encrypted locally; affects Lip Review spell checking, not speech recognition or other apps. Export is plaintext and explicit.", height: 40)
        let options = settings.options
        mode.selectItem(at: CleanupMode.allCases.firstIndex(of: options.mode)!)
        capital.state = options.capitalizeFirstWord ? .on : .off
        period.state = options.finalPeriod ? .on : .off
        hesitation.state = options.removeHesitations ? .on : .off
        refresh()
        precondition(top >= 0, "Settings document is too short")
    }
    required init?(coder: NSCoder) { fatalError("Not supported") }
    private func refreshModelStatus() {
        let profile = BarkProfile.selected()
        let present = FileManager.default.fileExists(atPath: profile.root.appendingPathComponent(profile.vocabularyFile).path)
        modelStatus.stringValue = "\(profile.downloadLabel) · " + (present ? "files present; verify with button" : "not installed; download required")
    }
    @objc private func selectModel() {
        guard download == nil && canChangeModel() else {
            model.selectItem(at: BarkProfile.allCases.firstIndex(of: BarkProfile.selected())!); NSSound.beep(); return
        }
        UserDefaults.standard.set(BarkProfile.allCases[model.indexOfSelectedItem].rawValue, forKey: "bark.model")
        refreshModelStatus(); changed()
    }
    @objc private func installModel() {
        if let download { download.cancel(); modelStatus.stringValue = "Canceling download…"; return }
        guard canChangeModel() else { modelStatus.stringValue = "Finish recording/transcription first."; return }
        let profile = BarkProfile.selected()
        install.title = "Cancel model download"; model.isEnabled = false
        download = Task { [weak self] in
            do {
                try await BarkInstaller.install(profile) { [weak self] message in
                    self?.modelStatus.stringValue = message
                }
                self?.changed()
            } catch { self?.modelStatus.stringValue = Task.isCancelled ? "Download canceled; verified files preserved." : "Setup failed: \(error)" }
            self?.download = nil; self?.install.title = "Download / verify selected model"; self?.model.isEnabled = true
        }
    }
    @objc private func openVocabulary() {
        if vocabularyController == nil { vocabularyController = VocabularyWindowController() }
        vocabularyController?.showWindow(nil)
    }
    @objc private func requestAccessibility() {
        _ = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary)
    }
    @objc private func update() {
        settings.autoInsert = insert.state == .on
        settings.options = CleanupOptions(mode: CleanupMode.allCases[mode.indexOfSelectedItem],
            capitalizeFirstWord: capital.state == .on, finalPeriod: period.state == .on,
            removeHesitations: hesitation.state == .on)
        refresh(); changed()
    }
    private func refresh() {
        let options = settings.options
        capital.isEnabled = options.mode != .verbatim
        period.isEnabled = options.mode != .verbatim
        hesitation.isEnabled = options.mode == .clean
        preview.stringValue = "Example model output: um, please send the report\n→ " + LocalCleanup.apply("um, please send the report", options: options).text
    }
}

@MainActor final class TranscriptWindowController: NSWindowController {
    private let processed = NSTextView()
    private let raw = NSTextView()
    private let model = NSTextView()
    private var vocabularySpelling: VocabularySpellingBinding?
    private let note = NSTextField(wrappingLabelWithString: "")
    init() {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 680, height: 470),
            styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = "Lip Transcript · Native Model Details"; window.isReleasedWhenClosed = false
        window.minSize = NSSize(width: 520, height: 360)
        super.init(window: window)
        let root = NSView(frame: NSRect(x: 0, y: 0, width: 680, height: 470))
        note.frame = NSRect(x: 18, y: 412, width: 644, height: 42)
        note.autoresizingMask = [.width, .minYMargin]
        note.textColor = .secondaryLabelColor; note.font = .systemFont(ofSize: 12)
        root.addSubview(note)
        let tabs = NSTabView(frame: NSRect(x: 18, y: 18, width: 644, height: 382))
        tabs.autoresizingMask = [.width, .height]
        for (title, view) in [("Output", processed), ("Original Bark transcript", raw), ("Model details", model)] {
            view.setAccessibilityLabel(title)
            view.isEditable = false; view.isSelectable = true; view.isRichText = false
            view.font = .systemFont(ofSize: title == "Model details" ? 13 : 16)
            view.textContainerInset = NSSize(width: 12, height: 12)
            view.frame = NSRect(x: 0, y: 0, width: 610, height: 340)
            view.autoresizingMask = [.width]; view.isHorizontallyResizable = false; view.isVerticallyResizable = true
            view.minSize = NSSize(width: 0, height: 340)
            view.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
            let scroll = NSScrollView(); scroll.hasVerticalScroller = true; scroll.documentView = view
            view.textContainer?.widthTracksTextView = true
            let tab = NSTabViewItem(identifier: title); tab.label = title; tab.view = scroll; tabs.addTabViewItem(tab)
        }
        root.addSubview(tabs); window.contentView = root
        vocabularySpelling = VocabularySpellingBinding(views: [processed, raw], window: window)
    }
    required init?(coder: NSCoder) { fatalError("Not supported") }
    override func showWindow(_ sender: Any?) {
        super.showWindow(sender); vocabularySpelling?.refresh()
    }
    func update(raw: String, processed: String, note: String, modelDetails: String = "") {
        self.raw.string = raw; self.processed.string = processed; self.note.stringValue = note
        self.model.string = modelDetails.isEmpty ? "No model timing details for this transcript." : modelDetails
    }
}

@MainActor func testCleanupWindows() {
    let suite = "dev.lip.local.cleanup-window-tests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let settings = SettingsWindowController(settings: CleanupSettings(defaults: defaults), changed: {})
    let review = TranscriptWindowController()
    review.update(raw: "um, please send the report", processed: "um, please send the report", note: "Native output preserved.", modelDetails: "short8 · GPU")
    for controller in [settings as NSWindowController, review] {
        let window = controller.window!; let content = window.contentView!
        content.layoutSubtreeIfNeeded()
        let fit = content.fittingSize
        precondition(fit.height <= window.contentLayoutRect.height + 1)
        precondition(fit.width <= window.contentLayoutRect.width + 1)
        precondition(!window.isVisible, "Layout test must not surface app windows")
    }
    print("Model settings/review construction/layout tests passed (6 assertions; not pixel/UI-interaction QA).")
}
