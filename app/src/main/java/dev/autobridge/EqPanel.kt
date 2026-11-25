package dev.autobridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** The music equalizer page, opened from settings: presets, an on/off switch and 10 draggable bands. */
class EqPanel(context: Context, private val k: Float) : FrameLayout(context) {
    private val s = Settings(context)
    private val eq = Equalizer.music
    private val bands = EqView(context, k) { g -> eq.gains = g; save("Custom") }
    private val presetViews = mutableMapOf<String, TextView>()
    private val power = text(30f)

    init {
        setBackgroundColor(BLACK)
        isClickable = true
        visibility = GONE
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(64), px(90), px(64), px(40))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(26f, MUTED).apply { text = "SOUND"; letterSpacing = 0.25f })
                    addView(text(64f, light = true).apply { text = "Equalizer" })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(power.apply { gravity = Gravity.CENTER; letterSpacing = 0.12f; setOnClickListener { eq.enabled = !eq.enabled; s.eqEnabled = eq.enabled; refresh() } },
                    LinearLayout.LayoutParams(px(240), px(110)))
                addView(button("DONE", primary = true) { close() }, LinearLayout.LayoutParams(px(240), px(110)).apply { marginStart = px(22) })
            })
            addView(text(26f, MUTED).apply { text = "PRESETS"; letterSpacing = 0.22f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(48); bottomMargin = px(18) })
            addView(LinearLayout(context).apply {
                Equalizer.PRESETS.forEachIndexed { i, (name, gains) ->
                    val b = button(name.uppercase()) { eq.gains = gains; bands.gains = gains.copyOf(); save(name) }
                    presetViews[name] = b
                    addView(b, LinearLayout.LayoutParams(0, px(100), 1f).apply { if (i > 0) marginStart = px(16) })
                }
            })
            addView(bands.apply { background = TruckBar.chamfer(BLACK, OUTLINE, px(40).toFloat()) },
                LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(40) })
            addView(text(26f, MUTED).apply {
                text = "Drag a band up or down. Music only; directions and calls aren't changed. Boosts lower the overall level a little so nothing distorts. " +
                    "For the cleanest sound, keep the truck's own Bass, Mid and Treble near the middle and shape it here."
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(24) })
        }
        addView(page, LayoutParams(-1, -1))
    }

    fun open() { bands.gains = eq.gains.copyOf(); refresh(); visibility = VISIBLE }

    /** True if the page was open. */
    fun close(): Boolean = (visibility == VISIBLE).also { visibility = GONE }

    private fun save(preset: String) {
        s.eqGains = Equalizer.encode(eq.gains); s.eqPreset = preset
        if (!eq.enabled) { eq.enabled = true; s.eqEnabled = true }  // touching the EQ means you want to hear it
        refresh()
    }

    private fun refresh() {
        val current = s.eqPreset
        presetViews.forEach { (name, v) ->
            val on = name == current
            v.setTextColor(if (on) BLUE else WHITE)
            v.background = TruckBar.chamfer(if (on) NAVY else BLACK, if (on) BLUE else OUTLINE, px(18).toFloat())
        }
        power.text = if (eq.enabled) "EQ ON" else "EQ OFF"
        power.setTextColor(if (eq.enabled) BLUE else MUTED)
        power.background = TruckBar.chamfer(if (eq.enabled) NAVY else BLACK, if (eq.enabled) BLUE else OUTLINE, px(20).toFloat())
        bands.dim = !eq.enabled; bands.invalidate()
    }

    private fun px(v: Int) = (v * k).roundToInt()

    private fun text(size: Float, color: Int = WHITE, light: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k); setTextColor(color)
        typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = text(if (primary) 32f else 26f, if (primary) BLUE else WHITE).apply {
        text = label; gravity = Gravity.CENTER; letterSpacing = 0.1f; maxLines = 1
        background = TruckBar.chamfer(if (primary) NAVY else BLACK, if (primary) BLUE else OUTLINE, px(18).toFloat())
        setOnClickListener { onClick() }
    }

    /** All 10 bands in one view: grid, a smooth curve through the knobs, and dB values; drag to set. */
    private class EqView(c: Context, private val k: Float, private val onChange: (FloatArray) -> Unit) : View(c) {
        var gains = FloatArray(Equalizer.BANDS.size)
            set(v) { field = v; invalidate() }
        var dim = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val top get() = 110f * k
        private val bottom get() = height - 110f * k
        private val left get() = 150f * k
        private val right get() = width - 60f * k
        private fun x(i: Int) = left + (right - left) * (i + 0.5f) / gains.size
        private fun y(db: Float) = top + (bottom - top) * (Equalizer.MAX_DB - db) / (2 * Equalizer.MAX_DB)

        override fun onDraw(c: Canvas) {
            val accent = if (dim) MUTED else BLUE
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            // grid: +12, +6, 0, -6, -12 dB
            for (db in listOf(12f, 6f, 0f, -6f, -12f)) {
                p.style = Paint.Style.STROKE; p.strokeWidth = if (db == 0f) 2.5f * k else 1.5f * k
                p.color = if (db == 0f) 0xFF5F6368.toInt() else 0xFF2A2D31.toInt()
                c.drawLine(left, y(db), right, y(db), p)
                p.style = Paint.Style.FILL; p.color = MUTED; p.textSize = 24f * k; p.textAlign = Paint.Align.RIGHT
                c.drawText(if (db > 0) "+${db.toInt()}" else "${db.toInt()}", left - 24f * k, y(db) + 8f * k, p)
            }
            // tracks and fills
            for (i in gains.indices) {
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                p.strokeWidth = 10f * k; p.color = 0xFF202124.toInt(); c.drawLine(x(i), top, x(i), bottom, p)
                p.color = (accent and 0xFFFFFF) or 0x66000000; c.drawLine(x(i), y(0f), x(i), y(gains[i]), p)
            }
            // curve through the knobs
            val path = Path().apply {
                moveTo(left, y(gains.first()))
                lineTo(x(0), y(gains[0]))
                for (i in 1 until gains.size) {
                    val mx = (x(i - 1) + x(i)) / 2
                    cubicTo(mx, y(gains[i - 1]), mx, y(gains[i]), x(i), y(gains[i]))
                }
                lineTo(right, y(gains.last()))
            }
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f * k; p.color = accent; c.drawPath(path, p)
            // knobs, values, labels
            for (i in gains.indices) {
                p.style = Paint.Style.FILL; p.color = BLACK; c.drawCircle(x(i), y(gains[i]), 24f * k, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 5f * k; p.color = accent; c.drawCircle(x(i), y(gains[i]), 24f * k, p)
                p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
                p.color = WHITE; p.textSize = 30f * k
                val g = gains[i]
                c.drawText(if (g > 0) "+${fmt(g)}" else fmt(g), x(i), top - 44f * k, p)
                p.color = MUTED; p.textSize = 28f * k
                c.drawText(Equalizer.LABELS[i], x(i), bottom + 64f * k, p)
            }
            p.textSize = 22f * k; p.textAlign = Paint.Align.RIGHT; c.drawText("Hz", right, bottom + 100f * k, p)
        }

        private fun fmt(g: Float) = if (g == g.roundToInt().toFloat()) "${g.roundToInt()}" else "%.1f".format(g)

        @SuppressLint("ClickableViewAccessibility")  // a drag surface; each band is also described for TalkBack
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) return true
            val i = (((e.x - left) / (right - left)) * gains.size).toInt().coerceIn(0, gains.size - 1)
            val db = (Equalizer.MAX_DB - (e.y - top) / (bottom - top) * 2 * Equalizer.MAX_DB).coerceIn(-Equalizer.MAX_DB, Equalizer.MAX_DB)
            val snapped = (db * 2).roundToInt() / 2f  // half-dB steps
            if (snapped != gains[i]) {
                gains = gains.copyOf().also { it[i] = snapped }
                contentDescription = "Equalizer: " + gains.indices.joinToString { "${Equalizer.LABELS[it]} hertz ${fmt(gains[it])} dB" }
                onChange(gains)
            }
            parent.requestDisallowInterceptTouchEvent(true)
            return true
        }
    }

    private companion object {
        const val WHITE = 0xFFF1F3F4.toInt()
        const val MUTED = 0xFF9AA0A6.toInt()
        const val BLUE = 0xFF8AB4F8.toInt()
        const val NAVY = 0xFF0D1B2E.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val OUTLINE = 0xFF8E939B.toInt()
    }
}
