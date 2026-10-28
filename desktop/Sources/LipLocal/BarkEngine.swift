// Adapted from Conway Research's pinned Bark Pro Core ML runner. See THIRD_PARTY.md.
import Foundation
import CoreML
import AVFoundation
import FluidAudio

struct Failure: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

// Core ML chooses supported devices per operation; never force a user's saved device preference.
enum BarkComputePolicy {
    static let units: MLComputeUnits = .all
    static let description = "automatic Core ML placement (Neural Engine/GPU/CPU allowed)"
}

// AVAudioRecorder enforces duration independently of main-run-loop timer delay.
// A bounded hardware-packet overrun remains decodable; never allocate an arbitrary
// file's frame count or silently claim that the unprocessed tail was transcribed.
enum BarkAudioBudget {
    static let seconds: Double = 300
    static let frames: Int64 = 16_000 * 300
    static let toleratedOverrun: Int64 = 16_000 * 2
    static func decodeFrames(_ recorded: Int64, allowRecorderPacketOverrun: Bool = false) -> Int64? {
        let limit = frames + (allowRecorderPacketOverrun ? toleratedOverrun : 0)
        guard recorded > 0, recorded <= limit else { return nil }
        return min(recorded, frames)
    }
    static func retainSource(transcribed: Bool, limited: Bool, canceled: Bool) -> Bool {
        !canceled && (!transcribed || limited)
    }
}

struct BarkWord: Codable, Sendable {
    let word: String
    let start: Double
    let end: Double
}
struct BarkTranscript: Codable, Sendable {
    let text: String
    let model: String
    let encoderFunction: String
    let accelerator: String
    let audioSeconds: Double
    let sourceAudioSeconds: Double?
    let processingSeconds: Double
    let meanTokenProbability: Float?
    let words: [BarkWord]
    var details: String {
        let probability = meanTokenProbability.map { String(format: "%.3f (uncalibrated token score, NOT accuracy)", $0) } ?? "unavailable"
        var rows = ["Bark 3.0 \(model) · native output preserved", "Encoder: \(encoderFunction) · \(accelerator)",
            String(format: "Audio: %.2f s · inference: %.2f s", audioSeconds, processingSeconds),
            "Mean token probability: \(probability)"]
        if let sourceAudioSeconds, sourceAudioSeconds > audioSeconds {
            rows.append(String(format: "Input: %.2f s; only the first %.2f s were decoded. Original audio retained for recovery.", sourceAudioSeconds, audioSeconds))
        }
        rows.append("Approximate word timings (padding offset removed; showing up to \(min(words.count, 48))/\(words.count) words):")
        rows += words.prefix(48).map { String(format: "%.2f–%.2f  %@", $0.start, $0.end, $0.word) }
        if words.count > 48 { rows.append("[Further timing rows omitted from history metadata; native transcript and word objects are unchanged.]") }
        return HistoryModelDetails.bounded(rows.joined(separator: "\n"),
            notice: "Additional timing metadata omitted; native transcript and word objects are unchanged.")
    }
}

actor BarkEngine {
    private var managers: [String: AsrManager] = [:]
    private var vocabulary: [Int: String]?
    private var preprocessor: MLModel?
    private var decoder: MLModel?
    private var joint: MLModel?
    let root: URL
    let profile: BarkProfile
    init(root: URL, profile: BarkProfile = .pro) { self.root = root; self.profile = profile }

    static func encoderFunction(sampleCountIncludingPadding: Int) -> String {
        sampleCountIncludingPadding <= 128_000 ? "short8" : "full15"
    }
    private func load(_ name: String, _ units: MLComputeUnits, function: String? = nil) throws -> MLModel {
        let cached = root.appendingPathComponent(name + ".mlmodelc", isDirectory: true)
        if !FileManager.default.fileExists(atPath: cached.path) {
            let source = root.appendingPathComponent(name + ".mlpackage")
            guard FileManager.default.fileExists(atPath: source.path) else { throw Failure("Missing Bark Pro model: \(name). Run the model download script.") }
            let temporary = try MLModel.compileModel(at: source)
            defer { try? FileManager.default.removeItem(at: temporary) }
            try FileManager.default.copyItem(at: temporary, to: cached)
        }
        let configuration = MLModelConfiguration()
        configuration.computeUnits = units; configuration.functionName = function
        return try MLModel(contentsOf: cached, configuration: configuration)
    }
    func prepare() throws {
        if vocabulary == nil {
            let raw = try JSONDecoder().decode([String: String].self,
                from: Data(contentsOf: root.appendingPathComponent(profile.vocabularyFile)))
            let words = Dictionary(uniqueKeysWithValues: raw.compactMap { k, v in Int(k).map { ($0, v) } })
            let front = try load("Preprocessor", BarkComputePolicy.units)
            let prediction = try load("Decoder", BarkComputePolicy.units)
            let decision = try load("JointDecisionv3", BarkComputePolicy.units)
            vocabulary = words; preprocessor = front; decoder = prediction; joint = decision
        }
        _ = try manager(function: profile == .clean ? "default" : "short8")
    }
    private func manager(function: String) throws -> AsrManager {
        if let cached = managers[function] { return cached }
        guard let preprocessor, let decoder, let joint, let vocabulary else { throw Failure("Bark model components unavailable") }
        let configuration = MLModelConfiguration(); configuration.computeUnits = BarkComputePolicy.units
        let models = try AsrModels(encoder: load("Encoder", BarkComputePolicy.units, function: function == "default" ? nil : function),
            preprocessor: preprocessor, decoder: decoder, joint: joint, configuration: configuration,
            vocabulary: vocabulary, version: .ultra)
        let manager = AsrManager(models: models)
        managers[function] = manager
        return manager
    }
    func transcribe(_ url: URL) async throws -> String { try await transcribeDetailed(url).text }
    func transcribeDetailed(_ url: URL, allowRecorderPacketOverrun: Bool = false) async throws -> BarkTranscript {
        try prepare()
        let file = try AVAudioFile(forReading: url)
        guard file.processingFormat.sampleRate == 16000, file.processingFormat.channelCount == 1,
            let frames = BarkAudioBudget.decodeFrames(file.length, allowRecorderPacketOverrun: allowRecorderPacketOverrun),
            let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(frames)) else {
            throw Failure("Expected nonempty mono 16 kHz audio, up to 5 minutes.")
        }
        try file.read(into: buffer, frameCount: AVAudioFrameCount(frames))
        guard buffer.frameLength == AVAudioFrameCount(frames) else { throw Failure("Recorded audio ended before its declared frame count.") }
        guard let pointer = buffer.floatChannelData?[0] else { throw Failure("Cannot read audio.") }
        let duration = Double(buffer.frameLength) / 16000
        let samples = Array(repeating: Float(0), count: profile.paddingSamples) + Array(UnsafeBufferPointer(start: pointer, count: Int(buffer.frameLength)))
        let function = profile == .clean ? "default" : Self.encoderFunction(sampleCountIncludingPadding: samples.count)
        let manager = try manager(function: function)
        var state = try TdtDecoderState()
        let started = ProcessInfo.processInfo.systemUptime
        let result = try await manager.transcribe(samples, decoderState: &state)
        // Preserve the model's own punctuation, case and numeral choices exactly.
        let words = buildWordTimings(from: result.tokenTimings ?? []).compactMap { word -> BarkWord? in
            guard word.startTime.isFinite && word.endTime.isFinite else { return nil }
            let start = min(duration, max(0, word.startTime - Double(profile.paddingSamples) / 16000))
            let end = min(duration, max(start, word.endTime - Double(profile.paddingSamples) / 16000))
            return BarkWord(word: word.word, start: start, end: end)
        }
        let probability = result.confidence.isFinite && (0...1).contains(result.confidence) ? result.confidence : nil
        return BarkTranscript(text: result.text, model: profile.rawValue, encoderFunction: function, accelerator: BarkComputePolicy.description,
            audioSeconds: duration, sourceAudioSeconds: Double(file.length) / 16000, processingSeconds: ProcessInfo.processInfo.systemUptime - started,
            meanTokenProbability: probability, words: words)
    }
}

func testNativeBarkPolicy() {
    precondition(BarkEngine.encoderFunction(sampleCountIncludingPadding: 128_000) == "short8")
    precondition(BarkEngine.encoderFunction(sampleCountIncludingPadding: 128_001) == "full15")
    let native = "Um, Don't change 12.5%, iPhone or punctuation!"
    let options = CleanupOptions(mode: .verbatim)
    precondition(LocalCleanup.apply(native, options: options).text == native)
    let suite = "dev.lip.native-tests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let settings = CleanupSettings(defaults: defaults)
    // Legacy manual choices must not constrain automatic placement after upgrade.
    defaults.set("cpu", forKey: "bark.accelerator")
    precondition(BarkComputePolicy.units == .all)
    precondition(settings.options.mode == .verbatim)
    print("Native Bark policy tests passed (5 assertions).")
}
