import Foundation
import CryptoKit

enum BarkProfile: String, CaseIterable, Codable {
    case clean, pro
    var title: String { self == .clean ? "Bark 3.0 Clean · standard" : "Bark 3.0 Pro · optional" }
    var root: URL {
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/LipLocal/\(self == .clean ? "BarkClean" : "BarkPro")/coreml")
    }
    var paddingSamples: Int { self == .clean ? 0 : 4000 }
    var vocabularyFile: String { self == .clean ? "parakeet_vocab.json" : "parakeet_v3_vocab.json" }
    var downloadLabel: String { self == .clean ? "448 MB Core ML export" : "1.23 GB Core ML export" }
    static func selected(_ defaults: UserDefaults = .standard) -> BarkProfile {
        defaults.string(forKey: "bark.model").flatMap(Self.init(rawValue:)) ?? .clean
    }
}

struct BarkManifest: Decodable {
    struct Entry: Decodable {
        struct LFS: Decodable { let sha256: String }
        let rfilename: String
        let localName: String
        let size: Int64
        let lfs: LFS?
        let blobId: String
    }
    let repo: String
    let revision: String
    let files: [Entry]
    static func load(_ profile: BarkProfile) throws -> Self {
        guard let url = Bundle.module.url(forResource: profile.rawValue, withExtension: "json", subdirectory: "Models") else { throw Failure("Missing pinned model manifest") }
        return try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
    }
}

// Only anonymous model downloads use the network. Recorded audio and text never enter this code.
enum BarkInstaller {
    static func valid(_ url: URL, entry: BarkManifest.Entry) throws -> Bool {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
            (attributes[.size] as? NSNumber)?.int64Value == entry.size else { return false }
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        var sha256 = SHA256(); var sha1 = Insecure.SHA1()
        if entry.lfs == nil { sha1.update(data: Data("blob \(entry.size)\0".utf8)) }
        while let data = try handle.read(upToCount: 1_048_576), !data.isEmpty {
            try Task.checkCancellation()
            if entry.lfs == nil { sha1.update(data: data) } else { sha256.update(data: data) }
        }
        let digest = entry.lfs == nil ? sha1.finalize().map { String(format: "%02x", $0) }.joined() : sha256.finalize().map { String(format: "%02x", $0) }.joined()
        return digest == (entry.lfs?.sha256 ?? entry.blobId)
    }
    static func install(_ profile: BarkProfile, progress: @escaping @MainActor @Sendable (String) -> Void) async throws {
        let manifest = try BarkManifest.load(profile)
        let root = profile.root
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        // A full extra model plus reserve is required; existing valid files are reused, never deleted.
        let missing = try manifest.files.filter { try !valid(root.appendingPathComponent($0.localName), entry: $0) }
        let required = missing.reduce(Int64(64 * 1024 * 1024)) { $0 + $1.size }
        let space = try FileManager.default.attributesOfFileSystem(forPath: root.path)[.systemFreeSize] as? NSNumber
        guard let space, space.int64Value >= required else { throw Failure("Not enough free space for selected model") }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 120; configuration.timeoutIntervalForResource = 3600
        let session = URLSession(configuration: configuration); defer { session.invalidateAndCancel() }
        for (index, entry) in manifest.files.enumerated() {
            try Task.checkCancellation()
            // Bundled manifest only: forbid relative traversal even if a resource is malformed.
            guard !entry.localName.hasPrefix("/"), !entry.localName.split(separator: "/").contains("..") else { throw Failure("Invalid model path") }
            let destination = root.appendingPathComponent(entry.localName)
            if try valid(destination, entry: entry) { continue }
            await progress("Downloading \(profile.title) · file \(index + 1)/\(manifest.files.count)")
            let url = URL(string: "https://huggingface.co/\(manifest.repo)/resolve/\(manifest.revision)/\(entry.rfilename)")!
            let (temporary, response) = try await session.download(from: url)
            defer { try? FileManager.default.removeItem(at: temporary) }
            guard (response as? HTTPURLResponse)?.statusCode == 200, try valid(temporary, entry: entry) else { throw Failure("Model size/checksum verification failed") }
            try Task.checkCancellation()
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: destination.path) {
                // Keep a recoverable copy of an invalid pre-existing artifact before replacing it.
                let backup = destination.appendingPathExtension("backup-\(UUID().uuidString)")
                try FileManager.default.moveItem(at: destination, to: backup)
            }
            try FileManager.default.moveItem(at: temporary, to: destination)
        }
        try Data(manifest.revision.utf8).write(to: root.appendingPathComponent("revision.txt"), options: .atomic)
        await progress("\(profile.title) verified · ready offline")
    }
}

func testModelCatalog() {
    let suite = "dev.lip.catalog-tests.\(UUID().uuidString)"; let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    precondition(BarkProfile.selected(defaults) == .clean)
    defaults.set("pro", forKey: "bark.model"); precondition(BarkProfile.selected(defaults) == .pro)
    defaults.set("unknown", forKey: "bark.model"); precondition(BarkProfile.selected(defaults) == .clean)
    precondition(BarkProfile.clean.paddingSamples == 0 && BarkProfile.pro.paddingSamples == 4000)
    precondition(BarkProfile.clean.root != BarkProfile.pro.root)
    for profile in BarkProfile.allCases {
        let manifest = try! BarkManifest.load(profile)
        precondition(manifest.revision.count == 40 && !manifest.files.isEmpty)
        precondition(Set(manifest.files.map(\.localName)).count == manifest.files.count)
    }
    let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("lip-integrity-test-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: temporary) }
    let payload = Data([1, 2, 3, 4])
    let sha = SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()
    let entry = BarkManifest.Entry(rfilename: "fixture", localName: "fixture", size: 4, lfs: .init(sha256: sha), blobId: "unused")
    try! payload.write(to: temporary)
    precondition(try! BarkInstaller.valid(temporary, entry: entry))
    try! Data([4, 3, 2, 1]).write(to: temporary)
    precondition(try! !BarkInstaller.valid(temporary, entry: entry))
    try! Data([1]).write(to: temporary)
    precondition(try! !BarkInstaller.valid(temporary, entry: entry))
    let gitHash = Insecure.SHA1.hash(data: Data("blob 4\0".utf8) + payload).map { String(format: "%02x", $0) }.joined()
    let gitEntry = BarkManifest.Entry(rfilename: "fixture", localName: "fixture", size: 4, lfs: nil, blobId: gitHash)
    try! payload.write(to: temporary)
    precondition(try! BarkInstaller.valid(temporary, entry: gitEntry))
    print("Model catalog/integrity tests passed (13 assertions).")
}
