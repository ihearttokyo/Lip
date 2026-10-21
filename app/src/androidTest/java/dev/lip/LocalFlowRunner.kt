package dev.lip

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import dev.lip.speech.ModelFile
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Main in-app cursor flow; not a bubble/cross-app or general speech-quality test. */
class LocalFlowRunner : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val report = JSONObject().put("passed", false).put("scope", "Main in-app cursor flow")
            .put("bubble_tested", false).put("cross_app_tested", false).put("speech_perfection_claim", false)
        var stage = "fixture_arguments"
        var home: Activity? = null
        var controller: Dictation? = null
        var store: AppStore? = null
        var previous: Map<String, *>? = null
        var ownedOperation: Long? = null
        var cleanupOk = true
        try {
            check(args.getString("fixture_sha256") == FIXTURE_SHA256)
            val streamMs = args.getString("stream_ms")?.toLong() ?: 7_540L
            check(streamMs in 7_540L..20_000L)
            report.put("fixture", "fleurs-en-013").put("fixture_source_sha256", FIXTURE_SHA256)
                .put("played_waveform_verification", "parent-owned authenticated injector").put("stream_ms", streamMs)
            stage = "isolation_and_permission"
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            check(targetContext.packageName == "dev.lip.android")
            check(targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            val fixture = floatArrayOf(-1f, -1f / 32768f, 0f, 1f / 32768f, 32767f / 32768f)
            val before = fixture.copyOf()
            check(pcm16le(fixture).contentEquals(byteArrayOf(0, -128, -1, -1, 0, 0, 1, 0, -1, 127)) && fixture.contentEquals(before))
            check(pcm16le(floatArrayOf()).isEmpty())
            check(listOf(Float.NaN, Float.POSITIVE_INFINITY, 1f, -1.1f, 0.1f).all { runCatching { pcm16le(floatArrayOf(it)) }.isFailure })
            report.put("pcm_witness_codec_fixture", true)
            val dictation = Dictation.get(targetContext).also { controller = it }
            val settings = dictation.store.also { store = it }
            check(!dictation.busy)
            stage = "verified_model"
            check(dictation.speechModel.verifiedFile() != null)
            report.put("model_verified", true).put("model_sha256", ModelFile.SHA256)
            previous = settings.settings.all.filterKeys { it in PREFS }
            settings.language = "en-US"; settings.style = "verbatim"; settings.cloudConsent = false
            settings.liveCleanup = false; settings.autoInsert = false; settings.historyEnabled = true
            val historyBefore = history(settings).map { it.id }.toSet()
            stage = "foreground_editor"
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { home = it }
            await(3_000) { var focused = false; runOnMainSync { focused = activity.hasWindowFocus() }; focused }
            lateinit var editor: EditText
            runOnMainSync {
                editor = views(activity.window.decorView).filterIsInstance<EditText>()
                    .single { it.contentDescription == "Dictation test editor" }
                editor.setText(SEED); editor.requestFocus(); editor.setSelection(5, 8)
                check(button(activity, "Try dictation").performClick())
                ownedOperation = field(dictation, "operation") as Long
            }
            stage = "actual_microphone_ready"
            await(60_000) {
                var ready = false
                runOnMainSync { ready = microphoneReady(dictation, report) }
                ready
            }
            val readyNs = SystemClock.elapsedRealtimeNanos()
            report.put("ready_elapsed_ns", readyNs).put("ready_elapsed_ms", readyNs / 1_000_000)
                .put("phase", dictation.phase.name)
            sendStatus(1, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, "\nLOCAL_FLOW_READY $report\n") })
            stage = "record_authenticated_guest_stream"
            val finishAt = readyNs + (streamMs + 2_000) * 1_000_000
            while (SystemClock.elapsedRealtimeNanos() < finishAt) {
                check(dictation.phase == Phase.LISTENING)
                Thread.sleep(minOf(100, (finishAt - SystemClock.elapsedRealtimeNanos()) / 1_000_000).coerceAtLeast(1))
            }
            val heldMs = (SystemClock.elapsedRealtimeNanos() - readyNs) / 1_000_000
            check(heldMs >= streamMs + 2_000)
            report.put("capture_after_ready_ms", heldMs)
            stage = "actual_finish_button"
            val stopped = SystemClock.elapsedRealtime()
            runOnMainSync { check(button(activity, "Finish dictation").performClick()) }
            try { await(15_000) { dictation.phase == Phase.READY || dictation.phase == Phase.ERROR } }
            catch (timeout: IllegalStateException) {
                report.put("latency_gate_passed", false).put("stop_at_deadline_ms", SystemClock.elapsedRealtime() - stopped)
                try {
                    runOnMainSync {
                        report.put("phase_at_deadline", dictation.phase.name).put("phase_at_deadline_message", dictation.message)
                        check(field(dictation, "operation") == ownedOperation)
                        report.put("capture_at_deadline", captureState(dictation))
                    }
                    runCatching { await(120_000) { var ended = false
                        runOnMainSync { ended = field(dictation, "operation") != ownedOperation || dictation.phase in setOf(Phase.READY, Phase.ERROR) }
                        ended } }.onFailure { report.put("diagnostic_error_class", it.javaClass.simpleName) }
                    runOnMainSync {
                        val owned = field(dictation, "operation") == ownedOperation
                        report.put("diagnostic_owned", owned).put("diagnostic_phase", dictation.phase.name).put("diagnostic_message", dictation.message)
                            .put("diagnostic_stop_elapsed_ms", SystemClock.elapsedRealtime() - stopped)
                        if (owned) report.put("diagnostic_raw", dictation.raw)
                    }
                } catch (error: Exception) { report.put("diagnostic_error_class", error.javaClass.simpleName) }
                finally { throw timeout } // Late native text is evidence, never a deadline pass.
            }
            report.put("stop_to_ready_ms", SystemClock.elapsedRealtime() - stopped)
            check(dictation.phase == Phase.READY)
            lateinit var raw: String
            stage = "fixture_facts_and_native_preview"
            runOnMainSync {
                raw = dictation.raw
                report.put("raw", raw).put("ready_phase", dictation.phase.name)
                check(dictation.message == "Ready · on-device transcript" && field(dictation, "operation") == ownedOperation)
                check(words(raw) == words(REFERENCE))
                check(listOf("three", "none", "hurt").all { anchor -> words(raw).count { it == anchor } == 1 })
                check(dictation.text == raw && dictation.preview == raw)
                check((field(activity, "preview") as TextView).text.toString() == raw)
                check(editor.text.toString() == SEED && editor.selectionStart == 5 && editor.selectionEnd == 8)
                check(dictation.canInsert)
            }
            report.put("reference_words_once", true).put("native_preview_matches_raw", true).put("no_auto_insert", true)
            stage = "actual_manual_insert"
            runOnMainSync { check(button(activity, "Insert").performClick()) }
            await(2_000) { dictation.phase == Phase.IDLE }
            runOnMainSync { check(editor.text.toString() == SEED.substring(0, 5) + raw + SEED.substring(8)) }
            report.put("selected_field_replacement", true).put("phase", dictation.phase.name)
            stage = "real_encrypted_history"
            var added = emptyList<Transcript>()
            await(5_000) {
                added = history(AppStore(targetContext)).filter { it.id !in historyBefore }
                added.isNotEmpty()
            }
            check(added.size == 1 && added.single().raw == raw && added.single().clean == raw &&
                added.single().language == "en-US" && !added.single().usedChatGpt)
            val settled = CountDownLatch(1)
            Work.io.execute { settled.countDown() }
            check(settled.await(3, TimeUnit.SECONDS))
            check(history(settings).count { it.id !in historyBefore } == 1)
            report.put("new_history_entries", 1).put("reopened_encrypted_history_matches_raw", true)
            stage = "model_setup_owner_on_destroy"
            verifySetupOwnerClosure(activity)
            report.put("on_destroy_closes_owned_setup", true).put("setup_test", "owned callback only; no download")
            report.put("passed", true)
        } catch (error: Exception) {
            report.put("error_stage", stage).put("error_class", error.javaClass.simpleName)
                .put("phase", controller?.phase?.name ?: "not_initialized")
        }
        finally {
            try { runOnMainSync {
                controller?.let { if (ownedOperation != null && field(it, "operation") == ownedOperation) it.cancel() }
                home?.let { if (!it.isDestroyed) it.finish() }
            } } catch (_: Exception) { cleanupOk = false }
            try { if (ownedOperation != null) {
                val closed = CountDownLatch(1)
                Work.speech.execute { closed.countDown() }
                if (!closed.await(15, TimeUnit.SECONDS)) cleanupOk = false
            } } catch (_: Exception) { cleanupOk = false }
            try {
                previous?.let { saved ->
                    val edit = store!!.settings.edit()
                    for (key in PREFS) when (val value = saved[key]) {
                        is String -> edit.putString(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        null -> edit.remove(key)
                        else -> error("Unexpected test preference type")
                    }
                    check(edit.commit())
                }
            } catch (_: Exception) { cleanupOk = false }
            report.put("cleanup_ok", cleanupOk).put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000)
            if (!cleanupOk) report.put("passed", false)
        }
        finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString(REPORT_KEY_STREAMRESULT, "\nLOCAL_FLOW_RESULT $report\n") })
    }

    private fun microphoneReady(dictation: Dictation, report: JSONObject): Boolean {
        if (dictation.phase != Phase.LISTENING) return false
        val capture = field(dictation, "localCapture") ?: return false
        val snapshot = synchronized(field(capture, "state")!!) {
            (field(capture, "acceptedBytes") as Long) to field(capture, "source")
        }
        val source = snapshot.second ?: return false
        val microphone = field(source, "microphone") as AudioRecord
        val config = microphone.activeRecordingConfiguration ?: return false
        val recording = microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING
        report.put("microphone_accepted_bytes", snapshot.first).put("microphone_recording", recording)
            .put("client_silenced", config.isClientSilenced)
        return snapshot.first >= 3_200 && recording && !config.isClientSilenced
    }
    private fun captureState(dictation: Dictation): JSONObject {
        val capture = field(dictation, "localCapture") ?: return JSONObject().put("present", false)
        lateinit var samples: FloatArray
        val result = synchronized(field(capture, "state")!!) {
            val result = JSONObject().put("present", true)
            for (key in listOf("decoding", "finishing", "inputEnded", "acceptedBytes", "scheduledBytes")) result.put(key, field(capture, key))
            val pcm = field(capture, "pcm")!!
            synchronized(pcm) {
                val count = field(pcm, "count") as Int
                val buffer = field(pcm, "samples") as FloatArray
                check(count in 0..480_000 && count <= buffer.size)
                samples = buffer.copyOf(count)
                result.put("pcm_count", count).put("pcm_startSample", field(pcm, "startSample")).put("pcm_ended", field(pcm, "ended"))
            }
            result
        }
        val bytes = pcm16le(samples) // Private copy; disk work never holds the capture or PCM locks.
        val file = File.createTempFile("lip-public-pcm-", ".pcm", targetContext.cacheDir)
        file.writeBytes(bytes)
        check(file.length() == bytes.size.toLong())
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return result.put("pcm_witness_path", file.absolutePath).put("pcm_witness_sha256", sha).put("pcm_witness_bytes", bytes.size)
    }

    private fun pcm16le(samples: FloatArray): ByteArray {
        require(samples.size <= 480_000)
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in samples) {
            require(value.isFinite() && value in -1f..1f)
            val sample = (value * 32768f).toInt()
            require(sample in Short.MIN_VALUE..Short.MAX_VALUE && sample / 32768f == value)
            bytes.putShort(sample.toShort())
        }
        return bytes.array()
    }

    private fun verifySetupOwnerClosure(activity: Activity) {
        val calls = AtomicInteger()
        runOnMainSync {
            val owner = MainActivity::class.java.getDeclaredField("cancelModelSetup").apply { isAccessible = true }
            check(owner.get(activity) == null)
            val cancel: () -> Unit = { calls.incrementAndGet(); Unit }
            owner.set(activity, cancel)
            activity.finish()
        }
        await(3_000) { var destroyed = false; runOnMainSync { destroyed = activity.isDestroyed }; destroyed }
        check(calls.get() == 1 && field(activity, "cancelModelSetup") == null)
    }

    private fun history(store: AppStore): List<Transcript> {
        val rows = mutableListOf<Transcript>()
        var cursor: String? = null
        repeat(20) {
            val page = store.historyPage(after = cursor, limit = 100)
            rows += page.entries
            cursor = page.nextCursor ?: return rows
        }
        error("Test history scope exceeds 2000 entries")
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).run { isAccessible = true; get(owner) }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(home: Activity, text: String) = views(home.window.decorView).filterIsInstance<Button>().single { it.text.toString() == text }
    private fun words(value: String) = Regex("[a-z0-9]+").findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList()
    private fun await(milliseconds: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + milliseconds
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(25) }
        check(condition()) { "Local flow condition timed out" }
    }
    companion object {
        private val PREFS = listOf("language", "style", "cloudConsent", "liveCleanup", "autoInsert", "history")
        private const val SEED = "left OLD right"
        private const val REFERENCE = "Although three people were inside the house when the car impacted it, none of them were hurt."
        private const val FIXTURE_SHA256 = "431d013d01d25cbf8d42587f4a14df57d09dbe9cbc2af4ea91ba93f9bd290ec4"
    }
}
