package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.lip.auth.SecureStore
import dev.lip.core.EditorSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Uses an isolated emulator. Text is supplied; cursor insertion and Keystore are real. */
class LipSmokeRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val store = SecureStore(targetContext)
            store.write("smoke", "你好\nこんにちは")
            check(store.read("smoke") == "你好\nこんにちは")
            store.write("smoke", "")
            check(store.read("smoke") == "")
            store.delete("smoke")
            check(store.read("smoke") == null)

            val history = HistoryRecords(
                { name -> store.read("smoke-$name") },
                { name, payload -> store.write("smoke-$name", payload) },
                { store.names("smoke-history-").map { it.removePrefix("smoke-") } },
                { name -> store.delete("smoke-$name") },
            )
            val legacy = Transcript(raw = "你好\nか\u3099", clean = "你好\nが", language = "ja-JP", usedChatGpt = false)
            store.write("smoke-history", AppStore.encodeHistory(listOf(legacy, legacy)))
            check(history.page().entries == listOf(legacy, legacy))
            check(store.read("smoke-history") == null)
            repeat(2) { index -> history.save(Transcript(raw = "a".repeat(1_100_000), clean = "b".repeat(1_100_000), language = "en-US", usedChatGpt = false, time = index.toLong())) }
            check(history.page(limit = 1).entries.size == 1 && history.page(limit = 1).nextCursor != null)
            check(history.page().entries.size == 4)
            val bytesOnDisk = java.io.File(targetContext.noBackupFilesDir, "encrypted").listFiles().orEmpty()
                .filter { it.name.startsWith("smoke-history-") }.sumOf { it.length() }
            check(bytesOnDisk > 4 * 1024 * 1024) { "Aggregate encrypted history did not exceed former limit" }
            history.clear()
            check(store.names("smoke-history-").isEmpty())

            val home = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            runOnMainSync {
                val views = flatten(home.window.decorView as ViewGroup)
                val editor = views.filterIsInstance<EditText>().single { it.contentDescription == "Dictation test editor" }
                editor.setText("draft 🙂")
                editor.setSelection(6, 8)
                val state = Bundle()
                callActivityOnSaveInstanceState(home, state)
                check(state.getString("scratch") == "draft 🙂" && state.getInt("scratchStart") == 6)
                val history = views.filterIsInstance<TextView>().single { it.text.toString() == "History" }
                history.performClick()
                check(flatten(home.window.decorView as ViewGroup).filterIsInstance<TextView>().any { it.text.toString().contains("kept here") })
            }
            runOnMainSync { home.finish() }
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            // Instrumentation force-stops its target at startup. Rebind only on this disposable test device.
            for (command in listOf(
                "settings put secure enabled_accessibility_services null",
                "settings put secure enabled_accessibility_services dev.lip.android/dev.lip.LipAccessibilityService",
                "settings put secure accessibility_enabled 1",
            )) automation.executeShellCommand(command).use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
            context.startActivity(Intent().setComponent(ComponentName(context.packageName, EditorFixtureActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val snapshotMethod = LipAccessibilityService::class.java.getDeclaredMethod("snapshot").apply { isAccessible = true }
            val commitMethod = LipAccessibilityService::class.java.getDeclaredMethod("commit", EditorSnapshot::class.java, String::class.java, kotlin.Function1::class.java).apply { isAccessible = true }
            var service: LipAccessibilityService? = null
            var original: EditorSnapshot? = null
            val deadline = System.currentTimeMillis() + 15_000
            var stable = 0
            while ((original == null || stable < 3) && System.currentTimeMillis() < deadline) {
                runOnMainSync {
                    service = LipAccessibilityService.instance
                    val current = service?.let { snapshotMethod.invoke(it) as? EditorSnapshot }
                    stable = if (current != null && current == original) stable + 1 else 0
                    original = current
                }
                if (original == null || stable < 3) Thread.sleep(150)
            }
            check(original?.packageName == context.packageName) { "External test editor did not become eligible" }
            val completed = CountDownLatch(1)
            var inserted = false
            runOnMainSync {
                commitMethod.invoke(service, original, "東京你好", { success: Boolean -> inserted = success; completed.countDown() })
            }
            check(completed.await(3, TimeUnit.SECONDS) && inserted) { "Cross-app cursor insertion was not confirmed: original=$original" }
            runOnMainSync {
                val after = snapshotMethod.invoke(service) as EditorSnapshot
                check(after.text == "Hello 東京你好")
                val stale = CountDownLatch(1)
                commitMethod.invoke(service, original, "MUST NOT INSERT", { success: Boolean -> check(!success); stale.countDown() })
                check(stale.count == 0L)
                check((snapshotMethod.invoke(service) as EditorSnapshot).text == "Hello 東京你好")
            }
            // Fresh-target insertion must be explicit; an old target remains invalid.
            val dictation = Dictation.get(targetContext)
            fun setState(name: String, value: Any) {
                Dictation::class.java.getDeclaredField(name).apply { isAccessible = true }.set(dictation, value)
            }
            val rebound = CountDownLatch(1)
            runOnMainSync {
                setState("phase", Phase.READY); setState("text", " 新しい"); setState("attemptedInsertion", false)
                setState("insertAction", { text: String, done: (Boolean) -> Unit -> commitMethod.invoke(service, original, text, done) })
                setState("insertValid", { false })
                dictation.insert()
                check(dictation.phase == Phase.READY && dictation.canInsertHere && !dictation.canInsert)
                val fresh = snapshotMethod.invoke(service) as EditorSnapshot
                dictation.insertHere { text, done ->
                    commitMethod.invoke(service, fresh, text, { success: Boolean -> done(success); rebound.countDown() })
                }
                dictation.cancel() // A dispatched commit cannot be undone or described as canceled.
                check(dictation.phase == Phase.INSERTING && dictation.message.contains("cannot undo"))
            }
            check(rebound.await(3, TimeUnit.SECONDS))
            waitForIdleSync()
            runOnMainSync {
                check((snapshotMethod.invoke(service) as EditorSnapshot).text == "Hello 東京你好 新しい")
                var repeated = false
                dictation.insertHere { _, _ -> repeated = true }
                check(!repeated)
            }
            runOnMainSync {
                val session = dev.lip.core.CaptureSession("en-US").apply { segment("retained before lock") }
                setState("capture", session); setState("phase", Phase.LISTENING); setState("text", "")
                val screenOff = LipAccessibilityService::class.java.getDeclaredField("screenReceiver").apply { isAccessible = true }
                    .get(service) as android.content.BroadcastReceiver
                screenOff.onReceive(service, Intent(Intent.ACTION_SCREEN_OFF))
                check(dictation.phase == Phase.READY && dictation.raw == "retained before lock")
                check(dictation.message.contains("locked or protected"))
                dictation.cancel()
            }
            // Real AudioRecord/pipe lifecycle on the disposable emulator, not human speech.
            automation.executeShellCommand("pm grant dev.lip.android android.permission.RECORD_AUDIO").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
            val pcm = PcmSource(targetContext)
            val bytes = AtomicInteger()
            val captured = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val failures = AtomicInteger()
            val reader = Thread {
                try {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pcm.input).use { input ->
                        val buffer = ByteArray(1280)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (bytes.addAndGet(count) >= 32_000) captured.countDown()
                        }
                    }
                } finally { stopped.countDown() }
            }.apply { start() }
            runOnMainSync { pcm.start({}, { failures.incrementAndGet() }) }
            check(captured.await(5, TimeUnit.SECONDS)) { "Local PCM stream did not deliver one second of audio" }
            runOnMainSync { pcm.finishAudio() }
            check(stopped.await(3, TimeUnit.SECONDS)) { "Finish did not close the PCM stream" }
            pcm.close(); reader.join(1000)
            check(failures.get() == 0)
            result.putString(REPORT_KEY_STREAMRESULT, "\nOK: native UI, draft state, Keystore/history migration+growth over4MiB/delete, cross-app selection insertion, stale-target refusal, explicit rebinding/one-attempt insertion, simulated screen-off retention, local PCM shutdown\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString(REPORT_KEY_STREAMRESULT, "FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun flatten(root: ViewGroup): List<android.view.View> = (0 until root.childCount).flatMap { index ->
        val view = root.getChildAt(index)
        listOf(view) + if (view is ViewGroup) flatten(view) else emptyList()
    }
}

class EditorFixtureActivity : Activity() {
    private lateinit var editor: EditText
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        editor = EditText(this).apply {
            setText("Hello world")
            setSelection(6, 11)
            requestFocus()
        }
        setContentView(editor)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) editor.post {
            val manager = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            editor.requestFocus()
            manager.restartInput(editor)
            manager.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
