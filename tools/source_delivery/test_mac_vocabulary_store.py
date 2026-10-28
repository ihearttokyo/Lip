"""Actual macOS AES-GCM store + file transfer; synthetic temp files/memory key only.
No GUI, installed app, production Keychain, model, microphone or external provider.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "desktop/Sources/LipLocal"

CHECKS = r'''
import Foundation
import CryptoKit
import Darwin
func expect(_ value: @autoclosure () throws -> Bool, _ name: String) { do { if try !value() { fatalError(name) } } catch { fatalError(name + ": unexpected failure") } }
func refuses(_ name: String, _ body: () throws -> Void) { do { try body(); fatalError("Expected refusal: " + name) } catch {} }
let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let codec = PortableVocabulary(unicode: try PinnedUnicode16(data: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))))
let key = SymmetricKey(data: Data(repeating: 0x6b, count: 32))
var calls: [Bool] = []
let store = VocabularyStore(root: root.appendingPathComponent("store"), codec: codec, keyProvider: { create in calls.append(create); return key })
expect(try store.read().entries.isEmpty, "Absent store empty")
expect(calls.isEmpty, "Absent ciphertext never touches Keychain provider")
expect(!FileManager.default.fileExists(atPath: store.root.path), "Absent read creates no directory")
let incoming = [PortableVocabulary.Entry(term: "OpenAI", weight: 4, source: "import"), .init(term: "こんにちは", language: "ja"), .init(term: "two words"), .init(term: "O'Neil")]
let preview = try store.preview(incoming: incoming)
expect(preview.mergedCount == 4 && preview.activeEnglishCount == 2, "Preview counts actual activation")
expect(calls.isEmpty, "Empty preview no key")
var notifications = 0
let observer = NotificationCenter.default.addObserver(forName: VocabularyStore.didChange, object: nil, queue: nil) { _ in notifications += 1 }
defer { NotificationCenter.default.removeObserver(observer) }
let first = try store.merge(incoming: incoming, lease: VocabularyLease())
expect(first.entries.count == 4 && first.revision != nil, "Confirmed encrypted merge")
expect(calls.first == true && calls.dropFirst().allSatisfy { !$0 }, "Only new store may create key")
let encrypted = store.root.appendingPathComponent("vocabulary.enc")
let original = try Data(contentsOf: encrypted)

expect(original.range(of: Data("OpenAI".utf8)) == nil, "No plaintext term in envelope")
expect(notifications == 1, "Confirmed change posts exactly once")
let repeated = try store.merge(incoming: incoming, lease: VocabularyLease())
let repeatedBytes = try Data(contentsOf: encrypted)
expect(repeated.revision == first.revision && repeatedBytes == original && notifications == 1, "Idempotent merge no rewrite/revision")
var info = stat(); expect(lstat(encrypted.path, &info) == 0 && info.st_mode & 0o777 == 0o600, "Ciphertext private permissions")
expect(lstat(store.root.path, &info) == 0 && info.st_mode & 0o777 == 0o700, "Directory private permissions")
expect(try store.root.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true, "Private store excluded from backup")
let canceled = VocabularyLease(); canceled.cancel()
refuses("Cancellation before merge") { _ = try store.merge(incoming: [.init(term: "Canceled")], lease: canceled) }
expect(try Data(contentsOf: encrypted) == original, "Canceled merge unchanged")
let cancelAtKey = VocabularyLease()
let cancelStore = VocabularyStore(root: root.appendingPathComponent("cancel-at-key"), codec: codec, keyProvider: { _ in cancelAtKey.cancel(); return key })
refuses("Cancellation while preparing stage") { _ = try cancelStore.merge(incoming: [.init(term: "Canceled")], lease: cancelAtKey) }
expect(try cancelStore.read().entries.isEmpty, "No canceled ciphertext publication")
let consumed = VocabularyLease(); _ = try store.merge(incoming: incoming, lease: consumed)
refuses("Lease one-use") { _ = try store.merge(incoming: [.init(term: "Reused")], lease: consumed) }
let secondStore = VocabularyStore(root: store.root, codec: codec, keyProvider: { _ in key })
_ = try secondStore.merge(incoming: [.init(term: "Later")], lease: VocabularyLease())
let latest = try store.merge(incoming: [.init(term: "openai", weight: 8), .init(term: "Newest")], lease: VocabularyLease())
expect(latest.entries.count == 6 && latest.entries.contains { $0.term == "Later" }, "Latest-state merge preserves another writer")
expect(latest.entries.contains { $0.term == "OpenAI" && $0.weight == 8 && $0.source == "import" }, "Existing approved spelling/source/maxweight")
let exported = try store.exportData(), destination = root.appendingPathComponent("vocabulary.json")
try VocabularyFiles.export(exported, to: destination, lease: VocabularyLease())
expect(try codec.decode(Data(contentsOf: destination)) == latest.entries, "Portable export exact shared entries")
refuses("Exclusive export existing target") { try VocabularyFiles.export(Data("changed".utf8), to: destination, lease: VocabularyLease()) }
expect(try Data(contentsOf: destination) == exported, "Existing export preserved")
let canceledExport = VocabularyLease(); canceledExport.cancel()
let absentExport = root.appendingPathComponent("canceled.json")
refuses("Canceled export before admission") { try VocabularyFiles.export(exported, to: absentExport, lease: canceledExport) }
expect(!FileManager.default.fileExists(atPath: absentExport.path), "Canceled export absent")
expect(try VocabularyFiles.snapshot(destination, lease: VocabularyLease()) == exported, "Actual bounded regular snapshot")
let link = root.appendingPathComponent("link.json"); expect(symlink(destination.path, link.path) == 0, "Synthetic symlink created")
refuses("Symlink import") { _ = try VocabularyFiles.snapshot(link, lease: VocabularyLease()) }
let pipe = root.appendingPathComponent("pipe.json"); expect(mkfifo(pipe.path, 0o600) == 0, "Synthetic no-writer FIFO")
refuses("Nonblocking FIFO refusal") { _ = try VocabularyFiles.snapshot(pipe, lease: VocabularyLease()) }
let oversized = root.appendingPathComponent("large.json")
let largeFD = open(oversized.path, O_CREAT | O_EXCL | O_WRONLY, 0o600)
expect(largeFD >= 0 && ftruncate(largeFD, off_t(PortableVocabulary.maxBytes + 1)) == 0, "Synthetic oversized regular file"); _ = close(largeFD)
refuses("16MiB bounded import") { _ = try VocabularyFiles.snapshot(oversized, lease: VocabularyLease()) }
let corrupt = Data([1,2,3]); try corrupt.write(to: encrypted)
refuses("Corrupt store read") { _ = try store.read() }
refuses("Corrupt store merge") { _ = try store.merge(incoming: incoming, lease: VocabularyLease()) }
refuses("Corrupt store export") { _ = try store.exportData() }
expect(try Data(contentsOf: encrypted) == corrupt, "Corrupt original preserved byte-exact")
try original.write(to: encrypted)
var attemptedCreate = false
let lostKey = VocabularyStore(root: store.root, codec: codec, keyProvider: { create in attemptedCreate = create; throw VocabularyStoreFailure.keyUnavailable })
refuses("Lost key never replaced") { _ = try lostKey.merge(incoming: incoming, lease: VocabularyLease()) }
let preservedBytes = try Data(contentsOf: encrypted)
expect(!attemptedCreate && preservedBytes == original, "Lost key ciphertext preserved")
expect(VocabularyStore.isActiveEnglish(.init(term: "O'Neil")) && !VocabularyStore.isActiveEnglish(.init(term: "a''b")) && !VocabularyStore.isActiveEnglish(.init(term: "trailing'")) && !VocabularyStore.isActiveEnglish(.init(term: "café")), "Real current consumer eligibility not speech bias")
print("PASS actual encrypted store/regular-file controls; synthetic temp and memory key only")

'''

class MacVocabularyStoreTests(unittest.TestCase):
    def test_actual_store_and_regular_file_controls(self):
        with tempfile.TemporaryDirectory(prefix="lip-mac-vocab-") as temporary:
            temp = Path(temporary).resolve()
            source = temp / "main.swift"
            source.write_text(CHECKS)
            executable = temp / "checks"
            compiled = subprocess.run(["xcrun", "swiftc", "-swift-version", "5", str(SOURCE / "PinnedUnicode16.swift"), str(SOURCE / "PortableVocabulary.swift"), str(SOURCE / "VocabularyStore.swift"), str(source), "-o", str(executable)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            run = subprocess.run([str(executable), str(temp), str(SOURCE / "VocabularyData/unicode16.json")], capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_actual_ui_typechecks_without_opening_windows(self):
        subprocess.run(["xcrun", "swiftc", "-swift-version", "5", "-typecheck", str(SOURCE / "PinnedUnicode16.swift"), str(SOURCE / "PortableVocabulary.swift"), str(SOURCE / "VocabularyStore.swift"), str(SOURCE / "VocabularyWindow.swift")], check=True, capture_output=True, text=True, timeout=30)


if __name__ == "__main__":
    unittest.main()
