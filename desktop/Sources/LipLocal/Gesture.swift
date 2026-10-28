import Foundation

// Pure state machine, driven by monotonic timestamps. No key repeat or recording IO.
struct FnGesture {
    enum Action: Equatable { case start, stop, cancel }
    enum Mode { case idle, pressed, waiting, locked, stopping }
    private(set) var mode = Mode.idle
    private var downAt = 0.0
    private var releasedAt = 0.0
    private var isDown = false
    let holdThreshold = 0.22
    let doubleTapWindow = 0.34
    mutating func key(down: Bool, at time: Double) -> [Action] {
        guard down != isDown else { return [] }
        isDown = down
        if down {
            switch mode {
            case .idle:
                mode = .pressed; downAt = time; return [.start]
            case .waiting:
                if time - releasedAt <= doubleTapWindow { mode = .locked; return [] }
                mode = .pressed; downAt = time; return [.cancel, .start]
            case .locked:
                mode = .stopping; return [.stop]
            default: return []
            }
        } else {
            switch mode {
            case .pressed:
                if time - downAt >= holdThreshold { mode = .idle; return [.stop] }
                mode = .waiting; releasedAt = time; return []
            case .stopping: mode = .idle; return []
            default: return []
            }
        }
    }
    mutating func tick(at time: Double) -> [Action] {
        if mode == .waiting && time - releasedAt > doubleTapWindow {
            mode = .idle; return [.cancel]
        }
        return []
    }
    mutating func reset() { self = FnGesture() }
    mutating func manualRecordingStarted() {
        reset()
        mode = .locked
    }
}

func testGestures() {
    func check(_ test: Bool, _ label: String) { precondition(test, label) }
    var g = FnGesture()
    check(g.key(down: true, at: 0) == [.start], "hold starts immediately")
    check(g.key(down: true, at: 0.1).isEmpty, "repeat ignored")
    check(g.key(down: false, at: 1) == [.stop], "hold release stops")
    g.reset()
    check(g.key(down: true, at: 0) == [.start], "tap starts capture")
    check(g.key(down: false, at: 0.08).isEmpty, "wait second tap")
    check(g.tick(at: 0.2).isEmpty, "window remains open")
    check(g.key(down: true, at: 0.25).isEmpty, "double tap locks")
    check(g.key(down: false, at: 0.3).isEmpty, "locked release keeps recording")
    check(g.tick(at: 5).isEmpty, "locked recording survives timer")
    check(g.key(down: true, at: 6) == [.stop], "single tap ends locked recording")
    check(g.key(down: false, at: 6.1).isEmpty, "stop release ignored")
    g.reset()
    _ = g.key(down: true, at: 0); _ = g.key(down: false, at: 0.05)
    check(g.tick(at: 0.5) == [.cancel], "accidental single tap cancels")
    check(g.tick(at: 0.6).isEmpty, "cancel only once")
    check(g.key(down: true, at: 1) == [.start], "next recording starts")
    print("Fn gesture tests passed (14 assertions).")
}

// Input Monitoring and microphone are independent grants; one must not mask the other.
func fnControlIssue(inputAllowed: Bool, tapInstalled: Bool, microphoneAllowed: Bool) -> String? {
    if !inputAllowed { return "Fn blocked · enable Lip Local in Privacy & Security → Input Monitoring" }
    if !tapInstalled { return "Fn listener unavailable · quit and reopen Lip Local" }
    if !microphoneAllowed { return "Microphone blocked · enable microphone from the Lip menu" }
    return nil
}
func testControlReadiness() {
    precondition(fnControlIssue(inputAllowed: false, tapInstalled: false, microphoneAllowed: true)?.contains("Input Monitoring") == true)
    precondition(fnControlIssue(inputAllowed: true, tapInstalled: false, microphoneAllowed: true)?.contains("listener unavailable") == true)
    precondition(fnControlIssue(inputAllowed: true, tapInstalled: true, microphoneAllowed: false)?.contains("Microphone blocked") == true)
    precondition(fnControlIssue(inputAllowed: true, tapInstalled: true, microphoneAllowed: true) == nil)
    print("Fn permission-readiness tests passed (4 assertions).")
}

func testManualRecordingGestures() {
    var g = FnGesture()
    g.manualRecordingStarted()
    precondition(g.mode == .locked, "Menu-started recording must enter locked mode")
    precondition(g.tick(at: 100).isEmpty, "Menu-started recording must not time out as an isolated tap")
    precondition(g.key(down: true, at: 101) == [.stop], "One Fn press must finish a menu-started recording")
    precondition(g.key(down: false, at: 101.1).isEmpty, "Stop release must not start/cancel another recording")
    precondition(g.key(down: true, at: 102) == [.start], "Next hold must start a new recording")
    precondition(g.key(down: false, at: 103) == [.stop], "Next hold release must transcribe normally")
    print("Menu-start/Fn-stop tests passed (6 assertions).")
}
