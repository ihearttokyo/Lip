package dev.lip.speech

import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/** CPU-only local ASR. Construct, transcribe and close on a worker; cancel is nonblocking. */
class WhisperEngine(modelPath: String, nativeLibraryDir: String) : Closeable {
    private val state = Any()
    private val inference = Any()
    private var closed = false
    private var cancellation = 0L
    private var handle: Long

    init {
        require(modelPath.isNotBlank() && '\u0000' !in modelPath)
        require(nativeLibraryDir.startsWith("/") && '\u0000' !in nativeLibraryDir)
        handle = nativeOpen(modelPath.toByteArray(Charsets.UTF_8), nativeLibraryDir.toByteArray(Charsets.UTF_8))
        check(handle != 0L) { "Cannot load the local speech model" }
    }

    class Segment internal constructor(
        utf8: ByteArray,
        val startMs: Long,
        val endMs: Long,
        val noSpeechProbability: Float,
    ) {
        val text: String = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(utf8)).toString()
        // Confidence signal, not proof of silence. Whisper also uses decoder log-probability.
        val likelyNoSpeech: Boolean get() = noSpeechProbability > 0.6f
    }

    internal object ExactZeroPcm {
        fun matches(pcm: FloatArray): Boolean = pcm.all { it == 0f }
    }

    /** A complete window snapshot, not a word stream; timestamps are relative to this PCM. */
    fun transcribe(pcm: FloatArray, language: String, dictionaryPrompt: String = ""): List<Segment> = synchronized(inference) {
        require(pcm.size in 1..MAX_SAMPLES) { "Use 16 kHz mono PCM windows of at most 30 seconds" }
        require(language in setOf("en", "ja", "zh")) { "Unsupported speech language" }
        require(dictionaryPrompt.length <= MAX_PROMPT_CHARACTERS && '\u0000' !in dictionaryPrompt) { "Speech dictionary prompt is too long or invalid" }
        val revision = synchronized(state) {
            check(!closed) { "Speech engine is closed" }
            nativeAbort(handle, false)
            cancellation
        }
        val segments = if (ExactZeroPcm.matches(pcm)) emptyArray<Segment>() else
            nativeTranscribe(handle, pcm, language.toByteArray(Charsets.UTF_8), dictionaryPrompt.toByteArray(Charsets.UTF_8))
        synchronized(state) {
            if (closed || cancellation != revision) throw CancellationException("Speech inference canceled")
        }
        segments.toList()
    }

    /** Abort the active inference. A later transcribe starts a fresh, independent window. */
    fun cancel() = synchronized(state) {
        if (!closed) { cancellation++; nativeAbort(handle, true) }
    }

    /** Terminal and idempotent. Waits for cooperative native cancellation before releasing memory. */
    override fun close() {
        synchronized(state) {
            if (closed) return
            closed = true
            cancellation++
            nativeAbort(handle, true)
        }
        synchronized(inference) {
            synchronized(state) { nativeClose(handle); handle = 0 }
        }
    }

    private external fun nativeOpen(modelPath: ByteArray, nativeLibraryDir: ByteArray): Long
    private external fun nativeTranscribe(handle: Long, pcm: FloatArray, language: ByteArray, prompt: ByteArray): Array<Segment>
    private external fun nativeAbort(handle: Long, abort: Boolean)
    private external fun nativeClose(handle: Long)

    companion object {
        const val MAX_SAMPLES = 16_000 * 30
        const val MAX_PROMPT_CHARACTERS = 1_024
        init { System.loadLibrary("lip_whisper") }
    }
}
