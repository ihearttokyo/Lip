package dev.lip

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Provider-owned public microphone probe; host input/native recording and accuracy are separate gates. */
class PlatformSpeechMicRunner : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        val report = JSONObject().put("event", "platform_speech_mic").put("passed", false)
            .put("scope", "foreground instrumented public microphone protocol; not bubble, phone or cloud")
            .put("backend_changed", false).put("model_download_requested", false)
            .put("external_scoring_required", true).put("scored_result", JSONObject.NULL)
            .put("host_fixture_input_verified", false).put("native_recorder_verified", false)
            .put("raw_selection", "index 1 for exactly two formatted/raw candidates; otherwise index 0")
            .put("transcript_selection", "first full final snapshot if present; otherwise ordered segments, never append final")
        val events = JSONArray()
        val segments = ArrayList<List<String>>()
        val seenSegments = HashSet<List<String>>()
        var finalCandidates: List<String>? = null
        var duplicateSegments = 0
        var readyCallbacks = 0
        var lateCallbacks = 0L
        var rmsCallbacks = 0L
        var bufferCallbacks = 0L
        var bounded = true
        var recognitionError: Int? = null
        var recognizer: SpeechRecognizer? = null
        var home: Activity? = null
        var cleanupOk = true
        var runFailed = false
        var stage = "isolation"
        val canceled = AtomicBoolean()
        val terminal = AtomicReference<String?>()
        val startedAtNs = AtomicLong()
        val readyAtNs = AtomicLong()
        val terminalAtNs = AtomicLong()
        val ready = CountDownLatch(1)
        val completion = CountDownLatch(1)
        val admissionFence = Any()
        fun open() = !canceled.get() && terminal.get() == null
        fun event(name: String, values: List<String>? = null, code: Int? = null) {
            val late = !open()
            if (late) lateCallbacks++
            if (events.length() >= 128) { bounded = false; return }
            val item = JSONObject().put("type", name).put("late_ignored", late)
                .put("callback_ns", SystemClock.elapsedRealtimeNanos())
            if (values != null) item.put("candidates", JSONArray(values)).put("selected_raw", raw(values))
            if (code != null) item.put("code", code)
            events.put(item)
        }
        fun resultEvent(name: String, bundle: Bundle): List<String> {
            val values = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (values.size > 4 || values.any { it.length > 512 }) bounded = false
            return values.take(4).map { it.take(512) }.also { event(name, it) }
        }
        fun complete(requestedOutcome: String) {
            synchronized(admissionFence) {
                val now = SystemClock.elapsedRealtimeNanos()
                val outcome = if (!sessionWithinWindow(startedAtNs.get(), now)) "timeout" else requestedOutcome
                if (terminal.compareAndSet(null, outcome)) {
                    terminalAtNs.set(now)
                    ready.countDown()
                    completion.countDown()
                }
            }
        }
        var fixtureId: String? = null
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle) {
                event("ready")
                synchronized(admissionFence) {
                    if (!open()) return
                    readyCallbacks++
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (!readyWithinWindow(startedAtNs.get(), now)) { complete("ready_timeout"); return }
                    if (readyAtNs.compareAndSet(0L, now)) {
                        val marker = JSONObject().put("event", "public_mic_ready").put("fixture_id", fixtureId)
                            .put("ready_ns", now).put("start_ns", startedAtNs.get())
                            .put("deadline_ns", startedAtNs.get() + MIC_MS * 1_000_000L)
                            .put("readiness_source", "onReadyForSpeech: endpointer-ready, not native PCM/unsilenced proof")
                        sendStatus(1, Bundle().apply {
                            putString("platform_speech_mic_ready", marker.toString())
                            putString(REPORT_KEY_STREAMRESULT,
                                "\nPUBLIC_MIC_READY: onReadyForSpeech; fixture_id=$fixtureId; ready_ns=$now; deadline_ns=${startedAtNs.get() + MIC_MS * 1_000_000L}\n")
                        })
                        ready.countDown()
                    }
                }
            }
            override fun onBeginningOfSpeech() = event("beginning")
            override fun onRmsChanged(rmsdB: Float) { rmsCallbacks++ }
            override fun onBufferReceived(buffer: ByteArray) { bufferCallbacks++ }
            override fun onEndOfSpeech() = event("speech_end")
            override fun onPartialResults(partialResults: Bundle) { resultEvent("partial", partialResults) }
            override fun onSegmentResults(segmentResults: Bundle) {
                val values = resultEvent("segment", segmentResults)
                if (!open()) return
                if (seenSegments.contains(values)) duplicateSegments++
                else if (segments.size < 32) { seenSegments.add(values); segments.add(values) }
                else bounded = false
            }
            override fun onResults(results: Bundle) {
                val values = resultEvent("final", results)
                if (!open()) return
                finalCandidates = values
                complete("results")
            }
            override fun onEndOfSegmentedSession() { event("segmented_end"); if (open()) complete("segmented_end") }
            override fun onError(error: Int) {
                event("error", code = error)
                if (open()) { recognitionError = error; complete("error") }
            }
            override fun onEvent(eventType: Int, params: Bundle) = event("provider_event", code = eventType)
        }
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            check(targetContext.packageName == "dev.lip.android" && Build.VERSION.SDK_INT >= 33)
            check(targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            checkBoundaries()
            stage = "admitted_fixture_argument"
            val id = args.getString("fixture_id")
            val admitted = fixtureArgumentValid(args.keySet(), id)
            report.put("fixture_argument_rejected", !admitted).put("fixture_id_missing", id == null)
                .put("override_keys_rejected", args.keySet() != setOf("fixture_id"))
            check(admitted)
            fixtureId = checkNotNull(id)
            val fixture = checkNotNull(FIXTURES[id])
            report.put("fixture", id).put("source_case_id", id).put("scoring_case_id", id).put("locale", fixture.locale)
            stage = "configured_provider"
            runOnMainSync {
                val key = targetContext.resources.getIdentifier("config_defaultOnDeviceSpeechRecognitionService", "string", "android")
                check(key != 0)
                val configured = ComponentName.unflattenFromString(targetContext.getString(key))
                check(configured == ComponentName.unflattenFromString(PROVIDER) && SpeechRecognizer.isOnDeviceRecognitionAvailable(targetContext))
                @Suppress("DEPRECATION")
                val service = targetContext.packageManager.getServiceInfo(checkNotNull(configured), 0)
                check(service.enabled && service.applicationInfo.enabled)
                report.put("provider", configured.flattenToString()).put("provider_uid", service.applicationInfo.uid)
                    .put("request_uid", targetContext.applicationInfo.uid)
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(targetContext)
            }
            stage = "foreground_main"
            home = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            val focused = AtomicBoolean()
            val focusDeadline = SystemClock.elapsedRealtime() + 3_000L
            while (!focused.get() && SystemClock.elapsedRealtime() < focusDeadline) {
                runOnMainSync { focused.set(home?.hasWindowFocus() == true) }
                if (!focused.get()) Thread.sleep(25)
            }
            check(focused.get())
            report.put("foreground_main_verified", true)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, fixture.locale)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            val ownedRecognizer = checkNotNull(recognizer)
            stage = "installed_locale_support"
            val support = installedSupport(ownedRecognizer, intent, fixture.locale)
            report.put("support", support)
            check(support.optString("outcome") == "support_result" && support.optBoolean("requested_language_installed"))
            stage = "public_microphone"
            runOnMainSync {
                ownedRecognizer.setRecognitionListener(listener)
                startedAtNs.set(SystemClock.elapsedRealtimeNanos())
                ownedRecognizer.startListening(intent)
                report.put("start_listening_requests", 1)
            }
            if (!ready.await(READY_MS, TimeUnit.MILLISECONDS)) complete("ready_timeout")
            val remaining = (MIC_MS - (SystemClock.elapsedRealtimeNanos() - startedAtNs.get()) / 1_000_000L).coerceAtLeast(0L)
            if (!completion.await(remaining, TimeUnit.MILLISECONDS)) complete("timeout")
        } catch (error: Exception) {
            runFailed = true
            if (error is InterruptedException) Thread.currentThread().interrupt()
            report.put("error_stage", stage).put("error_class", error.javaClass.simpleName)
        } finally {
            synchronized(admissionFence) { canceled.set(true) }
            try { runOnMainSync { recognizer?.cancel() } } catch (_: Exception) { cleanupOk = false }
            try { runOnMainSync { recognizer?.destroy() } } catch (_: Exception) { cleanupOk = false }
            try { runOnMainSync { home?.finish() } } catch (_: Exception) { cleanupOk = false }
            try { runOnMainSync {
                val orderedRaw = segments.joinToString(" ") { raw(it) }.trim()
                val selected = finalCandidates?.let(::raw) ?: orderedRaw
                val workflow = !runFailed && cleanupOk && bounded && duplicateSegments == 0 && readyCallbacks == 1 &&
                    readyWithinWindow(startedAtNs.get(), readyAtNs.get()) && terminal.get() == "results" && finalCandidates != null &&
                    sessionWithinWindow(startedAtNs.get(), terminalAtNs.get())
                report.put("events", events).put("events_bounded", bounded).put("ordered_segment_raw", orderedRaw)
                    .put("final_candidates", JSONArray(finalCandidates.orEmpty())).put("selected_raw", selected)
                    .put("candidate_zero_text", finalCandidates?.firstOrNull() ?: segments.joinToString(" ") { it.firstOrNull().orEmpty() }.trim())
                    .put("segment_count", segments.size).put("duplicate_segment_payloads", duplicateSegments)
                    .put("ready_callbacks", readyCallbacks).put("late_callbacks_ignored", lateCallbacks)
                    .put("rms_callback_count", rmsCallbacks).put("buffer_callback_count", bufferCallbacks)
                    .put("start_ns", startedAtNs.get()).put("ready_ns", readyAtNs.get()).put("terminal_ns", terminalAtNs.get())
                    .put("terminal", terminal.get() ?: "not_started_or_exception").put("recognition_error_code", recognitionError ?: JSONObject.NULL)
                    .put("recognition_ms", if (terminalAtNs.get() > 0) (terminalAtNs.get() - startedAtNs.get()) / 1_000_000L else -1)
                    .put("ready_limit_ms", READY_MS).put("microphone_session_limit_ms", MIC_MS)
                    .put("cancel_and_destroy_attempted", true).put("teardown_ok", cleanupOk)
                    .put("workflow_passed", workflow).put("passed", workflow)
            } } catch (error: Exception) {
                report.put("passed", false).put("error_stage", "receipt_snapshot").put("error_class", error.javaClass.simpleName)
            }
        }
        finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("platform_speech_mic", report.toString()) })
    }

    private fun installedSupport(recognizer: SpeechRecognizer, intent: Intent, locale: String): JSONObject {
        val done = CountDownLatch(1)
        val result = AtomicReference<JSONObject?>()
        runOnMainSync {
            recognizer.checkRecognitionSupport(intent, targetContext.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val value = JSONObject().put("outcome", "support_result")
                        .put("installed_on_device_languages", JSONArray(support.installedOnDeviceLanguages))
                        .put("requested_language_installed", support.installedOnDeviceLanguages.any { it.equals(locale, ignoreCase = true) })
                    if (result.compareAndSet(null, value)) done.countDown()
                }
                override fun onError(error: Int) {
                    if (result.compareAndSet(null, JSONObject().put("outcome", "error").put("error_code", error))) done.countDown()
                }
            })
        }
        return if (done.await(15, TimeUnit.SECONDS)) checkNotNull(result.get()) else JSONObject().put("outcome", "timeout")
    }

    private fun checkBoundaries() {
        check(FIXTURES.size == 3 && FIXTURES.keys.all { fixtureArgumentValid(setOf("fixture_id"), it) })
        check(!fixtureArgumentValid(emptySet(), null) && !fixtureArgumentValid(setOf("fixture_id"), "unknown"))
        check(!fixtureArgumentValid(setOf("fixture_id", "locale"), "fleurs-en-013"))
        check(raw(listOf("formatted", "raw")) == "raw" && raw(listOf("first")) == "first")
        check(!sessionWithinWindow(0L, 1L) && !sessionWithinWindow(1L, 0L))
        check(sessionWithinWindow(1L, 30_000_000_001L) && !sessionWithinWindow(1L, 30_000_000_002L))
        check(readyWithinWindow(1L, 12_000_000_001L) && !readyWithinWindow(1L, 12_000_000_002L))
    }
    private fun fixtureArgumentValid(keys: Set<String>, id: String?) = keys == setOf("fixture_id") && id != null && id in FIXTURES
    private fun raw(candidates: List<String>) = if (candidates.size == 2) candidates[1] else candidates.firstOrNull().orEmpty()
    private fun sessionWithinWindow(start: Long, end: Long) = start > 0 && end >= start && end - start <= MIC_MS * 1_000_000L
    private fun readyWithinWindow(start: Long, at: Long) = start > 0 && at >= start && at - start <= READY_MS * 1_000_000L
    private data class Fixture(val locale: String)
    private companion object {
        val FIXTURES = mapOf("fleurs-en-013" to Fixture("en-US"), "fleurs-ja-025" to Fixture("ja-JP"), "fleurs-zh-001" to Fixture("cmn-Hans-CN"))
        const val PROVIDER = "com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService"
        const val READY_MS = 12_000L
        const val MIC_MS = 30_000L
    }
}
