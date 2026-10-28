"""Foundation-only execution of actual portable/metadata/frame-budget Swift source.
No app, microphone, CoreML, models, permissions, installed history or dependency build.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "desktop/Sources/LipLocal"


class DesktopReviewFixes(unittest.TestCase):
    def test_actual_foundation_policy_and_portable_source(self):
        history = (SOURCE / "TranscriptHistory.swift").read_text()
        engine = (SOURCE / "BarkEngine.swift").read_text()
        definitions = history[history.index("struct HistoryRecord:"):history.index("enum HistoryKeychain")]
        policy = engine[engine.index("enum BarkAudioBudget"):engine.index("actor BarkEngine")]
        portable = (SOURCE / "HistoryPortable.swift").read_text().replace("import Foundation", "", 1)
        app = (SOURCE / "main.swift").read_text()
        recovery = app[app.index("    private func retainRecoveryRecording"):app.index("    func cancelHistoryCapture")]
        failure = engine[engine.index("struct Failure:"):engine.index("// Core ML chooses")]
        checks = r'''
func expect(_ condition: @autoclosure () throws -> Bool, _ name: String) rethrows {
    guard try condition() else { fatalError(name) }
}
func refuses(_ name: String, _ body: () throws -> Void) {
    do { try body(); fatalError("Expected refusal: " + name) } catch { }
}
let raw = " か\u{3099} 🙂\n你好 \u{00E9} e\u{301}  12.5% "
let output = "が 🙂\n你好 \u{00E9} e\u{301} 12.5%"
let timings = (0..<600).map { "\($0).00–\($0).20  語🙂か\u{3099} timing" }.joined(separator: "\n")
let legacy = HistoryRecord(id: UUID(), created: Date(timeIntervalSince1970: 1700000000.125),
    profile: "clean", original: raw, output: output, modelDetails: timings)
expect(timings.utf8.count > 4096, "Positive oversized legacy fixture")
let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
let archive = try encoder.encode(legacy)
let exported = try HistoryPortableCodec.decode(HistoryPortableCodec.export(legacy)).record
expect(exported.original.utf8.elementsEqual(raw.utf8), "Exact raw Unicode/whitespace")
expect(exported.output.utf8.elementsEqual(output.utf8), "Exact output Unicode/whitespace")
expect(exported.metadata.modelDetails!.utf8.count <= 4096, "Legacy metadata bounded")
expect(exported.metadata.modelDetails!.contains("original local history metadata is unchanged"), "Explicit portability summary")
expect(try encoder.encode(legacy) == archive, "Native legacy payload remains byte-exact/recoverable")
expect(try JSONDecoder().decode(HistoryRecord.self, from: archive).modelDetails == timings, "Full legacy timing recovery")
expect(try HistoryPortableCodec.classify(exported, local: [legacy]) == .identical, "Matching native summary stable")
let unrelated = HistoryPortableRecord(metadata: HistoryPortableMetadata(id: "unrelated-public-fixture", createdAtUnixMs: 1,
    language: nil, model: nil, modelDetails: nil, legacyCloudCleanup: false), original: raw, output: output)
expect(try HistoryPortableCodec.classify(unrelated, local: [legacy]) == .new, "Unrelated import through long archive")
let unrelatedBadPortableProfile = HistoryRecord(id: UUID(), created: legacy.created,
    profile: String(repeating: "🙂", count: 1200), original: raw, output: output, modelDetails: timings)
expect(try HistoryPortableCodec.classify(unrelated, local: [unrelatedBadPortableProfile]) == .new,
    "Unrelated native profile must not be portable-converted")
expect(try HistoryPortableCodec.classify(exported, local: [legacy, legacy]) == .ambiguous, "Duplicate matching IDs still ambiguous")
let altered = HistoryPortableRecord(metadata: exported.metadata, original: raw + "x", output: output)
expect(try HistoryPortableCodec.classify(altered, local: [legacy]) == .conflict, "Matching changed text remains conflict")
let imported = try HistoryPortableCodec.local(unrelated)
expect(try HistoryPortableCodec.portable(imported) == unrelated, "Imported metadata not summarized or rewritten")
let invalid = HistoryPortableRecord(metadata: HistoryPortableMetadata(id: "invalid", createdAtUnixMs: 1,
    language: nil, model: nil, modelDetails: String(repeating: "x", count: 4097), legacyCloudCleanup: false), original: raw, output: output)
refuses("Portable 4 KiB validator retained") { try HistoryPortableCodec.validate(invalid) }
let huge = String(repeating: "🙂か\u{3099}", count: 3000)
let bounded = HistoryModelDetails.portable(huge)
expect(bounded.utf8.count <= 4096 && !bounded.contains("�"), "UTF8-safe metadata boundary")
expect(HistoryModelDetails.portable("short exact\n") == "short exact\n", "Short metadata exact")
let words = (0..<1000).map { BarkWord(word: "word🙂", start: Double($0)/10, end: Double($0+1)/10) }
let native = BarkTranscript(text: raw, model: "clean", encoderFunction: "default", accelerator: "synthetic policy fixture",
    audioSeconds: 300, sourceAudioSeconds: 300.01, processingSeconds: 1, meanTokenProbability: nil, words: words)
expect(native.details.utf8.count <= 4096 && native.details.contains("Further timing rows omitted"), "New native diagnostics bounded/explicit")
expect(native.words.count == 1000 && native.text.utf8.elementsEqual(raw.utf8), "Native word objects/transcript untouched")
expect(native.details.contains("only the first"), "Decode cap disclosed")
let maxFrames = BarkAudioBudget.frames
expect(BarkAudioBudget.decodeFrames(0) == nil && BarkAudioBudget.decodeFrames(-1) == nil, "Empty/negative reject")
expect(BarkAudioBudget.decodeFrames(maxFrames) == maxFrames, "Exact five-minute accepted")
expect(BarkAudioBudget.decodeFrames(maxFrames+1) == nil, "Default/plain limit rejects even one-frame overrun")
expect(BarkAudioBudget.decodeFrames(maxFrames+1, allowRecorderPacketOverrun: true) == maxFrames, "Interactive one-frame overrun accepted/bounded")
expect(BarkAudioBudget.decodeFrames(maxFrames+BarkAudioBudget.toleratedOverrun, allowRecorderPacketOverrun: true) == maxFrames, "Bounded packet overrun accepted")
expect(BarkAudioBudget.decodeFrames(maxFrames+BarkAudioBudget.toleratedOverrun+1) == nil && BarkAudioBudget.decodeFrames(Int64.max) == nil, "Arbitrary overrun rejects without allocation")
for transcribed in [false, true] { for limited in [false, true] {
    expect(!BarkAudioBudget.retainSource(transcribed: transcribed, limited: limited, canceled: true), "Explicit cancellation never retains audio")
} }
expect(BarkAudioBudget.retainSource(transcribed: false, limited: false, canceled: false), "Failure recoverable")
expect(BarkAudioBudget.retainSource(transcribed: true, limited: true, canceled: false), "Unprocessed tail recoverable")
expect(!BarkAudioBudget.retainSource(transcribed: true, limited: false, canceled: false), "Normal success removes source")
// Actual recovery helper, only with a caller-owned synthetic temp root.
let owned = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true).resolvingSymlinksInPath()
let recovery = owned.appendingPathComponent("RecoveryAudio", isDirectory: true)
let source = owned.appendingPathComponent("lip-public-fixture.wav")
let payload = Data("PUBLIC_SYNTHETIC_RECOVERY_BYTES".utf8)
try payload.write(to: source)
let retained = try retainRecoveryRecording(source, root: recovery)
expect(!FileManager.default.fileExists(atPath: source.path) && (try Data(contentsOf: retained)) == payload, "Recovery moves exact synthetic bytes")
expect((try FileManager.default.attributesOfItem(atPath: retained.path)[.posixPermissions] as? NSNumber)?.intValue == 0o600, "Recovery file0600")
expect((try FileManager.default.attributesOfItem(atPath: recovery.path)[.posixPermissions] as? NSNumber)?.intValue == 0o700, "Recovery directory0700")
expect(try recovery.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true, "Recovery excluded from backup")
try Data("SECOND_PUBLIC_SOURCE".utf8).write(to: source)
refuses("No-clobber recovery path") { _ = try retainRecoveryRecording(source, root: recovery) }
expect(try Data(contentsOf: retained) == payload, "Existing recovery retained")
expect(try Data(contentsOf: source) == Data("SECOND_PUBLIC_SOURCE".utf8), "Conflicting new source retained")
let alias = owned.appendingPathComponent("RecoveryAlias", isDirectory: true)
try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: recovery)
refuses("Recovery refuses directory alias") { _ = try retainRecoveryRecording(source, root: alias) }
print("PASS: actual Foundation portable/metadata/audio-budget/recovery policies; no microphone/native inference")
'''
        checks = checks.replace("expect(try ", "try expect(try ").replace("expect(!FileManager.default.fileExists", "try expect(!FileManager.default.fileExists").replace("expect((try ", "try expect((try ")
        with tempfile.TemporaryDirectory(prefix="lip-desktop-review-controls-") as directory:
            source = Path(directory) / "main.swift"
            source.write_text("import Foundation\n" + failure + definitions + policy + portable + recovery + checks)
            binary = Path(directory) / "controls"
            result = subprocess.run(["swiftc", str(source), "-o", str(binary)], capture_output=True, text=True, timeout=45)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            executed = subprocess.run([str(binary), directory], capture_output=True, text=True, timeout=10)
            self.assertEqual(executed.returncode, 0, executed.stdout + executed.stderr)
            self.assertIn("PASS:", executed.stdout)

    def test_actual_plain_wrapper_keeps_strict_default(self):
        engine = (SOURCE / "BarkEngine.swift").read_text()
        failure = engine[engine.index("struct Failure:"):engine.index("// Core ML chooses")]
        budget = engine[engine.index("enum BarkAudioBudget"):engine.index("struct BarkWord")]
        wrapper = engine[engine.index("    func transcribe(_ url:"):engine.index("    func transcribeDetailed(")]
        # Execute the ACTUAL wrapper + ACTUAL Detailed admission prefix, replacing
        # only AV/CoreML I/O with deterministic metadata doubles. No audio/model.
        prefix = engine[engine.index("    func transcribeDetailed("):engine.index("        try file.read(into: buffer")]
        swift = r'''
import Foundation
struct Format { let sampleRate: Double = 16000; let channelCount = 1 }
typealias AVAudioFrameCount = UInt32
struct AVAudioFile {
    let length: Int64
    let processingFormat = Format()
    init(forReading url: URL) throws { length = Int64(url.lastPathComponent)! }
}
struct AVAudioPCMBuffer { init?(pcmFormat: Format, frameCapacity: AVAudioFrameCount) { if frameCapacity == 0 { return nil } } }
struct BarkTranscript { let text: String }
''' + failure + budget + "\nstruct ActualWrapperProbe { func prepare() throws {}\n" + wrapper + prefix + r'''
        _ = buffer
        return BarkTranscript(text: String(frames))
    }
}
@main struct Controls {
    static func main() async throws {
        let probe = ActualWrapperProbe()
        let max = BarkAudioBudget.frames
        let exact = URL(fileURLWithPath: "/" + String(max))
        let excess = URL(fileURLWithPath: "/" + String(max + 1))
        let text = try await probe.transcribe(exact)
        precondition(text == String(max))
        var plainRejected = false
        do { _ = try await probe.transcribe(excess) } catch { plainRejected = true }
        precondition(plainRejected, "Actual plain wrapper must not silently truncate")
        var detailedRejected = false
        do { _ = try await probe.transcribeDetailed(excess) } catch { detailedRejected = true }
        precondition(detailedRejected, "Actual Detailed default must remain strict")
        let recorderOnly = try await probe.transcribeDetailed(excess, allowRecorderPacketOverrun: true)
        precondition(recorderOnly.text == String(max))
        print("PASS: actual plain wrapper/admission prefix; deterministic AV metadata doubles only")
    }
}
'''
        with tempfile.TemporaryDirectory(prefix="lip-plain-wrapper-control-") as directory:
            source = Path(directory) / "probe.swift"
            source.write_text(swift)
            binary = Path(directory) / "control"
            compiled = subprocess.run(["swiftc", "-parse-as-library", str(source), "-o", str(binary)], capture_output=True, text=True, timeout=45)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            executed = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(executed.returncode, 0, executed.stdout + executed.stderr)
            self.assertIn("PASS:", executed.stdout)

    def test_actual_callsite_budget_recovery_and_authentication_order(self):
        main = (SOURCE / "main.swift").read_text()
        engine = (SOURCE / "BarkEngine.swift").read_text()
        history = (SOURCE / "TranscriptHistory.swift").read_text()
        self.assertIn("value.record(forDuration: BarkAudioBudget.seconds)", main)
        self.assertLess(main.index("value.prepareToRecord()"), main.index("value.record(forDuration:"))
        self.assertLess(main.index(".posixPermissions: 0o600", main.index("value.prepareToRecord()")), main.index("value.record(forDuration:"))
        self.assertIn("canceled: generation != operation", main)
        self.assertIn(".posixPermissions: 0o700", main)
        self.assertIn("flags.isExcludedFromBackup = true", main)
        self.assertIn("func transcribe(_ url: URL) async throws -> String { try await transcribeDetailed(url).text }", engine)
        self.assertIn("func transcribeDetailed(_ url: URL, allowRecorderPacketOverrun: Bool = false)", engine)
        self.assertIn("BarkAudioBudget.decodeFrames(file.length, allowRecorderPacketOverrun: allowRecorderPacketOverrun)", engine)
        self.assertEqual(main.count("transcribeDetailed(url, allowRecorderPacketOverrun: true)"), 1)
        self.assertIn("frameCapacity: AVAudioFrameCount(frames)", engine)
        self.assertIn("file.read(into: buffer, frameCount: AVAudioFrameCount(frames))", engine)
        classify = history[history.index("private func importClassificationLocked"):history.index("func previewImport")]
        self.assertLess(classify.index("try decode($0, key: value)"), classify.index("classify(record, local: all)"))
        self.assertNotIn("portable(decode", classify)


if __name__ == "__main__":
    unittest.main()
