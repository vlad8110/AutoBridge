package dev.autobridge

import android.annotation.SuppressLint
import android.content.Context
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
class SettingsDot(c: Context, k: Float, private val onOpen: () -> Unit, private val onHold: () -> Unit) : FrameLayout(c) {
    private val prefs = c.getSharedPreferences("autobridge", Context.MODE_PRIVATE)
    private val size = (112 * k).roundToInt()
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    init {
        // No disc behind it: just the gear, with a soft shadow so it still reads over a light map.
        addView(Gear(c), LayoutParams(-1, -1))
        contentDescription = "AutoBridge settings"
        layoutParams = LayoutParams(size, size)
    }

    /** Puts the gear where it was left, once the screen has a size. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Starts in the empty stretch of Android Auto's bottom bar, between the mic and the app icons (its top-right
        // corner held Android Auto's own buttons). New keys, so a gear left in the old corner moves too.
        if (changed) place(prefs.getFloat("gearX", 0.22f), prefs.getFloat("gearY", 0.97f))
    }

    private fun place(fx: Float, fy: Float) {
        val p = parent as? android.view.View ?: return
        x = (fx * (p.width - size)).coerceIn(0f, (p.width - size).toFloat().coerceAtLeast(0f))
        y = (fy * (p.height - size)).coerceIn(0f, (p.height - size).toFloat().coerceAtLeast(0f))
    }

    private var downX = 0f; private var downY = 0f; private var startX = 0f; private var startY = 0f; private var dragging = false
    /** Press and hold (without moving) goes straight to the equalizer. */
    private var held = false
    private val hold = Runnable { if (!dragging) { held = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onHold() } }

    @SuppressLint("ClickableViewAccessibility")  // a tap calls performClick below
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = parent as? android.view.View ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = x; startY = y; dragging = false; held = false; alpha = 1f; postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()) }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !held && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) { dragging = true; removeCallbacks(hold) }
                if (dragging) {
                    x = (startX + e.rawX - downX).coerceIn(0f, (p.width - size).toFloat())
                    y = (startY + e.rawY - downY).coerceIn(0f, (p.height - size).toFloat())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                alpha = 1f; removeCallbacks(hold)
                if (held) return true
                if (dragging) prefs.edit().putFloat("gearX", x / (p.width - size).coerceAtLeast(1))
                    .putFloat("gearY", y / (p.height - size).coerceAtLeast(1)).apply()
                else if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onOpen(); return true }

    /** A thin outline gear in the truck's line-icon style: eight rounded teeth around a hollow hub. */
    private class Gear(c: Context) : android.view.View(c) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; color = WHITE; strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private val path = android.graphics.Path()

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val r = minOf(w, h) / 2f * 0.62f; val cx = w / 2f; val cy = h / 2f
            val step = (2 * Math.PI / 8).toFloat()
            path.reset()
            for (i in 0 until 8) for ((j, pt) in listOf(-0.30f to 0.78f, -0.16f to 1f, 0.16f to 1f, 0.30f to 0.78f).withIndex()) {
                val a = i * step + pt.first * step
                val x = cx + pt.second * r * kotlin.math.cos(a); val y = cy + pt.second * r * kotlin.math.sin(a)
                if (i == 0 && j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            path.addCircle(cx, cy, r * 0.34f, android.graphics.Path.Direction.CW)
            p.strokeWidth = r * 0.15f
            p.pathEffect = android.graphics.CornerPathEffect(r * 0.08f)
            p.setShadowLayer(r * 0.25f, 0f, 0f, 0xD0000000.toInt())
        }

        override fun onDraw(c: android.graphics.Canvas) = c.drawPath(path, p)
    }
}
