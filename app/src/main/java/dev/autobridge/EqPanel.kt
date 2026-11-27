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
    private val power = icon(SettingsPanel.Glyph.EQ) { eq.enabled = !eq.enabled; s.eqEnabled = eq.enabled; refresh() }
    private val adjust = icon(SettingsPanel.Glyph.EDIT) { if (!driving()) { s.eqAdjust = !s.eqAdjust; refresh() } }
    private val aa = icon(SettingsPanel.Glyph.AA, momentary = true) { toAndroidAuto() }
    private val hint = text(26f, MUTED)
    private val slots = (1..SLOTS).map { n ->
        Lamp.button(context, k, "Custom $n", 27f, 18f, momentary = false) { load(n) }.apply {
            minWidth = 0; setPadding(0, 0, 0, px(6))
            setOnLongClickListener { store(n, this); true }
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
                // Icons only: EQ on/off, the pencil (shows or hides the fader caps in Park; while driving they're
                // always hidden), Android Auto, then the gear back to settings.
                addView(power, LinearLayout.LayoutParams(px(150), px(110)))
                addView(adjust, LinearLayout.LayoutParams(px(150), px(110)).apply { marginStart = px(22) })
                addView(aa, LinearLayout.LayoutParams(px(150), px(110)).apply { marginStart = px(22) })
                // Back to settings: a gear, last in the row.
                addView(GearView(context).apply {
                    contentDescription = "Settings"; setPadding(px(37), px(22), px(37), px(30))
                    Lamp.style(this, k, px(20).toFloat()); setOnClickListener { close() }
                }, LinearLayout.LayoutParams(px(150), px(110)).apply { marginStart = px(22) })
            })
            addView(text(26f, MUTED).apply { text = "PRESETS"; letterSpacing = 0.22f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(48); bottomMargin = px(18) })
            addView(LinearLayout(context).apply {
                // A dragged band is "Custom": no preset stays lit.
                Equalizer.PRESETS.forEachIndexed { i, (name, gains) ->
                    val b = Lamp.button(context, k, name, 27f, 18f, momentary = false) { eq.gains = gains; bands.gains = gains.copyOf(); save(name) }.apply { minWidth = 0; setPadding(0, 0, 0, px(6)) }
                    presetViews[name] = b
                    addView(b, LinearLayout.LayoutParams(0, px(100), 1f).apply { if (i > 0) marginStart = px(16) })
                }
                // The owner's saved curves, like the truck's radio favorites: tap to load, press and hold to save.
                slots.forEach { addView(it, LinearLayout.LayoutParams(0, px(100), 1f).apply { marginStart = px(16) }) }
            })
            addView(bands.apply { background = TruckBar.chamfer(BLACK, OUTLINE, px(40).toFloat()) },
                LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(40) })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(24) })
        }
        addView(page, LayoutParams(-1, -1))
    }

    /** The app left the screen or came back: meter only while the page can be seen (it costs the audio thread). */
    fun shown(visible: Boolean) { eq.metering = visible && visibility == VISIBLE; if (eq.metering) bands.invalidate() }

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

    /** An empty slot is Flat until something is saved into it. */
    private fun load(n: Int) {
        val g = s.eqSlot(n)?.let(Equalizer::decode) ?: FloatArray(Equalizer.BANDS.size)
        eq.gains = g; bands.gains = g.copyOf(); save("Custom $n")
    }

    /** Press and hold: saves the curve you hear now, and says so with a buzz, a chime and the slot's light blinking. */
    private fun store(n: Int, button: View) {
        s.setEqSlot(n, Equalizer.encode(eq.gains)); save("Custom $n")
        button.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        runCatching {  // a short two-note "done"; a busy audio stream just means no chime
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 220)
            postDelayed({ tone.release() }, 400)
        }
        for (i in 1..4) postDelayed({ Lamp.lit(button, i % 2 == 0) }, i * 120L)  // blink: off, on, off, on
    }

    private fun driving() = BridgeService.instance?.moving != false  // unknown counts as driving

    /** Also called when the truck starts or stops moving. */
    fun refresh() {
        val current = s.eqPreset
        presetViews.forEach { (name, v) -> Lamp.lit(v, name == current) }
        slots.forEachIndexed { i, b ->
            val n = i + 1; val empty = s.eqSlot(n) == null
            Lamp.lit(b, current == "Custom $n")
            b.setTextColor(if (empty) MUTED else WHITE)
            b.contentDescription = "Custom $n, " + if (empty) "empty (flat). Press and hold to save the current curve here." else "saved. Press and hold to save the current curve here instead."
        }
        Lamp.lit(power, eq.enabled); power.contentDescription = "Equalizer ${if (eq.enabled) "on" else "off"}"
        val running = BridgeService.instance?.video != null  // Android Auto's badge: blue while it runs, grey when not
        (aa.getChildAt(0) as SettingsPanel.GlyphView).running = running
        aa.contentDescription = "Android Auto, ${if (running) "running" else "not connected"}"
        val shown = s.eqAdjust && !driving()
        Lamp.lit(adjust, shown); adjust.alpha = if (driving()) 0.4f else 1f
        adjust.contentDescription = if (driving()) "Edit bands, parked only" else "Edit bands, ${if (shown) "shown" else "hidden"}"
        hint.text = when {
            driving() -> "Park the truck to adjust the bands."
            !shown -> "Tap the pencil to show the bands."
            else -> "Drag a band up or down. Changes music only; for the cleanest sound, keep the truck's own Bass, Mid and Treble centered."
        }
        bands.adjust = shown; bands.dim = !eq.enabled; bands.invalidate()
    }

    /** A lamp button with just a picture, like the gear. */
    private fun icon(glyph: SettingsPanel.Glyph, momentary: Boolean = false, onClick: () -> Unit) = FrameLayout(context).apply {
        addView(SettingsPanel.GlyphView(context, glyph), LayoutParams(px(60), px(60), Gravity.CENTER).apply { bottomMargin = px(6) })
        Lamp.style(this, k, px(20).toFloat(), momentary); setOnClickListener { onClick() }
    }

    private fun px(v: Int) = (v * k).roundToInt()

    private fun text(size: Float, color: Int = WHITE, light: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k); setTextColor(color)
        typeface = Fonts.of(context, if (light) Fonts.LIGHT else Fonts.REGULAR)
    }

    /** A settings gear, white: eight teeth around a ring. */
    class GearView(c: Context) : View(c) {
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

    /** All 10 bands in one view: grid, LED lights, a fader cap per band and dB values; drag to set. */
    private class EqView(c: Context, private val k: Float, private val onChange: (FloatArray) -> Unit) : View(c) {
        var gains = FloatArray(Equalizer.BANDS.size)
            set(v) { field = v; invalidate() }
        var dim = false
        /** False: only the lights, no fader caps or values, and dragging does nothing. */
        var adjust = true
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val top get() = 110f * k
        private val bottom get() = height - 110f * k
        private val left get() = 150f * k
        private val right get() = width - 60f * k
        private fun x(i: Int) = left + (right - left) * (i + 0.5f) / gains.size
        private fun y(db: Float) = top + (bottom - top) * (Equalizer.MAX_DB - db) / (2 * Equalizer.MAX_DB)

        override fun onDraw(c: Canvas) {
            p.typeface = face
            // grid: +12, +6, 0, -6, -12 dB
            for ((n, db) in GRID.withIndex()) {
                p.style = Paint.Style.STROKE; p.strokeWidth = if (db == 0f) 2.5f * k else 1.5f * k
                p.color = if (db == 0f) 0xFF5F6368.toInt() else 0xFF2A2D31.toInt()
                c.drawLine(left, y(db), right, y(db), p)
                p.style = Paint.Style.FILL; p.color = MUTED; p.textSize = 24f * k; p.textAlign = Paint.Align.RIGHT
                c.drawText(GRID_TEXT[n], left - 24f * k, y(db) + 8f * k, p)
            }
            meter(c)
            p.style = Paint.Style.FILL; p.color = MUTED; p.textSize = 28f * k; p.textAlign = Paint.Align.CENTER
            for (i in gains.indices) c.drawText(Equalizer.LABELS[i], x(i), bottom + 64f * k, p)
            p.textSize = 22f * k; p.textAlign = Paint.Align.RIGHT; c.drawText("Hz", right, bottom + 100f * k, p)
            if (!adjust) return
            // knobs, values, labels
            for (i in gains.indices) {
                cap(c, x(i), y(gains[i]))
                p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
                p.color = WHITE; p.textSize = 30f * k
                val g = gains[i]
                if (valueOf[i] != g) { valueOf[i] = g; valueText[i] = if (g > 0) "+${fmt(g)}" else fmt(g) }
                c.drawText(valueText[i], x(i), top - 44f * k, p)
            }
        }

        /** What each column shows; jumps up with the music and falls back slowly, like a stereo's lights. */
        private val shown = FloatArray(Equalizer.BANDS.size)
        private val seg = RectF(); private val halo = RectF()
        private val tick = Runnable { invalidate() }
        // Made once, not 30 times a second: the meter redraws the whole view.
        private val face = Fonts.of(context)
        private val valueText = Array(Equalizer.BANDS.size) { "" }
        private val valueOf = FloatArray(Equalizer.BANDS.size) { Float.NaN }

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
                    val a = if (dim) 0x66 else 0xFF  // EQ off: the lights dim with the caps
                    halo.set(seg.left - 5 * k, seg.top - 5 * k, seg.right + 5 * k, seg.bottom + 5 * k)
                    p.color = (led and 0xFFFFFF) or ((a / 5) shl 24); c.drawRoundRect(halo, 8 * k, 8 * k, p)  // soft glow
                    p.color = (led and 0xFFFFFF) or (a shl 24); c.drawRoundRect(seg, 3 * k, 3 * k, p)
                }
            }
            removeCallbacks(tick)
            // ~30 fps while lights move; 4 a second when no music plays and all are dark (just watching for music to start).
            val idle = heard == null && shown.all { it < 0.01f }
            if (eq.metering) postDelayed(tick, if (idle) 250L else 33L)  // only while the EQ page is open; one pending redraw, never a pile-up
        }

        // The knob: a slim black fader cap with chamfered corners, two grip grooves above and below, and a smoked-red
        // lamp in a dark slot, brightest in the middle (dimmed when the EQ is off). No line joins the bands.
        private val capBody = Path(); private val capSlot = RectF(); private var capFor = 0
        private val capFill = Paint(Paint.ANTI_ALIAS_FLAG); private val lampFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun cap(c: Canvas, cx: Float, cy: Float) {
            val w = 140f * k; val h = 38f * k; val cut = 7f * k
            if (capFor != width) {  // shapes in the cap's own coordinates, made once per size
                capFor = width
                capBody.reset(); capBody.moveTo(-w / 2 + cut, -h / 2); capBody.lineTo(w / 2 - cut, -h / 2); capBody.lineTo(w / 2, -h / 2 + cut)
                capBody.lineTo(w / 2, h / 2 - cut); capBody.lineTo(w / 2 - cut, h / 2); capBody.lineTo(-w / 2 + cut, h / 2)
                capBody.lineTo(-w / 2, h / 2 - cut); capBody.lineTo(-w / 2, -h / 2 + cut); capBody.close()
                capFill.shader = android.graphics.LinearGradient(0f, -h / 2, 0f, h / 2, intArrayOf(0xFF3A3B3D.toInt(), 0xFF1C1D1F.toInt(), 0xFF111213.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
                capSlot.set(-w * 0.27f, -h * 0.14f, w * 0.27f, h * 0.14f)
                lampFill.shader = android.graphics.RadialGradient(0f, 0f, w * 0.27f, intArrayOf(0xFFE2402E.toInt(), 0xFF8E0F16.toInt(), 0xFF2A0306.toInt()), floatArrayOf(0f, 0.35f, 1f), android.graphics.Shader.TileMode.CLAMP)
            }
            c.save(); c.translate(cx, cy)
            c.drawPath(capBody, capFill)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * k; p.color = 0xFF5A5C60.toInt(); c.drawPath(capBody, p)  // machined edge
            p.strokeWidth = 1.5f * k
            for (gy in floatArrayOf(-0.36f, -0.24f, 0.24f, 0.36f)) {  // grip grooves: dark line with a light lip under it
                p.color = 0xFF050506.toInt(); c.drawLine(-w / 2 + cut, gy * h, w / 2 - cut, gy * h, p)
                p.color = 0xFF2E2F32.toInt(); c.drawLine(-w / 2 + cut, gy * h + 1.5f * k, w / 2 - cut, gy * h + 1.5f * k, p)
            }
            p.style = Paint.Style.FILL; p.color = 0xFF050506.toInt()
            c.drawRoundRect(capSlot.left - 2 * k, capSlot.top - 2 * k, capSlot.right + 2 * k, capSlot.bottom + 2 * k, h, h, p)
            lampFill.alpha = if (dim) 0x55 else 0xFF
            c.drawRoundRect(capSlot, h, h, lampFill)
            p.style = Paint.Style.FILL; p.color = if (dim) 0x22FF6A50 else 0x8CFF6A50.toInt()  // the hot spot in the lens
            c.drawOval(-w * 0.09f, -h * 0.05f, w * 0.09f, h * 0.05f, p)
            c.restore()
        }

        private fun fmt(g: Float) = if (g == g.roundToInt().toFloat()) "${g.roundToInt()}" else "%.1f".format(g)

        @SuppressLint("ClickableViewAccessibility")  // a drag surface; each band is also described for TalkBack
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!adjust) return false
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
        const val SLOTS = 3  // saved curves, after the presets
        // A stereo level meter's lights.
        const val SEGMENTS = 18
        const val LED_GREEN = 0xFF3CF05A.toInt()
        const val LED_YELLOW = 0xFFFFD21A.toInt()
        const val LED_RED = 0xFFFF2B3C.toInt()
        const val LED_OFF = 0xFF151816.toInt()
        private val GRID = floatArrayOf(12f, 6f, 0f, -6f, -12f)  // dB lines
        private val GRID_TEXT = arrayOf("+12", "+6", "0", "-6", "-12")
    }
}
