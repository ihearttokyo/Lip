package dev.lip

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Test APK only: real guest microphone samples, never host playback or ASR output. */
class AudioInjectionRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val report = JSONObject().put("event", "guest_audio_probe").put("passed", false)
        val result = Bundle()
        var home: Activity? = null
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk")) {
                "Audio probe requires the isolated emulator, never a physical device"
            }
            check(targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Grant RECORD_AUDIO only on the verified isolated emulator first"
            }
            home = startActivitySync(Intent().setClassName(targetContext.packageName, "dev.lip.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val pcm = File.createTempFile("audio-injection-", ".pcm", targetContext.cacheDir)
            report.put("pcm_path", pcm.absolutePath).put("sample_rate", AudioToneGate.RATE)
                .put("channels", 1).put("format", "PCM16_LE").put("tone_hz", 1000)
            val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(AudioToneGate.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setPrivacySensitive(true)
                .setBufferSizeInBytes(maxOf(6400, AudioRecord.getMinBufferSize(AudioToneGate.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
            val gate = AudioToneGate()
            val windows = JSONArray()
            report.put("windows", windows)
            var captured = 0
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                pcm.outputStream().use { output ->
                    val samples = ShortArray(AudioToneGate.WINDOW_SAMPLES)
                    val encoded = ByteArray(samples.size * 2)
                    var filled = 0
                    recorder.startRecording()
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    val started = SystemClock.elapsedRealtime()
                    var ready = false
                    while (captured < AudioToneGate.RATE * 12
                        && SystemClock.elapsedRealtime() - started < 12_000) {
                        val count = recorder.read(samples, filled, minOf(samples.size - filled,
                            AudioToneGate.RATE * 12 - captured), AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0) { "AudioRecord read failed: $count" }
                        if (count == 0) { Thread.sleep(5); continue }
                        for (index in 0 until count) {
                            val sample = samples[filled + index].toInt()
                            encoded[index * 2] = sample.toByte()
                            encoded[index * 2 + 1] = (sample ushr 8).toByte()
                        }
                        output.write(encoded, 0, count * 2)
                        filled += count
                        captured += count
                        if (filled == samples.size) {
                            val configuration = checkNotNull(recorder.activeRecordingConfiguration) {
                                "Active recording configuration is missing"
                            }
                            check(!configuration.isClientSilenced) { "Android capture policy silenced the microphone" }
                            val window = gate.observe(samples)
                            windows.put(JSONObject().put("start_sample", captured - samples.size)
                                .put("rms", window.rms).put("tone_fraction", window.toneFraction)
                                .put("tone", window.tone).put("exact_zero", window.silent)
                                .put("client_silenced", configuration.isClientSilenced))
                            if (!ready) {
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
                                report.put("route", route)
                                output.flush()
                                sendStatus(1, Bundle().apply {
                                    putString("route", route.toString())
                                    putString(REPORT_KEY_STREAMRESULT,
                                        "\nREADY: guest AudioRecord started; unsilenced_window_samples=${samples.size}; pcm_path=${pcm.absolutePath}\n")
                                })
                                ready = true
                            }
                            filled = 0
                        }
                    }
                }
            } finally {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { recorder.stop() }
                recorder.release()
                report.put("captured_samples", captured).put("captured_bytes", pcm.length())
                    .put("tone_seen", gate.toneSeen).put("silence_run", gate.silenceRun)
                    .put("passed", gate.passed)
            }
            check(gate.passed) { "Require sustained dominant 1 kHz tone followed by one second of exact-zero PCM" }
        } catch (error: Throwable) {
            report.put("passed", false).put("error", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { runOnMainSync { home?.finish() } }
        }
        report.optString("pcm_path").takeIf { it.isNotEmpty() }?.let { path ->
            val receipt = File(path.removeSuffix(".pcm") + ".json")
            try {
                check(receipt.createNewFile()) { "Receipt path already exists" }
                report.put("receipt_path", receipt.absolutePath)
                receipt.writeText(report.toString())
            } catch (error: Exception) {
                report.put("passed", false).put("receipt_error", error.javaClass.simpleName)
            }
        }
        result.putString(REPORT_KEY_STREAMRESULT, "\n${report}\n")
        finish(if (report.optBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}

internal class AudioToneGate {
    companion object {
        const val RATE = 16_000
        const val WINDOW_SAMPLES = 1_600
    }
    data class Window(val rms: Double, val toneFraction: Double, val tone: Boolean, val silent: Boolean)
    var toneRun = 0
        private set
    var silenceRun = 0
        private set
    var toneSeen = false
        private set
    val passed get() = toneSeen && silenceRun >= 10

    fun observe(samples: ShortArray): Window {
        require(samples.size == WINDOW_SAMPLES)
        val coefficient = 2 * cos(2 * PI * 1000 / RATE)
        var energy = 0.0
        var previous = 0.0
        var beforePrevious = 0.0
        var silent = true
        for (sample in samples) {
            val value = sample.toDouble()
            energy += value * value
            silent = silent && sample == 0.toShort()
            val current = value + coefficient * previous - beforePrevious
            beforePrevious = previous
            previous = current
        }
        val rms = sqrt(energy / samples.size) / 32768
        val power = previous * previous + beforePrevious * beforePrevious - coefficient * previous * beforePrevious
        val fraction = if (energy == 0.0) 0.0 else (2 * power / (samples.size * energy)).coerceIn(0.0, 1.0)
        val tone = rms in 0.05..0.60 && fraction >= 0.85
        if (!toneSeen) {
            toneRun = if (tone) toneRun + 1 else 0
            toneSeen = toneRun >= 5
        }
        silenceRun = if (toneSeen && silent) silenceRun + 1 else 0
        return Window(rms, fraction, tone, silent)
    }
}
