package dev.autobridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
        val gear: String, val gearWord: String, val gearActive: Boolean,
        val range: String, val rangeUnit: String, val energy: String,
        val mid: String, val midUnit: String, val midLabel: String,          // speed, or time to the charge limit
        val charging: Boolean,
        val meterLeft: String, val meterCenter: String, val meterRight: String,
        val meterFrom: Float, val meterTo: Float, val meterMark: Float,      // 0..1 across the meter
        val meterTone: Tone, val power: String, val powerTone: Tone, val caption: String,
        val brake: String, val port: Port,
        val percent: String, val fill: Float, val batteryLabel: String,
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
            gearActive = gear != "P" && gear != NONE,
            range = t.rangeM?.let { "${(if (miles) it / 1609.344f else it / 1000).roundToInt()}" } ?: NONE,
            rangeUnit = if (miles) "mi" else "km",
            energy = t.batteryWh?.let { "${(it / 1000).roundToInt()}" + (t.capacityWh?.let { c -> " / ${(c / 1000).roundToInt()}" } ?: "") + " kWh" } ?: "",
            mid = NONE, midUnit = "", midLabel = "", charging = t.charging,
            meterLeft = "", meterCenter = "", meterRight = "", meterFrom = 0f, meterTo = 0f, meterMark = -1f,
            meterTone = Tone.GOOD, power = NONE, powerTone = Tone.NORMAL, caption = "",
            brake = when (t.parkingBrake) { true -> "On"; false -> "Off"; null -> NONE },
            port = when {
                t.portConnected == true -> Port.PLUGGED; t.portOpen == true -> Port.OPEN
                t.portOpen == false || t.portConnected == false -> Port.CLOSED; else -> Port.HIDDEN
            },
            percent = pct?.toString() ?: NONE, fill = (pct ?: 0) / 100f, batteryLabel = if (t.charging) "Charging" else "Battery",
        )
        return if (t.charging) common.copy(
            mid = t.chargeSec?.let { if (it < 3600) "${(it + 59) / 60}" else String.format(java.util.Locale.US, "%.1f", it / 3600f) } ?: NONE,
            midUnit = t.chargeSec?.let { if (it < 3600) "min" else "h" } ?: "",
            midLabel = t.chargeLimit?.let { "to ${it.roundToInt()}%" } ?: "Time left",
            meterLeft = "Charging", meterRight = t.currentA?.let { "${it.roundToInt()} A max" } ?: "",
            meterFrom = 0f, meterTo = (pct ?: 0) / 100f, meterMark = t.chargeLimit?.let { it / 100f } ?: -1f,
            power = kw?.let { number(abs(it)) } ?: NONE, powerTone = Tone.GOOD,
            caption = t.chargeLimit?.let { "Limit ${it.roundToInt()}%" } ?: "",
        ) else {
            // Truck reports power positive when the battery is drained, negative when regen fills it.
            // Square-root scale: town driving (10-30 kW) fills a third of a side, hard pulls still fit.
            val k = kw ?: 0f
            val f = Math.signum(k) * kotlin.math.sqrt((abs(k) / METER_KW).coerceAtMost(1f)) / 2
            common.copy(
                mid = t.speedMs?.let { "${(it * if (miles) 2.23694f else 3.6f).roundToInt()}" } ?: NONE,
                midUnit = if (miles) "mph" else "km/h", midLabel = "Speed",
                meterLeft = "Regen", meterCenter = "0", meterRight = "Power",
                meterFrom = minOf(0.5f, 0.5f + f), meterTo = maxOf(0.5f, 0.5f + f), meterMark = 0.5f,
                meterTone = if ((kw ?: 0f) < 0f) Tone.GOOD else Tone.BAD,
                power = kw?.let { if (it < 0f) "−" + number(-it) else number(it) } ?: NONE,
                powerTone = when { kw == null || kw == 0f -> Tone.NORMAL; kw < 0f -> Tone.GOOD; else -> Tone.BAD },
                caption = when (t.regen) { 1 -> "Regen off"; 2 -> "Partial regen"; 3 -> "One-pedal"; else -> "" },
            )
        }
    }

    /** 8 -> "8", 7.36 -> "7.4", 62.4 -> "62" (whole kW once it's 10 or more). */
    fun number(kw: Float): String = if (kw >= 10) "${kw.roundToInt()}" else String.format(java.util.Locale.US, "%.1f", kw).removeSuffix(".0")
}

/**
 * The truck bar under Android Auto, laid out like a dashboard strip: gear, range, speed, a
 * regen-to-power meter, brake, charge port and a cell battery gauge, on dark smoky glass.
 */
class TruckBar(context: Context) : FrameLayout(context) {
    private val barH = (resources.displayMetrics.heightPixels * 0.14f).roundToInt() - 10
    /** The port chip is also the hidden door to AutoBridge settings. */
    var onSettings: (() -> Unit)? = null
    private lateinit var card: LinearLayout
    private val big = barH * 0.25f
    private val unit = barH * 0.1f
    private val small = barH * 0.085f
    private val pad = (barH * 0.08f).roundToInt()

    private val gearLetter = text(big * 1.15f, WHITE, true)
    private val gearWord = text(small, MUTED)
    private val gearChip = chip(gearLetter, gearWord)
    private val range = text(big, WHITE, true)
    private val energy = text(small, MUTED)
    private val mid = text(big, WHITE, true)
    private val midLabel = text(small * 1.1f, MUTED)
    private val meterLeft = text(small * 1.1f, MUTED)
    private val meterCenter = text(small * 1.1f, MUTED)
    private val meterRight = text(small * 1.1f, MUTED)
    private val meter = Meter(context)
    private val power = text(big * 0.85f, WHITE, true)
    private val caption = text(small * 1.1f, MUTED)
    private val brake = text(big * 0.85f, WHITE, true)
    private val plugLabel = text(small, RED)
    private val plug = Plug(context)
    private val plugChip = chip(plug, plugLabel)
    private val alert = text(small, WHITE, true).apply { text = "!"; gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFD93025.toInt()) } }
    private val battery = Cells(context)
    private val percent = text(big * 0.8f, WHITE, true)
    private val batteryLabel = text(small * 1.1f, MUTED)

    init {
        setBackgroundColor(0xFF000000.toInt())  // pure black around the card; the card keeps its own color
        val chipSize = (barH * 0.62f).roundToInt()
        val portSize = (chipSize * 0.88f).roundToInt()
        val row = LinearLayout(context).apply {
            card = this
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            background = Chamfer(CARD, OUTLINE, barH * 0.13f)
            addView(gearChip, LinearLayout.LayoutParams(chipSize, chipSize))
            gap()
            addView(column(Gravity.START, range, text(small * 1.1f, MUTED).apply { text = "Range" }, energy))
            gap()
            addView(column(Gravity.CENTER, mid, midLabel))
            gap()
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(spread(meterLeft, meterCenter, meterRight))
                addView(meter, LinearLayout.LayoutParams(-1, (barH * 0.12f).roundToInt()).apply { setMargins(0, pad / 2, 0, pad / 2) })
                addView(spread(power, View(context), caption))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            gap()
            addView(column(Gravity.CENTER, brake, text(small * 1.1f, MUTED).apply { text = "Brake" }))
            gap()
            addView(FrameLayout(context).apply {
                addView(plugChip, LayoutParams(portSize, portSize).apply { setMargins(pad / 2, pad / 2, 0, 0) })
                addView(alert, LayoutParams((barH * 0.15f).roundToInt(), (barH * 0.15f).roundToInt(), Gravity.TOP or Gravity.START))
                setOnClickListener { onSettings?.invoke() }  // no ripple or hint: only the owner knows
            }, LinearLayout.LayoutParams(portSize + pad / 2, portSize + pad / 2))
            gap()
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(battery, LinearLayout.LayoutParams((barH * 1.25f).roundToInt(), (barH * 0.3f).roundToInt()))
                addView(spread(percent, View(context), batteryLabel), LinearLayout.LayoutParams((barH * 1.25f).roundToInt(), -2))
            })
        }
        addView(row, LayoutParams(-1, -1).apply { setMargins(pad * 2, pad, pad * 2, pad + pad / 2) })
        layoutParams = LinearLayout.LayoutParams(-1, barH)
    }

    fun show(t: Truck) {
        val b = BarText.of(t, java.util.Locale.getDefault().country in setOf("US", "GB", "LR", "MM"))
        gearLetter.text = b.gear; gearWord.text = b.gearWord
        gearLetter.setTextColor(if (b.gearActive) 0xFFD2E3FC.toInt() else WHITE)
        gearWord.setTextColor(if (b.gearActive) BLUE else MUTED)
        gearChip.background = box(if (b.gearActive) 0xFF1E2B44.toInt() else CARD, if (b.gearActive) BLUE else OUTLINE)
        card.background = Chamfer(CARD, if (b.charging) GREEN else OUTLINE, barH * 0.13f)
        range.text = withUnit(b.range, b.rangeUnit); energy.text = b.energy
        mid.text = withUnit(b.mid, b.midUnit); midLabel.text = b.midLabel
        meterLeft.text = b.meterLeft; meterCenter.text = b.meterCenter; meterRight.text = b.meterRight
        meter.from = b.meterFrom; meter.to = b.meterTo; meter.mark = b.meterMark
        meter.fill = if (b.meterTone == BarText.Tone.BAD) RED else GREEN; meter.invalidate()
        power.text = withUnit(b.power, "kW"); power.setTextColor(tone(b.powerTone)); caption.text = b.caption
        brake.text = b.brake
        plugChip.visibility = if (b.port == BarText.Port.HIDDEN) View.INVISIBLE else View.VISIBLE
        alert.visibility = if (b.port == BarText.Port.OPEN) View.VISIBLE else View.GONE
        val (word, fg, fill) = when (b.port) {
            BarText.Port.PLUGGED -> Triple("Plugged", GREEN, 0xFF1D3326.toInt())
            BarText.Port.OPEN -> Triple("Open", RED, 0xFF3A1F22.toInt())
            else -> Triple("Closed", MUTED, CARD)
        }
        plugLabel.text = word; plugLabel.setTextColor(if (b.port == BarText.Port.CLOSED) LIGHT else fg)
        plug.color = fg; plug.invalidate()
        plugChip.background = box(fill, if (b.port == BarText.Port.CLOSED) OUTLINE else fg)
        battery.fill = b.fill; battery.charging = b.charging; battery.invalidate()
        percent.text = withUnit(b.percent, "%"); batteryLabel.text = b.batteryLabel
    }

    // ---- building blocks ----

    private fun withUnit(v: String, u: String) = SpannableStringBuilder(v).apply {
        if (v != "—" && u.isNotEmpty()) {
            val s = length; append(" $u")
            setSpan(AbsoluteSizeSpan(unit.roundToInt()), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(LIGHT), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    private fun chip(top: View, word: TextView) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(top, if (top is TextView) LinearLayout.LayoutParams(-2, -2) else LinearLayout.LayoutParams((barH * 0.26f).roundToInt(), (barH * 0.26f).roundToInt()))
        addView(word, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pad / 3 })
    }

    private fun box(fill: Int, stroke: Int) = Chamfer(fill, stroke, barH * 0.09f)

    /** GM's button shape: square, top-right corner cut on a diagonal, thin outline. */
    private class Chamfer(private val fill: Int, private val stroke: Int, private val cut: Float) : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds; val w = 2f; val h = w / 2
            val path = android.graphics.Path().apply {
                moveTo(b.left + h, b.top + h); lineTo(b.right - cut, b.top + h); lineTo(b.right - h, b.top + cut)
                lineTo(b.right - h, b.bottom - h); lineTo(b.left + h, b.bottom - h); close()
            }
            p.style = Paint.Style.FILL; p.color = fill; c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = w; p.color = stroke; c.drawPath(path, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Drawable API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
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

    /** Battery with 10 cells, a terminal nub and a bolt while charging. */
    private class Cells(c: Context) : View(c) {
        var fill = 0f; var charging = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val nub = w * 0.04f; val bw = w - nub; val gap = h * 0.08f
            p.color = 0xFF5F6368.toInt(); c.drawRoundRect(RectF(bw, h * 0.3f, w, h * 0.7f), 3f, 3f, p)
            p.color = if (charging) 0x6681C995 else 0xFF3C4043.toInt(); c.drawRoundRect(RectF(0f, 0f, bw, h), h * 0.15f, h * 0.15f, p)
            p.color = 0xFF3C4043.toInt(); if (charging) c.drawRoundRect(RectF(2f, 2f, bw - 2f, h - 2f), h * 0.13f, h * 0.13f, p)
            val n = 10; val cw = (bw - gap * (n + 1)) / n; val lit = (fill * n).roundToInt()
            for (i in 0 until n) {
                p.color = if (i < lit) GREEN else 0xFF202124.toInt()
                val x = gap + i * (cw + gap); c.drawRoundRect(RectF(x, gap, x + cw, h - gap), 3f, 3f, p)
            }
            if (charging) {
                p.color = 0xFF0F1115.toInt()
                val cx = bw / 2; val path = android.graphics.Path().apply {
                    moveTo(cx + h * .08f, h * .12f); lineTo(cx - h * .16f, h * .55f); lineTo(cx, h * .55f)
                    lineTo(cx - h * .08f, h * .88f); lineTo(cx + h * .16f, h * .45f); lineTo(cx, h * .45f); close()
                }
                c.drawPath(path, p)
            }
        }
    }

    /** A simple plug: body, two prongs and a cable. */
    private class Plug(c: Context) : View(c) {
        var color = RED
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat(); p.color = color
            p.style = Paint.Style.FILL
            c.drawRoundRect(RectF(s * .22f, s * .3f, s * .78f, s * .68f), s * .08f, s * .08f, p)
            c.drawRect(s * .32f, s * .08f, s * .42f, s * .3f, p); c.drawRect(s * .58f, s * .08f, s * .68f, s * .3f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = s * .09f
            c.drawLine(s * .5f, s * .68f, s * .5f, s * .95f, p)
        }
    }

    companion object {
        /** The truck's button shape, for other screens (SettingsPanel). */
        fun chamfer(fill: Int, stroke: Int, cut: Float): android.graphics.drawable.Drawable = Chamfer(fill, stroke, cut)
        private const val WHITE = 0xFFF1F3F4.toInt()
        private const val LIGHT = 0xFFBDC1C6.toInt()
        private const val MUTED = 0xFF9AA0A6.toInt()
        private const val GREEN = 0xFF81C995.toInt()
        private const val RED = 0xFFF28B82.toInt()
        private const val BLUE = 0xFF8AB4F8.toInt()
        private const val CARD = 0xFF181C24.toInt()
        private const val OUTLINE = 0xFF8E939B.toInt()   // the truck's button outline gray
    }
}
