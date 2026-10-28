package dev.lip

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import dev.lip.core.CompletedDictationSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Disposable emulator only. Synthetic post-recognition producer/lifecycle test,
 * NOT recognition, microphone capture, editor insertion, or a provider send.
 */
class CompletedDictationRunner : Instrumentation() {
    private var mode = "producer"
    private lateinit var dictation: Dictation
    private lateinit var source: CompletedDictationSource
    private val report = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "producer"
        start()
    }

    override fun onStart() {
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            check(targetContext.packageName == "dev.lip.android")
            onMain {
                dictation = Dictation.get(targetContext)
                source = field("completedForAgent").get(dictation) as CompletedDictationSource
            }
            report.putString("scope", "synthetic post-recognition actual Dictation; no audio/editor/provider")
            when (mode) {
                "permission_denied" -> denied()
                "screen_off" -> screenOff()
                "producer" -> producer()
                else -> error("Unknown fixture mode")
            }
            report.putString("result", "PASS")
            finish(Activity.RESULT_OK, report)
        } catch (failure: Throwable) {
            report.putString("result", "FAIL")
            report.putString("failure", failure.javaClass.name + ": " + failure.message)
            report.putString("stack", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, report)
        }
    }

    private fun field(name: String) = Dictation::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) { field(name).set(dictation, value) }
    private fun onMain(block: () -> Unit) = runOnMainSync(block)
    private fun fixtureAttempt(): Long {
        dictation.cancel()
        dictation.setImeAgentProtected(false)
        dictation.setAccessibilityAgentProtected(false)
        set("completedAttempt", source.begin(true, "en-US", "clean"))
        set("sessionStyle", "polished")
        set("sessionLanguage", "en-US")
        set("sessionHistoryTicket", null) // Never retain the synthetic transcript.
        set("phase", Phase.TRANSCRIBING)
        return field("operation").getLong(dictation)
    }

    private fun produce(text: String = "Meet Zoë on Friday.", cleaning: (() -> Unit)? = null): CompletedDictationSource.Record? {
        val done = CountDownLatch(1)
        var ranCleaning = false
        val observer: () -> Unit = {
            if (dictation.phase == Phase.CLEANING && !ranCleaning) { ranCleaning = true; cleaning?.invoke() }
            if (dictation.phase == Phase.READY || dictation.phase == Phase.ERROR) done.countDown()
        }
        onMain {
            val id = fixtureAttempt()
            set("raw", text)
            dictation.observe(observer)
            Dictation::class.java.getDeclaredMethod("finish", String::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(dictation, text, id)
        }
        try { check(done.await(5, TimeUnit.SECONDS)) { "Actual finish callback did not complete" } }
        finally { onMain { dictation.unobserve(observer) } }
        var result: CompletedDictationSource.Record? = null
        onMain { result = dictation.currentCompletedForAgent() }
        return result
    }

    private fun denied() {
        check(targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED)
        onMain {
            source.complete(source.begin(true, "en-US", "clean"), "Synthetic old result")
            check(dictation.currentCompletedForAgent() == null && source.current() == null)
            dictation.start()
            check(dictation.phase == Phase.ERROR && source.current() == null)
        }
        report.putBoolean("actual_permission_denial_and_refused_start", true)
    }

    private fun producer() {
        check(targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val result = produce() ?: error("Successful actual finish did not publish")
        check(result.text == "Meet Zoë on Friday." && result.language == "en-US" && result.modelId == "clean")
        onMain { check(dictation.isCurrentCompletedForAgent(result)) }
        report.putBoolean("successful_finish_exact_payload", true)

        val cleaned = produce("Heading\nformat command new paragraph\nBody") ?: error("Missing cleaned result")
        check(cleaned.text != "Heading\nformat command new paragraph\nBody")
        onMain {
            dictation.useRaw()
            val raw = dictation.currentCompletedForAgent()!!
            check(raw.text == "Heading\nformat command new paragraph\nBody" && !dictation.isCurrentCompletedForAgent(cleaned))
            dictation.useCleaned()
            val again = dictation.currentCompletedForAgent()!!
            check(again !== cleaned && again.text == cleaned.text && !dictation.isCurrentCompletedForAgent(raw))
        }
        report.putBoolean("actual_output_selection_revises_identity", true)

        val late = produce(cleaning = { dictation.protectCapture() })
        check(late == null && source.current() == null)
        report.putBoolean("late_cleaning_after_protection_refused", true)

        produce() ?: error("Missing fixture result")
        onMain {
            set("phase", Phase.INSERTING)
            dictation.cancel()
            check(dictation.phase == Phase.INSERTING && source.current() == null)
        }
        report.putBoolean("inserting_cancel_revokes_before_early_return", true)

        produce() ?: error("Missing fixture result")
        onMain {
            set("phase", Phase.INSERTING)
            dictation.start()
            check(dictation.phase == Phase.INSERTING && source.current() == null)
        }
        report.putBoolean("busy_start_revokes_before_early_return", true)

        for (privacy in listOf("ime", "accessibility", "monitor_failure")) {
            produce() ?: error("Missing fixture result")
            onMain {
                when (privacy) {
                    "ime" -> dictation.setImeAgentProtected(true)
                    "accessibility" -> dictation.setAccessibilityAgentProtected(true)
                    else -> set("privacyMonitorReady", false)
                }
                check(dictation.currentCompletedForAgent() == null && source.current() == null)
                dictation.setImeAgentProtected(false)
                dictation.setAccessibilityAgentProtected(false)
                if (privacy == "monitor_failure") set("privacyMonitorReady", true)
                check(dictation.currentCompletedForAgent() == null)
            }
            report.putBoolean("${privacy}_revokes_without_restore", true)
        }
        produce() ?: error("Missing fixture result")
        onMain {
            set("raw", "Interrupted synthetic recovery")
            Dictation::class.java.getDeclaredMethod("failCapture", String::class.java)
                .apply { isAccessible = true }.invoke(dictation, "Synthetic interrupted recovery")
            check(dictation.phase == Phase.READY && dictation.text.isNotBlank() && source.current() == null)
        }
        report.putBoolean("recovery_ready_not_completed", true)
        onMain { dictation.cancel() }
    }

    private fun screenOff() {
        produce() ?: error("Missing fixture result")
        sendStatus(0, Bundle().apply { putString("progress", "ready_for_owned_emulator_screen_off") })
        val deadline = SystemClock.elapsedRealtime() + 15_000
        // Inspect owner directly: a guarded getter must NOT be what clears it.
        while (source.current() != null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        check(source.current() == null) { "Process receiver did not revoke on real screen-off" }
        report.putBoolean("actual_system_screen_off_process_receiver_revoked", true)
    }
}
