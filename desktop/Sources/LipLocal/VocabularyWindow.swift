import AppKit
import UniformTypeIdentifiers

/// A local, explicit prepared-JSON workflow. No history extraction or cloud transport.
@MainActor final class VocabularyWindowController: NSWindowController, NSWindowDelegate {
    private let store: VocabularyStore
    private let status = NSTextField(wrappingLabelWithString: "Choose a prepared vocabulary JSON file to review. No private history is scanned.")
    private let detail = NSTextView()
    private let importButton = NSButton(title: "Import JSON…", target: nil, action: nil)
    private let exportButton = NSButton(title: "Export plaintext JSON…", target: nil, action: nil)
    private let cancelButton = NSButton(title: "Cancel", target: nil, action: nil)
    private let refreshButton = NSButton(title: "Refresh", target: nil, action: nil)
    private var operation: UUID?
    private var lease: VocabularyLease?
    private var sheet: NSWindow?
    init(store: VocabularyStore = .shared) {
        self.store = store
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 680, height: 560), styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = "Lip · Portable vocabulary"; window.isReleasedWhenClosed = false; window.minSize = NSSize(width: 600, height: 500)
        super.init(window: window); window.delegate = self
        let root = NSView(frame: NSRect(x: 0, y: 0, width: 680, height: 560)); window.contentView = root
        let heading = NSTextField(labelWithString: "Your words, kept on this Mac")
        heading.font = .systemFont(ofSize: 23, weight: .semibold); heading.frame = NSRect(x: 24, y: 510, width: 632, height: 30); heading.autoresizingMask = [.width, .minYMargin]; root.addSubview(heading)
        let note = NSTextField(wrappingLabelWithString: "Import the shared Lip v1 JSON format; merges preserve existing approved spelling. Encrypted locally with a separate device-only Keychain key, excluded from backup. English single words apply only to Lip Review spell checking—not other apps, speech recognition, transcript rewriting or swipe models. Other languages and phrases stay in exports but are not activated.")
        note.font = .systemFont(ofSize: 12); note.textColor = .secondaryLabelColor
        note.frame = NSRect(x: 24, y: 400, width: 632, height: 100); note.autoresizingMask = [.width, .minYMargin]; root.addSubview(note)
        status.frame = NSRect(x: 24, y: 348, width: 632, height: 44); status.font = .systemFont(ofSize: 12); status.autoresizingMask = [.width, .minYMargin]; root.addSubview(status)
        detail.isEditable = false; detail.isRichText = false; detail.isSelectable = true
        detail.isContinuousSpellCheckingEnabled = false; detail.isGrammarCheckingEnabled = false; detail.isAutomaticSpellingCorrectionEnabled = false; detail.isAutomaticTextReplacementEnabled = false; detail.isAutomaticLinkDetectionEnabled = false; detail.writingToolsBehavior = .none
        detail.font = .systemFont(ofSize: 14); detail.textContainerInset = NSSize(width: 12, height: 12)
        detail.setAccessibilityLabel("Vocabulary counts and reviewed terms")
        let scroll = NSScrollView(frame: NSRect(x: 24, y: 72, width: 632, height: 266)); scroll.hasVerticalScroller = true; scroll.autoresizingMask = [.width, .height]; scroll.documentView = detail
        detail.frame = NSRect(origin: .zero, size: scroll.contentSize); detail.isVerticallyResizable = true; detail.isHorizontallyResizable = false; detail.autoresizingMask = [.width]; detail.textContainer?.widthTracksTextView = true; root.addSubview(scroll)
        for (button, action) in [(importButton, #selector(importJSON)), (exportButton, #selector(exportJSON)), (refreshButton, #selector(refresh)), (cancelButton, #selector(cancel))] { button.target = self; button.action = action }
        let buttons = NSStackView(views: [importButton, exportButton, refreshButton, cancelButton]); buttons.orientation = .horizontal; buttons.spacing = 10
        buttons.frame = NSRect(x: 24, y: 24, width: 632, height: 32); buttons.autoresizingMask = [.width]; root.addSubview(buttons)
        controls()
    }
    required init?(coder: NSCoder) { fatalError("Not supported") }
    override func showWindow(_ sender: Any?) { super.showWindow(sender); if operation == nil { refresh() } }
    func windowWillClose(_ notification: Notification) { cancel(); detail.string = "" }
    private func controls() { importButton.isEnabled = operation == nil; exportButton.isEnabled = operation == nil; refreshButton.isEnabled = operation == nil; cancelButton.isEnabled = operation != nil }
    private func begin() -> (UUID, VocabularyLease)? {
        guard operation == nil, window?.isVisible == true, window?.attachedSheet == nil else { return nil }
        let id = UUID(), lease = VocabularyLease(); operation = id; self.lease = lease; controls(); return (id, lease)
    }
    private func current(_ id: UUID) -> Bool { operation == id && window?.isVisible == true }
    private func finish(_ id: UUID, message: String) {
        guard operation == id else { return }
        operation = nil; lease = nil; controls()
        if window?.isVisible == true { status.stringValue = message }
    }
    @objc private func cancel() {
        lease?.cancel()
        if let sheet, sheet.sheetParent === window { window?.endSheet(sheet, returnCode: .cancel) }
        sheet = nil
        // Keep admission occupied until an actual worker drains. Picker/preview cancel
        // callbacks finish immediately; closing a worker never admits another operation.
        if operation != nil { status.stringValue = "Canceled; waiting for work to drain. An already-admitted write may finish." }
    }
    @objc private func refresh() {
        guard let (id, lease) = begin() else { return }
        status.stringValue = "Reading encrypted vocabulary…"
        let store = store
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let result = Result { try lease.check(); let value = try store.read(); try lease.check(); return value }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                if self.current(id), (try? lease.check()) != nil {
                    switch result {
                    case .success(let value): self.detail.string = "\(value.entries.count) stored entries\n\(value.activeEnglishWords.count) active English words\n\n" + value.entries.prefix(20).map { "\($0.term) [\($0.language)]" }.joined(separator: "\n") + (value.entries.count > 20 ? "\n… \(value.entries.count - 20) more stored entries" : "")
                    case .failure: self.detail.string = "Vocabulary unavailable. Existing files remain unchanged."
                    }
                }
                self.finish(id, message: result.isSuccess ? "Encrypted local vocabulary ready. Export is separate and plaintext." : "Vocabulary unavailable; existing files preserved.")
            }
        }
    }
    @objc private func importJSON() {
        guard let window, let (id, lease) = begin() else { return }
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.json]; panel.canChooseDirectories = false; panel.allowsMultipleSelection = false
        panel.message = "Read one prepared Lip v1 JSON file. No mailbox, WhatsApp database or history scan. Review terms before merging; this is not a PII or secret detector."
        sheet = panel
        panel.beginSheetModal(for: window) { [weak self] response in
            guard let self else { return }; self.sheet = nil
            guard self.current(id), response == .OK, let source = panel.url else { self.finish(id, message: "Import canceled. Source unchanged."); return }
            self.status.stringValue = "Reading bounded snapshot and preparing merge preview…"
            let store = self.store
            DispatchQueue.global(qos: .utility).async { [weak self] in
                let result = Result { let bytes = try VocabularyFiles.snapshot(source, lease: lease); let entries = try store.decode(bytes); try lease.check(); return try store.preview(incoming: entries) }
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    guard self.current(id), (try? lease.check()) != nil else { self.finish(id, message: "Import canceled. Source unchanged."); return }
                    switch result {
                    case .success(let preview): self.confirm(preview, id: id, lease: lease)
                    case .failure: self.finish(id, message: "Import unavailable or invalid. Source and existing vocabulary preserved. Choose a regular local Lip v1 JSON file (up to 16 MiB).")
                    }
                }
            }
        }
    }
    private func confirm(_ preview: VocabularyStore.Preview, id: UUID, lease: VocabularyLease) {
        guard let window, current(id) else { finish(id, message: "Import canceled."); return }
        let alert = NSAlert(); alert.messageText = "Merge reviewed vocabulary?"
        alert.informativeText = "\(preview.incoming.count) incoming entries; \(preview.existingCount) currently stored; \(preview.mergedCount) after merge (\(preview.activeEnglishCount) active English words). Existing spelling is preserved. Other languages and phrases are retained but inactive. The original JSON is never modified. This is not a PII or secret detector. First 20 terms appear below; review the full source yourself before approving."
        let text = NSTextView(frame: NSRect(x: 0, y: 0, width: 500, height: 220)); text.isEditable = false; text.isRichText = false; text.isContinuousSpellCheckingEnabled = false; text.isAutomaticSpellingCorrectionEnabled = false; text.writingToolsBehavior = .none
        text.string = preview.incoming.prefix(20).map { "\($0.term) [\($0.language)]" }.joined(separator: "\n") + (preview.incoming.count > 20 ? "\n… \(preview.incoming.count - 20) more incoming entries" : "")
        let scroll = NSScrollView(frame: text.frame); scroll.hasVerticalScroller = true; scroll.documentView = text; text.isVerticallyResizable = true; text.autoresizingMask = [.width]; text.textContainer?.widthTracksTextView = true; alert.accessoryView = scroll
        alert.addButton(withTitle: "Cancel"); alert.addButton(withTitle: "Merge vocabulary")
        sheet = alert.window
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self else { return }; self.sheet = nil
            guard self.current(id), response == .alertSecondButtonReturn, (try? lease.check()) != nil else { self.finish(id, message: "Import canceled; existing vocabulary preserved."); return }
            self.status.stringValue = "Merging against latest local vocabulary… An admitted write may finish after closing."
            let store = self.store
            DispatchQueue.global(qos: .utility).async { [weak self] in
                let result = Result { try store.merge(incoming: preview.incoming, lease: lease) }
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    if self.current(id), case .success(let value) = result { self.detail.string = "\(value.entries.count) encrypted entries stored; \(value.activeEnglishWords.count) active English words. Speech output is unchanged." }
                    self.finish(id, message: result.isSuccess ? "Merge confirmed. Source preserved; no history was scanned." : "Merge unconfirmed. Existing files preserved; refresh before retrying. No automatic retry.")
                }
            }
        }
    }
    @objc private func exportJSON() {
        guard let window, let (id, lease) = begin() else { return }
        let alert = NSAlert(); alert.messageText = "Export readable vocabulary JSON?"
        alert.informativeText = "This is PLAINTEXT, not encrypted backup. Anyone or any sync provider with access to the selected location can read your terms. Only portable vocabulary is exported; no transcript or history is scanned. Choose a NEW filename; existing files will not be overwritten. No cloud upload or sync is performed by Lip."
        alert.addButton(withTitle: "Cancel"); alert.addButton(withTitle: "Choose new file…"); sheet = alert.window
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self else { return }; self.sheet = nil
            guard self.current(id), response == .alertSecondButtonReturn, (try? lease.check()) != nil else { self.finish(id, message: "Export canceled."); return }
            let panel = NSSavePanel(); panel.allowedContentTypes = [.json]; panel.nameFieldStringValue = "Lip-vocabulary-\(UUID().uuidString).json"; self.sheet = panel
            panel.beginSheetModal(for: window) { [weak self] response in
                guard let self else { return }; self.sheet = nil
                guard self.current(id), response == .OK, let target = panel.url, (try? lease.check()) != nil else { self.finish(id, message: "Export canceled."); return }
                let store = self.store
                DispatchQueue.global(qos: .utility).async { [weak self] in
                    let result = Result { try lease.check(); let bytes = try store.exportData(); try VocabularyFiles.export(bytes, to: target, lease: lease) }
                    DispatchQueue.main.async { [weak self] in self?.finish(id, message: result.isSuccess ? "Plaintext vocabulary exported to your selected new file." : "Export unconfirmed. Existing destinations were not overwritten; a new partial file may remain. No automatic retry.") }
                }
            }
        }
    }
}

private extension Result {
    var isSuccess: Bool { if case .success = self { return true }; return false }
}
