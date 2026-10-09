package dev.lip

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.accessibilityservice.InputMethod
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.widget.ScrollView
import dev.lip.core.EditorGuard
import dev.lip.core.EditorSnapshot
import kotlin.math.abs

class LipAccessibilityService : AccessibilityService() {
    private var generation = 0L
    private var bubble: LinearLayout? = null
    private var waveform: WaveformView? = null
    private var caption: TextView? = null
    private var mic: View? = null
    private var confirm: TextView? = null
    private var reviewText: TextView? = null
    private var reviewScroll: View? = null
    private var reviewActions: View? = null
    private var rawChoice: Button? = null
    private var cleanedChoice: Button? = null
    private var reviewFeedback: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())
    private val dictation by lazy { Dictation.get(this) }
    private val observer: () -> Unit = { updateBubble() }

    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { dictation.protectCapture() }
    }

    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            generation++
            main.post { updateBubble() }
        }
        override fun onFinishInput() {
            super.onFinishInput()
            generation++
            main.post { updateBubble() }
        }
        override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int,
            newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
            super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
            main.post { updateBubble() }
        }
    }

    override fun onServiceConnected() {
        instance = this
        if (!screenReceiverRegistered) {
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
        dictation.observe(observer)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { updateBubble() }
    override fun onInterrupt() { dictation.interrupt(); removeBubble() }
    override fun onDestroy() {
        instance = null
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false }
        dictation.unobserve(observer)
        if (dictation.busy) dictation.interrupt()
        removeBubble()
        super.onDestroy()
    }

    private fun snapshot(): EditorSnapshot? {
        val method = inputMethod ?: return null
        if (!method.currentInputStarted || method.currentInputConnection == null) return null
        val info = method.currentInputEditorInfo ?: return null
        if (info.packageName == packageName) return null
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) return null
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!node.refresh() || !node.isEditable || !node.isFocused || node.packageName?.toString() != info.packageName) return null
        val sensitive = node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
            passwordType(info.inputType) || info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        if (sensitive) return null
        val contents = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start !in 0..contents.length || end !in 0..contents.length) return null
        return EditorSnapshot(generation, info.packageName, node.windowId,
            "${info.fieldId}:${node.viewIdResourceName}:${node.hashCode()}", contents, start, end)
    }

    private fun protectedScreen(): Boolean {
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) return true
        val info = inputMethod?.currentInputEditorInfo
        if (info != null && (passwordType(info.inputType) || info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)) return true
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return node != null && (node.isPassword || Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
    }

    private fun updateBubble() {
        val target = snapshot()
        if (protectedScreen()) dictation.protectCapture()
        val active = dictation.busy || dictation.phase == Phase.READY && dictation.text.isNotEmpty()
        if (!dictation.store.bubbleEnabled || protectedScreen() || target == null && !active) { removeBubble(); return }
        if (bubble == null) createBubble()
        waveform?.level = dictation.level
        caption?.text = when (dictation.phase) {
            Phase.LISTENING -> "Listening…"
            Phase.TRANSCRIBING -> "Transcribing…"
            Phase.CLEANING -> "Cleaning up…"
            Phase.INSERTING -> "Inserting…"
            Phase.READY -> if (dictation.canInsertHere) "Review · Insert here" else "Text kept · check field"
            Phase.ERROR -> "Check Lip"
            Phase.IDLE -> "Tap to speak"
        }
        mic?.contentDescription = if (dictation.phase == Phase.LISTENING) "Finish dictation" else "Start dictation"
        confirm?.text = when {
            dictation.phase == Phase.LISTENING -> "Finish"
            dictation.phase == Phase.READY -> if (dictation.canInsertHere && target != null) "Insert" else "Copy"
            else -> "Lip"
        }
        confirm?.contentDescription = when {
            dictation.phase == Phase.LISTENING -> "Finish dictation"
            dictation.phase == Phase.READY -> if (dictation.canInsertHere && target != null) "Insert here in the focused field" else "Copy retained transcript"
            else -> "Open Lip"
        }
        val reviewing = dictation.phase == Phase.READY
        val showing = reviewing || dictation.phase == Phase.LISTENING || dictation.phase == Phase.TRANSCRIBING
        reviewScroll?.visibility = if (showing) View.VISIBLE else View.GONE
        reviewActions?.visibility = if (reviewing) View.VISIBLE else View.GONE
        listOf(rawChoice to true, cleanedChoice to false).forEach { (button, raw) ->
            button?.apply {
                isEnabled = if (raw) dictation.canUseRaw else dictation.canUseCleaned
                isSelected = dictation.outputIsRaw == raw
                background = surface(if (isSelected) Palette.lavender else Color.WHITE, dp(14).toFloat(), Color.rgb(218, 215, 208))
            }
        }
        val value = dictation.preview
        val changedText = reviewText?.text?.toString() != value
        reviewText?.text = value
        if (!reviewing && showing && changedText) reviewScroll?.post { (reviewScroll as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
        reviewFeedback?.text = dictation.message
        reviewFeedback?.visibility = if (showing) View.VISIBLE else View.GONE
    }

    private fun createBubble() {
        val manager = getSystemService(WindowManager::class.java)
        val container = column().apply {
            background = surface(Palette.lavender, dp(24).toFloat())
            elevation = dp(8).toFloat()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = surface(Palette.lavender, dp(30).toFloat())
        }
        val dismiss = label("×", 25f).apply {
            gravity = Gravity.CENTER
            contentDescription = "Cancel dictation"
            setOnClickListener { dictation.cancel() }
        }
        content.addView(dismiss, LinearLayout.LayoutParams(dp(48), dp(48)))
        val center = column().apply { gravity = Gravity.CENTER; minimumHeight = dp(56) }
        waveform = WaveformView(this).also { center.addView(it, LinearLayout.LayoutParams(-1, dp(28))) }
        caption = label("Tap to speak", 10f).also { center.addView(it) }
        center.contentDescription = "Start dictation"
        center.isClickable = true
        center.isFocusable = true
        center.setOnClickListener {
            center.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            when {
                dictation.phase == Phase.LISTENING -> dictation.stop()
                dictation.phase == Phase.READY -> Unit
                dictation.busy -> Unit
                else -> {
                    val original = snapshot() ?: return@setOnClickListener
                    dictation.start(insert = { result, done -> commit(original, result, done) },
                        stillValid = { EditorGuard.canInsert(original, snapshot()) })
                }
            }
        }
        mic = center
        content.addView(center, LinearLayout.LayoutParams(0, -2, 1f))
        confirm = label("Lip", 16f).apply {
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener {
                if (dictation.phase == Phase.LISTENING) dictation.stop()
                else if (dictation.phase == Phase.READY) {
                    val target = snapshot()
                    if (target != null && dictation.canInsertHere)
                        dictation.insertHere { result, done -> commit(target, result, done) }
                    else copy(this@LipAccessibilityService, dictation.text)
                }
                else startActivity(Intent(this@LipAccessibilityService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.also { content.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        container.addView(content)
        reviewText = label("", 15f).apply { setPadding(dp(14), dp(10), dp(14), dp(10)) }
        reviewScroll = ScrollView(this).apply {
            isFocusable = false
            background = surface(Palette.cream, dp(14).toFloat())
            addView(reviewText)
        }.also { container.addView(it, LinearLayout.LayoutParams(-1, dp(160)).apply {
            marginStart = dp(5); marginEnd = dp(5); bottomMargin = dp(6)
        }) }
        reviewActions = column().apply {
            val choices = LinearLayout(this@LipAccessibilityService)
            rawChoice = action("Raw") { dictation.useRaw() }.apply { contentDescription = "Raw transcript" }
            cleanedChoice = action("Cleaned") { dictation.useCleaned() }.apply { contentDescription = "Cleaned transcript" }
            choices.addView(rawChoice, LinearLayout.LayoutParams(-2, -2, 1f))
            choices.addView(cleanedChoice, LinearLayout.LayoutParams(-2, -2, 1f).apply { marginStart = dp(8) })
            addSpaced(choices, 4)
            addView(action("Copy") { copy(this@LipAccessibilityService, dictation.text) }, LinearLayout.LayoutParams(-1, -2))
        }.also { container.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        reviewFeedback = label("", 12f).apply {
            setPadding(dp(12), dp(6), dp(12), dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ScrollView(this).apply {
            isFocusable = false
            addView(reviewFeedback)
        }.also { container.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        params = WindowManager.LayoutParams(
            minOf(dp(280), bubbleBounds().width() - dp(24)).coerceAtLeast(1), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dictation.store.settings.getInt("bubbleX", dp(12))
            y = dictation.store.settings.getInt("bubbleY", dp(70))
        }
        container.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val layout = params ?: return@addOnLayoutChangeListener
            val oldX = layout.x; val oldY = layout.y
            constrainBubble(layout, view)
            if (view.isAttachedToWindow && (layout.x != oldX || layout.y != oldY)) manager.updateViewLayout(view, layout)
        }
        installDrag(center, container)
        try { manager.addView(container, params); bubble = container }
        catch (_: Exception) { bubble = null }
    }

    private fun installDrag(handle: View, content: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        handle.setOnTouchListener { _, event ->
            val layout = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = layout.x; startY = layout.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) + abs(event.rawY - downY) > dp(10)) dragging = true
                    if (dragging) {
                        layout.x = startX - (event.rawX - downX).toInt()
                        layout.y = startY + (event.rawY - downY).toInt()
                        constrainBubble(layout, content)
                        getSystemService(WindowManager::class.java).updateViewLayout(content, layout)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) handle.performClick()
                    else dictation.store.settings.edit().putInt("bubbleX", layout.x).putInt("bubbleY", layout.y).apply()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun bubbleBounds(): Rect {
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Rect(metrics.bounds).apply { inset(insets.left, insets.top, insets.right, insets.bottom) }
    }

    private fun constrainBubble(layout: WindowManager.LayoutParams, content: View) {
        val screen = bubbleBounds()
        layout.x = layout.x.coerceIn(0, (screen.width() - content.width).coerceAtLeast(0))
        val maxY = minOf(screen.height() / 3, ((screen.height() - content.height) / 2).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(-maxY, maxY)
    }

    private fun commit(original: EditorSnapshot, text: String, done: (Boolean) -> Unit) {
        val current = snapshot()
        if (!EditorGuard.canInsert(original, current)) { done(false); return }
        val connection = inputMethod?.currentInputConnection ?: run { done(false); return }
        val expected = EditorGuard.expectedInsertion(original.text, original.start, original.end, text)
        connection.commitText(text, 1, null)
        // Accessibility commitText returns void. Do not retry an unconfirmed commit.
        main.postDelayed({
            val observed = snapshot()
            done(observed?.generation == original.generation && observed.fieldId == original.fieldId && observed.text == expected)
        }, 350)
    }

    private fun removeBubble() {
        bubble?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
        bubble = null
        rawChoice = null; cleanedChoice = null
    }

    companion object {
        @SuppressLint("StaticFieldLeak") // System-bound service; cleared in onDestroy, observers detached.
        var instance: LipAccessibilityService? = null; private set
        fun refresh() { instance?.updateBubble() }
        fun passwordType(type: Int): Boolean {
            val kind = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            return (kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }
        fun copy(context: android.content.Context, text: String) {
            val clip = ClipData.newPlainText("Lip dictation", text)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }
    }
}
