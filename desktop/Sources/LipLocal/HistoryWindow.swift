import AppKit
import UniformTypeIdentifiers
import Darwin

@MainActor final class HistoryWindowController: NSWindowController, NSTableViewDataSource, NSTableViewDelegate, NSWindowDelegate {
    private let history: HistoryService
    private let consentChanged: (Bool) -> Void
    private let enabled = NSButton(checkboxWithTitle: "Save future dictation history on this Mac", target: nil, action: nil)
    private let status = NSTextField(wrappingLabelWithString: "")
    private let table = NSTableView()
    private let detail = NSTextView()
    private let exportButton = NSButton(title: "Export selected JSON…", target: nil, action: nil)
    private let importButton = NSButton(title: "Import JSON…", target: nil, action: nil)
    private let cancelImportButton = NSButton(title: "Cancel import", target: nil, action: nil)
    private let refreshButton = NSButton(title: "Refresh", target: nil, action: nil)
    private let importFlow = HistoryImportFlow()
    private var importSheet: NSWindow?
    private var activity = HistoryWindowActivity()
    private let deleteButton = NSButton(title: "Delete selected…", target: nil, action: nil)
    private var records: [HistoryRecord] = []
    private var loading: Bool { activity.loading }
    init(history: HistoryService, consentChanged: @escaping (Bool) -> Void = { _ in }) {
        self.history = history; self.consentChanged = consentChanged
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 720, height: 650), styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = "Lip · Encrypted dictation history"; window.isReleasedWhenClosed = false; window.minSize = NSSize(width: 720, height: 600)
        super.init(window: window)
        window.delegate = self
        let root = NSView(frame: NSRect(x: 0, y: 0, width: 720, height: 650)); window.contentView = root
        enabled.frame = NSRect(x: 20, y: 606, width: 680, height: 26); enabled.autoresizingMask = [.width, .minYMargin]
        enabled.target = self; enabled.action = #selector(toggle); enabled.state = history.enabled ? .on : .off; root.addSubview(enabled)
        let note = NSTextField(wrappingLabelWithString: "Off by default. Encrypts original/output, model and date locally. No sync or typed/swiped capture. Stops at 50 MiB / 2,000 records; Off keeps old records. Privacy setup has a 250ms fallback, subject to UI scheduling; unverified context is memory-only. File import is separate and never enables automatic recording.")
        note.frame = NSRect(x: 20, y: 537, width: 680, height: 62); note.autoresizingMask = [.width, .minYMargin]; note.font = .systemFont(ofSize: 12); note.textColor = .secondaryLabelColor; root.addSubview(note)
        status.frame = NSRect(x: 20, y: 502, width: 520, height: 30); status.autoresizingMask = [.width, .minYMargin]; status.font = .systemFont(ofSize: 12); root.addSubview(status)
        for (id, title, width) in [("date", "Recorded", CGFloat(230)), ("model", "Model", CGFloat(300))] { let c = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(id)); c.title = title; c.width = width; table.addTableColumn(c) }
        table.delegate = self; table.dataSource = self; table.allowsMultipleSelection = false; table.setAccessibilityLabel("Local dictation records")
        let list = NSScrollView(frame: NSRect(x: 20, y: 328, width: 680, height: 168)); list.autoresizingMask = [.width, .minYMargin]; list.hasVerticalScroller = true; list.documentView = table; root.addSubview(list)
        detail.isEditable = false; detail.isRichText = false; detail.isContinuousSpellCheckingEnabled = false; detail.isGrammarCheckingEnabled = false; detail.isAutomaticSpellingCorrectionEnabled = false; detail.isAutomaticTextReplacementEnabled = false; detail.isAutomaticLinkDetectionEnabled = false; detail.writingToolsBehavior = .none; detail.isSelectable = true; detail.font = .monospacedSystemFont(ofSize: 12, weight: .regular); detail.setAccessibilityLabel("Selected history original and output")
        let review = NSScrollView(frame: NSRect(x: 20, y: 68, width: 680, height: 246)); review.autoresizingMask = [.width, .height]; review.hasVerticalScroller = true; review.documentView = detail
        detail.frame = NSRect(origin: .zero, size: review.contentSize); detail.minSize = NSSize(width: 0, height: review.contentSize.height)
        detail.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        detail.isVerticallyResizable = true; detail.isHorizontallyResizable = false; detail.autoresizingMask = [.width]
        detail.textContainer?.widthTracksTextView = true
        root.addSubview(review)
        importButton.target = self; importButton.action = #selector(importJSON)
        cancelImportButton.target = self; cancelImportButton.action = #selector(cancelImport)
        exportButton.target = self; exportButton.action = #selector(exportSelected)
        deleteButton.target = self; deleteButton.action = #selector(deleteSelected)
        refreshButton.target = self; refreshButton.action = #selector(reload)
        let controls = NSStackView(views: [importButton, exportButton, deleteButton, refreshButton])
        controls.orientation = .horizontal; controls.distribution = .fillProportionally; controls.spacing = 10
        controls.frame = NSRect(x: 20, y: 20, width: 680, height: 32); controls.autoresizingMask = [.width]; root.addSubview(controls)
        cancelImportButton.frame = NSRect(x: 550, y: 502, width: 150, height: 28); cancelImportButton.autoresizingMask = [.minXMargin, .minYMargin]
        cancelImportButton.isHidden = true; root.addSubview(cancelImportButton)
        reload()
    }
    deinit { importFlow.cancelCurrent() }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    private var selected: HistoryRecord? { records.indices.contains(table.selectedRow) ? records[table.selectedRow] : nil }
    func numberOfRows(in tableView: NSTableView) -> Int { records.count }
    func tableView(_ tableView: NSTableView, viewFor column: NSTableColumn?, row: Int) -> NSView? {
        let value = column?.identifier.rawValue == "date" ? records[row].created.formatted(date: .abbreviated, time: .shortened) : records[row].profile
        return NSTextField(labelWithString: value)
    }
    func tableViewSelectionDidChange(_ notification: Notification) { reviewSelection() }
    private func reviewSelection() {
        let importing = importFlow.busy
        importButton.isEnabled = !loading && !importing; refreshButton.isEnabled = !loading && !importing
        cancelImportButton.isHidden = !importing
        exportButton.isEnabled = !loading && !importing && selected != nil; deleteButton.isEnabled = !loading && !importing && selected != nil
        guard let record = selected else { detail.string = "Select a record to review. No text is copied automatically."; return }
        detail.string = "Output\n\(record.output)\n\nOriginal text\n\(record.original)\n\nModel details\n\(record.modelDetails)"
        if let metadata = record.imported {
            let externalID = HistoryImportPresentation.excerpt(metadata.id, limit: 256)
            detail.string += "\n\nImported file metadata (unverified)\nExternal ID: \(externalID)\nLanguage: \(metadata.language.map { HistoryImportPresentation.excerpt($0, limit: 256) } ?? "Unknown")\nModel: \(metadata.model.map { HistoryImportPresentation.excerpt($0, limit: 256) } ?? "Unknown")\nDetails: \(metadata.modelDetails.map { HistoryImportPresentation.excerpt($0, limit: 256) } ?? "Unknown")\nLegacy cloud-cleanup flag: \(metadata.legacyCloudCleanup)"
        }
    }
    @objc private func toggle() {
        if enabled.state == .on {
            let alert = NSAlert(); alert.messageText = "Keep encrypted dictation history?"
            alert.informativeText = "Save original and processed future dictation, model details and timestamps on this Mac using a device-local Keychain key. No cloud sync. Disabling stops future saves but keeps existing records. Uninstall/key loss may make records unrecoverable. Export is separate and decrypted."
            alert.addButton(withTitle: "Enable local history"); alert.addButton(withTitle: "Keep off")
            guard alert.runModal() == .alertFirstButtonReturn else { enabled.state = .off; return }
        }
        let wants = enabled.state == .on
        enabled.isEnabled = true // Off/supersede remains available during blocked setup.
        status.stringValue = wants ? "Preparing local history writer… No recording ticket until ready." : "Stopping new saves; finishing any already-claimed write and confirming the preference…"
        history.setEnabled(wants) { [weak self] event in
            guard let self, event.request == self.history.snapshot().request else { return }
            self.enabled.isEnabled = true; self.enabled.state = self.history.enabled ? .on : .off
            switch event.result {
            case .success:
                if !self.importFlow.busy { self.status.stringValue = self.history.enabled ? "Ready for new recordings only." : "Off. Existing encrypted records preserved." }
            case .failure:
                if !self.importFlow.busy { self.status.stringValue = "History not ready. Setting persistence unconfirmed; another instance or storage issue may block this writer." }
            }
        }
        consentChanged(wants) // Immediate memory invalidation; callback above is drain confirmation only.
    }
    @objc private func reload() { loadRecords(note: nil) }
    private func loadRecords(note: String?) {
        guard !importFlow.busy, let generation = activity.beginList() else { return }
        reviewSelection(); status.stringValue = "Reading local encrypted records…"
        history.list { [weak self] value in
            guard let self else { return }
            let completion = self.activity.finishList(generation)
            guard completion.accepted else { return }
            switch value {
            case .success(let listing): self.records = listing.records
                if completion.status, !self.importFlow.busy { self.status.stringValue = "\(listing.records.count) local records. \(listing.unreadable) unreadable records preserved." + (note.map { " " + $0 } ?? "") }
            case .failure(let error): self.records = []
                if completion.status, !self.importFlow.busy { self.status.stringValue = (error as? HistoryFailure)?.description ?? "History unavailable; existing records preserved." }
            }
            self.table.reloadData(); self.reviewSelection()
        }
    }
    override func showWindow(_ sender: Any?) { super.showWindow(sender); if !loading && !importFlow.busy { reload() } }
    func windowWillClose(_ notification: Notification) {
        guard let closing = notification.object as? NSWindow, closing === window else { return }
        activity.close(); cancelImport()
    }
    @objc private func cancelImport() {
        importFlow.cancelCurrent()
        if let sheet = importSheet, sheet.sheetParent === window { window?.endSheet(sheet, returnCode: .cancel) }
        importSheet = nil; reviewSelection()
        status.stringValue = importFlow.busy ? "Import canceled; waiting for in-flight work to drain. An already-admitted record may still finish." : "Import canceled. Automatic recording unchanged."
    }
    private func importDrained() {
        reviewSelection()
        if !importFlow.busy, window?.isVisible == true { reload() }
    }
    @objc private func importJSON() {
        guard !loading, let window, window.isVisible, window.attachedSheet == nil, let operation = importFlow.begin() else { return }
        _ = activity.newStatus(); reviewSelection()
        let alert = NSAlert(); alert.messageText = "Choose a transcript to restore?"
        alert.informativeText = "Lip will read one selected plaintext JSON file and show a preview. Nothing is imported until you confirm. It does not enable automatic recording or sync. The source is never changed or deleted."
        alert.addButton(withTitle: "Choose JSON…"); alert.addButton(withTitle: "Cancel")
        importSheet = alert.window
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self, self.importFlow.warning(operation) else { return }
            guard self.window?.isVisible == true else { _ = self.importFlow.cancel(operation); self.importDrained(); return }
            self.importSheet = nil
            guard response == .alertFirstButtonReturn else { _ = self.importFlow.cancel(operation); self.importDrained(); return }
            self.chooseImportFile(operation)
        }
    }
    private func chooseImportFile(_ operation: UUID) {
        guard let window, window.isVisible, importFlow.openPicker(operation) else { return }
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.json]; panel.allowsMultipleSelection = false; panel.canChooseDirectories = false; panel.canChooseFiles = true
        importSheet = panel
        panel.beginSheetModal(for: window) { [weak self] response in
            guard let self, self.importFlow.picking(operation) else { return }
            guard self.window?.isVisible == true else { _ = self.importFlow.cancel(operation); self.importDrained(); return }
            self.importSheet = nil
            guard response == .OK, let source = panel.url, self.importFlow.startRead(operation) else { _ = self.importFlow.cancel(operation); self.importDrained(); return }
            self.readImportSource(source, operation: operation)
        }
    }
    private func readImportSource(_ source: URL, operation: UUID) {
        status.stringValue = "Reading selected snapshot and checking local IDs…"; reviewSelection()
        let flow = importFlow, service = history
        HistoryImportReadQueue.queue.async { [weak self] in
            let result = Result { () throws -> HistoryPortableDocument in
                guard flow.reading(operation) else { throw HistoryFailure.importRevoked }
                let scoped = source.startAccessingSecurityScopedResource()
                defer { if scoped { source.stopAccessingSecurityScopedResource() } }
                return try HistoryImportSource.read(source, current: { flow.reading(operation) })
            }
            switch result {
            case .failure:
                DispatchQueue.main.async {
                    let visible = flow.failRead(operation)
                    guard let self else { return }
                    self.reviewSelection()
                    if visible, self.window?.isVisible == true { self.status.stringValue = "Selected source could not be validated; nothing imported. Existing history and source preserved." }
                    else if !flow.busy { self.importDrained() }
                }
            case .success(let document):
                guard flow.reading(operation) else {
                    DispatchQueue.main.async { _ = flow.failRead(operation); self?.importDrained() }; return
                }
                service.previewImport(document.record, operation: operation) { [weak self] event in
                    guard event.operation == operation else { return }
                    if self?.window?.isVisible != true { _ = flow.cancel(operation) }
                    switch event.result {
                    case .failure:
                        let visible = flow.failRead(operation); self?.reviewSelection()
                        if visible, self?.window?.isVisible == true { self?.status.stringValue = "Local archive preview failed; nothing imported. Existing data preserved." }
                        else if !flow.busy { self?.importDrained() }
                    case .success(let disposition):
                        let visible = flow.preview(operation, record: document.record, disposition: disposition)
                        guard visible, let self, self.window?.isVisible == true else { self?.importDrained(); return }
                        self.showImportPreview(document, disposition: disposition, operation: operation)
                    }
                }
            }
        }
    }
    private func showImportPreview(_ document: HistoryPortableDocument, disposition: HistoryPortableDisposition, operation: UUID) {
        guard importFlow.previewing(operation), let window, window.isVisible else { return }
        let alert = NSAlert(); alert.messageText = "Review transcript import"
        alert.informativeText = "Review this bounded snapshot. Metadata is unverified. Complete text will be encrypted locally only after confirmation; automatic recording stays unchanged."
        let text = NSTextView(frame: NSRect(x: 0, y: 0, width: 550, height: 300)); text.isEditable = false; text.isRichText = false; text.isAutomaticLinkDetectionEnabled = false; text.isContinuousSpellCheckingEnabled = false; text.isGrammarCheckingEnabled = false; text.isAutomaticSpellingCorrectionEnabled = false; text.isAutomaticTextReplacementEnabled = false; text.writingToolsBehavior = .none; text.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        text.string = HistoryImportPresentation.text(document, disposition: disposition)
        let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 550, height: 300)); scroll.hasVerticalScroller = true; scroll.documentView = text
        text.isVerticallyResizable = true; text.isHorizontallyResizable = false; text.autoresizingMask = [.width]; text.textContainer?.widthTracksTextView = true
        text.setAccessibilityLabel("Untrusted import snapshot; complete record is unchanged"); alert.accessoryView = scroll
        alert.addButton(withTitle: "Cancel")
        let allowed = disposition == .new || disposition == .identical
        if allowed { alert.addButton(withTitle: "Confirm import") }
        importSheet = alert.window
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self, self.importFlow.previewing(operation) else { return }
            guard self.window?.isVisible == true else { _ = self.importFlow.cancel(operation); self.importDrained(); return }
            self.importSheet = nil
            guard allowed, response == .alertSecondButtonReturn, let (record, authorization) = self.importFlow.confirm(operation) else { _ = self.importFlow.cancel(operation); self.importDrained(); return }
            self.status.stringValue = "Importing confirmed record… An already-admitted write may finish after closing."; self.reviewSelection()
            self.history.importRecord(record, authorization: authorization) { [weak self, flow = self.importFlow] event in
                let visible = flow.finishCommit(operation, authorization: event.operation)
                guard let self else { return }; self.reviewSelection()
                guard visible, self.window?.isVisible == true else { if !flow.busy { self.importDrained() }; return }
                switch event.result {
                case .success(let value): self.loadRecords(note: value == .identical ? "Identical transcript already exists; nothing rewritten." : "Imported encrypted local transcript. Automatic recording unchanged.")
                case .failure: self.status.stringValue = "Import unconfirmed. Review History before retrying; no existing record was overwritten and no automatic retry was made."
                }
            }
        }
    }
    @objc private func exportSelected() {
        guard !loading, !importFlow.busy else { return }
        guard let record = selected, let window else { return }
        let alert = NSAlert(); alert.messageText = "Export decrypted transcript JSON?"
        alert.informativeText = "The file contains the original/output and record metadata (ID, date, reported language, model/details and legacy cloud-cleanup flag) as readable text. Imported metadata is unverified; the exporter platform does not prove on-device capture. Anyone with access to it—including a sync provider for a chosen cloud folder—may read it. Use a NEW filename; existing files will not be overwritten. This is not an encrypted backup."
        alert.addButton(withTitle: "Choose export file…"); alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        guard window.isVisible, window.attachedSheet == nil else { return }
        let export = activity.beginExport()
        let panel = NSSavePanel(); panel.allowedContentTypes = [.json]; panel.nameFieldStringValue = "Lip-transcript-\(record.id.uuidString).json"
        panel.beginSheetModal(for: window) { [weak self] response in
            guard let self, self.activity.currentExport(export) else { return }
            guard response == .OK, let target = panel.url, self.window?.isVisible == true, !self.importFlow.busy,
                self.activity.startExport(export) else { self.activity.cancelExport(export); return }
            self.status.stringValue = "Exporting selected decrypted record…"
            DispatchQueue.global(qos: .utility).async {
                let message: String
                do { try HistoryExport.write(record, to: target); message = "Selected transcript exported as readable JSON." }
                catch { message = "Export failed; existing destination and history were preserved. Choose a new filename." }
                DispatchQueue.main.async { if self.activity.finishExport(export), !self.importFlow.busy, self.window?.isVisible == true { self.status.stringValue = message } }
            }
        }
    }
    @objc private func deleteSelected() {
        guard !loading, !importFlow.busy else { return }
        guard let record = selected else { return }
        let alert = NSAlert(); alert.messageText = "Delete this encrypted history record?"
        alert.informativeText = "Only the selected local record is removed. Exported files, inserted text, backups and the current Review transcript are not erased. This cannot be undone."
        alert.addButton(withTitle: "Cancel"); alert.addButton(withTitle: "Delete selected")
        guard alert.runModal() == .alertSecondButtonReturn else { return }
        guard let generation = activity.beginMutation() else { return }
        reviewSelection()
        history.delete(record.id) { [weak self] result in
            guard let self, self.activity.finishMutation(generation) else { return }
            switch result { case .success: self.reload(); case .failure: self.status.stringValue = "Delete was not confirmed; no automatic retry."; self.reviewSelection() }
        }
    }
}

/// Explicit export only. 0600 stage before plaintext write; never overwrite a target.
enum HistoryExport {
    static func write(_ record: HistoryRecord, to target: URL) throws {
        let stage = target.deletingLastPathComponent().appendingPathComponent(".lip-history-export-" + UUID().uuidString)
        let fd = open(stage.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw HistoryFailure.unavailable }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? handle.close(); try? FileManager.default.removeItem(at: stage) }
        try handle.write(contentsOf: HistoryVault.export(record)); try handle.synchronize(); try handle.close()
        try FileManager.default.moveItem(at: stage, to: target) // No remove/replace fallback; existing target survives.
    }
}

private enum HistoryImportReadQueue { static let queue = DispatchQueue(label: "dev.lip.history-import-read", qos: .utility) }
