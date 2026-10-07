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
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val snapshotMethod = LipAccessibilityService::class.java.getDeclaredMethod("snapshot").apply { isAccessible = true }
            val commitMethod = LipAccessibilityService::class.java.getDeclaredMethod("commit", EditorSnapshot::class.java, String::class.java, kotlin.Function1::class.java).apply { isAccessible = true }
            var service: LipAccessibilityService? = null
            var original: EditorSnapshot? = null
            val deadline = System.currentTimeMillis() + 15_000
            while (original == null && System.currentTimeMillis() < deadline) {
                runOnMainSync {
                    service = LipAccessibilityService.instance
                    original = service?.let { snapshotMethod.invoke(it) as? EditorSnapshot }
                }
                if (original == null) Thread.sleep(150)
            }
            check(original?.packageName == context.packageName) { "External test editor did not become eligible" }
            val completed = CountDownLatch(1)
            var inserted = false
            runOnMainSync {
                commitMethod.invoke(service, original, "東京你好", { success: Boolean -> inserted = success; completed.countDown() })
            }
            check(completed.await(3, TimeUnit.SECONDS) && inserted) { "Cross-app cursor insertion was not confirmed" }
            runOnMainSync {
                val after = snapshotMethod.invoke(service) as EditorSnapshot
                check(after.text == "Hello 東京你好")
                val stale = CountDownLatch(1)
                commitMethod.invoke(service, original, "MUST NOT INSERT", { success: Boolean -> check(!success); stale.countDown() })
                check(stale.count == 0L)
                check((snapshotMethod.invoke(service) as EditorSnapshot).text == "Hello 東京你好")
            }
            result.putString(REPORT_KEY_STREAMRESULT, "\nOK: native UI, draft state, Keystore round-trip/delete, cross-app selection insertion, stale-target refusal\n")
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
