package dev.lip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    val cream = Color.rgb(255, 254, 235)
    val lavender = Color.rgb(230, 213, 251)
    val ink = Color.rgb(25, 26, 22)
    val violet = Color.rgb(111, 67, 181)
    val muted = Color.rgb(94, 94, 88)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun surface(color: Int, radius: Float = 20f, border: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        border?.let { setStroke(1, it) }
    }

fun Context.column(padding: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.label(value: String, size: Float = 16f, serif: Boolean = false): TextView =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Palette.ink)
        typeface = Typeface.create(if (serif) "serif" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

fun Context.action(value: String, primary: Boolean = false, block: () -> Unit): Button =
    Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        setTextColor(Palette.ink)
        minHeight = dp(48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = surface(if (primary) Palette.lavender else Color.WHITE, dp(14).toFloat(), Color.rgb(218, 215, 208))
        setOnClickListener { block() }
    }

fun LinearLayout.addSpaced(view: View, gap: Int = 12, height: Int = -2) {
    addView(view, LinearLayout.LayoutParams(-1, height).apply { bottomMargin = context.dp(gap) })
}

class WaveformView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.violet
        strokeWidth = context.dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    var level = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val heights = floatArrayOf(.25f, .50f, .80f, 1f, .65f, .45f, .25f)
        heights.forEachIndexed { index, scale ->
            val x = width * (index + 1) / 8f
            val half = height * scale * (.18f + level * .22f)
            canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
        }
    }
}
