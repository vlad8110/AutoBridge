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
    private val power = text(32f)
    private val slots = (1..SLOTS).map { n ->
        Lamp.button(context, k, "EQ $n", 32f, momentary = false) { if (s.eqSlot(n) == null) store(n) else load(n) }.apply {
            minWidth = 0; setPadding(0, 0, 0, px(6))
            setOnLongClickListener { store(n); true }
        }
    }

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
                // Saved EQs, like the truck's radio favorites: tap to load, press and hold to save.
                slots.forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(px(170), px(110)).apply { if (i > 0) marginStart = px(22) }) }
                addView(View(context).apply { setBackgroundColor(OUTLINE) }, LinearLayout.LayoutParams(px(2), px(80)).apply { marginStart = px(28); marginEnd = px(6) })
                addView(power.apply {
                    gravity = Gravity.CENTER; letterSpacing = 0.02f; setPadding(0, 0, 0, px(6))
                    Lamp.style(this, k, px(20).toFloat(), momentary = false)
                    setOnClickListener { eq.enabled = !eq.enabled; s.eqEnabled = eq.enabled; refresh() }
                }, LinearLayout.LayoutParams(px(240), px(110)).apply { marginStart = px(22) })
                addView(Lamp.button(context, k, "Android Auto", 32f) { toAndroidAuto() }, LinearLayout.LayoutParams(px(300), px(110)).apply { marginStart = px(22) })
                // Back to settings: a gear, last in the row.
                addView(GearView(context).apply {
                    contentDescription = "Settings"; setPadding(px(37), px(22), px(37), px(30))
                    Lamp.style(this, k, px(20).toFloat()); setOnClickListener { close() }
                }, LinearLayout.LayoutParams(px(130), px(110)).apply { marginStart = px(22) })
            })
            addView(text(26f, MUTED).apply { text = "PRESETS"; letterSpacing = 0.22f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(48); bottomMargin = px(18) })
            addView(LinearLayout(context).apply {
                // A dragged band is "Custom": no preset stays lit.
                Equalizer.PRESETS.forEachIndexed { i, (name, gains) ->
                    val b = Lamp.button(context, k, name, 27f, 18f, momentary = false) { eq.gains = gains; bands.gains = gains.copyOf(); save(name) }.apply { minWidth = 0; setPadding(0, 0, 0, px(6)) }
                    presetViews[name] = b
                    addView(b, LinearLayout.LayoutParams(0, px(100), 1f).apply { if (i > 0) marginStart = px(16) })
                }
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

    /** Called after the page closes, so settings can update its Equalizer light. */
    var onClosed: (() -> Unit)? = null

    /** True if the page was open. */
    fun close(): Boolean = (visibility == VISIBLE).also { if (it) { visibility = GONE; eq.metering = false; onClosed?.invoke() } }

    private fun save(preset: String) {
        s.eqGains = Equalizer.encode(eq.gains); s.eqPreset = preset
        if (!eq.enabled) { eq.enabled = true; s.eqEnabled = true }  // touching the EQ means you want to hear it
        refresh()
    }

    private fun load(n: Int) {
        val g = Equalizer.decode(s.eqSlot(n))
        eq.gains = g; bands.gains = g.copyOf(); save("EQ $n")
    }

    private fun store(n: Int) {
        s.setEqSlot(n, Equalizer.encode(eq.gains)); save("EQ $n")
        android.widget.Toast.makeText(context, "Saved to EQ $n", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val current = s.eqPreset
        presetViews.forEach { (name, v) -> Lamp.lit(v, name == current) }
        slots.forEachIndexed { i, b ->
            val n = i + 1; val empty = s.eqSlot(n) == null
            Lamp.lit(b, current == "EQ $n")
            b.setTextColor(if (empty) MUTED else WHITE)
            b.contentDescription = "EQ $n, " + if (empty) "empty. Tap to save the current curve here." else "saved. Press and hold to save the current curve here instead."
        }
        power.text = if (eq.enabled) "EQ on" else "EQ off"
        Lamp.lit(power, eq.enabled)
        bands.dim = !eq.enabled; bands.invalidate()
    }

    private fun px(v: Int) = (v * k).roundToInt()

    private fun text(size: Float, color: Int = WHITE, light: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k); setTextColor(color)
        typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
    }

    /** A settings gear, white: eight teeth around a ring. */
    private class GearView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tooth = RectF()
        override fun onDraw(c: Canvas) {
            val w = width - paddingLeft - paddingRight; val h = height - paddingTop - paddingBottom
            val r = minOf(w, h) / 2f; val cx = paddingLeft + w / 2f; val cy = paddingTop + h / 2f
            p.style = Paint.Style.FILL; p.color = WHITE
            tooth.set(cx - r * 0.17f, cy - r, cx + r * 0.17f, cy - r * 0.5f)
            for (i in 0 until 8) { c.save(); c.rotate(i * 45f, cx, cy); c.drawRoundRect(tooth, r * 0.06f, r * 0.06f, p); c.restore() }
            c.drawCircle(cx, cy, r * 0.74f, p)
            p.color = BLACK; c.drawCircle(cx, cy, r * 0.32f, p)
        }
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
            val accent = if (dim) MUTED else WHITE
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
            val heard = if (eq.metering) eq.levelsHeard(System.nanoTime()) else null  // null: music stopped, let them fall
            val segH = (bottom - top) / SEGMENTS
            val half = (right - left) / gains.size * 0.165f  // 0.8: half as wide as 0.7
            p.style = Paint.Style.FILL
            for (i in gains.indices) {
                val target = heard?.get(i) ?: 0f
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
        const val SLOTS = 3  // saved EQs in the header
        // A stereo level meter's lights.
        const val SEGMENTS = 18
        const val LED_GREEN = 0xFF3CF05A.toInt()
        const val LED_YELLOW = 0xFFFFD21A.toInt()
        const val LED_RED = 0xFFFF2B3C.toInt()
        const val LED_OFF = 0xFF151816.toInt()
    }
}
