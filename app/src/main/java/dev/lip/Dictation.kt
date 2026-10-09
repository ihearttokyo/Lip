package dev.lip

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.lip.auth.AuthException
import dev.lip.auth.ChatGptClient
import dev.lip.core.CaptureSession
import dev.lip.core.CleanupRules
import dev.lip.core.OutputAssessment
import dev.lip.speech.ModelFile
import dev.lip.speech.LocalCapture
import dev.lip.speech.WhisperEngine
import dev.lip.speech.nativeSpeech
import java.io.File
import java.util.concurrent.Executors

object Work {
    val io = Executors.newFixedThreadPool(2)
    val speech = Executors.newSingleThreadExecutor { task -> Thread(task, "lip-model-lifetime").apply { isDaemon = true } }
}

enum class Phase { IDLE, LISTENING, TRANSCRIBING, CLEANING, INSERTING, READY, ERROR }

class Dictation private constructor(private val context: Context) {
    val store = AppStore(context)
    val chatGpt = ChatGptClient(context)
    val speechModel = ModelFile(File(context.noBackupFilesDir, "speech"))
    private val main = Handler(Looper.getMainLooper())
    private var localCapture: LocalCapture? = null
    @Volatile private var speechEngine: WhisperEngine? = null
    private val speechState = Any()
    private var capture: CaptureSession? = null
    @Volatile private var operation = 0L
    private var insertAction: ((String, (Boolean) -> Unit) -> Unit)? = null
    private var insertValid: () -> Boolean = { false }
    private var attemptedInsertion = false
    private val output = OutputChoices()
    private var cleanedStatus = ""
    private val listeners = mutableSetOf<() -> Unit>()
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
    val canUseRaw get() = !busy && output.raw.isNotBlank()
    val canUseCleaned get() = !busy && !output.cleaned.isNullOrBlank()
    val outputIsRaw: Boolean? get() = if (output.selected.isBlank()) null else output.isRaw
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
        output.reset(); cleanedStatus = ""
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state(Phase.ERROR, "Enable microphone access in Lip first"); return
        }
        val id = ++operation
        sessionLanguage = store.language; sessionStyle = store.style
        capture = CaptureSession(sessionLanguage)
        insertAction = insert; insertValid = stillValid; attemptedInsertion = false
        raw = ""; text = ""; level = 0f; liveClean = ""; liveRevision = -1; liveFailed = false
        state(Phase.LISTENING, "Loading local speech model · microphone off")
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
        val language = sessionLanguage
        val prompt = buildString {
            for (word in dictionary) {
                val extra = (if (isEmpty()) "" else ", ") + word
                if (length + extra.length > WhisperEngine.MAX_PROMPT_CHARACTERS) break
                append(extra)
            }
        }
        // All opens/closes share one queue. Cancelled contexts are never borrowed by a new session.
        Work.speech.execute {
            if (id != operation || phase != Phase.LISTENING) return@execute
            try {
                val existing = synchronized(speechState) { speechEngine }
                val loaded = nativeSpeech { existing ?: WhisperEngine(
                    speechModel.verifiedFile()?.absolutePath ?: error("Install Lip's offline speech model in Microphone settings first."),
                    context.applicationInfo.nativeLibraryDir) }
                val published = synchronized(speechState) {
                    if (id != operation || phase != Phase.LISTENING) false
                    else { speechEngine = loaded; true }
                }
                if (!published) {
                    if (loaded !== existing) loaded.close()
                    return@execute
                }
                main.post {
                    if (id != operation || phase != Phase.LISTENING) return@post
                    try {
                        val recording = nativeSpeech { LocalCapture(context, loaded, language, prompt,
                            onStableSegment = { value -> if (accepting(id)) {
                                capture?.segment(value); updateTranscript(id); schedulePreview(id)
                            } },
                            onPartial = { value -> if (accepting(id)) {
                                capture?.partial(value); updateTranscript(id)
                            } },
                            onEnd = { completeCapture(id) },
                            onError = { status -> if (accepting(id)) failCapture(status) },
                            onLevel = { value -> if (id == operation && phase == Phase.LISTENING) { level = value; changed() } }) }
                        localCapture = recording
                        recording.start()
                        state(Phase.LISTENING, "Listening locally · tap to finish")
                    } catch (_: Exception) { if (accepting(id)) failCapture("Cannot start local speech capture. Transcript kept.") }
                }
            } catch (_: Exception) {
                main.post { if (id == operation && phase == Phase.LISTENING)
                    failCapture("Local speech unavailable. Verify the model in Microphone settings and free device memory.") }
            }
        }
    }

    private fun accepting(id: Long) = id == operation && capture?.ended == false && phase in setOf(Phase.LISTENING, Phase.TRANSCRIBING)
    private fun updateTranscript(id: Long) {
        raw = capture?.transcript.orEmpty()
        if (raw.length > 32_768) { failCapture("Transcript reached the safe text limit. Text kept; start another dictation."); return }
        if (id == operation) changed()
    }

    fun stop() {
        if (phase != Phase.LISTENING) return
        capture?.stop()
        state(Phase.TRANSCRIBING, "Finishing local transcript")
        val recording = localCapture
        if (recording == null) { completeCapture(operation); return }
        recording.finish() // Final decode follows real pipe EOF, including the last accepted packet.
    }

    fun cancel() {
        if (phase == Phase.INSERTING) { message = "Insertion already requested. Check the field; Cancel cannot undo it."; changed(); return }
        operation++
        capture?.cancel(); releaseRecognizer()
        insertAction = null; raw = ""; text = ""
        output.reset(); cleanedStatus = ""
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
        operation++; releaseRecognizer(); text = raw
        output.complete(raw, null); cleanedStatus = ""
        state(if (text.isEmpty()) Phase.ERROR else Phase.READY, status)
        if (text.isNotEmpty()) saveHistory(Transcript(raw = raw, clean = text, language = sessionLanguage, usedChatGpt = false), operation)
    }
    private fun releaseRecognizer(keepWarm: Boolean = false) {
        previewTask?.let(main::removeCallbacks); previewTask = null
        localCapture?.cancel(); localCapture = null
        if (!keepWarm) {
            val previous = synchronized(speechState) { speechEngine.also { speechEngine = null } }
            previous?.let { engine -> Work.speech.execute { engine.close() } }
        }
        level = 0f
    }
    private fun completeCapture(id: Long) {
        if (!accepting(id)) return
        val value = capture?.complete().orEmpty()
        raw = value; releaseRecognizer(keepWarm = true); finish(value, id)
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
            val language = sessionLanguage
            val words = dictionary
            previewOperation = id
            Work.io.execute {
                val outcome = runCatching { if (id == operation) clean(stable, style, language, words, id, live = true) else null }
                val cleaned = outcome.getOrNull()
                main.post {
                    if (previewOperation == id) previewOperation = null
                    if (id != operation) { schedulePreview(operation); return@post }
                    if (phase != Phase.LISTENING || !store.cloudConsent || !store.liveCleanup) return@post
                    if (cleaned == null) { liveFailed = true; message = "Listening locally · ${CleanupRules.cleanupFailure(outcome.exceptionOrNull())} Raw preview kept."; changed(); return@post }
                    if (revision == current.revision && store.cloudConsent && store.liveCleanup) {
                        liveClean = cleaned.value; liveRevision = revision
                        message = if (cleaned.assessment.acceptableForAuto) "Listening · ChatGPT preview" else "Listening · preview needs review: ${cleaned.assessment.reasons.first()}"
                        changed()
                    } else if (revision != current.revision) schedulePreview(id)
                }
            }
        }.also { main.postDelayed(it, 2_000) }
    }

    private data class Cleaned(val value: String, val assessment: OutputAssessment)
    private fun clean(value: String, style: String, language: String, words: List<String>, id: Long, live: Boolean = false): Cleaned? {
        fun permitted() = id == operation && store.cloudConsent && (!live || store.liveCleanup && phase == Phase.LISTENING)
        if (!permitted()) return null
        val session = chatGpt.session() ?: throw AuthException("Continue with ChatGPT to enable cleanup.")
        if (!session.canUsePlan) throw AuthException("Enable ChatGPT plan use by continuing with ChatGPT again.")
        if (!permitted()) return null
        val models = chatGpt.listModels()
        val model = (models.firstOrNull { it.slug == store.model } ?: models.firstOrNull())?.slug
            ?: throw AuthException("No ChatGPT cleanup models are available. Try again later.")
        if (!permitted()) return null
        val cleaned = chatGpt.clean(value, model, style, words)
        require(cleaned.isNotBlank() && cleaned.length <= 65_536)
        return Cleaned(cleaned, CleanupRules.assess(value, cleaned, style, language))
    }

    private fun finish(value: String, id: Long) {
        if (value.isBlank()) { output.reset(); cleanedStatus = ""; state(Phase.ERROR, "No speech detected. Tap to try again."); return }
        val language = sessionLanguage
        val style = sessionStyle
        val words = dictionary
        state(Phase.CLEANING, if (style == "verbatim") "Preparing transcript" else "Cleaning up your words")
        Work.io.execute {
            if (id != operation) return@execute
            val local = CleanupRules.local(value, style, language)
            val outcome = runCatching { if (style == "verbatim") null else clean(value, style, language, words, id) }
            val cleaned = outcome.getOrNull()
            main.post {
                if (id != operation) return@post
                val polished = style != "verbatim" && cleaned != null && store.cloudConsent
                val completed = if (polished) cleaned!!.value else local
                output.complete(value, if (style == "verbatim") null else completed)
                text = completed
                val safeForAuto = style == "verbatim" || polished && cleaned!!.assessment.acceptableForAuto
                cleanedStatus = CleanupRules.cleanupStatus(style, cleaned?.assessment, store.cloudConsent, outcome.exceptionOrNull())
                state(Phase.READY, cleanedStatus)
                val entry = Transcript(raw = value, clean = completed, language = language, usedChatGpt = polished)
                saveHistory(entry, id)
                if (store.autoInsert && safeForAuto && canInsert) insert()
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
    fun useRaw() = useOutput(raw = true)
    fun useCleaned() = useOutput(raw = false)
    private fun useOutput(raw: Boolean) {
        if (output.isRaw == raw) return
        val selected = output.select(raw, busy) ?: return
        text = selected
        message = if (raw) "Raw transcript selected" else cleanedStatus
        changed() // Selection never renews insertion authority or dispatches another commit.
    }
    internal class OutputChoices {
        var raw = ""; private set
        var cleaned: String? = null; private set
        var isRaw = true; private set
        val selected: String get() = if (isRaw) raw else cleaned.orEmpty()
        fun complete(raw: String, cleaned: String?) {
            this.raw = raw; this.cleaned = cleaned; isRaw = cleaned == null
        }
        fun select(raw: Boolean, busy: Boolean): String? {
            if (busy) return null
            val value = (if (raw) this.raw else cleaned)?.takeIf { it.isNotBlank() } ?: return null
            isRaw = raw
            return value
        }
        fun reset() = complete("", null)
    }
    companion object {
        @SuppressLint("StaticFieldLeak") // Holds only the process-lifetime application context.
        @Volatile private var instance: Dictation? = null
        fun get(context: Context): Dictation = instance ?: synchronized(this) {
            instance ?: Dictation(context.applicationContext).also { instance = it }
        }
    }
}
