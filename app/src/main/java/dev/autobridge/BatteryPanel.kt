package dev.autobridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The settings page's battery details, worked out from [Truck] (pure, so it's unit-tested). "—" where unknown. */
object BatteryInfo {
    enum class State(val label: String) { CHARGING("CHARGING"), FULL("CHARGED"), PLUGGED("PLUGGED IN"), OPEN("PORT OPEN"), ON_BATTERY("ON BATTERY") }
    data class Info(
        val percent: Int?, val fill: Float, val limit: Float?,      // 0..1
        val energy: String, val range: String, val rangeUnit: String, val state: State,
        val power: String, val timeLabel: String, val timeLeft: String, val readyBy: String,
        val adding: String, val toAddLabel: String, val toAdd: String, val current: String,
    )

    private const val NONE = "—"

    fun of(t: Truck, localeMiles: Boolean, now: LocalTime): Info {
        val miles = t.miles ?: localeMiles
        val pct = t.batteryPercent
        val limit = t.chargeLimit?.let { (it / 100f).coerceIn(0f, 1f) }
        val kw = t.rateMw?.let { -it / 1_000_000f }?.takeIf { t.charging && it > 0f }  // power going into the battery
        val state = when {
            t.charging -> State.CHARGING
            t.chargeState == 2 -> State.FULL
            t.portConnected == true -> State.PLUGGED
            t.portOpen == true -> State.OPEN
            else -> State.ON_BATTERY
        }
        // Range gained per hour of charging, from the truck's own range per kWh right now.
        val perWh = if (t.rangeM != null && t.batteryWh != null && t.batteryWh > 0) t.rangeM / t.batteryWh else null
        val sec = t.chargeSec?.takeIf { t.charging && it > 0 }
        val toAddWh = if (limit != null && t.capacityWh != null && t.batteryWh != null) limit * t.capacityWh - t.batteryWh else null
        val limitText = t.chargeLimit?.let { "${it.roundToInt()}%" }
        return Info(
            percent = pct, fill = (pct ?: 0) / 100f, limit = limit,
            energy = if (t.batteryWh != null) "${(t.batteryWh / 1000).roundToInt()}" + (t.capacityWh?.let { " of ${(it / 1000).roundToInt()}" } ?: "") + " kWh" else NONE,
            range = t.rangeM?.let { "${(if (miles) it / 1609.344f else it / 1000).roundToInt()}" } ?: NONE,
            rangeUnit = if (miles) "mi" else "km", state = state,
            power = kw?.let { "${BarText.number(it)} kW" } ?: NONE,
            timeLabel = limitText?.let { "TIME TO $it" } ?: "TIME LEFT",
            timeLeft = sec?.let(BarText::duration) ?: NONE,
            readyBy = sec?.let { now.plusSeconds(it.toLong()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)) } ?: NONE,
            adding = if (kw != null && perWh != null) "+${(kw * 1000 * perWh / if (miles) 1609.344f else 1000f).roundToInt()} ${if (miles) "mi" else "km"}/h" else NONE,
            toAddLabel = limitText?.let { "TO $it" } ?: "TO LIMIT",
            toAdd = toAddWh?.let { if (it > 500) "${(it / 1000).roundToInt()} kWh" else "At limit" } ?: NONE,
            current = t.currentA?.let { "${it.roundToInt()} A" } ?: NONE,
        )
    }
}

/**
 * A big battery for the settings page: percent, energy and range on top, the pack with its cells,
 * charge limit and (while charging) a moving sweep, then six charging details underneath.
 */
class BatteryPanel(c: Context, private val k: Float) : View(c) {
    private var info = BatteryInfo.of(Truck(), true, LocalTime.now())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(t: Truck) {
        info = BatteryInfo.of(t, Locale.getDefault().country in setOf("US", "GB", "LR", "MM"), LocalTime.now())
        contentDescription = "Battery ${info.percent ?: "unknown"} percent, ${info.state.label.lowercase()}, range ${info.range} ${info.rangeUnit}"
        if (height != (height() * k).roundToInt()) requestLayout()  // one row of details when not charging, two while charging
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val i = info
        val charging = i.state == BatteryInfo.State.CHARGING
        val accent = when {
            charging -> GREEN
            i.percent == null -> MUTED
            i.percent <= 15 -> LOW
            i.percent <= 30 -> AMBER
            else -> MINT
        }
        c.save(); c.scale(k, k)
        val w = width / k
        // card
        val card = Path().apply { moveTo(1f, 1f); lineTo(w - 30f, 1f); lineTo(w - 1f, 30f); lineTo(w - 1f, H - 1f); lineTo(1f, H - 1f); close() }
        fill(BLACK); c.drawPath(card, p); line(if (charging) GREEN else OUTLINE, 2f); c.drawPath(card, p)
        // state pill
        val pill = i.state.label
        text(24f, false); p.letterSpacing = 0.12f; val pw = p.measureText(pill) + 44f  // measured as drawn
        val pillColor = when (i.state) { BatteryInfo.State.CHARGING, BatteryInfo.State.FULL -> GREEN; BatteryInfo.State.PLUGGED -> BLUE; BatteryInfo.State.OPEN -> AMBER; else -> MUTED }
        line(pillColor, 2f); c.drawRect(w - 44f - pw, 34f, w - 44f, 80f, p)
        text(24f, false, pillColor); p.letterSpacing = 0.12f; c.drawText(pill, w - 44f - pw + 22f, 66f, p); p.letterSpacing = 0f
        // headline: percent, energy, range
        text(120f, true); c.drawText(i.percent?.toString() ?: "—", 40f, 150f, p)
        val pctW = p.measureText(i.percent?.toString() ?: "—")
        text(48f, false, LIGHT); c.drawText("%", 48f + pctW, 150f, p)
        text(28f, false, MUTED); c.drawText(i.energy, 44f, 192f, p)
        text(72f, true); val rw = p.measureText(i.range)
        text(32f, false, LIGHT); val uw = p.measureText(" ${i.rangeUnit}")
        text(72f, true); c.drawText(i.range, w - 44f - uw - rw, 150f, p)
        text(32f, false, LIGHT); c.drawText(" ${i.rangeUnit}", w - 44f - uw, 150f, p)
        text(26f, false, MUTED); p.textAlign = Paint.Align.RIGHT; c.drawText("Range", w - 44f, 192f, p); p.textAlign = Paint.Align.LEFT
        // the pack
        val l = 44f; val r = w - 70f; val t = 252f; val b = 352f
        line(OUTLINE, 3f); c.drawRoundRect(RectF(l, t, r, b), 14f, 14f, p)
        fill(OUTLINE); c.drawRoundRect(RectF(r + 6f, t + 30f, r + 22f, b - 30f), 4f, 4f, p)
        val il = l + 10f; val ir = r - 10f; val it = t + 10f; val ib = b - 10f
        val fx = il + (ir - il) * i.fill
        if (charging && i.limit != null && i.limit > i.fill) {  // the part still to fill, up to the limit
            fill((GREEN and 0xFFFFFF) or 0x2E000000); c.drawRect(fx, it, il + (ir - il) * i.limit, ib, p)
        }
        if (i.fill > 0f) {
            fill(BLACK)  // opaque: a paint's alpha also fades its gradient
            p.shader = LinearGradient(il, 0f, fx, 0f, (accent and 0xFFFFFF) or 0x99000000.toInt(), accent, Shader.TileMode.CLAMP)
            c.drawRect(il, it, fx, ib, p); p.shader = null
            if (charging) {  // a bright sweep runs through the charge
                val phase = (SystemClock.uptimeMillis() % 2200L) / 2200f
                val sx = il + (fx - il) * phase
                fill(BLACK); p.shader = LinearGradient(sx - 120f, 0f, sx + 40f, 0f, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                c.save(); c.clipRect(il, it, fx, ib); c.drawRect(sx - 120f, it, sx + 40f, ib, p); c.restore(); p.shader = null
                postInvalidateDelayed(40)
            }
        }
        line(BLACK, 4f); for (n in 1 until 20) { val x = il + (ir - il) * n / 20f; c.drawLine(x, it, x, ib, p) }  // cells
        i.limit?.let { lim ->
            val lx = il + (ir - il) * lim
            fill(WHITE); c.drawRect(lx - 3f, t - 14f, lx + 3f, b + 14f, p)
            text(22f, false, LIGHT); p.textAlign = Paint.Align.CENTER; p.letterSpacing = 0.1f
            c.drawText("LIMIT ${(lim * 100).roundToInt()}%", lx.coerceIn(l + 70f, r - 70f), t - 24f, p)
            p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0f
        }
        // details
        // Power, time, ready-by and adding only mean something while charging; otherwise they'd sit as dashes.
        val cells = if (charging) listOf(
            "POWER" to i.power, i.timeLabel to i.timeLeft, "READY BY" to i.readyBy,
            "ADDING" to i.adding, i.toAddLabel to i.toAdd, "CURRENT LIMIT" to i.current,
        ) else listOf(i.toAddLabel to i.toAdd, "CURRENT LIMIT" to i.current)
        val cw = (w - 88f) / 3
        line(0xFF2A2D31.toInt(), 2f); c.drawLine(44f, 396f, w - 44f, 396f, p)
        cells.forEachIndexed { n, (label, value) ->
            val x = 44f + cw * (n % 3); val y = 442f + (n / 3) * 96f
            text(22f, false, MUTED); p.letterSpacing = 0.12f; c.drawText(label, x, y, p); p.letterSpacing = 0f
            text(40f, false, if (charging && value != "—" && n < 4) GREEN else WHITE); c.drawText(value, x, y + 48f, p)
        }
        c.restore()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (height() * k).roundToInt())

    private fun height() = if (info.state == BatteryInfo.State.CHARGING) H else H - 96f

    private fun fill(color: Int) { p.style = Paint.Style.FILL; p.color = color; p.shader = null }
    private fun line(color: Int, w: Float) { p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.shader = null }
    private fun text(size: Float, bold: Boolean, color: Int = WHITE) {
        fill(color); p.textSize = size; p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private companion object {
        const val H = 610f
        const val WHITE = 0xFFF1F3F4.toInt()
        const val LIGHT = 0xFFBDC1C6.toInt()
        const val MUTED = 0xFF9AA0A6.toInt()
        const val GREEN = 0xFF81C995.toInt()
        const val MINT = GREEN  // one green everywhere (was a separate mint that clashed with the truck bar)
        const val AMBER = 0xFFF2BD64.toInt()
        const val LOW = 0xFFFF857D.toInt()
        const val BLUE = 0xFF8AB4F8.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val OUTLINE = 0xFF8E939B.toInt()
    }
}
