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
/** [toAndroidAuto] closes the EQ and the settings page under it, straight back to Android Auto. */
class EqPanel(context: Context, private val k: Float, private val toAndroidAuto: () -> Unit) : FrameLayout(context) {
    private val s = Settings(context)
    private val eq = Equalizer.music
    private val bands = EqView(context, k) { g -> eq.gains = g; save(CUSTOM) }
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
                addView(power.apply { gravity = Gravity.CENTER; letterSpacing = 0.02f; setOnClickListener { eq.enabled = !eq.enabled; s.eqEnabled = eq.enabled; refresh() } },
                    LinearLayout.LayoutParams(px(240), px(110)))
                addView(button("Done") { close() }, LinearLayout.LayoutParams(px(240), px(110)).apply { marginStart = px(22) })
                // Filled like the settings page's own Android Auto button: the way back to driving.
                addView(button("Android Auto", primary = true) { toAndroidAuto() }, LinearLayout.LayoutParams(px(300), px(110)).apply { marginStart = px(22) })
            })
            addView(text(26f, MUTED).apply { text = "PRESETS"; letterSpacing = 0.22f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(48); bottomMargin = px(18) })
            addView(LinearLayout(context).apply {
                Equalizer.PRESETS.forEachIndexed { i, (name, gains) ->
                    val b = button(name) { eq.gains = gains; bands.gains = gains.copyOf(); save(name) }
                    presetViews[name] = b
                    addView(b, LinearLayout.LayoutParams(0, px(100), 1f).apply { if (i > 0) marginStart = px(16) })
                }
                // Not tappable: it lights up once you drag a band yourself.
                val custom = button(CUSTOM) {}.apply { isClickable = false }
                presetViews[CUSTOM] = custom
                addView(custom, LinearLayout.LayoutParams(0, px(100), 1f).apply { marginStart = px(16) })
            })
            addView(bands.apply { background = TruckBar.chamfer(BLACK, OUTLINE, px(40).toFloat()) },
                LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(40) })
            addView(text(26f, MUTED).apply {
                text = "Drag a band up or down. Changes music only; for the cleanest sound, keep the truck's own Bass, Mid and Treble centered."
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(24) })
        }
        addView(page, LayoutParams(-1, -1))
    }

    fun open() { eq.metering = true; bands.gains = eq.gains.copyOf(); refresh(); visibility = VISIBLE; bands.invalidate() }

    /** True if the page was open. */
    fun close(): Boolean = (visibility == VISIBLE).also { visibility = GONE; eq.metering = false }

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
        power.text = if (eq.enabled) "EQ on" else "EQ off"
        power.setTextColor(if (eq.enabled) BLUE else MUTED)
        power.background = TruckBar.chamfer(BLACK, if (eq.enabled) BLUE else OUTLINE, px(20).toFloat())  // outlined: a switch, not the Done button
        bands.dim = !eq.enabled; bands.invalidate()
    }

    private fun px(v: Int) = (v * k).roundToInt()

    private fun text(size: Float, color: Int = WHITE, light: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k); setTextColor(color)
        typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = text(if (primary) 32f else 26f, if (primary) BLUE else WHITE).apply {
        text = label; gravity = Gravity.CENTER; letterSpacing = 0.02f; maxLines = 1
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
            meter(c)
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
            p.style = Paint.Style.STROKE; p.strokeWidth = 11f * k; p.color = BLACK; c.drawPath(path, p)  // dark edge: readable over lit lights
            p.strokeWidth = 5f * k; p.color = accent; c.drawPath(path, p)
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

        /** What each column shows; jumps up with the music and falls back slowly, like a stereo's lights. */
        private val shown = FloatArray(Equalizer.BANDS.size)
        private val seg = RectF(); private val halo = RectF()

        /** Each band's track is a column of LED lights: green, then yellow, then red at the top. */
        private fun meter(c: Canvas) {
            val eq = Equalizer.music
            val live = eq.metering && System.nanoTime() / 1_000_000 - eq.levelsAt < 250  // music stopped: let them fall
            val segH = (bottom - top) / SEGMENTS
            val half = (right - left) / gains.size * 0.33f
            p.style = Paint.Style.FILL
            for (i in gains.indices) {
                val target = if (live) eq.levels[i] else 0f
                shown[i] = if (target > shown[i]) target else shown[i] * 0.88f
                val lit = (shown[i] * SEGMENTS).roundToInt()
                for (n in 0 until SEGMENTS) {
                    seg.set(x(i) - half, bottom - (n + 1) * segH + 4 * k, x(i) + half, bottom - n * segH - 4 * k)
                    if (n >= lit) { p.color = LED_OFF; c.drawRoundRect(seg, 3 * k, 3 * k, p); continue }
                    val led = when { n < SEGMENTS * 0.6f -> LED_GREEN; n < SEGMENTS * 0.85f -> LED_YELLOW; else -> LED_RED }
                    val a = if (dim) 0x66 else 0xFF  // EQ off: the lights dim with the curve
                    halo.set(seg.left - 5 * k, seg.top - 5 * k, seg.right + 5 * k, seg.bottom + 5 * k)
                    p.color = (led and 0xFFFFFF) or ((a / 5) shl 24); c.drawRoundRect(halo, 8 * k, 8 * k, p)  // soft glow
                    p.color = (led and 0xFFFFFF) or (a shl 24); c.drawRoundRect(seg, 3 * k, 3 * k, p)
                }
            }
            if (eq.metering) postInvalidateDelayed(33)  // ~30 fps, only while the EQ page is open
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
        const val CUSTOM = "Custom"
        const val WHITE = 0xFFF1F3F4.toInt()
        const val MUTED = 0xFF9AA0A6.toInt()
        const val BLUE = 0xFF8AB4F8.toInt()
        const val NAVY = 0xFF0D1B2E.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val OUTLINE = 0xFF8E939B.toInt()
        // A stereo level meter's lights.
        const val SEGMENTS = 18
        const val LED_GREEN = 0xFF3CF05A.toInt()
        const val LED_YELLOW = 0xFFFFD21A.toInt()
        const val LED_RED = 0xFFFF2B3C.toInt()
        const val LED_OFF = 0xFF151816.toInt()
    }
}
