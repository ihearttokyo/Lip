import Foundation
import CryptoKit
import Security
import LocalAuthentication
import Darwin

struct HistoryRecord: Codable, Equatable {
    let id: UUID
    let created: Date
    let profile: String
    let original: String
    let output: String
    let modelDetails: String
    var imported: HistoryPortableMetadata? = nil
}
struct HistoryLimits { var bytes = 50 * 1024 * 1024; var records = 2000; var payload = 1024 * 1024; var file = 2 * 1024 * 1024 }
enum HistoryFailure: Error, CustomStringConvertible {
    case unavailable, keyUnavailable, invalid, limit, revoked, importConflict, importRevoked
    var description: String { switch self {
    case .unavailable: return "Local history storage is unavailable; existing records were preserved."
    case .keyUnavailable: return "History key is unavailable or locked; existing records were preserved."
    case .invalid: return "History contains an invalid or unauthenticated record; it was preserved."
    case .limit: return "History limit reached; no old record was removed."
    case .revoked: return "History save canceled because consent changed."
    case .importConflict: return "Import ID conflicts with existing history; no record was overwritten."
    case .importRevoked: return "Import canceled or already admitted; an admitted write may still finish."
    } }
}

enum HistoryKeychain {
    // No keychain calls at app startup/default-off. Never show an authentication prompt.
    static func attributes() -> [String: Any] { [kSecUseDataProtectionKeychain as String: true,
            kSecAttrSynchronizable as String: false,
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "dev.lip.local.history.v1", kSecAttrAccount as String: "vault"] }
    static func addition(_ bytes: Data) -> [String: Any] {
        var add = attributes(); add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        add[kSecValueData as String] = bytes; return add
    }
    static func key(create: Bool) throws -> SymmetricKey {
        let base = attributes()
        var query = base
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        let auth = LAContext(); auth.interactionNotAllowed = true
        query[kSecUseAuthenticationContext as String] = auth
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess {
            guard let data = result as? Data, data.count == 32 else { throw HistoryFailure.keyUnavailable }
            return SymmetricKey(data: data)
        }
        guard status == errSecItemNotFound, create else { throw HistoryFailure.keyUnavailable }
        let value = SymmetricKey(size: .bits256)
        var add = addition(value.withUnsafeBytes { Data($0) })
        add[kSecUseAuthenticationContext as String] = auth
        guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw HistoryFailure.keyUnavailable }
        return value
    }
}

/// Private encrypted files only. Filesystem lock serializes independent app instances.
/// Injected fixture keys are used by CLI tests; production never writes a key to disk.
final class HistoryVault {
    // Canonicalize ONLY the trusted OS-supplied home/temp anchor, not a vault or
    // user-controlled ancestor. Foundation may re-alias /private/var to /var.
    static func canonicalAnchor(_ anchor: URL) throws -> URL {
        guard let pointer = realpath(anchor.path, nil) else { throw HistoryFailure.unavailable }
        defer { free(pointer) }; return URL(fileURLWithPath: String(cString: pointer), isDirectory: true)
    }
    struct Envelope: Codable { let version: Int; let id: UUID; let sealed: Data }
    let root: URL
    private let key: (Bool) throws -> SymmetricKey
    private let limits: HistoryLimits
    private let beforeAdmission: () -> Void
    private let beforePublish: () -> Void // Paused claimed-commit fixture; production no-op.
    init(root: URL, limits: HistoryLimits = HistoryLimits(), beforeAdmission: @escaping () -> Void = {}, beforePublish: @escaping () -> Void = {}, key: @escaping (Bool) throws -> SymmetricKey = { try HistoryKeychain.key(create: $0) }) {
        self.root = root; self.key = key; self.limits = limits; self.beforePublish = beforePublish; self.beforeAdmission = beforeAdmission
    }
    private let writerLock = NSLock()
    private var writerFD: Int32 = -1
    private var writerOwner: UUID?
    deinit { if writerFD >= 0 { _ = close(writerFD) } }
    private func directory(create: Bool) throws -> Bool {
        guard root.isFileURL, root.path.hasPrefix("/"), !root.path.contains("\0") else { throw HistoryFailure.invalid }
        var current = URL(fileURLWithPath: "/", isDirectory: true)
        for part in root.path.split(separator: "/").map(String.init) {
            guard part != ".", part != ".." else { throw HistoryFailure.invalid }
            current.appendPathComponent(part, isDirectory: true)
            var state = stat()
            if lstat(current.path, &state) != 0 {
                guard errno == ENOENT else { throw HistoryFailure.unavailable }
                if !create { return false }
                guard (mkdir(current.path, 0o700) == 0 || errno == EEXIST), lstat(current.path, &state) == 0 else { throw HistoryFailure.unavailable }
            }
            guard state.st_mode & S_IFMT == S_IFDIR else { throw HistoryFailure.invalid }
        }
        var leaf = stat()
        guard lstat(root.path, &leaf) == 0, leaf.st_uid == getuid(), leaf.st_mode & 0o077 == 0 else { throw HistoryFailure.invalid }
        return true // All ancestors checked BEFORE creating descendants; no link resolution fallback.
    }
    // One authorized writer service, across independent app instances. A second
    // instance cannot issue a recording ticket OR change consent behind its back.
    func claimWriter(_ owner: UUID) throws {
        writerLock.lock(); defer { writerLock.unlock() }
        if let current = writerOwner { guard current == owner else { throw HistoryFailure.unavailable }; return }
        _ = try directory(create: true)
        let fd = open(root.appendingPathComponent(".writer.lock").path, O_CREAT | O_RDWR | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw HistoryFailure.unavailable }
        guard flock(fd, LOCK_EX | LOCK_NB) == 0 else { _ = close(fd); throw HistoryFailure.unavailable }
        writerFD = fd; writerOwner = owner
    }
    func releaseWriter(_ owner: UUID) {
        writerLock.lock(); defer { writerLock.unlock() }
        if writerOwner == owner { _ = close(writerFD); writerFD = -1; writerOwner = nil }
    }
    func hasWriter(_ owner: UUID) -> Bool { writerLock.lock(); defer { writerLock.unlock() }; return writerOwner == owner }
    private func files() throws -> (records: [URL], bytes: Int, entries: Int) {
        var records: [URL] = []; var bytes = 0; var entries = 0
        let iterator = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard iterator.count <= limits.records + 16 else { throw HistoryFailure.limit }
        for file in iterator {
            let info = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard info.isRegularFile == true, info.isSymbolicLink != true, let size = info.fileSize, size <= limits.file else { throw HistoryFailure.invalid }
            if [".vault.lock", ".writer.lock"].contains(file.lastPathComponent) { continue }
            entries += 1; bytes += size
            guard entries <= limits.records, bytes <= limits.bytes else { throw HistoryFailure.limit }
            if file.lastPathComponent.hasPrefix(".pending-") { continue } // Encrypted orphan counts toward budget; never auto-deleted.
            guard let id = UUID(uuidString: file.deletingPathExtension().lastPathComponent), file.lastPathComponent == id.uuidString + ".json" else { throw HistoryFailure.invalid }
            records.append(file)
        }
        return (records, bytes, entries)
    }
    private func aad(_ id: UUID, version: Int = 1) -> Data { Data("lip-transcript-history-v\(version)|\(id.uuidString)".utf8) }
    private func decode(_ file: URL, key: SymmetricKey) throws -> HistoryRecord {
        let info = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard info.isRegularFile == true, info.isSymbolicLink != true, let size = info.fileSize, size <= limits.file else { throw HistoryFailure.invalid }
        do {
            let envelope = try JSONDecoder().decode(Envelope.self, from: Data(contentsOf: file))
            guard [1,2].contains(envelope.version), file.lastPathComponent == envelope.id.uuidString + ".json" else { throw HistoryFailure.invalid }
            let data = try AES.GCM.open(AES.GCM.SealedBox(combined: envelope.sealed), using: key, authenticating: aad(envelope.id, version: envelope.version))
            guard data.count <= limits.payload else { throw HistoryFailure.limit }
            let record = try JSONDecoder().decode(HistoryRecord.self, from: data)
            guard record.id == envelope.id, (record.imported != nil) == (envelope.version == 2) else { throw HistoryFailure.invalid }
            try HistoryPortableCodec.validateLocal(record)
            return record
        } catch { throw HistoryFailure.invalid }
    }
    func list() throws -> (records: [HistoryRecord], unreadable: Int) {
        guard try directory(create: false) else { return ([], 0) }
        let scanned = try files(); if scanned.records.isEmpty { return ([], scanned.entries) }
        let value = try key(false); var records: [HistoryRecord] = []; var unreadable = scanned.entries - scanned.records.count
        for file in scanned.records { do { records.append(try decode(file, key: value)) } catch { unreadable += 1 } }
        return (records.sorted { $0.created > $1.created }, unreadable)
    }
    private func withVaultLock<T>(create: Bool, absent: () throws -> T, action: () throws -> T) throws -> T {
        guard try directory(create: create) else { return try absent() }
        let fd = open(root.appendingPathComponent(".vault.lock").path, O_CREAT | O_RDWR | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw HistoryFailure.unavailable }
        defer { _ = close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_uid == getuid(), info.st_mode & 0o077 == 0 else { throw HistoryFailure.invalid }
        guard flock(fd, LOCK_EX) == 0 else { throw HistoryFailure.unavailable }
        defer { _ = flock(fd, LOCK_UN) }; return try action()
    }
    func save(_ record: HistoryRecord, commit: (() throws -> Void) throws -> Void = { try $0() }) throws {
        try HistoryPortableCodec.validateLocal(record)
        let plain = try JSONEncoder().encode(record)
        guard plain.count <= limits.payload else { throw HistoryFailure.limit }
        try withVaultLock(create: true, absent: { throw HistoryFailure.unavailable }) {
            try saveLocked(record, plain: plain, commit: commit)
        }
    }
    /// Caller already owns .vault.lock. Never recursively open/flock it.
    private func saveLocked(_ record: HistoryRecord, plain: Data, storedKey: SymmetricKey? = nil,
        commit: (() throws -> Void) throws -> Void) throws {
        let before = try files()
        guard before.entries < limits.records else { throw HistoryFailure.limit }
        guard !before.records.isEmpty || before.bytes == 0 else { throw HistoryFailure.invalid }
        let value = try storedKey ?? key(before.bytes == 0 && before.records.isEmpty) // Existing ciphertext NEVER gets a replacement key.
        if !before.records.isEmpty {
            guard before.records.contains(where: { (try? decode($0, key: value)) != nil }) else { throw HistoryFailure.invalid }
        }
        let version = record.imported == nil ? 1 : 2
        let box = try AES.GCM.seal(plain, using: value, authenticating: aad(record.id, version: version))
        guard let sealed = box.combined else { throw HistoryFailure.unavailable }
        let data = try JSONEncoder().encode(Envelope(version: version, id: record.id, sealed: sealed))
        guard data.count <= limits.file, data.count <= limits.bytes - before.bytes else { throw HistoryFailure.limit }
        let target = root.appendingPathComponent(record.id.uuidString + ".json")
        guard !FileManager.default.fileExists(atPath: target.path) else { throw HistoryFailure.invalid }
        let stage = root.appendingPathComponent(".pending-" + UUID().uuidString)
        try data.write(to: stage, options: .withoutOverwriting)
        defer { try? FileManager.default.removeItem(at: stage) } // Only THIS encrypted stage, never another record.
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: stage.path)
        let handle = try FileHandle(forWritingTo: stage); try handle.synchronize(); try handle.close()
        beforeAdmission() // Fixture hook outside CPU authorization locks; vault filesystem lock remains held. Production no-op.
        try commit { beforePublish(); try FileManager.default.moveItem(at: stage, to: target) }
    }
    private func importClassificationLocked(_ record: HistoryPortableRecord) throws -> (HistoryPortableDisposition, SymmetricKey?) {
        let scanned = try files()
        guard scanned.entries == scanned.records.count else { throw HistoryFailure.invalid } // Orphans are evidence, not free capacity.
        guard !scanned.records.isEmpty else { return (.new, nil) }
        let value = try key(false)
        // Authenticate ALL native records, but an unrelated legacy diagnostic
        // field must not become a portable-validation prerequisite for this ID.
        let all = try scanned.records.map { try decode($0, key: value) }
        return (try HistoryPortableCodec.classify(record, local: all), value)
    }
    func previewImport(_ record: HistoryPortableRecord) throws -> HistoryPortableDisposition {
        _ = try HistoryPortableCodec.encode(HistoryPortableDocument(sourcePlatform: "macos", record: record))
        return try withVaultLock(create: false, absent: { .new }) { try importClassificationLocked(record).0 }
    }
    func importRecord(_ record: HistoryPortableRecord, authorization: HistoryImportAuthorization) throws -> HistoryPortableDisposition {
        _ = try HistoryPortableCodec.encode(HistoryPortableDocument(sourcePlatform: "macos", record: record))
        try authorization.checkLive()
        return try withVaultLock(create: true, absent: { throw HistoryFailure.unavailable }) {
            try authorization.checkLive() // It may have been canceled while waiting for the filesystem lock.
            let (disposition, value) = try importClassificationLocked(record)
            switch disposition {
            case .identical: try authorization.claim(); return .identical
            case .conflict, .ambiguous: throw HistoryFailure.importConflict
            case .new:
                let local = try HistoryPortableCodec.local(record)
                let plain = try JSONEncoder().encode(local)
                guard plain.count <= limits.payload else { throw HistoryFailure.limit }
                try saveLocked(local, plain: plain, storedKey: value) { action in
                    try authorization.claim() // CPU-only; lock released before filesystem action.
                    try action()
                }
                let verified = try decode(root.appendingPathComponent(local.id.uuidString + ".json"), key: key(false))
                guard try HistoryPortableCodec.portable(verified) == record else { throw HistoryFailure.invalid }
                return .new
            }
        }
    }
    func delete(_ id: UUID) throws {
        try withVaultLock(create: false, absent: { throw HistoryFailure.invalid }) {
            let target = root.appendingPathComponent(id.uuidString + ".json")
            _ = try decode(target, key: key(false)) // Refuse unknown/corrupt/symlink targets rather than erase recovery evidence.
            try FileManager.default.removeItem(at: target)
        }
    }
    static func export(_ record: HistoryRecord) throws -> Data { try HistoryPortableCodec.export(record) }
}

/// One completed result per recording. Explicit cancel fences a queued final commit.
final class HistorySaveLease {
    private let lock = NSLock()
    private var live = true
    func cancel() { lock.lock(); defer { lock.unlock() }; live = false }
    func claim() throws {
        lock.lock(); defer { lock.unlock() }
        guard live else { throw HistoryFailure.revoked }; live = false
    }
}
struct HistoryTicket { let incarnation: UUID; let lease: HistorySaveLease }

/// Consent incarnation captured at RECORD START. Off→on never admits an old session.
final class HistoryConsent {
    private let lock = NSLock()
    private var enabled: Bool
    private var incarnation = UUID()
    init(enabled: Bool) { self.enabled = enabled }
    func ticket() -> HistoryTicket? { lock.lock(); defer { lock.unlock() }; return enabled ? HistoryTicket(incarnation: incarnation, lease: HistorySaveLease()) : nil }
    func set(_ value: Bool) { lock.lock(); defer { lock.unlock() }; enabled = value; incarnation = UUID() }
    func checkCurrent(_ ticket: HistoryTicket) throws {
        lock.lock(); defer { lock.unlock() }
        guard enabled, ticket.incarnation == incarnation else { throw HistoryFailure.revoked }
    }
    private func authorize(_ ticket: HistoryTicket, privacy: HistoryPrivacyLease?, now: Double) throws {
        lock.lock(); defer { lock.unlock() }
        guard enabled, ticket.incarnation == incarnation else { throw HistoryFailure.revoked }
        try ticket.lease.claim() // Atomic memory-only global + one-shot admission.
        try privacy?.claim(now: now) // Optional only for explicit/manual primitive API; automatic API always supplies one.
    }
    func withCommit(_ ticket: HistoryTicket, privacy: HistoryPrivacyLease? = nil, now: Double = 0, _ action: () throws -> Void) throws {
        try authorize(ticket, privacy: privacy, now: now)
        // No consent/lease lock is held during IO. An already-claimed operation
        // may finish; async disable confirmation drains it before claiming Off.
        try action()
    }
}

struct HistoryReadiness {
    let request: UUID
    let requested: Bool
    let ready: Bool
    let preparing: Bool
    let error: HistoryFailure?
}
struct HistorySettingEvent {
    let request: UUID
    let result: Result<Void, HistoryFailure>
}

final class HistoryService {
    private let defaults: UserDefaults
    private let owner = UUID()
    private let consent = HistoryConsent(enabled: false)
    private let vault: HistoryVault
    private let queue = DispatchQueue(label: "dev.lip.history-storage", qos: .utility)
    private let stateLock = NSLock()
    private var request = UUID()
    private var requested: Bool
    private var ready = false
    private var preparing: Bool
    private var failure: HistoryFailure?
    private let beforeAcquire: () -> Void // Deterministic paused-IO fixture; production no-op.
    var enabled: Bool { snapshot().requested }
    func snapshot() -> HistoryReadiness {
        stateLock.lock(); defer { stateLock.unlock() }
        return HistoryReadiness(request: request, requested: requested, ready: ready, preparing: preparing, error: failure)
    }
    init(defaults: UserDefaults = .standard, vault: HistoryVault? = nil, beforeAcquire: @escaping () -> Void = {}) {
        self.defaults = defaults; self.requested = defaults.bool(forKey: "history.enabled.v1"); self.preparing = self.requested
        self.vault = vault ?? HistoryVault(root: FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/LipLocal/TranscriptHistory-v1", isDirectory: true))
        self.beforeAcquire = beforeAcquire
        if requested { prepare(request: request, value: true, persist: false, done: { _ in }) }
    }
    deinit {
        let vault = vault, owner = owner
        queue.async { vault.releaseWriter(owner) } // No writer-close filesystem work on a UI deinit path.
    }
    /// Cheap recording-path snapshot only. No lstat/mkdir/open/flock/Keychain.
    func ticket() -> HistoryTicket? {
        stateLock.lock(); defer { stateLock.unlock() }
        return requested && ready ? consent.ticket() : nil
    }
    @discardableResult func setEnabled(_ value: Bool, done: @escaping (HistorySettingEvent) -> Void = { _ in }) -> UUID {
        stateLock.lock()
        let id = UUID(); request = id; requested = value; ready = false; preparing = true; failure = nil
        consent.set(false) // Revoke BEFORE queued acquisition/persistence; no old ticket can revive.
        stateLock.unlock()
        prepare(request: id, value: value, persist: true, done: done)
        return id
    }
    private func current(_ id: UUID) -> Bool {
        stateLock.lock(); defer { stateLock.unlock() }; return request == id
    }
    private func prepare(request id: UUID, value: Bool, persist: Bool, done: @escaping (HistorySettingEvent) -> Void) {
        queue.async { [self] in
            let result: Result<Void, HistoryFailure>
            do {
                guard current(id) else { throw HistoryFailure.revoked }
                beforeAcquire() // No state/consent lock held across potentially blocked disk setup.
                guard current(id) else { throw HistoryFailure.revoked }
                try vault.claimWriter(owner)
                guard current(id) else { throw HistoryFailure.revoked }
                if persist {
                    defaults.set(value, forKey: "history.enabled.v1")
                    guard defaults.synchronize() else { throw HistoryFailure.unavailable }
                }
                stateLock.lock()
                if request == id {
                    consent.set(value); ready = value; preparing = false; failure = nil
                    stateLock.unlock(); result = .success(())
                } else {
                    stateLock.unlock(); throw HistoryFailure.revoked
                }
            } catch {
                let issue = (error as? HistoryFailure) ?? .unavailable
                stateLock.lock()
                if request == id { ready = false; preparing = false; failure = issue; consent.set(false) }
                stateLock.unlock(); result = .failure(issue)
            }
            let event = HistorySettingEvent(request: id, result: result)
            DispatchQueue.main.async { done(event) } // Caller must compare event.request to snapshot().request.
        }
    }
    func automaticTicket(capture: UUID, privacy: HistoryPrivacyLease, now: Double) -> HistoryAutomaticTicket? {
        guard privacy.snapshot(now: now) == .eligible else { return nil }
        stateLock.lock(); defer { stateLock.unlock() }
        guard requested, ready, let storage = consent.ticket() else { return nil }
        return HistoryAutomaticTicket(capture: capture, settingRequest: request, storage: storage, privacy: privacy)
    }
    func saveAutomatically(_ record: HistoryRecord, ticket: HistoryAutomaticTicket, clock: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime }, done: @escaping (HistorySaveEvent) -> Void) {
        persist(record, ticket: ticket.storage, privacy: ticket.privacy, clock: clock) { saved, message in
            done(HistorySaveEvent(capture: ticket.capture, settingRequest: ticket.settingRequest, record: record.id, saved: saved, message: message))
        }
    }
    // Deliberate explicit-user-save API, never a recording-path fallback.
    func saveForExplicitUserRequest(_ record: HistoryRecord, ticket: HistoryTicket?, done: @escaping (String) -> Void) {
        persist(record, ticket: ticket, privacy: nil, clock: { ProcessInfo.processInfo.systemUptime }) { _, message in done(message) }
    }
    private func persist(_ record: HistoryRecord, ticket: HistoryTicket?, privacy: HistoryPrivacyLease?, clock: @escaping () -> Double, done: @escaping (Bool, String) -> Void) {
        guard let ticket else { return } // Default-off performs no vault/key work.
        queue.async { [self] in
            let message: String; let saved: Bool
            do {
                guard vault.hasWriter(owner) else { throw HistoryFailure.unavailable }; try consent.checkCurrent(ticket)
                if let privacy, privacy.snapshot(now: clock()) != .eligible { throw HistoryPrivacyFailure.denied }
                try vault.save(record) { action in
                    let now = clock() // No caller/provider code executes under admission locks.
                    try consent.withCommit(ticket, privacy: privacy, now: now, action)
                }
                saved = true; message = "Saved encrypted dictation history locally."
            } catch {
                saved = false
                message = error is HistoryPrivacyFailure ? "History not retained: protected, unverified or changed context. Current transcript kept in Review." : "History save not confirmed; current transcript kept. \((error as? HistoryFailure)?.description ?? "Storage unavailable; no automatic retry.")"
            }
            DispatchQueue.main.async { done(saved, message) }
        }
    }
    func list(_ done: @escaping (Result<(records: [HistoryRecord], unreadable: Int), Error>) -> Void) {
        queue.async { [self] in let value = Result { try vault.list() }; DispatchQueue.main.async { done(value) } }
    }
    @discardableResult func previewImport(_ record: HistoryPortableRecord, operation: UUID = UUID(), done: @escaping (HistoryImportEvent) -> Void) -> UUID {
        queue.async { [self] in
            let result = Result { try vault.previewImport(record) }
            DispatchQueue.main.async { done(HistoryImportEvent(operation: operation, result: result)) }
        }
        return operation
    }
    func importRecord(_ record: HistoryPortableRecord, authorization: HistoryImportAuthorization, done: @escaping (HistoryImportEvent) -> Void) {
        queue.async { [self] in
            let result = Result {
                _ = try HistoryPortableCodec.encode(HistoryPortableDocument(sourcePlatform: "macos", record: record))
                try authorization.checkLive()
                try vault.claimWriter(owner) // Explicit request only; no capture setting/ticket changes.
                return try vault.importRecord(record, authorization: authorization)
            }
            DispatchQueue.main.async { done(HistoryImportEvent(operation: authorization.id, result: result)) }
        }
    }
    func delete(_ id: UUID, done: @escaping (Result<Void, Error>) -> Void) {
        queue.async { [self] in let value = Result { try vault.claimWriter(owner); try vault.delete(id) }; DispatchQueue.main.async { done(value) } }
    }
}
