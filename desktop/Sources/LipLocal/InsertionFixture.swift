import AppKit
import ApplicationServices

// Disposable, opt-in test editor. Never starts recording or installs an Fn listener.
@MainActor final class InsertionFixtureApp: NSObject, NSApplicationDelegate {
    private var window: NSWindow!
    private let editor = NSTextView(usingTextLayoutManager: false)
    private let status = NSTextField(wrappingLabelWithString: "Ready. This is a disposable editor containing only synthetic test text.")
    private var run: NSButton!
    private var activeObserver: NSObjectProtocol?
    private var autoStarted = false
    func applicationDidFinishLaunching(_ notification: Notification) {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 650, height: 370), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Lip · Disposable Insertion Test"
        window.isReleasedWhenClosed = false
        let root = NSView(frame: NSRect(x: 0, y: 0, width: 650, height: 370))
        let heading = NSTextField(labelWithString: "Clipboard-free direct insertion test")
        heading.frame = NSRect(x: 20, y: 332, width: 610, height: 22); root.addSubview(heading)
        status.frame = NSRect(x: 20, y: 270, width: 610, height: 52); root.addSubview(status)
        editor.string = "LEFT RIGHT"; editor.isRichText = false
        editor.font = .systemFont(ofSize: 17); editor.setAccessibilityLabel("Disposable insertion test editor")
        editor.frame = NSRect(x: 0, y: 0, width: 610, height: 170)
        editor.isVerticallyResizable = true; editor.isHorizontallyResizable = false
        editor.textContainer?.widthTracksTextView = true
        let scroll = NSScrollView(frame: NSRect(x: 20, y: 90, width: 610, height: 170))
        scroll.hasVerticalScroller = true; scroll.documentView = editor; root.addSubview(scroll)
        run = NSButton(title: "Run guarded insertion test", target: self, action: #selector(runTest))
        run.frame = NSRect(x: 20, y: 44, width: 300, height: 30); root.addSubview(run)
        let close = NSButton(title: "Close test editor", target: self, action: #selector(closeTest))
        close.frame = NSRect(x: 450, y: 44, width: 180, height: 30); root.addSubview(close)
        window.contentView = root
        window.makeKeyAndOrderFront(nil)
        if CommandLine.arguments.contains("--auto-run") {
            activeObserver = NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification,
                object: NSApp, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.autoRunIfActive() }
            }
            NSApp.activate(ignoringOtherApps: true) // Only this explicitly authorized disposable fixture.
            DispatchQueue.main.async { [weak self] in self?.autoRunIfActive() }
        }
    }
    private func autoRunIfActive() {
        guard NSApp.isActive && !autoStarted else { return }
        autoStarted = true
        runTest()
    }
    @objc private func closeTest() { NSApp.terminate(nil) }
    @objc private func runTest() {
        run.isEnabled = false
        editor.string = "LEFT RIGHT"; editor.setSelectedRange(NSRange(location: 5, length: 0))
        window.makeFirstResponder(editor)
        let clipboard = NSPasteboard.general.changeCount // Only a counter; no clipboard contents are read/written.
        status.stringValue = "Testing local insertion into this disposable editor…"
        Task {
            let pid = getpid()
            var receipt: [String: Any] = ["complete": false, "test": "isolated-editor", "route": CommandLine.arguments.contains("--event-route") ? "unicode-events" : "selected-text-if-writable", "accessibilityAllowed": AXIsProcessTrusted(), "fixtureMetadata": CursorInsertion.fixtureMetadata()]
            let payload = "Bark insertion works 😀 and café. "
            let captured = CursorInsertion.capture(frontPID: pid, allowSelfForFixture: true)
            switch captured {
            case .failure(let issue): receipt["failure"] = issue.message
            case .success(let target):
                let outcome = await CursorInsertion.insert(payload, into: target, allowSelectedTextSetter: !CommandLine.arguments.contains("--event-route"))
                receipt["dispatchVerified"] = outcome.verified
                receipt["insertedExactly"] = editor.string == "LEFT " + payload + "RIGHT"
                receipt["caretAtExpectedPosition"] = editor.selectedRange() == NSRange(location: 5 + payload.utf16.count, length: 0)
                let preserved = editor.string
                let recaptured = CursorInsertion.capture(frontPID: pid, allowSelfForFixture: true)
                if case .success(let changedTarget) = recaptured {
                    editor.setSelectedRange(NSRange(location: 0, length: 0))
                    let refused = await CursorInsertion.insert("SHOULD_NOT_APPEAR", into: changedTarget)
                    receipt["changedCaretRefused"] = !refused.dispatched && editor.string == preserved
                } else { receipt["changedCaretRefused"] = false }
                editor.setSelectedRange(NSRange(location: 0, length: 4))
                let selected = CursorInsertion.capture(frontPID: pid, allowSelfForFixture: true)
                if case .failure = selected { receipt["selectedTextRefused"] = true }
                else { receipt["selectedTextRefused"] = false }
                receipt["outcome"] = outcome.message
            }
            receipt["clipboardUnchanged"] = NSPasteboard.general.changeCount == clipboard
            let checks = ["dispatchVerified", "insertedExactly", "caretAtExpectedPosition", "changedCaretRefused", "selectedTextRefused", "clipboardUnchanged"]
            receipt["complete"] = checks.allSatisfy { receipt[$0] as? Bool == true }
            let passed = receipt["complete"] as? Bool == true
            status.stringValue = passed ? "PASS: text inserted at caret, surrounding text preserved, clipboard untouched; moved-caret and selection guards refused further writes." : "FAIL: " + ((receipt["failure"] as? String) ?? (receipt["outcome"] as? String) ?? "See local test receipt.")
            let root = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/LipLocal")
            if let data = try? JSONSerialization.data(withJSONObject: receipt, options: [.sortedKeys]) {
                try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
                let url = root.appendingPathComponent("insertion-fixture.json")
                try? data.write(to: url, options: .atomic)
                try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            }
            run.isEnabled = true
        }
    }
}
