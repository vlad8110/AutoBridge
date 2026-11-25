package dev.autobridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
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
    data class Col(val value: String, val unit: String = "", val label: String, val tone: Tone = Tone.NORMAL)
    data class Bar(val percent: String, val fill: Float, val ringLabel: String, val charging: Boolean,
                   val cols: List<Col>, val gear: String, val gearActive: Boolean, val brake: String)

    private const val NONE = "—"

    fun of(t: Truck, localeMiles: Boolean): Bar {
        val miles = t.miles ?: localeMiles
        val f = t.fahrenheit ?: localeMiles
        val range = t.rangeM?.let { if (miles) "${(it / 1609.344f).roundToInt()} mi" else "${(it / 1000).roundToInt()} km" } ?: NONE
        val energy = if (t.batteryWh != null) Col("${(t.batteryWh / 1000).roundToInt()}", t.capacityWh?.let { " / ${(it / 1000).roundToInt()} kWh" } ?: " kWh", "Energy")
                     else Col(NONE, label = "Energy")
        val kw = t.rateMw?.let { kw(abs(it)) }
        val middle = if (t.charging) listOf(
            Col(kw?.let { "$it kW" } ?: NONE, label = t.currentA?.let { "${it.roundToInt()} A max" } ?: "Charging", tone = Tone.GOOD),
            Col(t.chargeSec?.let(::duration) ?: NONE, label = t.chargeLimit?.let { "to ${it.roundToInt()}%" } ?: "Time left"),
            Col(if (t.portConnected == true) "Plugged" else "Unplugged", label = if (t.portOpen == true) "Port open" else "Port closed"),
        ) else listOf(
            Col(t.speedMs?.let { if (miles) "${(it * 2.23694f).roundToInt()} mph" else "${(it * 3.6f).roundToInt()} km/h" } ?: NONE, label = "Speed"),
            Col(kw?.let { "$it kW" } ?: NONE, label = if ((t.rateMw ?: 0f) < 0f) "Regen" else "Power",
                tone = when { t.rateMw == null || t.rateMw == 0f -> Tone.NORMAL; t.rateMw < 0f -> Tone.GOOD; else -> Tone.BAD }),
            Col(when (t.regen) { 1 -> "Off"; 2 -> "Partial"; 3 -> "One-pedal"; else -> NONE }, label = "Regen braking"),
        )
        val outside = Col(t.outsideC?.let { "${(if (f) it * 9 / 5 + 32 else it).roundToInt()}°" } ?: NONE, label = "Outside")
        val gear = t.gear?.let(Truck::gearName) ?: NONE
        return Bar(
            percent = t.batteryPercent?.let { "$it%" } ?: NONE,
            fill = (t.batteryPercent ?: 0) / 100f,
            ringLabel = if (t.charging) "Charging" else "Battery",
            charging = t.charging,
            cols = listOf(Col(range, label = "Range"), energy) + middle + outside,
            gear = gear, gearActive = gear != "P" && gear != NONE,
            brake = when (t.parkingBrake) { true -> "On"; false -> "Off"; null -> NONE },
        )
    }

    /** 8 -> "8", 7.36 -> "7.4", 62.4 -> "62" (whole kW once it's 10 or more). */
    fun kw(mw: Float): String {
        val k = mw / 1_000_000f
        return if (k >= 10) "${k.roundToInt()}" else String.format(java.util.Locale.US, "%.1f", k).removeSuffix(".0")
    }

    fun duration(sec: Int): String = if (sec < 3600) "${(sec + 59) / 60} min" else "${sec / 3600} h ${(sec % 3600 + 59) / 60} min"
}

/** The smoky-glass strip under Android Auto: battery ring, then one column per value, then the gear chip. */
class TruckBar(context: Context) : FrameLayout(context) {
    private val barH = (resources.displayMetrics.heightPixels * 0.12f).roundToInt()
    private val valuePx = barH * 0.19f
    private val labelPx = barH * 0.085f
    private val ring = Ring(context)
    private val percent = text(valuePx * 1.3f, WHITE, bold = true)
    private val ringLabel = text(labelPx, MUTED)
    private val cols = List(7) { text(valuePx, WHITE, bold = true) to text(labelPx, MUTED) }
    private val brake = text(labelPx * 1.2f, MUTED)
    private val gear = text(barH * 0.3f, WHITE, bold = true).apply { gravity = Gravity.CENTER }

    init {
        // Soft teal and violet glow behind the glass.
        background = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(0xFF0F1115.toInt()) },
            glow(0x5A1D9E75, 0.08f), glow(0x4D534AB7, 0.92f),
        ))
        val pad = (barH * 0.08f).roundToInt()
        val glass = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x29FFFFFF, 0x8C2C303A.toInt(), 0xB812141A.toInt())).apply {
                cornerRadius = barH * 0.22f; setStroke(2, 0x33FFFFFF)
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(ring, LinearLayout.LayoutParams((barH * 0.6f).roundToInt(), (barH * 0.6f).roundToInt()))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(pad, 0, 0, 0)
                    addView(percent); addView(ringLabel)
                })
            }, weight(1.5f))
            divider()
            column(0); column(1)
            divider()
            column(2); column(3); column(4)
            divider()
            column(5)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.END; setPadding(0, 0, pad, 0)
                    addView(text(labelPx, MUTED).apply { text = "Brake" }); addView(brake)
                })
                addView(gear, LinearLayout.LayoutParams((barH * 0.6f).roundToInt(), (barH * 0.6f).roundToInt()))
            }, weight(1.3f))
        }
        addView(glass, LayoutParams(-1, -1).apply { setMargins(pad * 2, pad, pad * 2, pad + pad / 2) })
        layoutParams = LinearLayout.LayoutParams(-1, barH)
    }

    fun show(t: Truck) {
        val b = BarText.of(t, java.util.Locale.getDefault().country in setOf("US", "GB", "LR", "MM"))
        ring.fill = b.fill; ring.charging = b.charging; ring.invalidate()
        percent.text = b.percent
        ringLabel.text = b.ringLabel
        b.cols.forEachIndexed { i, c ->
            val (v, l) = cols[i]
            v.text = SpannableStringBuilder(c.value).apply {
                if (c.unit.isNotEmpty()) {
                    val s = length; append(c.unit)
                    setSpan(AbsoluteSizeSpan((labelPx * 1.3f).roundToInt()), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(MUTED), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            v.setTextColor(when (c.tone) { BarText.Tone.GOOD -> GREEN; BarText.Tone.BAD -> RED; else -> WHITE })
            l.text = c.label
        }
        brake.text = b.brake
        gear.text = b.gear
        gear.setTextColor(if (b.gearActive) 0xFFD2E3FC.toInt() else WHITE)
        gear.background = GradientDrawable().apply {
            cornerRadius = barH * 0.16f
            setColor(if (b.gearActive) 0x478AB4F8 else 0x1AFFFFFF)
            setStroke(2, if (b.gearActive) 0x998AB4F8.toInt() else 0x38FFFFFF)
        }
    }

    private fun LinearLayout.column(i: Int) = addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(cols[i].first); addView(cols[i].second)
    }, weight(1f))

    private fun LinearLayout.divider() = addView(View(context).apply { setBackgroundColor(0x1FFFFFFF) },
        LinearLayout.LayoutParams(2, (barH * 0.45f).roundToInt()).apply { setMargins(8, 0, 8, 0) })

    private fun weight(w: Float) = LinearLayout.LayoutParams(0, -1, w)

    private fun text(px: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px); setTextColor(color); maxLines = 1
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }

    private fun glow(color: Int, x: Float) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT; colors = intArrayOf(color, 0)
        gradientRadius = barH * 2.2f; setGradientCenter(x, 0.5f)
    }

    /** The battery ring; a lightning bolt in the middle while charging. */
    private class Ring(context: Context) : View(context) {
        var fill = 0f
        var charging = false
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x24FFFFFF; strokeCap = Paint.Cap.ROUND }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GREEN; strokeCap = Paint.Cap.ROUND }
        private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat(); val w = s * 0.1f
            track.strokeWidth = w; arc.strokeWidth = w
            val r = RectF(w, w, s - w, s - w)
            c.drawOval(r, track)
            c.drawArc(r, -90f, 360f * fill, false, arc)
            if (charging) c.drawPath(Path().apply {
                moveTo(s * .54f, s * .26f); lineTo(s * .38f, s * .54f); lineTo(s * .5f, s * .54f)
                lineTo(s * .45f, s * .74f); lineTo(s * .63f, s * .45f); lineTo(s * .51f, s * .45f); close()
            }, bolt)
        }
    }

    private companion object {
        const val WHITE = 0xFFF1F3F4.toInt()
        const val MUTED = 0xFF9AA0A6.toInt()
        const val GREEN = 0xFF81C995.toInt()
        const val RED = 0xFFF28B82.toInt()
    }
}
