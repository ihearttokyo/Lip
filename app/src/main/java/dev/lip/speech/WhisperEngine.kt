package dev.lip.speech

import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/** CPU-only local ASR. Construct, transcribe and close on a worker; cancel is nonblocking. */
class WhisperEngine internal constructor(modelPath: String, private val native: NativeCalls?) : Closeable {
    constructor(modelPath: String) : this(modelPath, null)

    internal interface NativeCalls {
        fun open(modelPath: ByteArray): Long
        fun transcribe(handle: Long, pcm: FloatArray, language: ByteArray, prompt: ByteArray): Array<Segment>
        fun abort(handle: Long, abort: Boolean)
        fun close(handle: Long)
    }
    private val state = Any()
    private val inference = Any()
    private var closed = false
    private var cancellation = 0L
    private var decoding = false
    private var borrower: CaptureLease? = null
    private var handle: Long

    init {
        require(modelPath.isNotBlank() && '\u0000' !in modelPath)
        if (native == null) System.loadLibrary("lip_whisper")
        handle = native?.open(modelPath.toByteArray(Charsets.UTF_8)) ?: nativeOpen(modelPath.toByteArray(Charsets.UTF_8))
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

    internal class CaptureLease internal constructor(private val engine: WhisperEngine) {
        fun transcribe(pcm: FloatArray, language: String, prompt: String = "") = engine.transcribeWindow(pcm, language, prompt, this)
        fun cancel() = synchronized(engine.state) {
            if (engine.borrower === this) engine.cancel()
        }
    }

    /** Issued before scheduling capture work, never while the previous native call is still active. */
    internal fun borrow() = synchronized(state) {
        check(!closed && !decoding && borrower == null) { "Speech engine is closed or still borrowed" }
        CaptureLease(this).also { borrower = it }
    }

    /** A complete window snapshot, not a word stream; timestamps are relative to this PCM. */
    fun transcribe(pcm: FloatArray, language: String, dictionaryPrompt: String = ""): List<Segment> =
        transcribeWindow(pcm, language, dictionaryPrompt, null)

    private fun transcribeWindow(pcm: FloatArray, language: String, dictionaryPrompt: String, capture: CaptureLease?): List<Segment> = synchronized(inference) {
        require(pcm.size in 1..MAX_SAMPLES) { "Use 16 kHz mono PCM windows of at most 30 seconds" }
        require(language in setOf("en", "ja", "zh")) { "Unsupported speech language" }
        require(dictionaryPrompt.length <= MAX_PROMPT_CHARACTERS && '\u0000' !in dictionaryPrompt) { "Speech dictionary prompt is too long or invalid" }
        val revision = synchronized(state) {
            check(!closed) { "Speech engine is closed" }
            if (capture == null) check(borrower == null) { "Speech engine is exclusively borrowed" }
            else if (borrower !== capture) throw CancellationException("Speech capture canceled")
            setAbort(false)
            decoding = true
            cancellation
        }
        try {
            val segments = if (ExactZeroPcm.matches(pcm)) emptyArray<Segment>() else
                native?.transcribe(handle, pcm, language.toByteArray(Charsets.UTF_8), dictionaryPrompt.toByteArray(Charsets.UTF_8))
                    ?: nativeTranscribe(handle, pcm, language.toByteArray(Charsets.UTF_8), dictionaryPrompt.toByteArray(Charsets.UTF_8))
            synchronized(state) {
                if (closed || cancellation != revision) throw CancellationException("Speech inference canceled")
            }
            segments.toList()
        } finally { synchronized(state) { decoding = false } }
    }

    /** Abort the active inference. A later transcribe starts a fresh, independent window. */
    fun cancel() = synchronized(state) {
        if (!closed) { borrower = null; cancellation++; setAbort(true) }
    }

    /** Terminal and idempotent. Waits for cooperative native cancellation before releasing memory. */
    override fun close() {
        synchronized(state) {
            if (closed) return
            closed = true
            borrower = null
            cancellation++
            setAbort(true)
        }
        synchronized(inference) {
            synchronized(state) { native?.close(handle) ?: nativeClose(handle); handle = 0 }
        }
    }

    private external fun nativeOpen(modelPath: ByteArray): Long
    private external fun nativeTranscribe(handle: Long, pcm: FloatArray, language: ByteArray, prompt: ByteArray): Array<Segment>
    private external fun nativeAbort(handle: Long, abort: Boolean)
    private external fun nativeClose(handle: Long)

    private fun setAbort(abort: Boolean) { native?.abort(handle, abort) ?: nativeAbort(handle, abort) }

    companion object {
        const val MAX_SAMPLES = 16_000 * 30
        const val MAX_PROMPT_CHARACTERS = 1_024
    }
}
