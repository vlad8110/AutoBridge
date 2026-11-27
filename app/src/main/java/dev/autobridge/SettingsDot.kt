package dev.autobridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A see-through gear over Android Auto, for reaching AutoBridge's settings without restarting it.
 * Tap opens settings; drag moves it, and it stays where it was left (kept as a fraction of the screen, so it also
 * stays put when the bars change Android Auto's size). Its touches never reach Android Auto.
 */
@SuppressLint("ViewConstructor")
class SettingsDot(c: Context, k: Float, private val onOpen: () -> Unit) : FrameLayout(c) {
    private val prefs = c.getSharedPreferences("autobridge", Context.MODE_PRIVATE)
    private val size = (112 * k).roundToInt()
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    init {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x59000000); setStroke((2 * k).roundToInt(), 0x55FFFFFF) }
        addView(EqPanel.GearView(c).apply { alpha = 0.6f; val pad = (26 * k).roundToInt(); setPadding(pad, pad, pad, pad) }, LayoutParams(-1, -1))
        contentDescription = "AutoBridge settings"
        layoutParams = LayoutParams(size, size)
    }

    /** Puts the gear where it was left, once the screen has a size. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) place(prefs.getFloat("dotX", 0.95f), prefs.getFloat("dotY", 0.05f))
    }

    private fun place(fx: Float, fy: Float) {
        val p = parent as? android.view.View ?: return
        x = (fx * (p.width - size)).coerceIn(0f, (p.width - size).toFloat().coerceAtLeast(0f))
        y = (fy * (p.height - size)).coerceIn(0f, (p.height - size).toFloat().coerceAtLeast(0f))
    }

    private var downX = 0f; private var downY = 0f; private var startX = 0f; private var startY = 0f; private var dragging = false

    @SuppressLint("ClickableViewAccessibility")  // a tap calls performClick below
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = parent as? android.view.View ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = x; startY = y; dragging = false; alpha = 1f }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) dragging = true
                if (dragging) {
                    x = (startX + e.rawX - downX).coerceIn(0f, (p.width - size).toFloat())
                    y = (startY + e.rawY - downY).coerceIn(0f, (p.height - size).toFloat())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                alpha = 1f
                if (dragging) prefs.edit().putFloat("dotX", x / (p.width - size).coerceAtLeast(1))
                    .putFloat("dotY", y / (p.height - size).coerceAtLeast(1)).apply()
                else if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onOpen(); return true }
}
