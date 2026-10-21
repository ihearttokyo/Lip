package dev.lip.speech

import android.content.Context
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.lip.PcmSource
import dev.lip.core.CaptureSession
import java.util.Locale
import java.util.concurrent.Executors

/** Owns capture/PCM only; the caller owns the exclusively borrowed engine and its worker-side close. */
internal class LocalCapture(
    private val context: Context,
    private val engine: WhisperEngine,
    private val language: String,
    private val dictionaryPrompt: String,
    private val onStableSegment: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onEnd: () -> Unit,
    private val onError: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    private val state = Any()
    private val pcm = PcmWindow()
    private val admission = Admission(language)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "lip-local-inference").apply { isDaemon = true } }
    private val nativeLanguage = language.substringBefore('-').lowercase(Locale.ROOT)
    private var source: PcmSource? = null
    private var started = false
    private var finishing = false
    private var inputEnded = false
    private var ended = false
    private var decoding = false
    private var acceptedBytes = 0L
    private var scheduledBytes = 0L

    init {
        require(nativeLanguage in setOf("en", "ja", "zh")) { "Unsupported local speech language" }
        require(dictionaryPrompt.length <= WhisperEngine.MAX_PROMPT_CHARACTERS && '\u0000' !in dictionaryPrompt) { "Invalid speech dictionary prompt" }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val epoch = synchronized(state) {
            check(!started && !ended && !finishing) { "Local capture can only start once" }
            started = true
            admission.generation
        }
        try {
            val microphone = nativeSpeech { PcmSource(context) }
            synchronized(state) { source = microphone }
            nativeSpeech { Thread({ read(microphone, epoch) }, "lip-local-reader").apply { isDaemon = true; start() } }
            nativeSpeech { microphone.start({ value -> dispatch(epoch) {
                if (synchronized(state) { !ended && !finishing }) onLevel(value)
            } }, { fail(epoch, "Microphone interrupted or silenced. Transcript kept.") }) }
        } catch (_: Exception) { fail(epoch, "Cannot start local microphone capture. Check microphone permission.") }
    }

    /** Stop the microphone, then wait for pipe EOF; all accepted packets enter the one final drain. */
    fun finish() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val epoch = synchronized(state) {
            if (ended || finishing) return
            finishing = true
            if (!started) inputEnded = true
            admission.generation
        }
        source?.finishAudio()
        requestDecode(epoch)
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val active = synchronized(state) {
            admission.cancel() // Also invalidates callbacks already queued on the main executor.
            (!ended).also { ended = true }
        }
        if (active) { source?.close(); pcm.cancel(); engine.cancel(); worker.shutdown() }
    }

    private fun read(microphone: PcmSource, epoch: Long) {
        try {
            nativeSpeech { ParcelFileDescriptor.AutoCloseInputStream(microphone.input) }.use { input ->
                val buffer = nativeSpeech { ByteArray(4_096) }
                while (admission.isCurrent(epoch)) {
                    val count = nativeSpeech { input.read(buffer) }
                    if (count < 0) break
                    if (count == 0) continue
                    synchronized(state) {
                        if (ended || !admission.isCurrent(epoch)) return
                        pcm.append(buffer, count)
                        acceptedBytes += count
                    }
                    requestDecode(epoch)
                }
            }
            val stopped = synchronized(state) {
                if (ended || !admission.isCurrent(epoch)) return
                inputEnded = true
                finishing
            }
            if (stopped) requestDecode(epoch)
            else fail(epoch, "Microphone stream ended before Finish. Transcript kept.")
        } catch (error: IllegalStateException) {
            fail(epoch, error.message ?: "Local PCM could not continue. Transcript kept.")
        } catch (_: Exception) { fail(epoch, "Microphone stream was interrupted. Transcript kept.") }
    }

    private fun requestDecode(epoch: Long) {
        val request = try {
            nativeSpeech { synchronized(state) {
                if (ended || decoding || !admission.isCurrent(epoch)) return
                val final = finishing && inputEnded
                if (!final && (finishing || acceptedBytes - scheduledBytes < 64_000)) return // Two seconds of new PCM, never a VAD/discard rule.
                val snapshot = if (final) pcm.finish() else pcm.snapshot() ?: return
                decoding = true
                scheduledBytes = acceptedBytes
                snapshot to final
            } }
        } catch (error: Exception) { fail(epoch, error.message ?: "PCM could not finish. Transcript kept."); return }
        try {
            nativeSpeech { worker.execute { decode(epoch, request.first, request.second) } }
        } catch (_: Exception) { fail(epoch, "Local speech worker was interrupted. Transcript kept.") }
    }

    private fun decode(epoch: Long, window: PcmWindow.Window?, final: Boolean) {
        try {
            if (synchronized(state) { ended || !admission.isCurrent(epoch) }) return
            val segments = nativeSpeech { if (window == null) emptyList() else engine.transcribe(window.samples, nativeLanguage, dictionaryPrompt) }
            val update = synchronized(state) {
                if (ended || !admission.isCurrent(epoch)) return
                val accepted = nativeSpeech { admission.admit(epoch, window, segments, final,
                    finishRequested = finishing) } ?: return
                decoding = false
                if (accepted.ended) ended = true
                // Ordered with failure under the same lock: accepted transcript callbacks precede an error.
                dispatch(epoch) {
                    admission.deliver(epoch, accepted, onStableSegment, onPartial, onEnd)
                }
                accepted
            }
            if (update.ended) { source?.close(); worker.shutdown() }
            if (!update.ended) requestDecode(epoch) // Coalesce audio arriving during inference; never queue every packet.
        } catch (_: IllegalArgumentException) { fail(epoch, "Local speech returned invalid segment boundaries. Transcript kept.") }
        catch (_: Exception) { fail(epoch, "Local speech inference was interrupted. Transcript kept.") }
    }

    private fun fail(epoch: Long, message: String) {
        synchronized(state) {
            if (ended || !admission.isCurrent(epoch)) return
            ended = true
            admission.abort() // Preserve already-queued accepted words; explicit Cancel still fences everything.
        }
        source?.close(); pcm.cancel(); engine.cancel()
        // Keep the borrowed engine exclusive until already-submitted decode work has returned.
        try {
            nativeSpeech { worker.execute { dispatch(epoch) { onError(message) } } }
        } catch (_: Exception) { dispatch(epoch) { onError(message) } }
        worker.shutdown()
    }

    private fun dispatch(epoch: Long, callback: () -> Unit) {
        context.mainExecutor.execute { if (admission.isCurrent(epoch)) callback() }
    }

    /** This admission path is also used by the real inference callback, not a pretend decoder. */
    internal class Admission(private val language: String) {
        data class Update(val stable: List<String>, val partial: String, val ended: Boolean)
        @Volatile var generation = 1L; private set
        private var ended = false
        private val text = CaptureSession(language)
        fun isCurrent(expected: Long): Boolean = expected == generation
        @Synchronized fun cancel() { generation++; ended = true }
        @Synchronized fun abort() { ended = true }
        fun deliver(expected: Long, update: Update, stable: (String) -> Unit, partial: (String) -> Unit, end: () -> Unit) {
            for (word in update.stable) {
                if (!isCurrent(expected)) return
                stable(word)
            }
            if (!isCurrent(expected)) return
            partial(update.partial)
            if (update.ended && isCurrent(expected)) end()
        }
        @Synchronized fun admit(expected: Long, window: PcmWindow.Window?, segments: List<WhisperEngine.Segment>, final: Boolean = false,
            finishRequested: Boolean = false): Update? {
            if (!isCurrent(expected) || ended) return null
            if (window == null) {
                require(final && segments.isEmpty()) { "Missing local speech window" }
                ended = true
                return Update(emptyList(), "", true)
            }
            var previousEnd = 0L
            for (segment in segments) {
                require(segment.startMs >= previousEnd && segment.endMs >= segment.startMs && segment.endMs <= window.samples.size / 16L) { "Invalid local speech timestamps" }
                previousEnd = segment.endMs
            }
            if (final) {
                ended = true
                return Update(segments.map { it.text }.filter(String::isNotBlank), "", true)
            }
            // Finish needs the retained audio, not an irreversible boundary from an older preview.
            if (finishRequested) return Update(emptyList(), text.join(segments.map { it.text }), false)
            val boundaryIndex = segments.indexOfLast { it.text.isNotBlank() && it.endMs * 16 <= window.samples.size - 8_000 }
            val boundary = segments.getOrNull(boundaryIndex)?.endMs ?: 0L
            if (boundary > 0) {
                window.owner.commit(window, boundary)
                return Update(segments.take(boundaryIndex + 1).map { it.text }.filter(String::isNotBlank),
                    text.join(segments.drop(boundaryIndex + 1).map { it.text }), false)
            }
            if (segments.all { it.text.isBlank() } && window.samples.size > 8_000 && window.samples.all { it == 0f })
                window.owner.consumeVerifiedSilence(window)
            return Update(emptyList(), text.join(segments.map { it.text }), false)
        }
    }
}
