package dev.lip

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.os.PowerManager
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.lip.core.CaptureSession
import dev.lip.core.CleanupRules
import dev.lip.core.CompletedDictationSource
import dev.lip.speech.BarkProfile
import dev.lip.speech.BarkModel
import dev.lip.speech.BarkLoadingTicket
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
    val speechModel get() = BarkProfile.fromId(store.speechModel).let { BarkModel.file(File(context.noBackupFilesDir, it.directoryName), it) }
    private var engineProfile: BarkProfile? = null
    @Volatile private var loadingTicket: BarkLoadingTicket? = null
    private var sessionProfile = BarkProfile.CLEAN
    private val main = Handler(Looper.getMainLooper())
    private val completedForAgent = CompletedDictationSource()
    private val completedAgentListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var completedAttempt: CompletedDictationSource.Attempt? = null
    @Volatile private var imeAgentProtected = false
    @Volatile private var accessibilityAgentProtected = false
    @Volatile private var privacyMonitorReady = false
    private val agentScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Process owned: protection does not depend on either UI service existing.
            protectCapture()
        }
    }
    /** Metadata only. Fresh own permission checks at begin/publish/read avoid the
     * privileged PackageManager grant/revoke observer API. Process death clears RAM.
     * Never reads an editor, clipboard, saved transcript, or account.
     */
    private fun agentPrivacyAllowed(): Boolean = try {
        privacyMonitorReady && !imeAgentProtected && !accessibilityAgentProtected &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false
    } catch (_: Exception) { false }

    fun revokeCompletedForAgent() {
        val changed = completedAttempt != null || completedForAgent.current() != null
        completedForAgent.revoke()
        completedAttempt = null
        if (changed) completedAgentChanged()
    }
    fun observeCompletedForAgent(listener: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        completedAgentListeners.add(listener)
        runCatching(listener)
    }
    fun unobserveCompletedForAgent(listener: () -> Unit) { completedAgentListeners.remove(listener) }
    private fun completedAgentChanged() {
        val notify = {
            completedAgentListeners.toList().forEach { listener ->
                if (listener in completedAgentListeners) runCatching(listener)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) notify() else main.post { notify() }
    }
    fun revalidateCompletedAgentPrivacy() { if (!agentPrivacyAllowed()) revokeCompletedForAgent() }
    fun currentCompletedForAgent(): CompletedDictationSource.Record? {
        if (!agentPrivacyAllowed()) { revokeCompletedForAgent(); return null }
        return completedForAgent.current()
    }
    fun isCurrentCompletedForAgent(record: CompletedDictationSource.Record): Boolean =
        currentCompletedForAgent() === record && completedForAgent.isCurrent(record)

    fun setImeAgentProtected(protected: Boolean) {
        imeAgentProtected = protected
        if (protected) protectCapture()
    }
    fun setAccessibilityAgentProtected(protected: Boolean) {
        accessibilityAgentProtected = protected
        if (protected) protectCapture()
    }

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
    private var sessionHistoryTicket: HistoryConsentTicket? = null
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

    init {
        privacyMonitorReady = try {
            context.registerReceiver(agentScreenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            true
        } catch (_: Exception) { false } // Recording remains local; handoff fails closed.
    }

    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun unobserve(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.toList().forEach { it() } }
    private fun state(value: Phase, status: String) { phase = value; message = status; changed() }

    fun start(insert: ((String, (Boolean) -> Unit) -> Unit)? = null, stillValid: () -> Boolean = { true }, retainHistory: Boolean = true) {
        check(Looper.myLooper() == Looper.getMainLooper())
        // An explicit refused start also revokes old/ongoing handoff provenance;
        // it must not alter the already-active capture or start another microphone.
        revokeCompletedForAgent()
        if (busy) return
        val language = store.language
        val profile = BarkProfile.fromId(store.speechModel)
        completedAttempt = completedForAgent.begin(agentPrivacyAllowed() && language == "en-US", language, profile.id)
        output.reset(); cleanedStatus = ""
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state(Phase.ERROR, "Enable microphone access in Lip first"); return
        }
        if (language != "en-US") {
            state(Phase.ERROR, "Bark supports English only. Select English in Settings; existing multilingual history is preserved."); return
        }
        loadingTicket?.cancel()
        loadingTicket = BarkLoadingTicket()
        val id = ++operation
        sessionHistoryTicket?.revoke()
        sessionHistoryTicket = if (retainHistory) store.historyTicket() else null
        sessionLanguage = language; sessionStyle = store.style
        sessionProfile = profile
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
        val profile = sessionProfile
        val ticket = loadingTicket ?: return
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
                val previous = synchronized(speechState) {
                    if (engineProfile != profile) speechEngine.also { speechEngine = null; engineProfile = null } else null
                }
                previous?.close()
                val existing = synchronized(speechState) { speechEngine }
                val directory = File(context.noBackupFilesDir, profile.directoryName)
                val model = BarkModel.file(directory, profile)
                // Metadata is only a candidate: checked native loading seals and hashes
                // the selected bytes. Extract missing bundled input on this speech worker.
                if (existing == null && model.candidateForSealedVerification() == null)
                    BarkModel.installBundled(context, directory, profile) { ticket.isCanceled() }
                val loaded = nativeSpeech { existing ?: BarkModel.engine(
                    model.candidateForSealedVerification()?.absolutePath ?: error("Install the selected offline model first."), profile, ticket) }
                val published = synchronized(speechState) {
                    if (id != operation || phase != Phase.LISTENING) false
                    else { speechEngine = loaded; engineProfile = profile; true }
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
                                capture?.segment(value); updateTranscript(id)
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
        revokeCompletedForAgent()
        sessionHistoryTicket?.revoke()
        if (phase == Phase.INSERTING) { message = "Insertion already requested. Check the field; Cancel cannot undo it."; changed(); return }
        operation++
        capture?.cancel(); releaseRecognizer()
        insertAction = null; raw = ""; text = ""
        output.reset(); cleanedStatus = ""
        state(Phase.IDLE, "Canceled · tap to speak")
    }

    fun interrupt() {
        revokeCompletedForAgent()
        sessionHistoryTicket?.revoke()
        if (busy && phase != Phase.INSERTING) failCapture("Dictation interrupted. Transcript kept locally.")
    }
    fun protectCapture() {
        revokeCompletedForAgent()
        sessionHistoryTicket?.revoke()
        if (phase in setOf(Phase.LISTENING, Phase.TRANSCRIBING)) failCapture("Microphone stopped for a locked or protected screen. Transcript kept locally.")
    }
    private fun failCapture(status: String) {
        revokeCompletedForAgent()
        sessionHistoryTicket?.revoke()
        raw = capture?.complete() ?: raw
        operation++; releaseRecognizer(); text = raw
        output.complete(raw, null); cleanedStatus = ""
        state(if (text.isEmpty()) Phase.ERROR else Phase.READY, status)
        // Interrupted/protected/error recovery is MEMORY ONLY. Never auto-retain it.
    }
    private fun releaseRecognizer(keepWarm: Boolean = false) {
        loadingTicket?.cancel(); loadingTicket = null
        previewTask?.let(main::removeCallbacks); previewTask = null
        localCapture?.cancel(); localCapture = null
        if (!keepWarm) {
            val previous = synchronized(speechState) { speechEngine.also { speechEngine = null; engineProfile = null } }
            previous?.let { engine -> Work.speech.execute { engine.close() } }
        }
        level = 0f
    }
    private fun completeCapture(id: Long) {
        if (!accepting(id)) return
        val value = capture?.complete().orEmpty()
        raw = value; releaseRecognizer(keepWarm = true); finish(value, id)
    }





    private fun finish(value: String, id: Long) {
        if (value.isBlank()) { revokeCompletedForAgent(); output.reset(); cleanedStatus = ""; state(Phase.ERROR, "No speech detected. Tap to try again."); return }
        val language = sessionLanguage
        val style = sessionStyle
        val attempt = completedAttempt
        state(Phase.CLEANING, "Preparing transcript locally")
        Work.io.execute {
            if (id != operation) return@execute
            val local = try { CleanupRules.local(value, style, language) }
            catch (_: Exception) {
                main.post { if (id == operation) failCapture("Local transcript formatting failed. Original text kept.") }
                return@execute
            }
            main.post {
                if (id != operation) return@post
                revalidateCompletedAgentPrivacy()
                if (attempt != null && completedForAgent.complete(attempt, local) != null) completedAgentChanged()
                output.complete(value, if (style == "verbatim") null else local)
                text = local
                cleanedStatus = if (local == value) "Ready · on-device transcript" else "Review local format commands · raw kept"
                state(Phase.READY, cleanedStatus)
                saveHistory(Transcript(raw = value, clean = local, language = language, usedChatGpt = false, model = sessionProfile.id), id)
                // Never auto-insert transformed text; retain existing editor/focus guards.
                if (store.autoInsert && local == value && canInsert) insert()
            }
        }
    }

    private fun saveHistory(entry: Transcript, id: Long) {
        val ticket = sessionHistoryTicket ?: return // Off/unverified/incognito start cannot be upgraded later.
        Work.io.execute {
            if (id != operation) return@execute
            try { store.save(entry, ticket) }
            catch (_: Exception) { main.post { if (id == operation && store.historyResultCurrent(ticket)) { message += " History save failed; text remains available."; changed() } } }
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
        revalidateCompletedAgentPrivacy()
        completedAttempt?.let { if (completedForAgent.revise(it, selected) != null) completedAgentChanged() }
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
