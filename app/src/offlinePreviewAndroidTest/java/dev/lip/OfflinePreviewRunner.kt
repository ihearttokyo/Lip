package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import dev.lip.speech.BarkModel
import dev.lip.speech.BarkProfile
import dev.lip.speech.BundledModelPublisher
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import dev.lip.speech.WhisperEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exact packaged offline models, production checked JNI, public synthetic audio.
 * No microphone, UI, default keyboard mutation, cloud, or physical phone.
 */
class OfflinePreviewRunner : Instrumentation() {
    private var publisherOnly = false
    override fun onCreate(arguments: Bundle?) {
        publisherOnly = arguments?.getString("publisher_only") == "true"
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val result = Bundle()
        var completed = 0
        try {
            check(targetContext.packageName == "dev.lip.android.preview") { "Preview identity required" }
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk")) {
                "Only a disposable emulator is authorized"
            }
            if (publisherOnly) {
                checkPublisher(result)
                finish(Activity.RESULT_OK, result)
                return
            }
            result.putString("target", targetContext.applicationInfo.sourceDir)
            result.putString("abis", Build.SUPPORTED_ABIS.joinToString())
            val pcm = readPcm()
            result.putInt("public_pcm_samples", pcm.size)
            check(pcm.size == 69_634 && pcm.any { it != 0f }) { "Unexpected public WAV" }
            for (profile in listOf(BarkProfile.CLEAN, BarkProfile.PRO)) {
                val started = System.nanoTime()
                check(BarkModel.bundledAvailable(targetContext, profile)) { "Missing ${profile.id} APK asset" }
                val path = BarkModel.installBundled(targetContext,
                    File(targetContext.noBackupFilesDir, profile.directoryName), profile)
                    ?: error("Packaged model missing")
                check(path.length() == BarkModel.BYTES)
                val engine = BarkModel.engine(path.absolutePath, profile)
                try {
                    val first = assertTranscript(engine.transcribe(pcm, "en"), pcm.size)
                    result.putString("${profile.id}_first", first)
                    // Idle cancellation must not poison the next independent capture.
                    // This is not a claim of a mid-inference race test.
                    engine.cancel()
                    val rearmed = assertTranscript(engine.transcribe(pcm, "en"), pcm.size)
                    result.putString("${profile.id}_rearmed", rearmed)
                    check(first == rearmed) { "Fresh rearm changed deterministic public transcript" }
                } finally {
                    engine.close()
                    // close() conservatively retains handle on uncertain native retirement.
                    // This explicit assertion distinguishes proven close from that path.
                    val field = WhisperEngine::class.java.getDeclaredField("handle").apply { isAccessible = true }
                    check(field.getLong(engine) == 0L) { "Checked native retirement not confirmed" }
                }
                result.putBoolean("${profile.id}_checked_close", true)
                result.putLong("${profile.id}_elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                completed++
                result.putInt("completed_profiles", completed)
                sendStatus(0, Bundle(result).apply { putString("progress", "${profile.id} complete") })
            }
            check(completed == 2)
            result.putString("result", "PASS: two packaged profiles; four actual checked-JNI decodes; idle cancel/rearm; checked close")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putInt("completed_profiles", completed)
            result.putString("result", "FAIL")
            result.putString("failure", failure.javaClass.name + ": " + failure.message)
            result.putString("stack", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkPublisher(result: Bundle) {
        // Tiny fresh owned files only: no model loading, no modification of user paths.
        val directory = Files.createTempDirectory(targetContext.noBackupFilesDir.toPath(), "publisher-control-").toFile()
        val payload = byteArrayOf(1, 3, 5, 7)
        val source = File(directory, "success.part").apply { writeBytes(payload) }
        val target = File(directory, "success.gguf")
        BundledModelPublisher.publish(source, target)
        check(!source.exists() && target.readBytes().contentEquals(payload)) { "Atomic move did not preserve verified inode bytes" }
        result.putBoolean("native_publish_success", true)
        val conflictSource = File(directory, "conflict.part").apply { writeBytes(payload) }
        val old = byteArrayOf(9, 8, 6)
        val conflictTarget = File(directory, "conflict.gguf").apply { writeBytes(old) }
        var refused = false
        try { BundledModelPublisher.publish(conflictSource, conflictTarget) }
        catch (_: FileAlreadyExistsException) { refused = true }
        check(refused && conflictSource.readBytes().contentEquals(payload) && conflictTarget.readBytes().contentEquals(old)) {
            "Native NOREPLACE failed to preserve both existing target and staged source"
        }
        result.putBoolean("native_eexist_preserved_both", true)
        result.putString("owned_control_directory", directory.absolutePath)
        result.putString("result", "PASS: actual Android native renameat2 success and EEXIST no-clobber controls")
    }

    private fun assertTranscript(segments: List<WhisperEngine.Segment>, samples: Int): String {
        check(segments.isNotEmpty()) { "No actual speech result" }
        val text = segments.joinToString(" ") { it.text }.trim()
        check(text.contains("Friday", ignoreCase = true) && text.split(Regex("\\s+")).size >= 4) {
            "Public speech result missing expected Friday phrase: $text"
        }
        val duration = samples * 1_000L / 16_000
        var previous = 0L
        for (segment in segments) {
            check(segment.startMs >= previous && segment.endMs >= segment.startMs && segment.endMs <= duration + 500) {
                "Timestamp range invalid: ${segment.startMs}..${segment.endMs} duration=$duration"
            }
            previous = segment.startMs
        }
        return text
    }

    private fun readPcm(): FloatArray {
        val bytes = context.assets.open("offline-preview-test.wav").use { it.readBytes() }
        check(bytes.size == 143_364)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        check(ascii(0) == "RIFF" && ascii(8) == "WAVE")
        var at = 12; var format = false; var pcmStart = -1; var pcmBytes = 0
        while (at <= bytes.size - 8) {
            val size = data.getInt(at + 4)
            check(size >= 0 && size <= bytes.size - at - 8) { "Invalid WAV chunk" }
            if (ascii(at) == "fmt ") {
                check(size >= 16 && data.getShort(at + 8).toInt() == 1 && data.getShort(at + 10).toInt() == 1)
                check(data.getInt(at + 12) == 16_000 && data.getShort(at + 22).toInt() == 16)
                format = true
            }
            if (ascii(at) == "data") { check(pcmStart < 0); pcmStart = at + 8; pcmBytes = size }
            at += 8 + size + (size and 1)
        }
        check(format && pcmStart >= 0 && pcmBytes % 2 == 0)
        return FloatArray(pcmBytes / 2) { data.getShort(pcmStart + 2 * it) / 32768f }
    }
}
