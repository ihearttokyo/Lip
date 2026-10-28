import AppKit
import ApplicationServices
import CoreGraphics
import Darwin

/// Capability-limited reader interface: no text/range/title/clipboard method exists.
enum HistoryMetadataAttribute { case role, subrole, protectedContent, enabled }
protocol HistoryMetadataAccess {
    associatedtype Element
    func trusted() -> Bool
    func focused() -> Element?
    func pid(_ element: Element) -> Int32?
    func equal(_ lhs: Element, _ rhs: Element) -> Bool
    func string(_ element: Element, _ attribute: HistoryMetadataAttribute) -> String?
    func boolean(_ element: Element, _ attribute: HistoryMetadataAttribute) -> Bool?
    func valueSettable(_ element: Element) -> Bool?
}
final class HistoryMetadataReader<Access: HistoryMetadataAccess> {
    private let access: Access
    private let cancelled: () -> Bool
    private(set) var original: Access.Element?
    private let identity = UUID()
    init(access: Access, cancelled: @escaping () -> Bool = { false }) { self.access = access; self.cancelled = cancelled }
    func read() -> HistoryFieldMetadata? {
        guard !cancelled(), access.trusted(), !cancelled(), let element = access.focused(), !cancelled(), let pid = access.pid(element) else { return nil }
        if original == nil { original = element }
        guard let original else { return nil }
        let token = access.equal(original, element) ? identity : UUID()
        guard !cancelled() else { return nil }; let role = access.string(element, .role)
        guard !cancelled() else { return nil }; let subrole = access.string(element, .subrole)
        guard !cancelled() else { return nil }; let protected = access.boolean(element, .protectedContent)
        guard !cancelled() else { return nil }; let enabled = access.boolean(element, .enabled)
        guard !cancelled() else { return nil }; let editable = access.valueSettable(element)
        guard !cancelled() else { return nil }
        return HistoryFieldMetadata(pid: pid, identity: token, role: role, subrole: subrole, protectedContent: protected, enabled: enabled, editable: editable)
    }
}

/// Discovery binds the destruction subscription, but ONLY a new same-identity
/// metadata read after every subscription/drain can arm the capture lease.
enum HistoryObservationSetup {
    static func arm<Access: HistoryMetadataAccess>(reader: HistoryMetadataReader<Access>, lease: HistoryPrivacyLease,
        clock: () -> Double, subscribeDestruction: (Access.Element) -> Bool, drainPending: () -> Void, cancelled: () -> Bool) -> Bool {
        guard !cancelled(), let discovery = reader.read(), discovery.eligible, let element = reader.original,
            subscribeDestruction(element) else { lease.revoke(); return false }
        drainPending()
        guard !cancelled() else { lease.revoke(); return false }
        let started = clock(), latest = reader.read(), finished = clock()
        guard !cancelled(), let latest, latest.eligible, latest.pid == discovery.pid, latest.identity == discovery.identity else { lease.revoke(); return false }
        return lease.arm(latest, sampledAt: started, now: finished, observationReady: true)
    }
}

private struct HistorySystemMetadataAccess: HistoryMetadataAccess {
    let system = AXUIElementCreateSystemWide()
    func trusted() -> Bool { AXIsProcessTrusted() } // Never prompts or grants access.
    func focused() -> AXUIElement? {
        var output: CFTypeRef?
        guard AXUIElementCopyAttributeValue(system, kAXFocusedUIElementAttribute as CFString, &output) == .success,
            let output, CFGetTypeID(output) == AXUIElementGetTypeID() else { return nil }
        return (output as! AXUIElement)
    }
    func pid(_ element: AXUIElement) -> Int32? {
        var value: pid_t = 0
        return AXUIElementGetPid(element, &value) == .success && value > 0 ? value : nil
    }
    func equal(_ lhs: AXUIElement, _ rhs: AXUIElement) -> Bool { CFEqual(lhs, rhs) }
    private func name(_ attribute: HistoryMetadataAttribute) -> CFString {
        switch attribute {
        case .role: return kAXRoleAttribute as CFString
        case .subrole: return kAXSubroleAttribute as CFString
        case .protectedContent: return NSAccessibility.Attribute.containsProtectedContent.rawValue as CFString
        case .enabled: return kAXEnabledAttribute as CFString
        }
    }
    func string(_ element: AXUIElement, _ attribute: HistoryMetadataAttribute) -> String? {
        guard attribute == .role || attribute == .subrole else { return nil }
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, name(attribute), &value) == .success,
            let value, CFGetTypeID(value) == CFStringGetTypeID(), let result = value as? String, result.utf8.count <= 64 else { return nil }
        return result
    }
    func boolean(_ element: AXUIElement, _ attribute: HistoryMetadataAttribute) -> Bool? {
        guard attribute == .protectedContent || attribute == .enabled else { return nil }
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, name(attribute), &value) == .success,
            let value, CFGetTypeID(value) == CFBooleanGetTypeID() else { return nil }
        return CFBooleanGetValue((value as! CFBoolean))
    }
    func valueSettable(_ element: AXUIElement) -> Bool? {
        var value = DarwinBoolean(false)
        // Attribute SETTABILITY only. Never copy AXValue or any text/range.
        return AXUIElementIsAttributeSettable(element, kAXValueAttribute as CFString, &value) == .success ? value.boolValue : nil
    }
}

/// Main-actor public platform facts. Not a graphical-screen-unlocked oracle.
/// Construct only for explicitly enabled history, never as permission acquisition.
@MainActor final class HistoryPlatformEnvironment {
    let feed: HistoryEnvironmentFeed
    private let app: NSApplication
    private var tokens: [(NotificationCenter, NSObjectProtocol)] = []
    private var distributed: NSObjectProtocol?
    private var timer: Timer?
    init(app: NSApplication, feed: HistoryEnvironmentFeed = HistoryEnvironmentFeed()) {
        self.app = app; self.feed = feed
        let workspace = NSWorkspace.shared.notificationCenter
        let notifications: [(NotificationCenter, Notification.Name)] = [
            (NotificationCenter.default, Notification.Name.NSApplicationProtectedDataWillBecomeUnavailable),
            (NotificationCenter.default, Notification.Name.NSApplicationProtectedDataDidBecomeAvailable),
            (workspace, NSWorkspace.sessionDidResignActiveNotification),
            (workspace, NSWorkspace.sessionDidBecomeActiveNotification),
            (workspace, NSWorkspace.willSleepNotification), (workspace, NSWorkspace.didWakeNotification),
            (workspace, NSWorkspace.didActivateApplicationNotification)
        ]
        for (center, name) in notifications {
            let token = center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.feed.revoke(at: ProcessInfo.processInfo.systemUptime)
                    // A later periodic fresh snapshot may permit NEW leases only.
                }
            }
            tokens.append((center, token))
        }
        distributed = DistributedNotificationCenter.default().addObserver(forName: NSNotification.Name("com.apple.screenIsLocked"), object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.feed.revoke(at: ProcessInfo.processInfo.systemUptime) }
        } // Extra revoke-only defense, never a source of initial allow authority.
        refresh() // AFTER every lifecycle subscription exists.
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in MainActor.assumeIsolated { self?.refresh() } }
        if let timer { RunLoop.main.add(timer, forMode: .common) }
    }
    private func refresh() {
        HistoryEnvironmentSampler.publish(to: feed, clock: { ProcessInfo.processInfo.systemUptime }) { [self] in
        var facts = HistoryEnvironmentFacts()
        facts.protectedDataAvailable = app.isProtectedDataAvailable
        facts.frontPID = NSWorkspace.shared.frontmostApplication?.processIdentifier
        if let raw = CGSessionCopyCurrentDictionary() as? [String: Any] {
            // Do not retain/log session dictionary, username or unrelated fields.
            func boolean(_ key: String) -> Bool? {
                guard let value = raw[key as String] as CFTypeRef?, CFGetTypeID(value) == CFBooleanGetTypeID() else { return nil }
                return CFBooleanGetValue((value as! CFBoolean))
            }
            facts.onConsole = boolean(kCGSessionOnConsoleKey)
            facts.loginDone = boolean(kCGSessionLoginDoneKey)
            if let value = raw[kCGSessionUserIDKey as String] as CFTypeRef?, CFGetTypeID(value) == CFNumberGetTypeID(), let number = value as? NSNumber {
                facts.currentUserMatches = number.int64Value == Int64(getuid())
            }
        }
        return facts
        }
    }
    deinit {
        timer?.invalidate(); for (center, token) in tokens { center.removeObserver(token) }
        if let distributed { DistributedNotificationCenter.default().removeObserver(distributed) }
        feed.revoke(at: ProcessInfo.processInfo.systemUptime)
    }
}

/// Wrapper lifetime cancels immediately without joining a worker blocked in AX IPC.
/// Production caller is opt-in; real signed/editor/runtime qualification remains open.
final class HistoryPrivacyMonitor {
    let lease: HistoryPrivacyLease
    private let core: HistoryAXObservationCore
    init(feed: HistoryEnvironmentFeed, ready: @escaping (HistoryPrivacyLease?) -> Void) {
        let lease = HistoryPrivacyLease(feed: feed); self.lease = lease
        core = HistoryAXObservationCore(feed: feed, lease: lease, ready: ready); core.start()
    }
    func cancel() { core.stop() }
    deinit { core.stop() }
}
private final class HistoryAXObservationCore {
    private let feed: HistoryEnvironmentFeed
    private let lease: HistoryPrivacyLease
    private let ready: (HistoryPrivacyLease?) -> Void
    private let lock = NSLock()
    private var stopped = false
    init(feed: HistoryEnvironmentFeed, lease: HistoryPrivacyLease, ready: @escaping (HistoryPrivacyLease?) -> Void) {
        self.feed = feed; self.lease = lease; self.ready = ready
    }
    func stop() { lease.revoke(); lock.lock(); stopped = true; lock.unlock() }
    private func isStopped() -> Bool { lock.lock(); defer { lock.unlock() }; return stopped }
    func start() {
        if !HistoryObservationCapacity.shared.launch({ [self] in run() }) { stop(); reply() }
    }
    private func reply() {
        let lease = lease, callback = ready
        DispatchQueue.main.async { callback(lease.snapshot(now: ProcessInfo.processInfo.systemUptime) == .eligible ? lease : nil) }
    }
    private func run() {
        // Thread's strong self owns callback context through source invalidation.
        let initial = feed.latest()
        guard initial.ready, initial.facts.eligible, let pid = initial.facts.frontPID, !isStopped(), AXIsProcessTrusted() else { lease.revoke(); reply(); return }
        let app = AXUIElementCreateApplication(pid)
        var observer: AXObserver?
        guard AXObserverCreate(pid, { _, _, _, context in
            if let context { Unmanaged<HistoryAXObservationCore>.fromOpaque(context).takeUnretainedValue().stop() }
        }, &observer) == .success, let observer else { lease.revoke(); reply(); return }
        let loop = CFRunLoopGetCurrent(), source = AXObserverGetRunLoopSource(observer)
        var subscriptions: [(AXUIElement, CFString)] = []
        defer {
            lease.revoke()
            for (element, name) in subscriptions { AXObserverRemoveNotification(observer, element, name) }
            CFRunLoopRemoveSource(loop, source, .defaultMode); CFRunLoopSourceInvalidate(source)
            // No callback can run on this worker loop after its source is invalidated.
        }
        let context = Unmanaged.passUnretained(self).toOpaque()
        for name in [kAXFocusedUIElementChangedNotification, kAXFocusedWindowChangedNotification, kAXLayoutChangedNotification] {
            guard AXObserverAddNotification(observer, app, name as CFString, context) == .success else { lease.revoke(); reply(); return }
            subscriptions.append((app, name as CFString))
        }
        CFRunLoopAddSource(loop, source, .defaultMode)
        let reader = HistoryMetadataReader(access: HistorySystemMetadataAccess(), cancelled: { [self] in isStopped() })
        let armed = HistoryObservationSetup.arm(reader: reader, lease: lease, clock: { ProcessInfo.processInfo.systemUptime },
            subscribeDestruction: { element in
                guard AXObserverAddNotification(observer, element, kAXUIElementDestroyedNotification as CFString, context) == .success else { return false }
                subscriptions.append((element, kAXUIElementDestroyedNotification as CFString)); return true
            }, drainPending: { CFRunLoopRunInMode(.defaultMode, 0, true) }, cancelled: { [self] in isStopped() })
        guard armed else { lease.revoke(); reply(); return }
        reply()
        while !isStopped(), lease.snapshot(now: ProcessInfo.processInfo.systemUptime) == .eligible {
            CFRunLoopRunInMode(.defaultMode, 0.1, true)
            if isStopped() { break }
            let start = ProcessInfo.processInfo.systemUptime
            lease.observe(reader.read(), sampledAt: start, now: ProcessInfo.processInfo.systemUptime)
        }
    }
}
