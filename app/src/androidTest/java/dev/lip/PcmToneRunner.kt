package dev.lip

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import dev.lip.speech.PcmWindow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Test-only calibration of the real production byte-array microphone, pipe and PCM window. */
class PcmToneRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val report = JSONObject().put("event", "guest_audio_probe").put("passed", false)
            .put("pipeline", "PcmSource/PcmWindow").put("asr_tested", false)
            .put("fixture_wav_sha256", "17c0e211f28c04b75ba98ddc26eb87861021bc87d8f93a10f8a2fe360b030f79")
            .put("sample_rate", AudioToneGate.RATE).put("channels", 1).put("format", "PCM16_LE").put("tone_hz", 1000)
        val pcm = PcmWindow()
        val gate = AudioToneGate()
        val windows = JSONArray()
        val accepted = AtomicLong()
        val received = AtomicLong()
        val sourceErrors = AtomicInteger()
        val readerError = AtomicReference<Exception?>()
        val writer = AtomicReference<Thread?>()
        val ready = CountDownLatch(1)
        val eof = CountDownLatch(1)
        val readySent = AtomicBoolean()
        var source: PcmSource? = null
        var reader: Thread? = null
        var home: Activity? = null
        var witness: File? = null
        var startedAt = 0L
        var stoppedAt = 0L
        var stage = "pure_codec_check"
        var cleanupOk = true
        report.put("windows", windows)
        try {
            PcmToneCodec.codecCheck()
            stage = "isolation_and_permission"
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            check(targetContext.packageName == "dev.lip.android")
            check(targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            stage = "foreground_activity"
            home = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val focused = AtomicBoolean()
            val focusDeadline = SystemClock.elapsedRealtime() + 3_000
            while (!focused.get() && SystemClock.elapsedRealtime() < focusDeadline) {
                runOnMainSync { focused.set(home?.hasWindowFocus() == true) }
                if (!focused.get()) Thread.sleep(25)
            }
            check(focused.get())
            runOnMainSync { check(!Dictation.get(targetContext).busy) }
            val file = File.createTempFile("audio-injection-pcm-", ".pcm", targetContext.cacheDir).also { witness = it }
            report.put("pcm_path", file.absolutePath)
            lateinit var microphone: PcmSource
            runOnMainSync {
                microphone = PcmSource(targetContext) { count ->
                    writer.set(Thread.currentThread())
                    accepted.addAndGet(count.toLong())
                }
                source = microphone
            }
            val recorder = PcmSource::class.java.getDeclaredField("microphone").run {
                isAccessible = true; get(microphone) as AudioRecord
            }
            reader = thread(isDaemon = true, name = "lip-pcm-tone-reader") {
                try {
                    file.outputStream().use { output ->
                        ParcelFileDescriptor.AutoCloseInputStream(microphone.input).use { input ->
                            val buffer = ByteArray(4_096) // Same reader packet size as LocalCapture.
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) { eof.countDown(); break }
                                check(count > 0 && received.get() + count <= MAX_BYTES)
                                pcm.append(buffer, count)
                                output.write(buffer, 0, count)
                                received.addAndGet(count.toLong())
                                if (!readySent.get() && received.get() >= AudioToneGate.WINDOW_SAMPLES * 2) {
                                    val configuration = checkNotNull(recorder.activeRecordingConfiguration)
                                    check(sourceErrors.get() == 0 && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
                                        && !configuration.isClientSilenced)
                                    val first = checkNotNull(pcm.snapshot())
                                    val window = gate.observe(PcmToneCodec.shorts(first.samples, 0, AudioToneGate.WINDOW_SAMPLES))
                                    windows.put(windowReport(0, window).put("client_silenced", configuration.isClientSilenced))
                                    val route = JSONObject().put("client_source", configuration.clientAudioSource)
                                        .put("client_rate_hz", configuration.clientFormat.sampleRate)
                                        .put("client_channels", configuration.clientFormat.channelCount)
                                        .put("client_encoding", configuration.clientFormat.encoding)
                                        .put("device_rate_hz", configuration.format.sampleRate)
                                        .put("device_channels", configuration.format.channelCount)
                                        .put("device_encoding", configuration.format.encoding)
                                        .put("record_session_id", recorder.audioSessionId)
                                        .put("client_session_id", configuration.clientAudioSessionId)
                                        .put("device_id", configuration.audioDevice?.id ?: -1)
                                        .put("device_type", configuration.audioDevice?.type ?: -1)
                                    report.put("route", route).put("ready_received_bytes", received.get())
                                        .put("unsilenced_ready_window", true)
                                    output.flush()
                                    sendStatus(1, Bundle().apply {
                                        putString("route", route.toString())
                                        putString(REPORT_KEY_STREAMRESULT,
                                            "\nREADY: guest AudioRecord started; unsilenced_window_samples=${AudioToneGate.WINDOW_SAMPLES}; pcm_path=${file.absolutePath}\n")
                                    })
                                    readySent.set(true)
                                    ready.countDown()
                                }
                            }
                        }
                    }
                } catch (error: Exception) { readerError.set(error); ready.countDown() }
            }
            stage = "start_production_microphone"
            runOnMainSync {
                microphone.start({}, { sourceErrors.incrementAndGet() })
                startedAt = SystemClock.elapsedRealtime()
            }
            stage = "first_full_pcm_window"
            check(ready.await(12, TimeUnit.SECONDS) && readySent.get() && readerError.get() == null)
            stage = "hold_active_capture"
            while (SystemClock.elapsedRealtime() - startedAt < 12_000) {
                check(sourceErrors.get() == 0 && readerError.get() == null && eof.count > 0)
                Thread.sleep(25)
            }
            stage = "finish_and_drain_real_pipe"
            stoppedAt = SystemClock.elapsedRealtime()
            runOnMainSync { microphone.finishAudio() }
            check(eof.await(3, TimeUnit.SECONDS))
            reader?.join(3_000); writer.get()?.join(3_000)
            check(reader?.isAlive == false && writer.get()?.isAlive == false)
            check(sourceErrors.get() == 0 && readerError.get() == null)
            check(received.get() > 0 && received.get() == accepted.get() && received.get() % 2 == 0L)
            stage = "windowed_pcm_witness"
            val final = checkNotNull(pcm.finish())
            check(final.startSample == 0L && final.samples.size * 2L == received.get() && file.length() == received.get())
            val actualHash = file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                hex(digest.digest())
            }
            val encodedHash = hex(MessageDigest.getInstance("SHA-256").digest(PcmToneCodec.bytes(final.samples)))
            report.put("pcm_sha256", actualHash).put("windowed_pcm_sha256", encodedHash)
            check(actualHash == encodedHash)
            report.put("windowed_pcm_hash_matches_pipe", true)
            stage = "unchanged_strict_tone_and_zero_gate"
            var offset = AudioToneGate.WINDOW_SAMPLES // The first window was already measured before READY.
            while (offset + AudioToneGate.WINDOW_SAMPLES <= final.samples.size) {
                val window = gate.observe(PcmToneCodec.shorts(final.samples, offset, AudioToneGate.WINDOW_SAMPLES))
                windows.put(windowReport(offset, window))
                offset += AudioToneGate.WINDOW_SAMPLES
            }
            check(gate.passed) { "Require sustained dominant 1 kHz tone followed by one second of exact-zero PCM" }
            report.put("passed", true)
        } catch (error: Exception) {
            report.put("passed", false).put("error_stage", stage).put("error_class", error.javaClass.simpleName)
        } finally {
            try { runOnMainSync { source?.close() } } catch (_: Exception) { cleanupOk = false }
            try { reader?.join(3_000); writer.get()?.join(3_000) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); cleanupOk = false }
            if (reader?.isAlive == true || writer.get()?.isAlive == true) cleanupOk = false
            try { runOnMainSync { home?.finish() } } catch (_: Exception) { cleanupOk = false }
            val permissionUnchanged = targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            pcm.cancel()
            if (!cleanupOk || !permissionUnchanged || readerError.get() != null || sourceErrors.get() != 0) report.put("passed", false)
            report.put("accepted_bytes", accepted.get()).put("received_bytes", received.get())
                .put("captured_bytes", witness?.length() ?: 0).put("captured_samples", received.get() / 2)
                .put("eof_received", eof.count == 0L).put("reader_joined", reader?.isAlive != true)
                .put("writer_joined", writer.get()?.isAlive != true).put("source_errors", sourceErrors.get())
                .put("reader_error", readerError.get()?.javaClass?.simpleName ?: "none").put("cleanup_ok", cleanupOk)
                .put("permission_unchanged", permissionUnchanged)
                .put("capture_held_ms", if (startedAt > 0 && stoppedAt > 0) stoppedAt - startedAt else -1)
                .put("tone_seen", gate.toneSeen).put("silence_run", gate.silenceRun)
        }
        witness?.let { file ->
            try {
                val receipt = File(file.absolutePath.removeSuffix(".pcm") + ".json")
                check(receipt.createNewFile())
                report.put("receipt_path", receipt.absolutePath)
                receipt.writeText(report.toString())
            } catch (error: Exception) { report.put("passed", false).put("receipt_error", error.javaClass.simpleName) }
        }
        finish(if (report.optBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString(REPORT_KEY_STREAMRESULT, "\n$report\n") })
    }

    private fun windowReport(offset: Int, window: AudioToneGate.Window) = JSONObject()
        .put("start_sample", offset).put("rms", window.rms).put("tone_fraction", window.toneFraction)
        .put("tone", window.tone).put("exact_zero", window.silent)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object { const val MAX_BYTES = 16_000L * 2 * 13 } // Twelve seconds plus the accepted Stop tail.
}

/** Pure lossless codec checks, callable on the host JVM without creating AudioRecord. */
internal object PcmToneCodec {
    fun shorts(samples: FloatArray, start: Int, count: Int): ShortArray {
        require(start in 0..samples.size && count in 0..samples.size - start)
        return ShortArray(count) { index ->
            val value = samples[start + index]
            require(value.isFinite() && value >= -1f && value < 1f)
            val scaled = value * 32768f
            check(scaled == scaled.toInt().toFloat()) { "PCM window changed an integer PCM16 sample" }
            scaled.toInt().toShort()
        }
    }
    fun bytes(samples: FloatArray): ByteArray {
        val decoded = shorts(samples, 0, samples.size)
        return ByteArray(decoded.size * 2) { index ->
            (decoded[index / 2].toInt() shr (if (index % 2 == 0) 0 else 8)).toByte()
        }
    }
    fun codecCheck() {
        val fixture = byteArrayOf(0, 0x80.toByte(), 0xFF.toByte(), 0x7F, 0, 0, 1, 0, 0xFF.toByte(), 0xFF.toByte())
        val window = PcmWindow()
        var offset = 0
        for (count in listOf(1, 3, 2, 3, 1)) {
            window.append(fixture.copyOfRange(offset, offset + count)); offset += count
        }
        val final = checkNotNull(window.finish())
        check(final.startSample == 0L && final.samples.size * 2 == fixture.size && window.finish() == null)
        check(shorts(final.samples, 0, 5).contentEquals(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 0, 1, -1)))
        check(bytes(final.samples).contentEquals(fixture))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, 1f, -1.01f, 0.1f)) {
            check(runCatching { shorts(floatArrayOf(invalid), 0, 1) }.isFailure)
        }
        check(runCatching { shorts(final.samples, 4, 2) }.isFailure)
    }
}
