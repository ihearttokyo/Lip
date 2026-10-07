package dev.lip

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.lip.auth.ChatGptClient
import dev.lip.core.TextRules
import java.util.concurrent.Executors

object Work { val io = Executors.newFixedThreadPool(2) }

enum class Phase { IDLE, LISTENING, TRANSCRIBING, CLEANING, INSERTING, READY, ERROR }

class Dictation private constructor(private val context: Context) {
    val store = AppStore(context)
    val chatGpt = ChatGptClient(context)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var operation = 0L
    private var insertAction: ((String, (Boolean) -> Unit) -> Unit)? = null
    private val listeners = mutableSetOf<() -> Unit>()
    private var timeout: Runnable? = null
    var phase = Phase.IDLE; private set
    var message = "Tap to speak"; private set
    var raw = ""; private set
    var text = ""; private set
    var level = 0f; private set
    val busy get() = phase in setOf(Phase.LISTENING, Phase.TRANSCRIBING, Phase.CLEANING, Phase.INSERTING)
    val canInsert get() = phase == Phase.READY && insertAction != null

    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun unobserve(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.toList().forEach { it() } }
    private fun state(value: Phase, status: String) { phase = value; message = status; changed() }

    fun start(insert: ((String, (Boolean) -> Unit) -> Unit)? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (busy) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state(Phase.ERROR, "Enable microphone access in Lip first"); return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            state(Phase.ERROR, "On-device speech is unavailable. Install your phone's offline speech service/model."); return
        }
        val id = ++operation
        val language = store.language
        val style = store.style
        insertAction = insert
        raw = ""; text = ""; level = 0f
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { if (id == operation) state(Phase.LISTENING, "Listening · tap to finish") }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) {
                        if (id == operation && phase == Phase.LISTENING) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f); changed() }
                    }
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { if (id == operation) { state(Phase.TRANSCRIBING, "Finishing on-device transcript"); awaitFinal(id) } }
                    override fun onError(error: Int) {
                        if (id != operation) return
                        releaseRecognizer()
                        state(Phase.ERROR, when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone unavailable. Grant access, or try inside Lip."
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Offline speech model missing for $language. Install it in your phone's speech settings."
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap to try again."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Finish other recording and try again."
                            else -> "On-device speech stopped (code $error). Try dictation inside Lip."
                        })
                    }
                    override fun onResults(results: Bundle?) {
                        if (id != operation) return
                        val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        releaseRecognizer()
                        finish(result, id, language, style)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (id == operation) {
                            raw = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                            changed()
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                })
            }
            state(Phase.LISTENING, "Listening · tap to finish")
            timeout = Runnable { if (id == operation) stop() }.also { main.postDelayed(it, 60_000) }
        } catch (_: Exception) {
            releaseRecognizer()
            state(Phase.ERROR, "Cannot start on-device speech. Check microphone and offline model settings.")
        }
    }

    fun stop() {
        if (phase == Phase.LISTENING) {
            state(Phase.TRANSCRIBING, "Finishing on-device transcript")
            recognizer?.stopListening()
            awaitFinal(operation)
        }
    }

    private fun awaitFinal(id: Long) {
        timeout?.let(main::removeCallbacks)
        timeout = Runnable {
            if (id == operation && phase == Phase.TRANSCRIBING) {
                operation++
                releaseRecognizer()
                state(Phase.ERROR, "Speech service did not finish. Partial transcript is still available.")
            }
        }.also { main.postDelayed(it, 12_000) }
    }

    fun cancel() {
        operation++
        recognizer?.cancel()
        releaseRecognizer()
        insertAction = null
        raw = ""; text = ""
        state(Phase.IDLE, "Canceled · tap to speak")
    }

    private fun releaseRecognizer() {
        timeout?.let(main::removeCallbacks); timeout = null
        val previous = recognizer
        recognizer = null
        previous?.destroy()
        level = 0f
    }

    private fun finish(value: String, id: Long, language: String, style: String) {
        raw = value
        val normalized = TextRules.normalize(value)
        if (normalized.isEmpty()) { state(Phase.ERROR, "No speech detected. Tap to try again."); return }
        if (value.length > 16_000) { state(Phase.ERROR, "Dictation is too long. Shorten it; the raw transcript is still available."); return }
        state(Phase.CLEANING, if (style == "verbatim") "Preparing transcript" else "Cleaning up your words")
        Work.io.execute {
            var cleaned = normalized
            var usedPlan = false
            var status = "Ready · on-device transcript"
            try {
                if (style != "verbatim" && store.cloudConsent && chatGpt.session()?.canUsePlan == true) {
                    val models = chatGpt.listModels()
                    val model = models.firstOrNull { it.slug == store.model } ?: models.firstOrNull()
                        ?: error("No available model")
                    val candidate = chatGpt.clean(normalized, model.slug, style, store.dictionary())
                    require(candidate.isNotBlank() && candidate.length <= 32_000)
                    cleaned = candidate
                    store.model = model.slug
                    usedPlan = true
                    status = "Ready · cleaned with ChatGPT"
                } else if (style != "verbatim") status = "Ready · sign in for ChatGPT cleanup"
            } catch (_: Exception) { status = "ChatGPT unavailable · raw transcript kept" }
            main.post {
                if (id != operation) return@post
                text = cleaned
                state(Phase.READY, status)
                val entry = Transcript(raw = value, clean = cleaned, language = language, usedChatGpt = usedPlan)
                Work.io.execute {
                    try { store.save(entry) }
                    catch (_: Exception) { main.post { if (id == operation) { message = "History could not be saved. Text remains available."; changed() } } }
                }
                if (store.autoInsert && insertAction != null) insert()
            }
        }
    }

    fun insert() {
        if (phase != Phase.READY || text.isEmpty()) return
        val action = insertAction ?: run { message = "Copy the text, or dictate into a focused field"; changed(); return }
        insertAction = null // At most one commit attempt: an uncertain commit must never be retried.
        val id = operation
        var replied = false
        state(Phase.INSERTING, "Inserting completed text")
        val complete: (Boolean) -> Unit = { success ->
            main.post {
                if (id == operation && !replied) {
                    replied = true
                    message = if (success) "Inserted · tap for another dictation" else "Editor changed or insertion unconfirmed. Text kept; check the field before copying."
                    phase = if (success) Phase.IDLE else Phase.READY
                    changed()
                }
            }
        }
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
