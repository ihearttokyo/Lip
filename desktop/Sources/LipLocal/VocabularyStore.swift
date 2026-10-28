import Foundation
import CryptoKit
import Security
import LocalAuthentication
import Darwin

enum VocabularyStoreFailure: Error, CustomStringConvertible {
    case unavailable, invalid, canceled, keyUnavailable
    var description: String { switch self {
    case .unavailable: return "Vocabulary storage unavailable. Existing files were preserved; no automatic retry was made."
    case .invalid: return "Vocabulary could not be authenticated or validated. Existing files were preserved."
    case .canceled: return "Canceled. An already-admitted write may still finish."
    case .keyUnavailable: return "Vocabulary key unavailable or locked. Existing files were preserved."
    } }
}

/// Cancellation wins only before claim. Claim is one-use and never reset.
final class VocabularyLease: @unchecked Sendable {
    private let lock = NSLock()
    private var canceled = false, claimed = false
    func cancel() { lock.lock(); canceled = true; lock.unlock() }
    func check() throws { lock.lock(); defer { lock.unlock() }; if canceled { throw VocabularyStoreFailure.canceled } }
    func claim() throws { lock.lock(); defer { lock.unlock() }; guard !canceled, !claimed else { throw VocabularyStoreFailure.canceled }; claimed = true }
}

private enum VocabularyKeychain {
    static func key(create: Bool) throws -> SymmetricKey {
        var query: [String: Any] = [kSecUseDataProtectionKeychain as String: true,
            kSecAttrSynchronizable as String: false, kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "dev.lip.local.portable-vocabulary.v1", kSecAttrAccount as String: "vocabulary"]
        let auth = LAContext(); auth.interactionNotAllowed = true
        query[kSecUseAuthenticationContext as String] = auth
        var search = query; search[kSecReturnData as String] = true; search[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(search as CFDictionary, &result)
        if status == errSecSuccess {
            guard let bytes = result as? Data, bytes.count == 32 else { throw VocabularyStoreFailure.keyUnavailable }
            return SymmetricKey(data: bytes)
        }
        guard status == errSecItemNotFound, create else { throw VocabularyStoreFailure.keyUnavailable }
        let key = SymmetricKey(size: .bits256)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        query[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        guard SecItemAdd(query as CFDictionary, nil) == errSecSuccess else { throw VocabularyStoreFailure.keyUnavailable }
        return key
    }
}

final class VocabularyStore: @unchecked Sendable {
    static let didChange = Notification.Name("dev.lip.portable-vocabulary.changed.v1")
    static let shared = VocabularyStore(root: FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support/LipLocal/PortableVocabulary", isDirectory: true))
    struct Snapshot: CustomStringConvertible {
        let entries: [PortableVocabulary.Entry]
        let revision: UUID?
        var activeEnglishWords: [String] { entries.filter { VocabularyStore.isActiveEnglish($0) }.map(\.term) }
        var description: String { "VocabularySnapshot(count: \(entries.count))" }
    }
    struct Preview: CustomStringConvertible {
        let incoming: [PortableVocabulary.Entry]
        let existingCount: Int, mergedCount: Int, activeEnglishCount: Int
        var description: String { "VocabularyPreview(count: \(incoming.count))" }
    }
    let root: URL
    private let codec: PortableVocabulary
    private let keyProvider: (Bool) throws -> SymmetricKey
    init(root: URL, codec: PortableVocabulary = PortableVocabulary(), keyProvider: @escaping (Bool) throws -> SymmetricKey = { try VocabularyKeychain.key(create: $0) }) {
        self.root = root; self.codec = codec; self.keyProvider = keyProvider
    }
    static func isActiveEnglish(_ entry: PortableVocabulary.Entry) -> Bool {
        guard ["en", "und"].contains(entry.language), (1...48).contains(entry.term.utf8.count) else { return false }
        var lastLetter = false
        for byte in entry.term.utf8 {
            if (65...90).contains(byte) || (97...122).contains(byte) { lastLetter = true }
            else if byte == 39, lastLetter { lastLetter = false }
            else { return false }
        }
        return lastLetter
    }
    // Walk ancestors without following symlinks, then operate through the held leaf descriptor.
    private func directory(create: Bool) throws -> Int32? {
        guard root.isFileURL, root.path.hasPrefix("/"), !root.path.contains("\0") else { throw VocabularyStoreFailure.invalid }
        var fd = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC)
        guard fd >= 0 else { throw VocabularyStoreFailure.unavailable }
        do {
            let components = root.path.split(separator: "/")
            guard !components.isEmpty else { throw VocabularyStoreFailure.invalid }
            for component in components {
                let part = String(component)
                guard part != ".", part != ".." else { throw VocabularyStoreFailure.invalid }
                var next = openat(fd, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
                if next < 0, errno == ENOENT {
                    if !create { _ = close(fd); return nil }
                    guard mkdirat(fd, part, 0o700) == 0 || errno == EEXIST else { throw VocabularyStoreFailure.unavailable }
                    next = openat(fd, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
                }
                guard next >= 0 else { throw VocabularyStoreFailure.invalid }
                _ = close(fd); fd = next
            }
            var state = stat()
            guard fstat(fd, &state) == 0, state.st_uid == getuid(), state.st_mode & 0o077 == 0 else { throw VocabularyStoreFailure.invalid }
            if create {
                var value = URLResourceValues(); value.isExcludedFromBackup = true
                var url = root; try url.setResourceValues(value)
            }
            return fd
        } catch { _ = close(fd); throw error }
    }
    private func locked<T>(create: Bool, absent: () throws -> T, _ body: (Int32) throws -> T) throws -> T {
        guard let directory = try directory(create: create) else { return try absent() }
        defer { _ = close(directory) }
        let fd = openat(directory, ".lock", O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw VocabularyStoreFailure.unavailable }
        defer { _ = close(fd) }
        var state = stat()
        guard fstat(fd, &state) == 0, state.st_mode & S_IFMT == S_IFREG, state.st_uid == getuid(), state.st_mode & 0o077 == 0,
              flock(fd, LOCK_EX) == 0 else { throw VocabularyStoreFailure.invalid }
        defer { _ = flock(fd, LOCK_UN) }
        return try body(directory)
    }
    private func readLocked(_ directory: Int32) throws -> Snapshot {
        let fd = openat(directory, "vocabulary.enc", O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC)
        if fd < 0, errno == ENOENT { return Snapshot(entries: [], revision: nil) }
        guard fd >= 0 else { throw VocabularyStoreFailure.invalid }
        defer { _ = close(fd) }
        var state = stat()
        guard fstat(fd, &state) == 0, state.st_mode & S_IFMT == S_IFREG, state.st_uid == getuid(), state.st_mode & 0o077 == 0 else { throw VocabularyStoreFailure.invalid }
        let data = try VocabularyFiles.read(fd: fd, limit: PortableVocabulary.maxBytes + 65)
        guard data.count >= 65, data[0] == 1, let revision = UUID(uuidString: String(decoding: data[1..<37], as: UTF8.self)) else { throw VocabularyStoreFailure.invalid }
        do {
            let bytes = try AES.GCM.open(AES.GCM.SealedBox(combined: data.dropFirst(37)), using: keyProvider(false), authenticating: aad(revision))
            return Snapshot(entries: try codec.decode(bytes), revision: revision)
        } catch { throw VocabularyStoreFailure.invalid }
    }
    private func aad(_ revision: UUID) -> Data { Data("lip-portable-vocabulary-v1|\(revision.uuidString)".utf8) }
    func read() throws -> Snapshot { try locked(create: false, absent: { Snapshot(entries: [], revision: nil) }) { try readLocked($0) } }
    func decode(_ bytes: Data) throws -> [PortableVocabulary.Entry] { try codec.decode(bytes) }
    func preview(incoming: [PortableVocabulary.Entry]) throws -> Preview {
        let old = try read(), merged = try codec.merge(existing: old.entries, incoming: incoming)
        return Preview(incoming: incoming, existingCount: old.entries.count, mergedCount: merged.count, activeEnglishCount: merged.filter(Self.isActiveEnglish).count)
    }
    func exportData() throws -> Data { try codec.encode(read().entries) }
    func merge(incoming: [PortableVocabulary.Entry], lease: VocabularyLease) throws -> Snapshot {
        try lease.check()
        var changed = false
        let result: Snapshot = try locked(create: true, absent: { throw VocabularyStoreFailure.unavailable }) { directory in
            try lease.check()
            let old = try readLocked(directory)
            let merged = try codec.merge(existing: old.entries, incoming: incoming)
            let plain = try codec.encode(merged)
            if plain == (try codec.encode(old.entries)) { try lease.claim(); return old }
            let revision = UUID(), key = try keyProvider(old.revision == nil)
            let sealed = try AES.GCM.seal(plain, using: key, authenticating: aad(revision))
            guard let combined = sealed.combined else { throw VocabularyStoreFailure.unavailable }
            var data = Data([1]); data.append(Data(revision.uuidString.utf8)); data.append(combined)
            let stage = ".pending-" + UUID().uuidString
            let fd = openat(directory, stage, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
            guard fd >= 0 else { throw VocabularyStoreFailure.unavailable }
            defer { _ = close(fd); _ = unlinkat(directory, stage, 0) } // Only our unpredictable encrypted staging file.
            try VocabularyFiles.write(data, fd: fd)
            guard fsync(fd) == 0 else { throw VocabularyStoreFailure.unavailable }
            try lease.claim() // Deliberate authorization linearizes immediately before publication.
            guard renameat(directory, stage, directory, "vocabulary.enc") == 0, fsync(directory) == 0 else { throw VocabularyStoreFailure.unavailable }
            let confirmed = try readLocked(directory)
            guard confirmed.revision == revision else { throw VocabularyStoreFailure.invalid }
            changed = true; return confirmed
        }
        if changed { NotificationCenter.default.post(name: Self.didChange, object: self) }
        return result
    }
}

/// Descriptor-only bounded snapshots; no pathname re-open between validation and reading.
enum VocabularyFiles {
    static func read(fd: Int32, limit: Int = PortableVocabulary.maxBytes, lease: VocabularyLease? = nil) throws -> Data {
        var state = stat()
        guard fstat(fd, &state) == 0, state.st_mode & S_IFMT == S_IFREG, state.st_size >= 0, state.st_size <= limit else { throw VocabularyStoreFailure.invalid }
        var result = Data(), buffer = [UInt8](repeating: 0, count: 65536)
        while true {
            try lease?.check()
            let amount = Darwin.read(fd, &buffer, buffer.count)
            if amount < 0, errno == EINTR { continue }
            guard amount >= 0 else { throw VocabularyStoreFailure.unavailable }
            if amount == 0 { break }
            guard amount <= limit - result.count else { throw VocabularyStoreFailure.invalid }
            result.append(contentsOf: buffer.prefix(amount))
        }
        guard result.count == state.st_size else { throw VocabularyStoreFailure.invalid }
        return result
    }
    static func snapshot(_ source: URL, lease: VocabularyLease) throws -> Data {
        let access = source.startAccessingSecurityScopedResource(); defer { if access { source.stopAccessingSecurityScopedResource() } }
        let fd = open(source.path, O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC)
        guard fd >= 0 else { throw VocabularyStoreFailure.unavailable }; defer { _ = close(fd) }
        return try read(fd: fd, lease: lease)
    }
    static func write(_ data: Data, fd: Int32) throws {
        try data.withUnsafeBytes { bytes in
            var offset = 0
            while offset < bytes.count {
                let count = Darwin.write(fd, bytes.baseAddress!.advanced(by: offset), bytes.count - offset)
                if count < 0, errno == EINTR { continue }
                guard count > 0 else { throw VocabularyStoreFailure.unavailable }; offset += count
            }
        }
    }
    static func export(_ data: Data, to target: URL, lease: VocabularyLease) throws {
        guard data.count <= PortableVocabulary.maxBytes else { throw VocabularyStoreFailure.invalid }
        let access = target.startAccessingSecurityScopedResource(); defer { if access { target.stopAccessingSecurityScopedResource() } }
        try lease.claim()
        let fd = open(target.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw VocabularyStoreFailure.unavailable }; defer { _ = close(fd) }
        try write(data, fd: fd)
        guard fsync(fd) == 0 else { throw VocabularyStoreFailure.unavailable }
    }
}
