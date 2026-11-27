package dev.autobridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the bar shows, worked out from [Truck] (pure, so it's unit-tested). "—" where the truck shares nothing. */
object BarText {
    enum class Tone { NORMAL, GOOD, BAD }
    enum class Port { HIDDEN, CLOSED, PLUGGED, OPEN }
    data class Bar(
        val gear: String, val gearWord: String,
        val range: String, val rangeUnit: String, val energy: String,
        val mid: String, val midUnit: String, val midLabel: String,          // speed
        val timeLeft: String, val timeLabel: String,                         // while charging: "1 h 45 min", "to 80%"
        val charging: Boolean,
        val meterLeft: String, val meterCenter: String, val meterRight: String,
        val meterFrom: Float, val meterTo: Float, val meterMark: Float,      // 0..1 across the meter
        val meterTone: Tone, val power: String, val powerTone: Tone, val caption: String,
        val brakeOn: Boolean, val port: Port,
        val percent: String,
        val showSpeed: Boolean = true,  // only out of Park (also when the gear is unknown)
        val showPort: Boolean = true,   // only while the port is open, plugged in or charging
    )

    private const val NONE = "—"
    /** kW at either end of the driving meter. */
    private const val METER_KW = 200f

    fun of(t: Truck, localeMiles: Boolean): Bar {
        val miles = t.miles ?: localeMiles
        val gear = t.gear?.let(Truck::gearName) ?: NONE
        val kw = t.rateMw?.let { it / 1_000_000f }
        val pct = t.batteryPercent
        val common = Bar(
            gear = gear,
            gearWord = when (gear) { "P" -> "Park"; "R" -> "Reverse"; "N" -> "Neutral"; "D" -> "Drive"; NONE -> ""; else -> "Gear $gear" },
            range = t.rangeM?.let { "${(if (miles) it / 1609.344f else it / 1000).roundToInt()}" } ?: NONE,
            rangeUnit = if (miles) "mi" else "km",
            energy = t.batteryWh?.let { "${(it / 1000).roundToInt()}" + (t.capacityWh?.let { c -> " / ${(c / 1000).roundToInt()}" } ?: "") + " kWh" } ?: "",
            mid = t.speedMs?.let { "${(it * if (miles) 2.23694f else 3.6f).roundToInt()}" } ?: NONE,
            midUnit = if (miles) "mph" else "km/h", midLabel = "Speed", timeLeft = "", timeLabel = "", charging = t.charging,
            meterLeft = "", meterCenter = "", meterRight = "", meterFrom = 0f, meterTo = 0f, meterMark = -1f,
            meterTone = Tone.GOOD, power = NONE, powerTone = Tone.NORMAL, caption = "",
            brakeOn = t.parkingBrake == true,
            port = when {
                t.portConnected == true -> Port.PLUGGED; t.portOpen == true -> Port.OPEN
                t.portOpen == false || t.portConnected == false -> Port.CLOSED; else -> Port.HIDDEN
            },
            percent = pct?.toString() ?: NONE,
            showSpeed = gear != "P",
            showPort = t.charging || t.portOpen == true || t.portConnected == true,
        )
        return if (t.charging) common.copy(
            timeLeft = t.chargeSec?.let(::duration) ?: "",
            timeLabel = "left",  // the limit is already on the meter's right ("Limit 80%")
            meterLeft = "", meterRight = t.chargeLimit?.let { "Limit ${it.roundToInt()}%" } ?: "",
            meterFrom = 0f, meterTo = (pct ?: 0) / 100f, meterMark = t.chargeLimit?.let { it / 100f } ?: -1f,
            power = kw?.let { number(abs(it)) } ?: NONE, powerTone = Tone.GOOD,
            caption = t.currentA?.let { "${it.roundToInt()} A max" } ?: "",
        ) else {
            // Truck reports power positive when the battery is drained, negative when regen fills it.
            // Square-root scale: town driving (10-30 kW) fills a third of a side, hard pulls still fit.
            val k = kw ?: 0f
            val f = Math.signum(k) * kotlin.math.sqrt((abs(k) / METER_KW).coerceAtMost(1f)) / 2
            common.copy(
                meterLeft = "Regen", meterCenter = "0", meterRight = "Power",
                meterFrom = minOf(0.5f, 0.5f + f), meterTo = maxOf(0.5f, 0.5f + f), meterMark = 0.5f,
                meterTone = if ((kw ?: 0f) < 0f) Tone.GOOD else Tone.BAD,
                power = kw?.let { if (it < 0f) "−" + number(-it) else number(it) } ?: NONE,
                powerTone = when { kw == null || kw == 0f -> Tone.NORMAL; kw < 0f -> Tone.GOOD; else -> Tone.BAD },
                caption = when (t.regen) { 1 -> "Regen off"; 2 -> "Partial regen"; 3 -> "One-pedal"; else -> "" },
            )
        }
    }

    /** 900 -> "15 min", 6300 -> "1 h 45 min", 3600 -> "1 h" (minutes rounded up, so it never says 0 while charging). */
    fun duration(sec: Int): String {
        val min = (sec.coerceAtLeast(0) + 59) / 60
        return when { min < 60 -> "$min min"; min % 60 == 0 -> "${min / 60} h"; else -> "${min / 60} h ${min % 60} min" }
    }

    /** 8 -> "8", 7.36 -> "7.4", 62.4 -> "62" (whole kW once it's 10 or more). */
    fun number(kw: Float): String = if (kw >= 10) "${kw.roundToInt()}" else String.format(java.util.Locale.US, "%.1f", kw).removeSuffix(".0")
}

/**
 * The truck bar under Android Auto, laid out like a dashboard strip on black: a gear icon (with the
 * parking brake as red brackets), range, speed, a regen-to-power meter, the charge door (also the
 * hidden door to settings) and the battery ring with the truck.
 */
class TruckBar(context: Context) : FrameLayout(context) {
    private val barH = (resources.displayMetrics.heightPixels * 0.14f).roundToInt() - 15  // everything inside scales from this
    /** The charge door icon is also the hidden door to AutoBridge settings. */
    var onSettings: (() -> Unit)? = null
    private val big = barH * 0.25f
    private val unit = barH * 0.1f
    private val small = barH * 0.085f
    private val pad = (barH * 0.08f).roundToInt()

    private val gearIcon = GearIcon(context)
    private val range = text(big, WHITE, true)
    private val energy = text(small, WHITE)
    private val mid = text(big, WHITE, true)
    private val midLabel = text(small * 1.1f, WHITE)
    private val meterLeft = text(small * 1.1f, WHITE)
    private val meterCenter = text(small * 1.1f, WHITE)
    private val meterRight = text(small * 1.1f, WHITE)
    private val meter = Meter(context)
    private val power = text(big * 0.85f, WHITE, true)
    private val caption = text(small * 1.1f, WHITE)
    private val portIcon = PortIcon(context)
    private val batteryIcon = BatteryIcon(context)
    private val speedBox = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val portBox = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

    init {
        setBackgroundColor(BLACK)
        val icon = (barH * 0.7f).roundToInt()
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad / 2, pad * 2, pad / 2)
            background = Chamfer(BLACK, OUTLINE, barH * 0.13f)
            addView(gearIcon, LinearLayout.LayoutParams(icon, icon))
            gap()
            addView(column(Gravity.START, range, text(small * 1.1f, WHITE).apply { text = "Range" }, energy))
            // Speed only while driving and the port only while it's open: when hidden, the meter takes their room.
            addView(speedBox.apply { gap(); addView(column(Gravity.CENTER, mid, midLabel)) })
            gap()
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(spread(meterLeft, meterCenter, meterRight))
                addView(meter, LinearLayout.LayoutParams(-1, (barH * 0.12f).roundToInt()).apply { setMargins(0, pad / 2, 0, pad / 2) })
                addView(LinearLayout(context).apply {  // power (and time left) gets the room, the caption only what it needs
                    gravity = Gravity.BOTTOM
                    addView(power, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(caption, LinearLayout.LayoutParams(-2, -2))
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(portBox.apply {
                gap()
                addView(portIcon.apply { setOnClickListener { onSettings?.invoke() } }, LinearLayout.LayoutParams(icon, icon))  // no ripple or hint: only the owner knows
            })
            gap()
            addView(batteryIcon, LinearLayout.LayoutParams(icon, icon))
        }
        addView(row, LayoutParams(-1, -1).apply { setMargins(pad * 4 / 5, pad, pad * 4 / 5, pad + pad / 2) })  // lines up with Android Auto's card edges
        layoutParams = LinearLayout.LayoutParams(-1, barH)
    }

    fun show(t: Truck) {
        val b = BarText.of(t, Truck.localeMiles())
        gearIcon.set(b.gear, b.gearWord, b.brakeOn)
        range.text = withUnit(b.range, b.rangeUnit); energy.text = b.energy
        mid.text = withUnit(b.mid, b.midUnit); midLabel.text = b.midLabel
        meterLeft.text = b.meterLeft; meterCenter.text = b.meterCenter; meterRight.text = b.meterRight
        meter.from = b.meterFrom; meter.to = b.meterTo; meter.mark = b.meterMark
        meter.fill = if (b.meterTone == BarText.Tone.BAD) RED else GREEN; meter.invalidate()
        power.text = withUnit(b.power, "kW", whiteUnit = false).apply {  // "11 kW" takes the power colour
            if (b.timeLeft.isNotEmpty()) {
                append("   "); val s = length; append(b.timeLeft)
                setSpan(AbsoluteSizeSpan((big * 0.62f).roundToInt()), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(WHITE), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)  // only the kW is coloured
                val l = length; append(" ${b.timeLabel}")
                setSpan(AbsoluteSizeSpan(unit.roundToInt()), l, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(WHITE), l, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        power.setTextColor(tone(b.powerTone)); caption.text = b.caption
        portIcon.set(if (b.charging && b.port == BarText.Port.PLUGGED) PortIcon.CHARGING else b.port.ordinal)
        batteryIcon.set(b.percent.toIntOrNull())
        speedBox.visibility = if (b.showSpeed) View.VISIBLE else View.GONE
        portBox.visibility = if (b.showPort) View.VISIBLE else View.GONE
    }

    // ---- building blocks ----

    private fun withUnit(v: String, u: String, whiteUnit: Boolean = true) = SpannableStringBuilder(v).apply {
        if (v != "—" && u.isNotEmpty()) {
            val s = length; append(" $u")
            setSpan(AbsoluteSizeSpan(unit.roundToInt()), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (whiteUnit) setSpan(ForegroundColorSpan(WHITE), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun tone(t: BarText.Tone) = when (t) { BarText.Tone.GOOD -> GREEN; BarText.Tone.BAD -> RED; else -> WHITE }

    private fun text(px: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px); setTextColor(color); maxLines = 1; includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun column(g: Int, vararg v: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = g or Gravity.CENTER_VERTICAL
        v.forEachIndexed { i, view -> addView(view, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) topMargin = pad / 2 }) }
    }

    private fun spread(l: View, c: View, r: View) = LinearLayout(context).apply {
        gravity = Gravity.BOTTOM
        addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        addView(c, if (c is TextView) LinearLayout.LayoutParams(-2, -2) else LinearLayout.LayoutParams(0, 0))  // a bare View would take the whole row
        addView(r, LinearLayout.LayoutParams(0, -2, 1f))
        (r as? TextView)?.gravity = Gravity.END
    }

    private fun LinearLayout.gap() = addView(View(context), LinearLayout.LayoutParams(pad * 3, 1))

    /** Track with a fill between [from] and [to] (0..1) and a white marker at [mark]. */
    private class Meter(c: Context) : View(c) {
        var from = 0f; var to = 0f; var mark = -1f; var fill = GREEN
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val w = width.toFloat(); val r = h / 2
            p.color = 0xFF3C4043.toInt(); c.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
            if (to > from) { p.color = fill; c.drawRoundRect(RectF(from * w, 0f, to * w, h), r, r, p) }
            if (mark >= 0f) { p.color = WHITE; c.drawRoundRect(RectF(mark * w - h * 0.1f, -h * 0.25f, mark * w + h * 0.1f, h * 1.25f), 2f, 2f, p) }
        }
    }

    /** Icons drawn on a 320-unit square, like the SVG designs they come from. */
    private abstract class Icon(c: Context) : View(c) {
        protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
        abstract fun draw320(c: Canvas)
        override fun onDraw(c: Canvas) { c.save(); c.scale(width / 320f, height / 320f); draw320(c); c.restore() }
        protected fun fill(color: Int) = p.apply { style = Paint.Style.FILL; this.color = color; shader = null }
        protected fun line(color: Int, w: Float) = p.apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; shader = null }
        protected fun word(c: Canvas, text: String, y: Float, size: Float, color: Int, bold: Boolean = false) {
            fill(color).apply { textSize = size; textAlign = Paint.Align.CENTER; typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL) }
            c.drawText(text, 160f, y, p)
        }
        /** The black GM tile: gray outline, top-right corner cut. */
        protected fun tile(c: Canvas) {
            c.drawPath(TILE, fill(BLACK)); c.drawPath(TILE, line(OUTLINE, 3f))
        }
        private val box = RectF()  // reused: the charge door redraws ~16 times a second for hours
        protected fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, paint: Paint) { box.set(l, t, r, b); c.drawRoundRect(box, rad, rad, paint) }
    }

    /** Big gear letter with a P R N D L ring; red brackets close around it while the parking brake is on. */
    private class GearIcon(c: Context) : Icon(c) {
        private var gear = "—"; private var gearWord = ""; private var brake = false
        fun set(g: String, w: String, b: Boolean) {
            if (g == gear && w == gearWord && b == brake) return
            gear = g; gearWord = w; brake = b; contentDescription = "$w${if (b) ", parking brake on" else ""}"; invalidate()
        }
        override fun draw320(c: Canvas) {
            tile(c)
            val color = when (gear) { "—" -> MUTED; "R" -> AMBER; else -> BLUE }
            val ring = RectF(30f, 30f, 290f, 290f)
            listOf("P", "R", "N", "D", "L").forEachIndexed { i, g ->
                c.drawArc(ring, 135f + i * 54f + 4f, 46f, false, line(if (g == gear) color else 0x1FF4F7FA, 9f))
            }
            if (brake) {
                val o = RectF(78f, 83f, 242f, 247f)
                c.drawArc(o, 140f, 80f, false, line(BRAKE, 9f)); c.drawArc(o, -40f, 80f, false, line(BRAKE, 9f))
            }
            word(c, gear, 214f, 140f, color, bold = true)
            word(c, gearWord, 292f, 38f, WHITE)
        }
    }

    /** The Sierra EV charge door, drawn from the real one: shut, open with the socket, plugged in, or charging. */
    private class PortIcon(c: Context) : Icon(c) {
        private var glow: android.graphics.Shader? = null
        private var glowColor = 0
        private var state = -1
        private val tick = Runnable { invalidate() }
        fun set(s: Int) {
            if (s == state) return
            state = s; contentDescription = "Charge port ${WORDS[s].lowercase()}"; invalidate()
        }
        override fun draw320(c: Canvas) {
            tile(c)
            val shut = state == BarText.Port.CLOSED.ordinal || state == BarText.Port.HIDDEN.ordinal
            val plugged = state == BarText.Port.PLUGGED.ordinal || state == CHARGING
            val color = COLORS[state]
            if (!shut) {
                var a = 0.22f
                if (state == CHARGING) {  // the glow breathes while charging
                    a = 0.11f + 0.11f * kotlin.math.sin(android.os.SystemClock.uptimeMillis() / 1800f * 2 * Math.PI.toFloat())
                    removeCallbacks(tick); postDelayed(tick, 125)  // 8 a second is smooth enough for an 1.8 s breath; one pending redraw: any other redraw would start another chain
                }
                // Built once per colour, faded with the paint's alpha (it scales the whole gradient): this redraws
                // 8 times a second for hours while charging, so no new gradient per frame.
                if (glow == null || glowColor != color) {
                    glow = android.graphics.RadialGradient(176f, 132f, 112f, color or 0xFF000000.toInt(), color and 0xFFFFFF, android.graphics.Shader.TileMode.CLAMP)
                    glowColor = color
                }
                fill(BLACK); p.shader = glow; p.alpha = (a * 255).toInt()
                c.drawCircle(176f, 132f, 112f, p); p.shader = null; p.alpha = 255
            }
            rr(c, 66f, 40f, 286f, 224f, 40f, fill(0xFF0A0A0A.toInt())); rr(c, 66f, 40f, 286f, 224f, 40f, line(OUTLINE, 4f))
            if (shut) {
                rr(c, 72f, 46f, 280f, 218f, 35f, fill(0xFF1C1D1F.toInt())); rr(c, 72f, 46f, 280f, 218f, 35f, line((color and 0xFFFFFF) or 0x73000000, 2f))
                c.drawLine(266f, 118f, 266f, 146f, line(DIM, 5f))
                c.save(); c.translate(176f, 132f); c.scale(1.4f, 1.4f); fill(DIM)
                rr(c, -11f, -6f, 11f, 8f, 4f, p); c.drawRect(-7f, -14f, -4f, -6f, p); c.drawRect(4f, -14f, 7f, -6f, p); c.drawRect(-1.5f, 8f, 1.5f, 17f, p)
                c.restore()
            } else {
                val door = DOOR
                c.drawPath(door, fill(0xFF1C1D1F.toInt())); c.drawPath(door, line(OUTLINE, 3f))
                rr(c, 80f, 54f, 272f, 210f, 26f, fill(0xFF161718.toInt()))
                rr(c, 236f, 106f, 258f, 150f, 6f, fill(0xFF0D0D0D.toInt())); rr(c, 236f, 106f, 258f, 150f, 6f, line(EDGE, 2f))
                c.drawLine(247f, 126f, 253f, 144f, line(DIM, 5f))
                rr(c, 132f, 56f, 220f, 208f, 44f, fill(0xFF0A0A0A.toInt()))
                rr(c, 141f, 64f, 211f, 200f, 35f, line(color, 5f))
                if (!plugged) {
                    c.drawPath(SOCKET_TOP, fill(0xFF26272A.toInt()))
                    c.drawCircle(176f, 112f, 22f, fill(0xFF101010.toInt())); c.drawCircle(176f, 112f, 22f, line(DIM, 2.5f))
                    fill(EDGE); c.drawCircle(168f, 105f, 4.5f, p); c.drawCircle(184f, 105f, 4.5f, p); c.drawCircle(176f, 122f, 4.5f, p); c.drawCircle(167f, 118f, 2.2f, p); c.drawCircle(185f, 118f, 2.2f, p)
                    rr(c, 152f, 144f, 200f, 186f, 10f, fill(0xFF1C1D1F.toInt())); rr(c, 152f, 144f, 200f, 186f, 10f, line(EDGE, 2f))
                    rr(c, 163f, 160f, 189f, 169f, 3f, fill(0xFF0A0A0A.toInt()))
                } else {
                    val cable = CABLE
                    c.drawPath(cable, line(0xFF2A2B2E.toInt(), 16f)); c.drawPath(cable, line((color and 0xFFFFFF) or 0x99000000.toInt(), 3f))
                    rr(c, 148f, 76f, 204f, 200f, 28f, fill(0xFF26272A.toInt())); rr(c, 148f, 76f, 204f, 200f, 28f, line(color, 3f))
                    rr(c, 162f, 88f, 190f, 96f, 4f, fill((color and 0xFFFFFF) or 0x59000000))
                    if (state == CHARGING) c.drawPath(BOLT, fill(color))
                }
            }
            word(c, WORDS[state], 292f, 38f, WHITE)
        }
        companion object {
            const val CHARGING = 4  // after BarText.Port's HIDDEN, CLOSED, PLUGGED, OPEN
            private val WORDS = listOf("Port", "Closed", "Plugged", "Open", "Charging")
            private val COLORS = listOf(MUTED, MUTED, BLUE, AMBER, MINT)
        }
    }

    /** The white Sierra in a 270° ring that fills with the charge; amber at 30% or less, red at 15% or less. */
    private class BatteryIcon(c: Context) : Icon(c) {
        private var pct: Int? = -1
        private val truck = android.graphics.BitmapFactory.decodeResource(c.resources, R.drawable.sierra_truck)
        fun set(v: Int?) {
            if (v == pct) return
            pct = v; contentDescription = v?.let { "Battery $it percent" } ?: "Battery unknown"; invalidate()
        }
        override fun draw320(c: Canvas) {
            val ring = RectF(30f, 30f, 290f, 290f)
            c.drawArc(ring, 135f, 270f, false, line(0x1FF4F7FA, 11f))
            val v = pct
            if (v != null && v > 0) c.drawArc(ring, 135f, 270f * v.coerceAtMost(100) / 100f, false, line(if (v <= 15) LOW else if (v <= 30) AMBER else MINT, 11f))
            truck?.let { c.drawBitmap(it, null, RectF(38f, 74f, 282f, 211f), p.apply { shader = null; isFilterBitmap = true }) }
            word(c, v?.let { "$it%" } ?: "—", 298f, 64f, WHITE, bold = true)
        }
    }

    companion object {
        // The icons' fixed shapes, on their 320-unit square: built once, not on every frame.
        private val TILE = android.graphics.Path().apply { moveTo(3f, 3f); lineTo(281f, 3f); lineTo(317f, 39f); lineTo(317f, 317f); lineTo(3f, 317f); close() }
        private val DOOR = android.graphics.Path().apply { moveTo(68f, 58f); lineTo(26f, 76f); quadTo(18f, 79f, 18f, 88f); lineTo(18f, 176f); quadTo(18f, 185f, 26f, 188f); lineTo(68f, 206f); close() }
        private val SOCKET_TOP = android.graphics.Path().apply { moveTo(166f, 80f); lineTo(186f, 80f); lineTo(182f, 92f); lineTo(170f, 92f); close() }
        private val CABLE = android.graphics.Path().apply { moveTo(190f, 196f); cubicTo(214f, 214f, 236f, 226f, 252f, 248f) }
        private val BOLT = android.graphics.Path().apply { moveTo(182f, 112f); lineTo(160f, 146f); lineTo(176f, 146f); lineTo(170f, 174f); lineTo(194f, 136f); lineTo(178f, 136f); close() }
        /** The truck's button shape, for other screens (SettingsPanel). */
        fun chamfer(fill: Int, stroke: Int, cut: Float): android.graphics.drawable.Drawable = Chamfer(fill, stroke, cut)
        private const val GREEN = 0xFF81C995.toInt()
        private const val RED = 0xFFF28B82.toInt()
        private const val BLUE = 0xFF8AB4F8.toInt()
        private const val AMBER = 0xFFF2BD64.toInt()
        private const val MINT = GREEN  // one green everywhere (was a separate mint that clashed)
        private const val LOW = 0xFFFF857D.toInt()
        private const val BRAKE = 0xFFFF857D.toInt()
        private const val DIM = 0xFF5F6368.toInt()
        private const val EDGE = 0xFF3C4043.toInt()
    }
}
