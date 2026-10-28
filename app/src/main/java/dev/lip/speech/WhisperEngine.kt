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
        // Legacy/test fixtures are synchronous. Checked Bark overrides these
        // with nonblocking native phase queries and proven retirement.
        fun admission(handle: Long): Boolean = true
        fun retainedWork(handle: Long): Boolean = false
        fun closeChecked(handle: Long): Boolean { close(handle); return true }
    }
    private val state = Any()
    private val inference = Any()
    private var closed = false
    private var cancellation = 0L
    private var decoding = false
    private var nativeUncertain = false
    private var retirementUnconfirmed = false
    private var borrower: CaptureLease? = null
    private class CompletedCapture(val lease: CaptureLease, val pcm: FloatArray, val startSample: Long,
        val language: String, val prompt: String, val segments: List<Segment>)
    private var completedCapture: CompletedCapture? = null
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
        fun transcribe(pcm: FloatArray, language: String, prompt: String = "", startSample: Long = 0, reuseIdentical: Boolean = false) =
            engine.transcribeWindow(pcm, language, prompt, this, startSample, reuseIdentical)
        fun cancel() = synchronized(engine.state) {
            if (engine.borrower === this) engine.cancel()
        }
    }

    /** Issued before scheduling capture work, never while the previous native call is still active. */
    internal fun borrow() = synchronized(state) {
        check(!closed && !decoding && borrower == null) { "Speech engine is closed or still borrowed" }
        requireAdmission()
        CaptureLease(this).also { borrower = it }
    }

    /** A complete window snapshot, not a word stream; timestamps are relative to this PCM. */
    fun transcribe(pcm: FloatArray, language: String, dictionaryPrompt: String = ""): List<Segment> =
        transcribeWindow(pcm, language, dictionaryPrompt, null)

    private fun transcribeWindow(pcm: FloatArray, language: String, dictionaryPrompt: String, capture: CaptureLease?, startSample: Long = 0, reuseIdentical: Boolean = false): List<Segment> = synchronized(inference) {
        require(pcm.size in 1..MAX_SAMPLES) { "Use 16 kHz mono PCM windows of at most 30 seconds" }
        require(startSample >= 0) { "Invalid speech sample range" }
        require(language in setOf("en", "ja", "zh")) { "Unsupported speech language" }
        require(dictionaryPrompt.length <= MAX_PROMPT_CHARACTERS && '\u0000' !in dictionaryPrompt) { "Speech dictionary prompt is too long or invalid" }
        val revision = synchronized(state) {
            check(!closed) { "Speech engine is closed" }
            if (capture == null) check(borrower == null) { "Speech engine is exclusively borrowed" }
            else if (borrower !== capture) throw CancellationException("Speech capture canceled")
            // Guard BEFORE cache, zero-PCM and abort reset: none may bypass
            // unknown native work or a globally poisoned native admission lane.
            requireAdmission()
            val completed = completedCapture
            if (reuseIdentical && capture != null && completed?.lease === capture && completed.startSample == startSample &&
                completed.language == language && completed.prompt == dictionaryPrompt && completed.pcm.contentEquals(pcm))
                return completed.segments.toList()
            completedCapture = null
            setAbort(false)
            decoding = true
            cancellation
        }
        try {
            val retainedPcm = if (capture != null) pcm.copyOf() else null
            val segments = if (ExactZeroPcm.matches(pcm)) emptyArray<Segment>() else
                native?.transcribe(handle, pcm, language.toByteArray(Charsets.UTF_8), dictionaryPrompt.toByteArray(Charsets.UTF_8))
                    ?: nativeTranscribe(handle, pcm, language.toByteArray(Charsets.UTF_8), dictionaryPrompt.toByteArray(Charsets.UTF_8))
            synchronized(state) {
                if (closed || cancellation != revision) throw CancellationException("Speech inference canceled")
                requireAdmission()
                if (capture != null) completedCapture = CompletedCapture(capture, retainedPcm!!, startSample, language, dictionaryPrompt, segments.toList())
            }
            segments.toList()
        } finally { synchronized(state) {
            // A returning exception is not proof that asynchronous native work
            // retired. Keep busy/resources and invalidate cached completion.
            if (nativeUncertain || retainedNativeWork()) {
                nativeUncertain = true
                completedCapture = null
                decoding = true
            } else decoding = false
        } }
    }

    /** Abort the active inference. A later transcribe starts a fresh, independent window. */
    fun cancel() = synchronized(state) {
        if (!closed) { borrower = null; completedCapture = null; cancellation++; setAbort(true) }
    }

    /** Terminal. Potentially blocking retirement never holds the state monitor. */
    override fun close() {
        synchronized(state) {
            if (handle == 0L || retirementUnconfirmed) return
            if (!closed) {
                closed = true
                borrower = null
                completedCapture = null
                cancellation++
                setAbort(true)
            }
        }
        synchronized(inference) {
            val current = synchronized(state) {
                if (handle == 0L || retirementUnconfirmed) return
                handle
            }
            val retired = try {
                native?.closeChecked(current) ?: run { nativeClose(current); true }
            } catch (failure: Throwable) {
                synchronized(state) {
                    retirementUnconfirmed = true; nativeUncertain = true; decoding = true
                }
                throw failure
            }
            synchronized(state) {
                if (retired) { handle = 0L; decoding = false; nativeUncertain = false }
                else { retirementUnconfirmed = true; nativeUncertain = true; decoding = true }
            }
        }
    }

    // Called only under state; checked native implementations must use atomic
    // phase queries, never wait for native compute/close while holding this lock.
    private fun requireAdmission() {
        check(!nativeUncertain && handle != 0L) { "Native speech work has not retired" }
        if (retainedNativeWork()) {
            nativeUncertain = true; decoding = true; completedCapture = null
            error("Native speech work has not retired")
        }
        val admitted = try { native?.admission(handle) ?: true }
        catch (failure: Throwable) {
            nativeUncertain = true; decoding = true; completedCapture = null
            throw failure
        }
        check(admitted) { "Native speech engine is not available for new work" }
    }

    private fun retainedNativeWork(): Boolean = try {
        native?.retainedWork(handle) ?: false
    } catch (_: Throwable) { true } // Unknown query cannot grant idle admission.

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
