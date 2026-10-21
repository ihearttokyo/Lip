package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.ViewGroup
import android.view.View
import android.view.ViewTreeObserver
import android.view.Choreographer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ScrollView
import dev.lip.auth.SecureStore
import dev.lip.core.EditorSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Uses an isolated emulator. Supplied text is NOT ASR/NOT cloud; UI, insertion and Keystore are real. */
class LipSmokeRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk")) {
                "Smoke fixtures require the disposable emulator"
            }
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
            val dictation = Dictation.get(targetContext)
            checkOutputChoices(dictation, Phase.IDLE, "Copy completed text", result,
                root = { home.window.decorView as ViewGroup },
                preview = { MainActivity::class.java.getDeclaredField("preview").apply { isAccessible = true }.get(home) as TextView })
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
            checkOutputChoices(dictation, Phase.READY, "Copy", result,
                root = { LipAccessibilityService::class.java.getDeclaredField("bubble").apply { isAccessible = true }.get(service) as ViewGroup },
                preview = { LipAccessibilityService::class.java.getDeclaredField("reviewText").apply { isAccessible = true }.get(service) as TextView })
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
            result.putString(REPORT_KEY_STREAMRESULT, "\nOK: native UI, supplied-text Home+bubble Raw/Cleaned Unicode round trip (NOT ASR/NOT cloud), Copy availability, latched insertion authority, draft state, Keystore/history migration+growth over4MiB/delete, cross-app selection insertion, stale-target refusal, explicit rebinding/one-attempt insertion, simulated screen-off retention, local PCM shutdown\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString(REPORT_KEY_STREAMRESULT, "FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun checkOutputChoices(dictation: Dictation, afterAttempt: Phase, copyLabel: String, result: Bundle,
        root: () -> ViewGroup, preview: () -> TextView) {
        val raw = " か\u3099 🙂\ngetUser 你好\n"
        val cleaned = "が 🙂\ngetUser 你好\n"
        fun field(name: String) = Dictation::class.java.getDeclaredField(name).apply { isAccessible = true }
        val notify = Dictation::class.java.getDeclaredMethod("changed").apply { isAccessible = true }
        val names = listOf("phase", "message", "raw", "text", "attemptedInsertion", "insertAction", "insertValid", "cleanedStatus")
        var saved = emptyMap<String, Any?>()
        lateinit var output: Dictation.OutputChoices
        var savedRaw = ""
        var savedCleaned: String? = null
        var savedIsRaw = true
        var savedBubble: Boolean? = null
        var captured = false
        var dispatched = 0
        val forbiddenInsert: (String, (Boolean) -> Unit) -> Unit = { _, _ -> dispatched++ }
        try {
            runOnMainSync {
                check(!dictation.busy)
                saved = names.associateWith { field(it).get(dictation) }
                output = field("output").get(dictation) as Dictation.OutputChoices
                savedRaw = output.raw; savedCleaned = output.cleaned; savedIsRaw = output.isRaw
                val settings = dictation.store.settings
                savedBubble = if (settings.contains("bubble")) settings.getBoolean("bubble", true) else null
                captured = true
                settings.edit().putBoolean("bubble", true).apply()
            }
            for (attempted in listOf(false, true)) {
                val phase = if (attempted) afterAttempt else Phase.READY
                val valid: () -> Boolean = { attempted }
                var operation: Any? = null
                lateinit var surface: ViewGroup
                runOnMainSync {
                    output.complete(raw, cleaned)
                    field("raw").set(dictation, raw); field("text").set(dictation, cleaned)
                    field("phase").set(dictation, phase); field("attemptedInsertion").set(dictation, attempted)
                    field("insertAction").set(dictation, forbiddenInsert); field("insertValid").set(dictation, valid)
                    field("cleanedStatus").set(dictation, "Supplied cleaned candidate · NOT ASR/NOT cloud")
                    operation = field("operation").get(dictation)
                    notify.invoke(dictation)
                    surface = root()
                }
                awaitOutputDraw(surface) // Text changes must lay out before measuring the scroll extent.
                runOnMainSync { flatten(surface).filterIsInstance<ScrollView>().firstOrNull()?.let { scroll ->
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                } }
                awaitOutputDraw(surface)
                lateinit var rawButton: Button
                lateinit var cleanedButton: Button
                lateinit var copy: Button
                lateinit var currentPreview: TextView
                fun selection(isRaw: Boolean) {
                    val value = if (isRaw) raw else cleaned
                    check(dictation.text == value && currentPreview.text.toString() == value) { "Native preview changed candidate bytes" }
                    check(rawButton.isSelected == isRaw && cleanedButton.isSelected != isRaw)
                    check(output.raw == raw && output.cleaned == cleaned)
                    check(dictation.phase == phase && field("attemptedInsertion").get(dictation) == attempted)
                    check(field("operation").get(dictation) == operation)
                    check(field("insertAction").get(dictation) === forbiddenInsert && field("insertValid").get(dictation) === valid)
                    check(!dictation.canInsert && dictation.canInsertHere == !attempted)
                    check(copy.isShown && copy.isEnabled && copy.isClickable && dispatched == 0)
                }
                runOnMainSync {
                    val views = flatten(root())
                    rawButton = views.filterIsInstance<Button>().single { it.contentDescription == "Raw transcript" }
                    cleanedButton = views.filterIsInstance<Button>().single { it.contentDescription == "Cleaned transcript" }
                    copy = views.filterIsInstance<Button>().single { it.text.toString() == copyLabel }
                    listOf(rawButton, cleanedButton, copy).forEach(::requireFullyVisible)
                    check(listOf(rawButton, cleanedButton).all { it.isEnabled && it.height >= targetContext.dp(48) })
                    currentPreview = preview()
                    check(currentPreview in views)
                    requireFullyVisible(currentPreview)
                    selection(false)
                    check(rawButton.performClick()); selection(true)
                    check(cleanedButton.performClick()); selection(false)
                }
                waitForIdleSync()
                if (!attempted && afterAttempt == Phase.IDLE)
                    captureOutputViews("output_home_cleaned_png", listOf(currentPreview, rawButton, cleanedButton, copy), false, result)
                runOnMainSync {
                    check(rawButton.performClick()); selection(true)
                    if (attempted) {
                        dictation.insert()
                        dictation.insertHere { _, _ -> dispatched++ }
                        selection(true) // Neither method dispatched nor rebound the attempted target.
                    }
                }
                waitForIdleSync()
                if (!attempted && afterAttempt == Phase.READY)
                    captureOutputViews("output_bubble_raw_png", listOf(currentPreview, rawButton, cleanedButton, copy), true, result)
            }
        } finally {
            if (captured) runOnMainSync {
                saved.forEach { (name, value) -> field(name).set(dictation, value) }
                output.complete(savedRaw, savedCleaned)
                if (savedIsRaw) output.select(raw = true, busy = false)
                dictation.store.settings.edit().apply {
                    savedBubble?.let { putBoolean("bubble", it) } ?: remove("bubble")
                }.apply()
                notify.invoke(dictation)
            }
        }
    }
    private fun requireFullyVisible(view: View) {
        val visible = Rect()
        check(view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0 &&
            view.getGlobalVisibleRect(visible) && visible.width() == view.width && visible.height() == view.height) {
            "Owned output view is detached or clipped"
        }
    }
    private fun awaitOutputDraw(view: View) {
        val drawn = CountDownLatch(1)
        lateinit var observer: ViewTreeObserver
        lateinit var listener: ViewTreeObserver.OnDrawListener
        var scheduled = false
        runOnMainSync {
            val surface = view.rootView
            check(surface.isAttachedToWindow)
            observer = surface.viewTreeObserver
            listener = ViewTreeObserver.OnDrawListener {
                if (!scheduled) {
                    scheduled = true
                    surface.post {
                        if (observer.isAlive) observer.removeOnDrawListener(listener)
                        Choreographer.getInstance().postFrameCallback { drawn.countDown() }
                    }
                }
            }
            observer.addOnDrawListener(listener)
            surface.invalidate()
        }
        try { check(drawn.await(3, TimeUnit.SECONDS)) { "Owned output view did not draw before capture" } }
        finally { runOnMainSync { if (observer.isAlive) observer.removeOnDrawListener(listener) } }
    }
    private fun captureOutputViews(key: String, views: List<View>, rawSelected: Boolean, result: Bundle) {
        // Queue-idle is not a render fence. Wait for drawing, then verify the presented selection pixels.
        repeat(3) {
            awaitOutputDraw(views.first())
            val bounds = Rect()
            val samples = mutableListOf<Triple<Int, Int, Int>>()
            runOnMainSync {
                views.forEach { view ->
                    requireFullyVisible(view)
                    val position = IntArray(2)
                    view.getLocationOnScreen(position)
                    bounds.union(position[0], position[1], position[0] + view.width, position[1] + view.height)
                    val rawButton = view.contentDescription == "Raw transcript"
                    if (rawButton || view.contentDescription == "Cleaned transcript") {
                        val selected = rawButton == rawSelected
                        check(view.isSelected == selected)
                        val color = if (selected) Palette.lavender else Color.WHITE
                        for (part in 1..3) samples.add(Triple(position[0] + targetContext.dp(8), position[1] + view.height * part / 4, color))
                    }
                }
            }
            // Only the owned public-fixture preview/buttons survive the crop, never the account card.
            val screenshot = try { getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot() }
            catch (error: Exception) {
                result.putString(key, "NOT RUN: screenshot API (${error.javaClass.simpleName})"); return
            }
            if (screenshot == null) { result.putString(key, "NOT RUN: screenshot API returned no pixels"); return }
            var cropped: Bitmap? = null
            var file: java.io.File? = null
            try {
                check(screenshot.width.toLong() * screenshot.height <= 8_000_000)
                check(bounds.width() > 0 && bounds.height() > 0 && Rect(0, 0, screenshot.width, screenshot.height).contains(bounds))
                check(samples.size == 6)
                if (samples.all { (x, y, color) -> screenshot.getPixel(x, y) == color }) {
                    val image = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
                    cropped = image
                    val saved = java.io.File.createTempFile(key, ".png", targetContext.cacheDir)
                    file = saved
                    saved.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    check(saved.length() in 1..2_097_152)
                    result.putString(key, saved.absolutePath)
                    result.putBoolean("${key}_selected_pixels", true)
                    return
                }
            } catch (error: java.io.IOException) {
                file?.delete()
                result.putString(key, "NOT RUN: screenshot export (${error.javaClass.simpleName})"); return
            } finally {
                if (cropped !== screenshot) cropped?.recycle()
                screenshot.recycle()
            }
        }
        error("Presented screenshot did not match current Raw/Cleaned selection pixels")
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
