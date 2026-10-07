package dev.lip

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Continuous local PCM; no recording file, network transport or endpoint restarts. */
@SuppressLint("MissingPermission") // Dictation checks RECORD_AUDIO before construction; denial still throws.
internal class PcmSource(private val context: Context) : Closeable {
    private val format = AudioFormat.Builder().setSampleRate(16_000)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
    private val microphone = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(format).setPrivacySensitive(true)
        .setBufferSizeInBytes(maxOf(6_400, AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .build()
    private val pipe = try { ParcelFileDescriptor.createReliablePipe() } catch (error: Exception) { microphone.release(); throw error }
    val input: ParcelFileDescriptor get() = pipe[0]
    private val running = AtomicBoolean(false)
    private var everStarted = false
    val started get() = everStarted
    private var callback: AudioManager.AudioRecordingCallback? = null

    fun start(level: (Float) -> Unit, failed: () -> Unit) {
        check(microphone.state == AudioRecord.STATE_INITIALIZED)
        val monitoring = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                if (running.get() && configs.any { it.isClientSilenced }) failed()
            }
        }
        callback = monitoring
        microphone.registerAudioRecordingCallback(context.mainExecutor, monitoring)
        microphone.startRecording()
        check(microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        running.set(true); everStarted = true
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    val buffer = ByteArray(1_280)
                    while (running.get()) {
                        val count = microphone.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count <= 0) { if (running.get()) failed(); break }
                        output.write(buffer, 0, count)
                        var squares = 0.0
                        for (index in 0 until count - 1 step 2) {
                            val sample = ((buffer[index].toInt() and 255) or (buffer[index + 1].toInt() shl 8)).toShort().toDouble()
                            squares += sample * sample
                        }
                        level((sqrt(squares / (count / 2)) / 6_000).toFloat().coerceIn(0f, 1f))
                    }
                }
            } catch (_: Exception) { if (running.get()) failed() }
            finally { microphone.release() }
        }, "lip-local-pcm").apply { isDaemon = true; start() }
    }

    fun finishAudio() {
        if (running.getAndSet(false)) runCatching { microphone.stop() }
        callback?.let { runCatching { microphone.unregisterAudioRecordingCallback(it) } }; callback = null
        runCatching { pipe[1].close() }
    }
    override fun close() {
        finishAudio()
        runCatching { pipe[0].close() }
        if (!everStarted) runCatching { microphone.release() }
    }
}
