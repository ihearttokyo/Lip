package dev.lip.agent

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** Native metadata-only chooser. No query, account credential, transport, or default persistence.
 * Selecting a row never commits an action; only the separate explicit footer can do so.
 */
class AgentEffortMenu(
    private val context: Context,
    private val anchor: View,
    model: Model,
    onModeSelected: (AgentEffort) -> Unit,
    onExplicitAction: (AgentEffort) -> Unit,
    onDismiss: (DismissReason) -> Unit = {},
) {
    data class ModeAvailability(val enabled: Boolean, val unavailableReason: String? = null)
    data class Model(
        val title: String,
        val destinationLabel: String,
        val selected: AgentEffort,
        val modes: Map<AgentEffort, ModeAvailability>,
        val actionLabel: String,
        val actionEnabled: Boolean = false,
    )
    enum class DismissReason { CANCELED, OUTSIDE, ACTION, ANCHOR_LOST }

    private val title = model.title.take(80)
    // Destination/account metadata is bounded by the admitted descriptor. Keep
    // its FULL disclosure: truncation could hide the account that receives text.
    private val destinationLabel = model.destinationLabel
    private val modes = model.modes.toMap()
    private val actionLabel = model.actionLabel.take(80)
    private val actionEnabled = model.actionEnabled
    private var selected = model.selected
    private var selectCallback: ((AgentEffort) -> Unit)? = onModeSelected
    private var actionCallback: ((AgentEffort) -> Unit)? = onExplicitAction
    private var dismissCallback: ((DismissReason) -> Unit)? = onDismiss
    private var popup: PopupWindow? = null
    private var panel: ScrollView? = null
    private var action: Button? = null
    private val rows = LinkedHashMap<AgentEffort, LinearLayout>()
    private val indicators = LinkedHashMap<AgentEffort, TextView>()
    private var terminal = false
    private var shown = false
    private var reason = DismissReason.CANCELED
    private var ownerWindow: android.os.IBinder? = null
    private var observedTree: ViewTreeObserver? = null
    private var initialFrame: Rect? = null
    private var initialAnchor: Rect? = null
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { dismiss(DismissReason.ANCHOR_LOST) }
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (!anchorCurrent() || safeFrame() != initialFrame || anchorBounds() != initialAnchor)
            dismiss(DismissReason.ANCHOR_LOST)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun availability(mode: AgentEffort) = modes[mode] ?: ModeAvailability(false, "Not supported by this route.")
    private fun unavailable(mode: AgentEffort) = availability(mode).unavailableReason?.takeIf { it.isNotBlank() }
        ?: "Not supported by this route."

    /** False means no safe attached presentation was available; no callback/action is dispatched. */
    fun show(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (terminal || shown) return false
        if (!anchor.isAttachedToWindow || !anchor.isShown || anchor.windowToken == null) {
            dismiss(DismissReason.ANCHOR_LOST); return false
        }
        val frame = safeFrame() ?: run { dismiss(DismissReason.ANCHOR_LOST); return false }
        val margin = dp(8)
        val maxWidth = frame.width() - 2 * margin
        val maxHeight = frame.height() - 2 * margin
        if (maxWidth < dp(48) || maxHeight < dp(48)) { dismiss(DismissReason.ANCHOR_LOST); return false }
        val width = minOf(dp(328), maxWidth)
        val content = createPanel()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = minOf(content.measuredHeight.coerceAtLeast(dp(48)), maxHeight)
        val location = IntArray(2); anchor.getLocationOnScreen(location)
        val rtl = anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val x = (if (rtl) location[0] else location[0] + anchor.width - width)
            .coerceIn(frame.left + margin, frame.right - margin - width)
        val above = location[1] - frame.top - margin
        val below = frame.bottom - margin - location[1] - anchor.height
        val desiredY = if (above >= height || above >= below) location[1] - height - dp(4)
            else location[1] + anchor.height + dp(4)
        val y = desiredY.coerceIn(frame.top + margin, frame.bottom - margin - height)
        ownerWindow = anchor.windowToken
        initialFrame = Rect(frame)
        initialAnchor = anchorBounds()
        panel = content
        val window = PopupWindow(content, width, height, true).apply {
            setBackgroundDrawable(surface(Color.BLACK, 18))
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
            isClippingEnabled = true
            setIsClippedToScreen(true)
            setIsLaidOutInScreen(true)
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            animationStyle = 0 // Only our user-triggered, reduced-motion-aware reveal.
            setTouchInterceptor { _, event ->
                when {
                    event.actionMasked == MotionEvent.ACTION_CANCEL -> { dismiss(DismissReason.CANCELED); true }
                    event.actionMasked == MotionEvent.ACTION_OUTSIDE ||
                        event.actionMasked == MotionEvent.ACTION_DOWN && (event.x < 0 || event.y < 0 || event.x >= width || event.y >= height) -> {
                        dismiss(DismissReason.OUTSIDE); true
                    }
                    else -> false
                }
            }
            setOnDismissListener { finishDismiss() } // Framework Back/Escape cancel here.
        }
        popup = window
        anchor.addOnAttachStateChangeListener(attachListener)
        observedTree = anchor.viewTreeObserver.also { it.addOnGlobalLayoutListener(layoutListener) }
        return try {
            window.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.LEFT, x, y)
            shown = true
            content.post {
                if (!interacting()) return@post
                if (ValueAnimator.areAnimatorsEnabled()) {
                    content.translationX = (if (rtl) -1 else 1) * dp(18).toFloat()
                    content.animate().translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
                }
                (rows[selected]?.takeIf { it.isEnabled } ?: rows.values.firstOrNull { it.isEnabled } ?: content).requestFocus()
            }
            true
        } catch (_: RuntimeException) {
            dismiss(DismissReason.ANCHOR_LOST); false
        }
    }

    fun dismiss(reason: DismissReason = DismissReason.CANCELED) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (terminal) return
        this.reason = reason
        panel?.animate()?.cancel()
        if (popup?.isShowing == true) popup?.dismiss() else finishDismiss()
    }

    private fun finishDismiss() {
        if (terminal) return
        terminal = true
        panel?.animate()?.cancel()
        anchor.removeOnAttachStateChangeListener(attachListener)
        observedTree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        observedTree = null
        val callback = dismissCallback
        selectCallback = null; actionCallback = null; dismissCallback = null
        rows.clear(); indicators.clear(); action = null; panel = null; popup = null
        callback?.invoke(reason)
    }
    private fun anchorBounds(): Rect {
        val at = IntArray(2); anchor.getLocationOnScreen(at)
        return Rect(at[0], at[1], at[0] + anchor.width, at[1] + anchor.height)
    }
    private fun anchorCurrent() = anchor.isAttachedToWindow && anchor.isShown && anchor.windowToken === ownerWindow
    private fun interacting(): Boolean {
        if (terminal || !shown || popup?.isShowing != true) return false
        // Revalidate synchronously on EVERY interaction. A translated/moved anchor
        // can be clicked before the next GlobalLayout notification; that old UI
        // must never select a mode or commit an explicit destination action.
        if (!anchorCurrent() || safeFrame() != initialFrame || anchorBounds() != initialAnchor) {
            dismiss(DismissReason.ANCHOR_LOST)
            return false
        }
        return true
    }
    private fun safeFrame(): Rect? {
        val frame = Rect(); anchor.getWindowVisibleDisplayFrame(frame)
        val bounds = context.getSystemService(android.view.WindowManager::class.java)?.currentWindowMetrics?.bounds
        if (bounds != null && !frame.intersect(bounds)) return null
        val insets = anchor.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        if (bounds != null && insets != null) {
            frame.left = maxOf(frame.left, bounds.left + insets.left)
            frame.top = maxOf(frame.top, bounds.top + insets.top)
            frame.right = minOf(frame.right, bounds.right - insets.right)
            frame.bottom = minOf(frame.bottom, bounds.bottom - insets.bottom)
        }
        return frame.takeUnless { it.isEmpty }
    }
    private fun surface(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Color.rgb(69, 69, 69))
    }
    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private fun createPanel(): ScrollView {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutDirection = anchor.layoutDirection
            background = surface(Color.BLACK, 18)
        }
        column.addView(text(title, 18f).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4)); isAccessibilityHeading = true
        })
        column.addView(text(destinationLabel, 13f).apply { setPadding(dp(8), 0, dp(8), dp(12)) })
        var previousId = View.NO_ID
        for (mode in AgentEffort.entries) {
            val available = availability(mode)
            val row = object : LinearLayout(context) {
                override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true; info.isChecked = selected == mode
                }
            }.apply {
                id = View.generateViewId(); orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48); setPadding(dp(12), dp(8), dp(12), dp(8))
                isEnabled = available.enabled; isFocusable = available.enabled; isClickable = available.enabled
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = mode.label + if (available.enabled) "" else ". Unavailable: ${unavailable(mode)}"
                if (previousId != View.NO_ID) accessibilityTraversalAfter = previousId
            }
            val indicator = text("", 18f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; gravity = Gravity.CENTER }
            row.addView(indicator, LinearLayout.LayoutParams(dp(28), -2))
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text(mode.label, 16f))
            if (!available.enabled) labels.addView(text(unavailable(mode), 13f))
            labels.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener {
                if (interacting() && availability(mode).enabled) {
                    selected = mode; updateSelection(); selectCallback?.invoke(mode)
                }
            }
            rows[mode] = row; indicators[mode] = indicator
            column.addView(row, LinearLayout.LayoutParams(-1, -2))
            previousId = row.id
        }
        column.addView(text("Choosing a mode does not send anything.", 13f).apply { setPadding(dp(8), dp(12), dp(8), dp(8)) })
        action = Button(context).apply {
            id = View.generateViewId(); text = actionLabel; textSize = 16f; isAllCaps = false; setTextColor(Color.WHITE)
            minHeight = dp(48); minimumHeight = dp(48); background = surface(Color.rgb(37, 37, 37), 12)
            accessibilityTraversalAfter = previousId
            setOnClickListener {
                if (interacting() && actionEnabled && availability(selected).enabled) {
                    val callback = actionCallback; val choice = selected
                    dismiss(DismissReason.ACTION)
                    callback?.invoke(choice) // Sole commit callback, after terminal dismissal.
                }
            }
            column.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        updateSelection()
        return ScrollView(context).apply {
            isFillViewport = false; isFocusableInTouchMode = true; clipToPadding = false
            contentDescription = "Agent mode choices"; setBackgroundColor(Color.BLACK)
            addView(column, ViewGroup.LayoutParams(-1, -2))
        }
    }
    private fun updateSelection() {
        for ((mode, row) in rows) {
            row.isSelected = mode == selected
            row.stateDescription = if (mode == selected) "Selected" else if (availability(mode).enabled) "Not selected" else unavailable(mode)
            indicators[mode]?.text = if (mode == selected) "✓" else ""
            row.background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF),
                surface(if (mode == selected) Color.rgb(37, 37, 37) else Color.BLACK, 12), null)
        }
        action?.isEnabled = actionEnabled && availability(selected).enabled
        action?.stateDescription = when {
            !actionEnabled -> "No admitted destination action is available"
            !availability(selected).enabled -> unavailable(selected)
            else -> "Explicit action; choosing a mode alone does not send"
        }
    }
}
