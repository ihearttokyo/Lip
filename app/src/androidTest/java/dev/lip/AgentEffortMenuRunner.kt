package dev.lip

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.lip.agent.*
import dev.lip.core.CompletedDictationSource
import dev.lip.keyboard.LipInputMethodService
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/** Owned-emulator real IME/controller/popup/input events; synthetic RAM completion and
 * in-process adapter ONLY. No microphone, model, clipboard, history or provider call.
 */
class AgentEffortMenuRunner : Instrumentation() {
    private lateinit var ax: UiAutomation
    private lateinit var settings: AgentHandoffPreferencesStore
    private lateinit var dictation: Dictation
    private var original: AgentHandoffPreferences? = null
    private val report = Bundle()
    private var stage = "setup"
    private var landscape = false
    private var variant = "portrait"
    @Volatile private var sends = 0
    private var deliveredEffort: AgentEffort? = null
    private var longAccountLabel = false
    private var verifiedFixtureWindow = false
    private var stableInputRequired = false
    private var firstGestureRecorded = false
    private var contextChecks = 0
    private var fixtureGeneration = ""
    private var probeActivity: String? = null
    private var probeConnection = 0L
    private val originalRootDirections = LinkedHashMap<View, Int>()
    private val adapter = object : AgentAdapter {
        override val destination by lazy { AgentDestination(AgentRouteChoice.PAIRED_CODEX,
            "Synthetic local agent", "In-process fixture, no network", "public-fixture-only",
            if (longAccountLabel) LONG_ACCOUNT else "Disposable synthetic account", ACTION,
            setOf(AgentEffort.MEDIUM, AgentEffort.HIGH)) }
        override fun dispatch(payload: AgentHandoffBroker.Payload): AgentDispatchOutcome {
            sends++ // Count BEFORE validation: even a wrong unexpected call must fail the control.
            check(payload.text == FIXTURE && payload.language == "en-US" && payload.modelId == "clean")
            check(payload.destination === destination && payload.effort == AgentEffort.HIGH)
            deliveredEffort = payload.effort
            return AgentDispatchOutcome.DISPATCHED
        }
    }
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        variant = arguments?.getString("visualVariant") ?: "portrait"
        if (variant !in setOf("portrait", "landscape", "narrow", "font2", "rtl", "rtl-view", "reduced-motion")) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("failure", "Unknown fixture variant") }); return
        }
        landscape = variant == "landscape"
        longAccountLabel = arguments?.getString("longAccountLabel") == "true"
        start()
    }
    override fun onStart() {
        var code = Activity.RESULT_CANCELED
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk"))
            check(targetContext.packageName == "dev.lip.android")
            check(targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check(targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            ax = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ax.serviceInfo = ax.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            settings = AgentHandoffPreferencesStore(targetContext)
            original = settings.load()
            dictation = Dictation.get(targetContext)
            ui { check(!dictation.busy); check(dictation.currentCompletedForAgent() == null) { "Existing RAM result: refuse to overwrite" } }
            // am instrument force-stops its target, including a preselected IME.
            // The parent explicitly reselects Gboard -> Lip on this OWNED VM only
            // after this handshake. The runner never changes the default keyboard.
            stage = "owned-ime-selection-handshake"
            report.putString("fixture_handshake", "ready_for_owned_ime_selection")
            sendStatus(1, Bundle().apply { putString("progress", "ready_for_owned_ime_selection") })
            waitUntil(10_000) { LipInputMethodService.debugInstance?.get() != null }
            report.putBoolean("owned_ime_rebound_after_instrumentation_start", true)
            val generation = java.util.UUID.randomUUID().toString()
            fixtureGeneration = generation
            targetContext.startActivity(Intent().setClassName("dev.lip.android.test", "dev.lip.ImeFixtureActivity")
                .putExtra("fixtureGeneration", generation).putExtra("landscapeFixture", landscape).putExtra("darkFixture", true)
                .putExtra("agentFocusProbe", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val marker = find("Lip synthetic generation $generation")
            val editor = find("Synthetic ordinary editor")
            check(marker.windowId == editor.windowId)
            verifiedFixtureWindow = true
            // Setup only: resolve the generation-owned AX node, not screen
            // coordinates that can become stale across the requested rotation.
            // No refocus/retry is permitted after stable-input admission below.
            report.putString("initial_focus_fixture_generation", generation)
            report.putInt("initial_focus_node_window", editor.windowId)
            val focusAccepted = editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val clickAccepted = editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            report.putBoolean("initial_AX_focus_accepted", focusAccepted)
            report.putBoolean("initial_AX_click_accepted", clickAccepted)
            check(focusAccepted && clickAccepted) { "Generation-owned initial AX focus/click was refused" }
            waitUntil { LipInputMethodService.debugInstance?.get()?.isInputViewShown == true }
            find("q")
            if (variant == "rtl-view") ui { applyViewRtlFixture() }
            // This runner qualifies an already-stable ordinary editor, NOT cold
            // input startup. One explicit accessibility idle observation only;
            // context departure below fails, never refocuses or retries a gesture.
            ax.waitForIdle(300, 3_000)
            assertOrdinaryContext("stable_input_before_binding")
            stableInputRequired = true
            assertOrdinaryContext("initial_positive_focus_probe")
            check(probeFocus(generation = "wrong-generation", timeoutMs = 250) == null)
            check(probeFocus(action = "$FOCUS_ACTION.wrong", timeoutMs = 250) == null)
            assertOrdinaryContext("positive_focus_probe_after_negative_requests")
            report.putBoolean("focus_probe_wrong_generation_and_action_refused", true)
            report.putString("input_fixture_scope", "stable-input popup usability only; not cold startup qualification")
            lateinit var toolbarBefore: List<Rect>
            ui {
                check(settings.save(AgentHandoffPreferences()))
                service().configureAgentFixture(null)
                toolbarBefore = toolbarBounds()
                check(toolbarBefore.size == 7)
                check(settings.save(AgentHandoffPreferences(AgentRouteChoice.PAIRED_CODEX, true, AgentEffort.MEDIUM)))
                service().configureAgentFixture(adapter)
                check(slot().isEnabled)
                check(toolbarBounds() == toolbarBefore) { "Pin changed familiar seven toolbar positions" }
                val config = service().resources.configuration
                when (variant) {
                    "portrait" -> check(config.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                    "landscape" -> check(config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                    "narrow" -> check(config.screenWidthDp <= 340) { "Narrow variant was not applied" }
                    "font2" -> check(config.fontScale >= 1.99f) { "Font2 variant was not applied" }
                    "rtl" -> check(slot().layoutDirection == View.LAYOUT_DIRECTION_RTL) { "RTL variant was not applied" }
                    "rtl-view" -> check(slot().layoutDirection == View.LAYOUT_DIRECTION_RTL) { "Native View RTL fixture was not applied" }
                    "reduced-motion" -> check(!android.animation.ValueAnimator.areAnimatorsEnabled()) { "Reduced motion was not applied" }
                }
                report.putBoolean("actual_variant_condition_verified", true)
                report.putString("configuration", config.toString())
                report.putFloat("font_scale", config.fontScale)
                report.putInt("layout_direction", slot().layoutDirection)
                report.putBoolean("animators_enabled", android.animation.ValueAnimator.areAnimatorsEnabled())
            }
            checkCount(0); report.putBoolean("seven_toolbar_positions_preserved", true)
            publish()

            stage = "open-and-five-modes"
            open()
            inspectFiveModes()
            screenshot("five-modes")
            clickMode(AgentEffort.HIGH)
            checkCount(0)
            ui { check(settings.load().defaultEffort == AgentEffort.MEDIUM) { "Row selection changed persisted default" } }
            report.putBoolean("open_and_selection_no_dispatch", true)
            back(); waitMenuClosed(); checkCount(0)
            report.putBoolean("back_cancels", true)

            stage = "outside"
            open()
            val frame = Rect()
            ui { slot().getWindowVisibleDisplayFrame(frame) }
            touch(frame.left + 2f, frame.top + 2f)
            waitMenuClosed(); checkCount(0)
            report.putBoolean("outside_cancels", true)

            stage = "actual-touch-cancel"
            open()
            lateinit var cancelRow: View
            ui {
                cancelRow = checkNotNull(rows()[AgentEffort.HIGH])
                cancelRow.requestRectangleOnScreen(Rect(0, 0, cancelRow.width, cancelRow.height), true)
            }
            waitForIdleSync()
            val cancelBounds = viewBounds(cancelRow)
            val cancelDown = SystemClock.uptimeMillis()
            assertOrdinaryContext("cancel_gesture_before")
            event(MotionEvent.ACTION_DOWN, cancelBounds.exactCenterX(), cancelBounds.exactCenterY(), cancelDown)
            event(MotionEvent.ACTION_CANCEL, cancelBounds.exactCenterX(), cancelBounds.exactCenterY(), cancelDown)
            assertOrdinaryContext("cancel_gesture_after")
            waitMenuClosed(); checkCount(0)
            report.putBoolean("actual_ACTION_CANCEL_closes_without_dispatch", true)

            stage = "default-setting-change"
            open()
            lateinit var oldFooter: Button
            ui {
                oldFooter = footer()
                check(settings.save(settings.load().copy(defaultEffort = AgentEffort.HIGH)))
                check(menu() == null) { "Successful preference save did not proactively dismiss popup" }
                check(sends == 0)
                oldFooter.performClick() // Actual old native callback must reject changed settings.
            }
            waitMenuClosed(); checkCount(0)
            ui { check(settings.save(settings.load().copy(defaultEffort = AgentEffort.MEDIUM))) }
            report.putBoolean("settings_change_no_dispatch", true)

            stage = "equal-settings-ABA"
            open()
            ui {
                oldFooter = footer()
                val a = settings.load(); val revision = settings.snapshot().revision
                check(settings.save(a.copy(defaultEffort = AgentEffort.HIGH)))
                check(menu() == null) { "First ABA commit did not proactively dismiss popup" }
                check(settings.save(a)); check(settings.snapshot().revision !== revision)
                check(menu() == null) { "Return ABA commit resurrected popup" }
                oldFooter.performClick()
            }
            waitMenuClosed(); checkCount(0)
            report.putBoolean("settings_ABA_old_footer_refused", true)

            stage = "worker-thread-settings-change"
            open()
            ui { oldFooter = footer() }
            val workerFailure = AtomicReference<Throwable?>()
            val worker = Thread {
                try { check(settings.save(settings.load().copy(defaultEffort = AgentEffort.HIGH))) }
                catch (failure: Throwable) { workerFailure.set(failure) }
            }
            worker.start(); worker.join(5_000)
            check(!worker.isAlive) { "Worker preference save did not finish" }
            workerFailure.get()?.let { throw it }
            // Preference observer posts safely to main. Require dismissal BEFORE
            // touching the retained footer, not merely a lazy click-time guard.
            waitMenuClosed()
            ui { check(menu() == null); oldFooter.performClick() }
            checkCount(0)
            ui { check(settings.save(settings.load().copy(defaultEffort = AgentEffort.MEDIUM))) }
            report.putBoolean("worker_settings_change_proactively_dismisses", true)

            stage = "source-revoked"
            open()
            ui { oldFooter = footer(); dictation.revokeCompletedForAgent(); oldFooter.performClick() }
            waitMenuClosed(); checkCount(0)
            report.putBoolean("source_revocation_old_footer_refused", true)
            publish()

            stage = "anchor-lost"
            open()
            ui {
                oldFooter = footer(); val anchor = slot()
                try { anchor.visibility = View.INVISIBLE; oldFooter.performClick() }
                finally { anchor.visibility = View.VISIBLE }
            }
            waitMenuClosed(); checkCount(0)
            report.putBoolean("anchor_lost_old_footer_refused", true)

            stage = "translated-anchor-pre-layout"
            open()
            ui {
                oldFooter = footer(); val anchor = slot(); val before = anchor.translationX
                try {
                    anchor.translationX = before + 7 * targetContext.resources.displayMetrics.density
                    oldFooter.performClick() // SAME main turn, before GlobalLayout can notify.
                } finally { anchor.translationX = before }
            }
            waitMenuClosed(); checkCount(0)
            report.putBoolean("prelayout_anchor_translation_old_footer_refused", true)

            stage = "sole-explicit-footer"
            open(); clickMode(AgentEffort.HIGH); checkCount(0)
            revealFooter()
            val liveFooter = actualFooter()
            touch(viewBounds(liveFooter))
            waitUntil { sends == 1 }; waitMenuClosed()
            checkCount(1); check(deliveredEffort == AgentEffort.HIGH)
            report.putBoolean("sole_explicit_footer_exact_public_payload_high_once", true)
            ui { liveFooter.performClick() }
            checkCount(1)
            ui { service().configureAgentFixture(adapter) }
            touch(viewBounds(actualSlot())); checkCount(1)
            report.putBoolean("duplicate_after_ui_rebind_refused", true)

            stage = "fresh-controller-and-root"
            ui {
                controller().close()
                val fresh = AgentShortcutController(service(), dictation::currentCompletedForAgent,
                    dictation::isCurrentCompletedForAgent, settings,
                    { route -> (field(service(), "agentFixtureAdapter").get(service()) as? AgentAdapter)?.takeIf { it.destination.route == route } },
                    { service().javaClass.getDeclaredMethod("agentEligible").apply { isAccessible = true }.invoke(service()) as Boolean }, {})
                field(service(), "agentController").set(service(), fresh)
                service().setInputView(service().onCreateInputView())
                service().configureAgentFixture(adapter)
                if (variant == "rtl-view") applyViewRtlFixture()
            }
            waitUntil {
                val view = actualSlot()
                view.isAttachedToWindow && view.isShown &&
                    (variant != "rtl-view" || view.layoutDirection == View.LAYOUT_DIRECTION_RTL && !view.isLayoutRequested && !view.rootView.isLayoutRequested)
            }
            touch(viewBounds(actualSlot())); checkCount(1)
            report.putBoolean("duplicate_after_actual_controller_and_root_recreation_refused", true)
            report.putString("recreation_scope", "In-process actual controller/root recreated by fixture; not process death")
            report.putInt("synthetic_adapter_calls", sends)
            report.putString("variant", variant)
            report.putString("evidence_scope", "Real owned-IME/native popup/controller; synthetic RAM completion and in-process adapter; no provider/mic/ASR")
            report.putString("result", "PASS")
            code = Activity.RESULT_OK
        } catch (failure: Throwable) {
            report.putString("result", "FAIL"); report.putString("stage", stage)
            report.putString("failure", failure.javaClass.name + ": " + failure.message)
            report.putString("stack", failure.stackTraceToString())
            // Diagnostics NEVER replace this original failure with a direct-show
            // success. Gesture admission remains a required actual-input gate.
            runCatching { failureDiagnostics() }.onFailure {
                report.putString("diagnostic_failure", it.javaClass.name + ": " + it.message)
            }
        } finally {
            try {
                ui {
                    originalRootDirections.forEach { (root, direction) ->
                        root.layoutDirection = direction
                        check(root.layoutDirection == direction)
                    }
                    report.putString("root_resolved_directions_restored", originalRootDirections.values.joinToString())
                    originalRootDirections.clear()
                }
                report.putBoolean("native_root_observed_resolved_direction_restored", true)
            } catch (failure: Throwable) {
                code = Activity.RESULT_CANCELED
                report.putString("rtl_fixture_restore_failure", failure.javaClass.name + ": " + failure.message)
            }
            try {
                if (::dictation.isInitialized) ui { dictation.revokeCompletedForAgent() }
                if (LipInputMethodService.debugInstance?.get() != null) ui {
                    service().configureAgentFixture(null)
                    original?.let { check(settings.save(it)) { "Original preferences not restored" } }
                    check(!slot().isEnabled)
                } else original?.let { check(settings.save(it)) }
                report.putBoolean("preferences_restored", original == null || settings.load() == original)
            } catch (failure: Throwable) {
                code = Activity.RESULT_CANCELED; report.putString("cleanup_failure", failure.javaClass.name + ": " + failure.message)
            }
        }
        finish(code, report)
    }

    private fun applyViewRtlFixture() {
        // Only owned native View policy, not an application/OS locale override.
        val root = slot().rootView
        originalRootDirections.putIfAbsent(root, root.layoutDirection)
        report.putString("root_original_resolved_directions", originalRootDirections.values.joinToString())
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        report.putString("rtl_view_scope", "Forced native View RTL fixture; NOT app-locale/Arabic integration. Public observed/resolved direction restored; raw INHERIT/LOCALE policy is not observed.")
    }

    private fun failureDiagnostics() {
        report.putInt("diagnostic_synthetic_adapter_calls", sends)
        if (LipInputMethodService.debugInstance?.get() == null) {
            report.putString("diagnostic_service", "No current debug IME instance")
            return
        }
        ui {
            val anchor = slot()
            val raw = Rect(); val rawVisible = anchor.getGlobalVisibleRect(raw)
            val rootScreen = IntArray(2); anchor.rootView.getLocationOnScreen(rootScreen)
            val slotScreen = IntArray(2); anchor.getLocationOnScreen(slotScreen)
            val slotWindow = IntArray(2); anchor.getLocationInWindow(slotWindow)
            val computed = Rect(raw).apply { offset(rootScreen[0], rootScreen[1]) }
            report.putBoolean("diagnostic_slot_enabled", anchor.isEnabled)
            report.putBoolean("diagnostic_slot_shown", anchor.isShown)
            report.putBoolean("diagnostic_slot_attached", anchor.isAttachedToWindow)
            report.putBoolean("diagnostic_ime_input_shown", service().isInputViewShown)
            report.putBoolean("diagnostic_raw_visible", rawVisible)
            report.putString("diagnostic_raw_global_visible_rect", raw.toShortString())
            report.putString("diagnostic_root_screen_origin", rootScreen.joinToString())
            report.putString("diagnostic_slot_screen_origin", slotScreen.joinToString())
            report.putString("diagnostic_slot_window_origin", slotWindow.joinToString())
            report.putString("diagnostic_computed_touch_bounds", computed.toShortString())
            report.putString("diagnostic_slot_actual_screen_bounds", Rect(slotScreen[0], slotScreen[1],
                slotScreen[0] + anchor.width, slotScreen[1] + anchor.height).toShortString())
            report.putString("diagnostic_gesture_show_result", controller().lastMenuShowResult?.toString() ?: "not-called")
            report.putString("diagnostic_gesture_dismiss_reason", controller().lastMenuDismissReason?.name ?: "none")
            report.putBoolean("diagnostic_gesture_menu_present", menu() != null)
        }
        if (!verifiedFixtureWindow) return // No screenshot of an unrelated startup window.
        if (::ax.isInitialized) screenshot("failure-before-direct-diagnostic")
        ui {
            report.putBoolean("diagnostic_direct_show_return", controller().showModes(slot()))
            report.putString("diagnostic_direct_show_immediate_result", controller().lastMenuShowResult?.toString() ?: "not-called")
            report.putString("diagnostic_direct_immediate_dismiss_reason", controller().lastMenuDismissReason?.name ?: "none")
        }
        SystemClock.sleep(500)
        ui {
            report.putString("diagnostic_direct_settled_show_result", controller().lastMenuShowResult?.toString() ?: "not-called")
            report.putString("diagnostic_direct_settled_dismiss_reason", controller().lastMenuDismissReason?.name ?: "none")
            report.putBoolean("diagnostic_direct_settled_menu_present", menu() != null)
        }
        if (::ax.isInitialized) screenshot("failure-direct-diagnostic")
        report.putBoolean("diagnostic_direct_show_does_not_override_FAIL", true)
    }

    /** Fresh response on the ACTUAL current wrapper; no text/cursor APIs or fake connection creation. */
    private fun probeFocus(action: String = FOCUS_ACTION, generation: String = fixtureGeneration,
        timeoutMs: Long = 2_000): Bundle? {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val nonce = java.util.UUID.randomUUID().toString()
        val done = java.util.concurrent.CountDownLatch(1)
        val response = AtomicReference<Bundle?>()
        val receiver = object : android.os.ResultReceiver(null) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                if (code == 1 && data?.getString("nonce") == nonce && response.compareAndSet(null, Bundle(data))) done.countDown()
            }
        }
        // Marshal the framework base class, not this test APK's anonymous
        // subclass: the remote editor's BootClassLoader cannot resolve our class.
        // The base receiver retains the SAME one-use Binder endpoint/nonce check.
        val parcel = android.os.Parcel.obtain()
        val remoteReceiver = try {
            receiver.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            android.os.ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        var connection: android.view.inputmethod.InputConnection? = null
        ui { connection = service().currentInputConnection }
        val enqueued = checkNotNull(connection).performPrivateCommand(action, Bundle().apply {
            putString("generation", generation); putString("nonce", nonce); putParcelable("agentFocusReceiver", remoteReceiver)
        })
        if (!enqueued) {
            check(action != FOCUS_ACTION || generation != fixtureGeneration) { "Current fixture input connection refused valid probe enqueue" }
            return null // Synchronous malformed-request refusal is a valid negative.
        }
        if (!done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
        return response.get()
    }

    private fun assertOrdinaryContext(label: String) {
        // Keep the initial real AX identity/focus check. A modal popup intentionally
        // can occlude the activity in interactive AX windows; later checks use a
        // fresh metadata witness from the same real InputConnection instead.
        val node = if (!stableInputRequired || label == "first_gesture_before_down")
            find("Synthetic ordinary editor", requireVisible = false) else null
        val witness = if (stableInputRequired) checkNotNull(probeFocus()) { "Fresh focus probe missing/timed out at $label" } else null
        val focused = witness?.getBoolean("hasFocus") ?: checkNotNull(node).isFocused
        if (node != null) check(node.isFocused) { "Initial real AX ordinary editor is not focused" }
        if (witness != null) {
            check(witness.getString("generation") == fixtureGeneration && witness.getBoolean("attached") &&
                witness.getBoolean("activityAlive") && witness.getBoolean("currentFocusNormal") &&
                witness.getBoolean("rootFocusNormal") && witness.getBoolean("hasFocus") && witness.getInt("inputType", -1) == 1 &&
                witness.getInt("imeOptions", -1) and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0)
            val activity = checkNotNull(witness.getString("activityIncarnation")); check(activity.isNotBlank())
            val connection = witness.getLong("connectionIncarnation"); check(connection > 0)
            if (probeActivity == null) { probeActivity = activity; probeConnection = connection }
            check(probeActivity == activity && probeConnection == connection) { "Activity/connection witness incarnation changed" }
        }
        var type = -1; var options = -1; var currentType = -1; var currentOptions = -1
        var sourcePackage = ""; var fieldId = 0
        ui {
            val info = service().currentInputEditorInfo
            type = info?.inputType ?: -1; options = info?.imeOptions ?: -1
            sourcePackage = info?.packageName.orEmpty(); fieldId = info?.fieldId ?: 0
            val policyInfo = field(service(), "info").get(service()) as? android.view.inputmethod.EditorInfo
            currentType = policyInfo?.inputType ?: -1; currentOptions = policyInfo?.imeOptions ?: -1
        }
        val metadata = "focused=$focused package=$sourcePackage field=$fieldId inputType=$type imeOptions=$options policyType=$currentType policyOptions=$currentOptions witness=${witness != null}"
        report.putString("ordinary_context_latest", "$label $metadata")
        contextChecks++; report.putInt("ordinary_context_checks", contextChecks)
        if (label == "stable_input_before_binding") report.putBoolean("initial_real_AX_identity_focus_verified", true)
        if (label == "first_gesture_before_down") {
            report.putString("original_first_gesture_editor_metadata", metadata)
            val actualNode = checkNotNull(node)
            val nodeRect = Rect(); actualNode.getBoundsInScreen(nodeRect)
            report.putString("original_first_gesture_focused_node", "description=${actualNode.contentDescription} class=${actualNode.className} window=${actualNode.windowId} focused=$focused bounds=${nodeRect.toShortString()}")
        }
        check(focused && sourcePackage == "dev.lip.android.test" && type == 1 && currentType == 1 &&
            options and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 &&
            currentOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0) {
            "Ordinary fixture context changed at $label: $metadata; no refocus/retry permitted"
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun service() = checkNotNull(LipInputMethodService.debugInstance?.get())
    private fun controller() = field(service(), "agentController").get(service()) as AgentShortcutController
    private fun slot() = field(service(), "agentSlot").get(service()) as View
    private fun menu() = field(controller(), "menu").get(controller()) as? AgentEffortMenu
    private fun panel() = field(checkNotNull(menu()), "panel").get(menu()) as ScrollView
    @Suppress("UNCHECKED_CAST") private fun rows() = field(checkNotNull(menu()), "rows").get(menu()) as Map<AgentEffort, LinearLayout>
    private fun footer() = field(checkNotNull(menu()), "action").get(menu()) as Button
    private fun actualFooter(): Button { lateinit var view: Button; ui { view = footer() }; return view }
    private fun actualSlot(): View { lateinit var view: View; ui { view = slot() }; return view }
    private fun ui(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        runOnMainSync { try { action() } catch (caught: Throwable) { failure.set(caught) } }
        failure.get()?.let { throw it }
    }
    private fun waitUntil(timeout: Long = 5_000, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        do { if (predicate()) return; SystemClock.sleep(50) } while (SystemClock.uptimeMillis() < until)
        error("Timed out at $stage")
    }
    private fun checkCount(expected: Int) { ui { check(sends == expected) { "Unexpected synthetic dispatches $sends, expected $expected at $stage" } } }
    private fun publish() {
        if (stableInputRequired) assertOrdinaryContext("before_synthetic_publish")
        ui {
            check(!dictation.busy)
            val source = field(dictation, "completedForAgent").get(dictation) as CompletedDictationSource
            val attempt = source.begin(true, "en-US", "clean")
            field(dictation, "completedAttempt").set(dictation, attempt)
            check(source.complete(attempt, FIXTURE) != null)
            dictation.javaClass.getDeclaredMethod("completedAgentChanged").apply { isAccessible = true }.invoke(dictation)
            check(dictation.currentCompletedForAgent()?.text == FIXTURE)
        }
    }
    private fun toolbarBounds(): List<Rect> {
        val toolbar = field(service(), "toolsBar").get(service()) as ViewGroup
        check(toolbar.childCount == 7)
        check(toolbar.getChildAt(1) === slot())
        return (0 until toolbar.childCount).map { child ->
            val view = toolbar.getChildAt(child); val at = IntArray(2); view.getLocationOnScreen(at)
            Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
        }
    }
    private fun open() {
        waitUntil { actualSlot().isAttachedToWindow && actualSlot().isShown && actualSlot().isEnabled }
        val bounds = viewBounds(actualSlot())
        if (!firstGestureRecorded) {
            report.putString("original_first_gesture_touch_bounds", bounds.toShortString())
            assertOrdinaryContext("first_gesture_before_down")
            firstGestureRecorded = true
        } else assertOrdinaryContext("longpress_before")
        val now = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), now)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 120)
        event(MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), now)
        assertOrdinaryContext("longpress_after")
        waitUntil { var present = false; ui { present = menu() != null }; present }
        SystemClock.sleep(230)
        checkCount(0)
    }
    private fun inspectFiveModes() {
        ui {
            check(rows().keys == AgentEffort.entries.toSet())
            if (variant == "rtl-view") {
                check(slot().layoutDirection == View.LAYOUT_DIRECTION_RTL)
                check(panel().getChildAt(0).layoutDirection == View.LAYOUT_DIRECTION_RTL)
                report.putInt("native_popup_scrollview_direction", panel().layoutDirection)
                report.putBoolean("actual_native_anchor_column_RTL", true)
            }
            val account = adapter.destination.accountLabel
            val labels = ArrayList<String>()
            fun collect(view: View) {
                if (view is android.widget.TextView) labels.add(view.text.toString())
                if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
            collect(panel())
            check(labels.any { it.contains(account) }) { "Destination/account disclosure was truncated" }
            report.putInt("account_label_characters_preserved", account.length)
            rows().forEach { (mode, row) ->
                check(row.isEnabled == (mode in setOf(AgentEffort.MEDIUM, AgentEffort.HIGH)))
                check(row.height >= (48 * targetContext.resources.displayMetrics.density).toInt())
                if (!row.isEnabled) check(row.contentDescription.toString().contains("Unavailable"))
                val node = row.createAccessibilityNodeInfo()
                check(node.className.toString() == "android.widget.RadioButton" && node.isCheckable)
                check(node.isChecked == (mode == AgentEffort.MEDIUM))
                check(node.isEnabled == row.isEnabled)
            }
        }
        for (mode in AgentEffort.entries) {
            lateinit var row: View
            ui { row = checkNotNull(rows()[mode]); row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true) }
            waitForIdleSync(); check(!viewBounds(row).isEmpty)
        }
        report.putInt("reachable_mode_count", 5)
        report.putBoolean("unsupported_modes_disabled_with_reason", true)
        report.putBoolean("actual_native_radio_node_semantics_verified", true)
        ui { panel().scrollTo(0, 0) }
    }
    private fun clickMode(mode: AgentEffort) {
        lateinit var row: View
        ui { row = checkNotNull(rows()[mode]); check(row.isEnabled); row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true) }
        waitForIdleSync(); touch(viewBounds(row))
        ui {
            check(row.isSelected)
            val node = row.createAccessibilityNodeInfo()
            check(node.className.toString() == "android.widget.RadioButton" && node.isCheckable && node.isChecked && node.isEnabled)
            rows().forEach { (candidate, view) -> check(view.createAccessibilityNodeInfo().isChecked == (candidate == mode)) }
        }
    }
    private fun revealFooter() {
        ui { val button = footer(); button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true) }
        waitForIdleSync()
    }
    private fun waitMenuClosed() = waitUntil { var closed = false; ui { closed = menu() == null }; closed }
    private fun back() {
        if (stableInputRequired) assertOrdinaryContext("back_before")
        val now = SystemClock.uptimeMillis()
        check(ax.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true))
        check(ax.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true))
        if (stableInputRequired) assertOrdinaryContext("back_after")
    }
    private fun viewBounds(view: View): Rect {
        val result = Rect()
        ui {
            check(view.isAttachedToWindow); check(view.getGlobalVisibleRect(result))
            // getGlobalVisibleRect is ROOT-relative, not screen coordinates. A
            // popup and IME have different window origins; convert explicitly.
            val origin = IntArray(2); view.rootView.getLocationOnScreen(origin)
            result.offset(origin[0], origin[1])
        }
        check(!result.isEmpty); return result
    }
    private fun nodeBounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it); check(!it.isEmpty) }
    private fun touch(bounds: Rect) = touch(bounds.exactCenterX(), bounds.exactCenterY())
    private fun touch(x: Float, y: Float) {
        if (stableInputRequired) assertOrdinaryContext("touch_before")
        val now = SystemClock.uptimeMillis(); event(MotionEvent.ACTION_DOWN, x, y, now)
        event(MotionEvent.ACTION_UP, x, y, now); SystemClock.sleep(100)
        if (stableInputRequired) assertOrdinaryContext("touch_after")
    }
    private fun event(action: Int, x: Float, y: Float, down: Long) {
        val value = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { check(ax.injectInputEvent(value, true)) } finally { value.recycle() }
    }
    private fun find(name: String, requireVisible: Boolean = true): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil {
            val matches = ArrayList<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 30 || matches.size > 1) return
                if ((!requireVisible || node.isVisibleToUser) && (node.contentDescription?.toString() == name || node.text?.toString() == name)) matches.add(node)
                for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
            }
            ax.windows.forEach { window ->
                val root = window.root
                if (root?.packageName?.toString() in setOf("dev.lip.android", "dev.lip.android.test")) visit(root, 0)
            }
            if (matches.size == 1) { found = matches.single(); true } else false
        }
        return checkNotNull(found)
    }
    private fun screenshot(name: String) {
        val image = checkNotNull(ax.takeScreenshot())
        val file = File(targetContext.getExternalFilesDir(null), "agent-$variant-$name.png")
        FileOutputStream(file).use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle(); report.putString("screenshot_$name", file.absolutePath)
    }
    companion object {
        private const val FOCUS_ACTION = "dev.lip.agent.TEST_FOCUS_PROBE"
        private const val FIXTURE = "Public fixture: meet Alex on Friday. 🙂\nNo provider receives this text."
        private const val ACTION = "Run synthetic agent action"
        private val LONG_ACCOUNT = ("Public synthetic account disclosure " + "account identity must remain fully visible ".repeat(8)).take(320)
    }
}
