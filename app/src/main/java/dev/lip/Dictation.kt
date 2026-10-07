package dev.lip

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.lip.auth.ChatGptClient
import dev.lip.core.CaptureSession
import dev.lip.core.TextRules
import java.util.concurrent.Executors

object Work { val io = Executors.newFixedThreadPool(2) }

enum class Phase { IDLE, LISTENING, TRANSCRIBING, CLEANING, INSERTING, READY, ERROR }

class Dictation private constructor(private val context: Context) {
    val store = AppStore(context)
    val chatGpt = ChatGptClient(context)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var audio: PcmSource? = null
    private var capture: CaptureSession? = null
    @Volatile private var operation = 0L
    private var insertAction: ((String, (Boolean) -> Unit) -> Unit)? = null
    private var insertValid: () -> Boolean = { false }
    private var attemptedInsertion = false
    private val listeners = mutableSetOf<() -> Unit>()
    private var timeout: Runnable? = null
    private var previewTask: Runnable? = null
    private var previewOperation: Long? = null
    private var liveClean = ""
    private var liveRevision = -1L
    private var liveFailed = false
    private var sessionLanguage = "en-US"
    private var sessionStyle = "polished"
    private var dictionary = emptyList<String>()
    @Volatile var phase = Phase.IDLE; private set
    var message = "Tap to speak"; private set
    var raw = ""; private set
    var text = ""; private set
    var level = 0f; private set
    val busy get() = phase in setOf(Phase.LISTENING, Phase.TRANSCRIBING, Phase.CLEANING, Phase.INSERTING)
    val canInsert get() = phase == Phase.READY && !attemptedInsertion && insertAction != null && insertValid()
    val canInsertHere get() = phase == Phase.READY && text.isNotEmpty() && !attemptedInsertion
    val preview: String get() {
        val current = capture
        return if (phase == Phase.LISTENING && current != null && liveRevision == current.revision && liveClean.isNotEmpty())
            current.join(listOf(liveClean, current.current)) else text.ifEmpty { raw }
    }

    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun unobserve(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.toList().forEach { it() } }
    private fun state(value: Phase, status: String) { phase = value; message = status; changed() }

    fun start(insert: ((String, (Boolean) -> Unit) -> Unit)? = null, stillValid: () -> Boolean = { true }) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (busy) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state(Phase.ERROR, "Enable microphone access in Lip first"); return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            state(Phase.ERROR, "On-device speech is unavailable. Install your phone's offline speech service/model."); return
        }
        val id = ++operation
        sessionLanguage = store.language; sessionStyle = store.style
        capture = CaptureSession(sessionLanguage)
        insertAction = insert; insertValid = stillValid; attemptedInsertion = false
        raw = ""; text = ""; level = 0f; liveClean = ""; liveRevision = -1; liveFailed = false
        state(Phase.LISTENING, "Checking offline speech support…")
        Work.io.execute {
            val words = runCatching { store.dictionary() }.getOrDefault(emptyList())
            main.post {
                if (id != operation || phase != Phase.LISTENING) return@post
                dictionary = words
                prepareRecognition(id)
            }
        }
    }

    private fun prepareRecognition(id: Long) {
        try {
            val source = PcmSource(context).also { audio = it }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, sessionLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source.input)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(dictionary))
                if (sessionStyle == "polished") putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY)
            }
            val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (accepting(id) && phase == Phase.LISTENING) { clearTimeout(); state(Phase.LISTENING, "Listening · tap to finish") } }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit // PCM measures the actual local microphone.
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit // An utterance endpoint is not the user's Finish action.
                override fun onPartialResults(results: Bundle?) {
                    if (accepting(id)) {
                        if (phase == Phase.LISTENING) clearTimeout()
                        capture?.partial(words(results)); updateTranscript(id)
                    }
                }
                override fun onSegmentResults(results: Bundle) {
                    if (accepting(id)) {
                        if (phase == Phase.LISTENING) clearTimeout()
                        capture?.segment(words(results)); updateTranscript(id); schedulePreview(id)
                    }
                }
                override fun onEndOfSegmentedSession() {
                    if (!accepting(id)) return
                    if (capture?.finishing == true) completeCapture(id)
                    else failCapture("Offline service ended the continuous session early. Transcript kept; try another supported speech provider.")
                }
                override fun onResults(results: Bundle?) {
                    if (!accepting(id)) return
                    // A provider ignoring segmented mode must not silently become one-utterance dictation.
                    capture?.segment(words(results)); updateTranscript(id)
                    if (capture?.finishing == true) completeCapture(id)
                    else failCapture("This offline service did not honor continuous mode. Transcript kept; a compatible service is required.")
                }
                override fun onError(error: Int) {
                    if (!accepting(id)) return
                    failCapture(when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone unavailable. Grant access, or try inside Lip."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Offline model missing for $sessionLanguage. Install it in Lip's speech settings."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech service ended without a result. Transcript kept."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Finish other recording and try again."
                        else -> "Offline speech stopped (code $error). Transcript kept; check the speech provider."
                    })
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            var started = false
            fun begin() {
                if (started || id != operation || phase != Phase.LISTENING) return
                started = true; clearTimeout()
                try {
                    engine.startListening(intent)
                    source.start({ value -> main.post { if (id == operation && phase == Phase.LISTENING) { level = value; changed() } } },
                        { main.post { if (accepting(id)) failCapture("Microphone interrupted or silenced. Transcript kept.") } })
                    state(Phase.LISTENING, "Microphone on · waiting for offline recognizer")
                    timeout = Runnable { if (accepting(id) && phase == Phase.LISTENING) failCapture("Offline recognizer did not become ready. Transcript kept.") }.also { main.postDelayed(it, 12_000) }
                } catch (_: Exception) { failCapture("Cannot capture microphone audio. Check permission and offline speech support.") }
            }
            timeout = Runnable { if (id == operation && !started) failCapture("Speech support check timed out. Check your offline provider.") }.also { main.postDelayed(it, 12_000) }
            engine.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (!accepting(id)) return
                    if (support.installedOnDeviceLanguages.none { it.equals(sessionLanguage, true) || it.equals(sessionLanguage.substringBefore('-'), true) })
                        failCapture("Offline model missing for $sessionLanguage. Install it in Lip's speech settings.")
                    else begin()
                }
                override fun onError(error: Int) {
                    if (!accepting(id)) return
                    if (error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) begin()
                    else failCapture("Offline provider cannot support this session (code $error). Check speech settings.")
                }
            })
        } catch (_: Exception) { failCapture("Cannot prepare on-device speech. Check microphone and offline model settings.") }
    }

    private fun accepting(id: Long) = id == operation && capture?.ended == false && phase in setOf(Phase.LISTENING, Phase.TRANSCRIBING)
    private fun words(results: Bundle?) = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    private fun updateTranscript(id: Long) {
        raw = capture?.transcript.orEmpty()
        if (raw.length > 32_768) { failCapture("Transcript reached the safe text limit. Text kept; start another dictation."); return }
        if (id == operation) changed()
    }

    fun stop() {
        if (phase != Phase.LISTENING) return
        capture?.stop()
        if (audio?.started != true) { completeCapture(operation); return }
        state(Phase.TRANSCRIBING, "Finishing on-device transcript")
        audio?.finishAudio() // Segmented request ends only when this PCM stream closes.
        clearTimeout()
        if (recognizer == null) { completeCapture(operation); return }
        val id = operation
        timeout = Runnable { if (id == operation && phase == Phase.TRANSCRIBING) failCapture("Speech service did not finish. Partial transcript kept.") }
            .also { main.postDelayed(it, 12_000) }
    }

    fun cancel() {
        if (phase == Phase.INSERTING) { message = "Insertion already requested. Check the field; Cancel cannot undo it."; changed(); return }
        operation++
        capture?.cancel(); recognizer?.cancel(); releaseRecognizer()
        insertAction = null; raw = ""; text = ""
        state(Phase.IDLE, "Canceled · tap to speak")
    }

    fun interrupt() {
        if (busy && phase != Phase.INSERTING) failCapture("Dictation interrupted. Transcript kept locally.")
    }
    fun protectCapture() {
        if (phase in setOf(Phase.LISTENING, Phase.TRANSCRIBING)) failCapture("Microphone stopped for a locked or protected screen. Transcript kept locally.")
    }
    private fun failCapture(status: String) {
        raw = capture?.complete() ?: raw
        operation++; releaseRecognizer(); text = TextRules.normalize(raw)
        state(if (text.isEmpty()) Phase.ERROR else Phase.READY, status)
        if (text.isNotEmpty()) saveHistory(Transcript(raw = raw, clean = text, language = sessionLanguage, usedChatGpt = false), operation)
    }
    private fun clearTimeout() { timeout?.let(main::removeCallbacks); timeout = null }
    private fun releaseRecognizer() {
        clearTimeout(); previewTask?.let(main::removeCallbacks); previewTask = null
        audio?.close(); audio = null
        val previous = recognizer; recognizer = null; previous?.destroy()
        level = 0f
    }
    private fun completeCapture(id: Long) {
        if (!accepting(id)) return
        val value = capture?.complete().orEmpty()
        raw = value; releaseRecognizer(); finish(value, id)
    }

    private fun schedulePreview(id: Long) {
        val current = capture ?: return
        if (phase != Phase.LISTENING || sessionStyle == "verbatim" || !store.cloudConsent || !store.liveCleanup || liveFailed || current.finalized.isBlank() || previewOperation != null) return
        previewTask?.let(main::removeCallbacks)
        // Only stable segments, never every partial hypothesis. One preview request at a time.
        previewTask = Runnable {
            if (id != operation || phase != Phase.LISTENING || !store.cloudConsent || !store.liveCleanup) return@Runnable
            val revision = current.revision
            val stable = current.finalized
            val style = sessionStyle
            val words = dictionary
            previewOperation = id
            Work.io.execute {
                val cleaned = runCatching { if (id == operation) clean(stable, style, words, id, live = true) else null }.getOrNull()
                main.post {
                    if (previewOperation == id) previewOperation = null
                    if (id != operation) { schedulePreview(operation); return@post }
                    if (phase != Phase.LISTENING) return@post
                    if (cleaned == null) { liveFailed = true; message = "Listening · live cleanup unavailable; raw preview kept"; changed(); return@post }
                    if (revision == current.revision && store.cloudConsent && store.liveCleanup) {
                        liveClean = cleaned; liveRevision = revision; changed()
                    } else if (revision != current.revision) schedulePreview(id)
                }
            }
        }.also { main.postDelayed(it, 2_000) }
    }

    private fun clean(value: String, style: String, words: List<String>, id: Long, live: Boolean = false): String? {
        fun permitted() = id == operation && store.cloudConsent && (!live || store.liveCleanup && phase == Phase.LISTENING)
        if (!permitted() || chatGpt.session()?.canUsePlan != true) return null
        if (!permitted()) return null
        val models = chatGpt.listModels()
        val model = (models.firstOrNull { it.slug == store.model } ?: models.firstOrNull())?.slug ?: error("No available model")
        if (!permitted()) return null
        val cleaned = chatGpt.clean(value, model, style, words)
        require(cleaned.isNotBlank() && cleaned.length <= 65_536)
        return cleaned
    }

    private fun finish(value: String, id: Long) {
        val normalized = TextRules.normalize(value)
        if (normalized.isEmpty()) { state(Phase.ERROR, "No speech detected. Tap to try again."); return }
        val language = sessionLanguage
        val style = sessionStyle
        val words = dictionary
        state(Phase.CLEANING, if (style == "verbatim") "Preparing transcript" else "Cleaning up your words")
        Work.io.execute {
            if (id != operation) return@execute
            val cleaned = if (style == "verbatim") normalized else runCatching { clean(normalized, style, words, id) }.getOrNull()
            main.post {
                if (id != operation) return@post
                val polished = style != "verbatim" && cleaned != null && store.cloudConsent
                text = if (style == "verbatim" || polished) cleaned ?: normalized else normalized
                state(Phase.READY, when {
                    style == "verbatim" -> "Ready · on-device transcript"
                    polished -> "Ready · cleaned with ChatGPT"
                    else -> "Cleanup unavailable · raw kept. Review, reconnect, or use raw; nothing auto-inserted."
                })
                val entry = Transcript(raw = value, clean = text, language = language, usedChatGpt = polished)
                saveHistory(entry, id)
                if (store.autoInsert && (polished || style == "verbatim") && canInsert) insert()
            }
        }
    }

    private fun saveHistory(entry: Transcript, id: Long) {
        Work.io.execute {
            try { store.save(entry) }
            catch (_: Exception) { main.post { if (id == operation) { message += " History save failed; text remains available."; changed() } } }
        }
    }

    fun insertHere(action: (String, (Boolean) -> Unit) -> Unit) {
        if (!canInsertHere) return
        insertAction = action; insertValid = { true }; insert()
    }
    fun insert() {
        if (phase != Phase.READY || text.isEmpty() || attemptedInsertion) return
        if (!canInsert) { message = "Original editor changed. Review, then choose Insert here in the intended field."; changed(); return }
        val action = insertAction ?: return
        insertAction = null; attemptedInsertion = true // Never retry an uncertain dispatched commit.
        val id = operation
        var replied = false
        state(Phase.INSERTING, "Inserting completed text")
        val complete: (Boolean) -> Unit = { success -> main.post {
            if (id == operation && !replied) {
                replied = true
                message = if (success) "Inserted · tap for another dictation" else "Insertion unconfirmed. Text kept; check the field before copying."
                phase = if (success) Phase.IDLE else Phase.READY; changed()
            }
        } }
        try { action(text, complete) } catch (_: Exception) { complete(false) }
        main.postDelayed({ complete(false) }, 1_500)
    }
    fun useRaw() {
        if (raw.isNotBlank() && !busy) { text = TextRules.normalize(raw); state(Phase.READY, "Raw transcript selected") }
    }
    companion object {
        @SuppressLint("StaticFieldLeak") // Holds only the process-lifetime application context.
        @Volatile private var instance: Dictation? = null
        fun get(context: Context): Dictation = instance ?: synchronized(this) {
            instance ?: Dictation(context.applicationContext).also { instance = it }
        }
    }
}
