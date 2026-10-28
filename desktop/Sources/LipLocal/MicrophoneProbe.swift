import Foundation
import AVFoundation
import ApplicationServices

// Shared by interactive recording and the explicit, opt-in installed-app probe.
func makeLocalRecording(at url: URL) throws -> AVAudioRecorder {
    let recorder = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatLinearPCM,
        AVSampleRateKey: 16000, AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16,
        AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false])
    guard recorder.record() else { throw Failure("Microphone could not start") }
    do { try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path) }
    catch { recorder.stop(); throw error }
    return recorder
}

@MainActor func runMicrophoneProbe() async -> Int32 {
    let root = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/LipLocal")
    let report = root.appendingPathComponent("microphone-probe.json")
    var result: [String: Any] = ["complete": false,
        "microphoneAllowed": AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
        "inputMonitoringAllowed": CGPreflightListenEventAccess(),
        "physicalFnTested": false, "audioOrTranscriptRetained": false]
    var exitCode: Int32 = 1
    do {
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else {
            throw Failure("Microphone permission is not authorized; no prompt or recording attempted")
        }
        let engine = BarkEngine(root: modelRoot)
        try await engine.prepare()
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("lip-probe-\(UUID().uuidString).wav")
        defer { try? FileManager.default.removeItem(at: url) }
        let recorder = try makeLocalRecording(at: url)
        defer { recorder.stop() }
        try await Task.sleep(for: .seconds(3))
        recorder.stop()
        let audio = try AVAudioFile(forReading: url)
        guard audio.length > 0, let pcm = AVAudioPCMBuffer(pcmFormat: audio.processingFormat,
            frameCapacity: AVAudioFrameCount(audio.length)) else { throw Failure("No recorded PCM") }
        try audio.read(into: pcm)
        let samples = pcm.floatChannelData.map { Array(UnsafeBufferPointer(start: $0[0], count: Int(pcm.frameLength))) } ?? []
        result["recordedSeconds"] = Double(audio.length) / audio.processingFormat.sampleRate
        result["peakAmplitude"] = samples.map { abs($0) }.max() ?? 0
        let start = ProcessInfo.processInfo.systemUptime
        let text = try await engine.transcribe(url)
        result["inferenceSeconds"] = ProcessInfo.processInfo.systemUptime - start
        result["transcriptCharacters"] = text.count
        result["complete"] = true
        exitCode = 0
    } catch {
        // Probe errors are local operational messages, never an audio/transcript payload.
        result["failure"] = String(describing: error)
    }
    do {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let data = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
        try data.write(to: report, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: report.path)
        print(String(decoding: data, as: UTF8.self))
    } catch { fputs("Could not write local probe receipt\n", stderr); return 1 }
    return exitCode
}
