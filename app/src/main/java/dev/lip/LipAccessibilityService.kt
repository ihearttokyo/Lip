package dev.lip

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.accessibilityservice.InputMethod
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
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
    private var reviewFeedback: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())
    private val dictation by lazy { Dictation.get(this) }
    private val observer: () -> Unit = { updateBubble() }

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
        dictation.observe(observer)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { updateBubble() }
    override fun onInterrupt() { dictation.cancel(); removeBubble() }
    override fun onDestroy() {
        instance = null
        dictation.unobserve(observer)
        if (dictation.busy) dictation.cancel()
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

    private fun updateBubble() {
        val target = snapshot()
        if (!dictation.store.bubbleEnabled || target == null) { removeBubble(); return }
        if (bubble == null) createBubble()
        waveform?.level = dictation.level
        caption?.text = when (dictation.phase) {
            Phase.LISTENING -> "Listening…"
            Phase.TRANSCRIBING -> "Transcribing…"
            Phase.CLEANING -> "Cleaning up…"
            Phase.INSERTING -> "Inserting…"
            Phase.READY -> if (dictation.canInsert) "Review, then tap ✓" else "Text kept · check field"
            Phase.ERROR -> "Check Lip"
            Phase.IDLE -> "Tap to speak"
        }
        mic?.contentDescription = if (dictation.phase == Phase.LISTENING) "Finish dictation" else "Start dictation"
        confirm?.text = if (dictation.phase == Phase.READY) { if (dictation.canInsert) "✓" else "Copy" } else "Lip"
        confirm?.contentDescription = if (dictation.phase == Phase.READY) { if (dictation.canInsert) "Insert completed dictation" else "Copy retained transcript" } else "Open Lip"
        val reviewing = dictation.phase == Phase.READY
        reviewScroll?.visibility = if (reviewing) View.VISIBLE else View.GONE
        reviewActions?.visibility = if (reviewing) View.VISIBLE else View.GONE
        reviewText?.text = dictation.text
        reviewFeedback?.text = dictation.message
        reviewFeedback?.visibility = if (reviewing) View.VISIBLE else View.GONE
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
        val center = column().apply { gravity = Gravity.CENTER }
        waveform = WaveformView(this).also { center.addView(it, LinearLayout.LayoutParams(dp(76), dp(28))) }
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
                    dictation.start { result, done -> commit(original, result, done) }
                }
            }
        }
        mic = center
        content.addView(center, LinearLayout.LayoutParams(dp(100), dp(56)))
        confirm = label("Lip", 16f).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                if (dictation.phase == Phase.READY) {
                    if (dictation.canInsert) dictation.insert() else copy(this@LipAccessibilityService, dictation.text)
                }
                else startActivity(Intent(this@LipAccessibilityService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.also { content.addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }
        container.addView(content)
        reviewText = label("", 15f).apply { setPadding(dp(14), dp(10), dp(14), dp(10)) }
        reviewScroll = ScrollView(this).apply {
            isFocusable = false
            background = surface(Palette.cream, dp(14).toFloat())
            addView(reviewText)
        }.also { container.addView(it, LinearLayout.LayoutParams(dp(210), dp(160)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6)
        }) }
        reviewActions = LinearLayout(this).apply {
            addView(action("Raw") { dictation.useRaw() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(action("Copy") { copy(this@LipAccessibilityService, dictation.text) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }.also { container.addView(it) }
        reviewFeedback = label("", 12f).apply {
            setPadding(dp(12), dp(6), dp(12), dp(10))
            maxLines = 5
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { container.addView(it, LinearLayout.LayoutParams(dp(220), -2)) }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dictation.store.settings.getInt("bubbleX", dp(12))
            y = dictation.store.settings.getInt("bubbleY", dp(70))
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
                        layout.x = (startX - (event.rawX - downX).toInt()).coerceIn(0, resources.displayMetrics.widthPixels - dp(210))
                        layout.y = (startY + (event.rawY - downY).toInt()).coerceIn(-resources.displayMetrics.heightPixels / 3, resources.displayMetrics.heightPixels / 3)
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
