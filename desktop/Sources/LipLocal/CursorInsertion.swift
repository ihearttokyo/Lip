import AppKit
import ApplicationServices

struct CursorSnapshot: Equatable, Sendable {
    let pid: pid_t
    let location: Int
    let selectionLength: Int
    let textLength: Int
    let before: String
    let after: String
    let role: String
    let secure: Bool
    let enabled: Bool

    var eligible: Bool {
        ["AXTextField", "AXTextArea", "AXComboBox"].contains(role) && !secure && enabled &&
            selectionLength == 0 && location >= 0 && location <= textLength
    }
    func advanced(by text: String) -> CursorSnapshot {
        CursorSnapshot(pid: pid, location: location + text.utf16.count, selectionLength: 0,
            textLength: textLength + text.utf16.count,
            before: String(decoding: Array((before + text).utf16.suffix(32)), as: UTF16.self),
            after: after, role: role, secure: secure, enabled: enabled)
    }
}

struct CursorIssue: Error {
    let message: String
    init(_ message: String) { self.message = message }
}

final class CursorTarget: @unchecked Sendable {
    let element: AXUIElement
    let snapshot: CursorSnapshot
    private let lock = NSLock()
    private var attempted = false
    init(element: AXUIElement, snapshot: CursorSnapshot) { self.element = element; self.snapshot = snapshot }
    func claim() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if attempted { return false }
        attempted = true; return true
    }
}

struct InsertionOutcome {
    let verified: Bool
    let dispatched: Bool
    let message: String
}

enum CursorInsertion {
    private static let system = AXUIElementCreateSystemWide()
    private static func attribute(_ element: AXUIElement, _ name: String) -> CFTypeRef? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, name as CFString, &value) == .success else { return nil }
        return value
    }
    private static func focused() -> AXUIElement? {
        guard let value = attribute(system, kAXFocusedUIElementAttribute), CFGetTypeID(value) == AXUIElementGetTypeID() else { return nil }
        return (value as! AXUIElement)
    }
    private static func string(_ element: AXUIElement, range: CFRange, length: Int) -> String? {
        guard range.length > 0 else { return "" }
        var range = range
        guard let encoded = AXValueCreate(.cfRange, &range) else { return nil }
        var output: CFTypeRef?
        if AXUIElementCopyParameterizedAttributeValue(element, kAXStringForRangeParameterizedAttribute as CFString,
            encoded, &output) == .success, let text = output as? String { return text }
        // Some native editors expose AXValue but not AXStringForRange. Bound that read.
        guard length <= 8192, let text = attribute(element, kAXValueAttribute) as? String,
            (text as NSString).length == length, range.location + range.length <= length else { return nil }
        return (text as NSString).substring(with: NSRange(location: range.location, length: range.length))
    }
    private static func snapshot(_ element: AXUIElement) -> CursorSnapshot? {
        var pid: pid_t = 0
        guard AXUIElementGetPid(element, &pid) == .success,
            let role = attribute(element, kAXRoleAttribute) as? String else { return nil }
        let subrole = attribute(element, kAXSubroleAttribute) as? String ?? ""
        let secure = subrole == "AXSecureTextField" || (attribute(element, "AXProtectedContent") as? Bool == true)
        // Never read the contents of a protected field, even to build a context guard.
        guard !secure, ["AXTextField", "AXTextArea", "AXComboBox"].contains(role),
            let selected = attribute(element, kAXSelectedTextRangeAttribute), CFGetTypeID(selected) == AXValueGetTypeID() else { return nil }
        // Web contenteditable editors may omit AXNumberOfCharacters. A bounded
        // AXValue read can establish length without weakening caret/focus guards.
        let count: Int
        if let number = attribute(element, kAXNumberOfCharactersAttribute) as? NSNumber { count = number.intValue }
        else if let value = attribute(element, kAXValueAttribute) as? String, value.utf16.count <= 32_768 { count = value.utf16.count }
        else { return nil }
        var range = CFRange()
        guard AXValueGetValue(selected as! AXValue, .cfRange, &range), range.location >= 0,
            range.length == 0, range.location <= count else { return nil }
        if attribute(element, "AXEditable") as? Bool == false { return nil }
        let enabled = (attribute(element, kAXEnabledAttribute) as? Bool) ?? true
        let leading = CFRange(location: max(0, range.location - 32), length: min(32, range.location))
        let trailing = CFRange(location: range.location, length: min(32, count - range.location))
        guard let before = string(element, range: leading, length: count),
            let after = string(element, range: trailing, length: count) else { return nil }
        return CursorSnapshot(pid: pid, location: range.location, selectionLength: range.length,
            textLength: count, before: before, after: after, role: role, secure: secure, enabled: enabled)
    }
    @MainActor static func fixtureMetadata() -> [String: String] {
        guard let element = focused() else { return ["focused": "missing"] }
        var pid: pid_t = 0; AXUIElementGetPid(element, &pid)
        guard pid == getpid() else { return ["focused": "not-disposable-fixture"] }
        var metadata = ["focused": "fixture", "snapshot": snapshot(element) == nil ? "unavailable" : "available"]
        for name in [kAXRoleAttribute, kAXSubroleAttribute, kAXNumberOfCharactersAttribute, kAXSelectedTextRangeAttribute, kAXEnabledAttribute, "AXEditable"] {
            if let value = attribute(element, name) { metadata[name] = String(describing: value) }
            else { metadata[name] = "missing" }
        }
        metadata["valueLength"] = (attribute(element, kAXValueAttribute) as? String).map { String($0.utf16.count) } ?? "missing"
        return metadata // No field contents; this is guarded to the disposable test process only.
    }
    static func capture(frontPID: pid_t?, allowSelfForFixture: Bool = false) -> Result<CursorTarget, CursorIssue> {
        guard AXIsProcessTrusted() else { return .failure(CursorIssue("Enable Lip Local in Accessibility to insert directly.")) }
        guard let frontPID, (allowSelfForFixture || frontPID != getpid()), let element = focused() else {
            return .failure(CursorIssue("No supported external text cursor was focused when recording began."))
        }
        AXUIElementSetMessagingTimeout(element, 0.25)
        guard let snapshot = snapshot(element), snapshot.pid == frontPID, snapshot.eligible else {
            return .failure(CursorIssue("The focused field is protected, selected, read-only or lacks a safe text cursor."))
        }
        return .success(CursorTarget(element: element, snapshot: snapshot))
    }
    @MainActor private static func current(_ target: CursorTarget) async -> CursorSnapshot? {
        let front = NSWorkspace.shared.frontmostApplication?.processIdentifier
        guard front == target.snapshot.pid, let element = focused(), CFEqual(element, target.element) else { return nil }
        return snapshot(element)
    }
    static func chunks(_ text: String) -> [String]? {
        guard !text.isEmpty, text.utf16.count <= 16_384 else { return nil }
        var result: [String] = []; var chunk = ""
        for character in text {
            let next = String(character)
            guard next.utf16.count <= 20 else { return nil }
            if chunk.utf16.count + next.utf16.count > 20 { result.append(chunk); chunk = "" }
            chunk += next
        }
        if !chunk.isEmpty { result.append(chunk) }
        return result
    }
    private static func modifiersAreSafe() -> Bool {
        CGEventSource.flagsState(.combinedSessionState).intersection([.maskCommand, .maskControl, .maskAlternate, .maskShift]).isEmpty
    }
    @MainActor private static func verify(_ target: CursorTarget, old: CursorSnapshot, expected: CursorSnapshot) async -> Bool {
        for _ in 0..<20 {
            guard let value = await current(target) else { return false }
            if value == expected { return true }
            if value != old { return false }
            try? await Task.sleep(for: .milliseconds(15))
        }
        return false
    }
    @MainActor static func insert(_ text: String, into target: CursorTarget, allowSelectedTextSetter: Bool = true, authorized: @escaping () async -> Bool = { true }) async -> InsertionOutcome {
        guard await authorized() else {
            return InsertionOutcome(verified: false, dispatched: false, message: "Not inserted: session canceled or direct insertion disabled. Transcript kept.")
        }
        guard AXIsProcessTrusted(), target.snapshot.eligible, let parts = chunks(text), modifiersAreSafe(),
            await current(target) == target.snapshot else {
            return InsertionOutcome(verified: false, dispatched: false, message: "Not inserted: original field, cursor, contents or permission changed. Transcript kept.")
        }
        // Prefer an atomic selected-text setter only if the specific app advertises it.
        // Apple documents AXSelectedText as normally read-only; no whole-field AXValue rewrite.
        var settable = DarwinBoolean(false)
        let atomic = allowSelectedTextSetter && AXUIElementIsAttributeSettable(target.element, kAXSelectedTextAttribute as CFString, &settable) == .success && settable.boolValue
        if !atomic && text.unicodeScalars.contains(where: { CharacterSet.controlCharacters.union(.newlines).contains($0) }) {
            return InsertionOutcome(verified: false, dispatched: false, message: "Not inserted: this field cannot safely accept multiline/control text without paste. Transcript kept; clipboard untouched.")
        }
        guard target.claim() else {
            return InsertionOutcome(verified: false, dispatched: false, message: "Insertion already attempted; check the field before retrying manually.")
        }
        guard await authorized(), await current(target) == target.snapshot else {
            return InsertionOutcome(verified: false, dispatched: false, message: "Not inserted: original target or session changed. Transcript kept.")
        }
        if atomic {
            // A dispatched error may have mutated the app: do NOT fall back or retry.
            let result = AXUIElementSetAttributeValue(target.element, kAXSelectedTextAttribute as CFString, text as CFString)
            var confirmed = false
            if result == .success { confirmed = await verify(target, old: target.snapshot, expected: target.snapshot.advanced(by: text)) }
            return InsertionOutcome(verified: confirmed, dispatched: true,
                message: confirmed ? "Inserted at original cursor · clipboard untouched" : "Insertion unconfirmed. Transcript kept; check the field before retrying. Clipboard untouched.")
        }
        guard let source = CGEventSource(stateID: .privateState) else {
            return InsertionOutcome(verified: false, dispatched: false, message: "Cannot create local text input. Transcript kept; clipboard untouched.")
        }
        var expected = target.snapshot
        var dispatched = false
        for part in parts {
            guard await authorized(), modifiersAreSafe(), await current(target) == expected else {
                return InsertionOutcome(verified: false, dispatched: dispatched, message: "Insertion stopped: cursor/focus/content changed. A prefix may be present; transcript kept. Clipboard untouched.")
            }
            guard let down = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: true),
                let up = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: false) else {
                return InsertionOutcome(verified: false, dispatched: dispatched, message: "Text input unavailable. A prefix may be present; transcript kept. Clipboard untouched.")
            }
            let utf16 = Array(part.utf16)
            down.flags = []; up.flags = []
            down.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: utf16)
            up.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: utf16)
            // No pasteboard, Cmd-V, Return/Enter, focus activation, or submit action.
            // Route only to the original process; revalidate and acknowledge every chunk.
            down.postToPid(target.snapshot.pid); up.postToPid(target.snapshot.pid)
            dispatched = true
            let next = expected.advanced(by: part)
            guard await verify(target, old: expected, expected: next) else {
                return InsertionOutcome(verified: false, dispatched: true, message: "Insertion unconfirmed or partial. Transcript kept; check the field before retrying. Clipboard untouched.")
            }
            expected = next
        }
        return InsertionOutcome(verified: true, dispatched: true, message: "Inserted at original cursor · clipboard untouched")
    }
}

func testCursorInsertionGuards() {
    var checks = 0
    func check(_ condition: Bool, _ label: String) { precondition(condition, label); checks += 1 }
    let base = CursorSnapshot(pid: 12, location: 5, selectionLength: 0, textLength: 10, before: "LEFT ", after: "RIGHT", role: "AXTextArea", secure: false, enabled: true)
    check(base.eligible, "plain caret accepted")
    let advanced = base.advanced(by: "hello 😀 ")
    check(advanced.location == 14 && advanced.textLength == 19, "UTF16 cursor math")
    check(advanced.before == "LEFT hello 😀 " && advanced.after == "RIGHT", "context advances without replacing suffix")
    for changed in [
        CursorSnapshot(pid: 13, location: 5, selectionLength: 0, textLength: 10, before: "LEFT ", after: "RIGHT", role: "AXTextArea", secure: false, enabled: true),
        CursorSnapshot(pid: 12, location: 4, selectionLength: 0, textLength: 10, before: "LEFT", after: " RIGHT", role: "AXTextArea", secure: false, enabled: true),
        CursorSnapshot(pid: 12, location: 5, selectionLength: 0, textLength: 10, before: "EDIT ", after: "RIGHT", role: "AXTextArea", secure: false, enabled: true)] {
        check(changed != base, "changed process/caret/content refused")
    }
    for refused in [
        CursorSnapshot(pid: 12, location: 0, selectionLength: 3, textLength: 10, before: "", after: "RIGHT", role: "AXTextArea", secure: false, enabled: true),
        CursorSnapshot(pid: 12, location: 5, selectionLength: 0, textLength: 10, before: "LEFT ", after: "RIGHT", role: "AXTextField", secure: true, enabled: true),
        CursorSnapshot(pid: 12, location: 5, selectionLength: 0, textLength: 10, before: "LEFT ", after: "RIGHT", role: "AXButton", secure: false, enabled: true),
        CursorSnapshot(pid: 12, location: 5, selectionLength: 0, textLength: 10, before: "LEFT ", after: "RIGHT", role: "AXTextArea", secure: false, enabled: false)] {
        check(!refused.eligible, "selection/protected/unsupported/disabled refused")
    }
    let unicode = String(repeating: "é😀👨‍👩‍👧‍👦 ", count: 4)
    let chunks = CursorInsertion.chunks(unicode)!
    check(chunks.joined() == unicode && chunks.allSatisfy { $0.utf16.count <= 20 }, "Unicode chunks preserve graphemes")
    check(CursorInsertion.chunks("") == nil, "empty rejected")
    check(CursorInsertion.chunks(String(repeating: "a", count: 16_385)) == nil, "bounded input")
    let target = CursorTarget(element: AXUIElementCreateSystemWide(), snapshot: base)
    check(target.claim() && !target.claim(), "dispatch token is one-shot")
    print("Cursor insertion guard tests passed (\(checks) assertions; no live editor mutation).")
}
