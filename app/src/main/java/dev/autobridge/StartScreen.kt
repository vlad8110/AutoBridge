package dev.autobridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The screen while Android Auto comes up: the phone and the truck on a dotted line, one step card rolling
 * in a box on that line (Bluetooth → Wi-Fi → Phone app → Android Auto → Picture), and the last few status
 * lines rolling underneath. A failed step turns red and shows what to do, with Retry and Send log.
 * Drawn in the 2048-wide design of the mock (promo/v0.8/startup.html) and scaled to fit.
 */
@SuppressLint("ViewConstructor")
class StartScreen(c: Context, onSettings: () -> Unit, private val onLog: () -> Unit, private val onRetry: () -> Unit) : FrameLayout(c) {
    data class State(val step: Int, val failed: String?)

    private class Entry(val text: String, val at: Float?, val kind: Console.Kind)

    private var lines = emptyList<String>()
    private val log = ArrayList<Entry>()
    private var base = 0L
    private var state = State(0, null)
    private var ready = false
    private var readyAt = 0L
    private var roll = 0f; private var rollFrom = 0f; private var rollTo = 0f; private var rollAt = 0L
    private var logAt = 0L

    private val k0 = resources.displayMetrics.widthPixels / 2012f
    private val scene = Scene()
    private val gear = IconButton(true).apply { contentDescription = "Settings"; setOnClickListener { onSettings() } }
    private val logBtn = IconButton(false).apply { contentDescription = "Send the log to the phone"; setOnClickListener { onLog() } }
    private val retry = Lamp.button(c, k0, "Retry", 36f) { onRetry() }
    private val send = Lamp.button(c, k0, "Send log", 36f) { onLog() }

    init {
        setBackgroundColor(BLACK)
        addView(scene, LayoutParams(-1, -1))
        for (v in listOf(logBtn, gear, retry, send)) addView(v, LayoutParams(0, 0))
        showButtons()
    }

    /** The service's last few status lines (newest last). */
    fun status(all: String) {
        val now = System.currentTimeMillis()
        val next = all.lines().filter { it.isNotBlank() }
        var k = min(lines.size, next.size)  // the service keeps the last 6: find where the old list continues
        while (k > 0 && lines.takeLast(k) != next.take(k)) k--
        val added = next.drop(k)
        lines = next
        // Lines that arrive together (opening the screen) have no timing to show.
        added.forEach { add(it, if (added.size == 1) now else null) }
        state = parse(next)
        rollTo(state.step.toFloat())
        showButtons()
    }

    /** A one-off note (from settings): shown in the log only. */
    fun note(text: String) = add(text, null)

    /** The first picture arrived (true), or Android Auto went away (false). */
    fun ready(on: Boolean) {
        if (ready == on) return
        ready = on; readyAt = System.currentTimeMillis()
        if (on) rollTo(4f)
        showButtons(); scene.invalidate()
    }

    private fun add(text: String, now: Long?) {
        if (now != null && text.startsWith("Waiting for phone over Bluetooth")) base = now
        log += Entry(text, now?.let { if (base == 0L) null else (it - base) / 1000f }, Console.kind(text))
        while (log.size > 8) log.removeAt(0)
        logAt = System.currentTimeMillis()
        scene.invalidate()
    }

    private fun rollTo(target: Float) {
        if (target == rollTo) return
        rollFrom = roll; rollTo = target; rollAt = System.currentTimeMillis()
    }

    private fun failed() = state.failed != null && !ready

    private fun showButtons() {
        val f = failed()
        retry.visibility = if (f) VISIBLE else GONE
        send.visibility = if (f) VISIBLE else GONE
    }

    // ---- layout: the mock's 2048-wide coordinates, content from y = 220 down ----

    private var k = 1f; private var ox = 0f; private var oy = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        k = min(w / 2048f, h / 1650f); ox = (w - 2048 * k) / 2; oy = (h - 1650 * k) / 2 - 220 * k
        place(logBtn, 1502, 250, 142, 104); place(gear, 1665, 232, 200, 140)
        place(retry, 1215, 1304, 230, 112); place(send, 1469, 1304, 230, 112)
        post { requestLayout() }
    }

    private fun place(v: View, x: Int, y: Int, w: Int, h: Int) {
        (v.layoutParams as LayoutParams).apply {
            width = (w * k).roundToInt(); height = (h * k).roundToInt()
            leftMargin = (ox + x * k).roundToInt(); topMargin = (oy + y * k).roundToInt()
        }
    }

    /** Log (three lines) or Settings (a gear), on the lamp button. */
    private inner class IconButton(private val isGear: Boolean) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = WHITE; strokeCap = Paint.Cap.ROUND }
        init { Lamp.style(this, k0, 22 * k0) }
        override fun onDraw(c: Canvas) {
            val s = min(width, height) * (if (isGear) 0.54f else 0.52f); val u = s / 54
            c.translate((width - s) / 2, (height - s) / 2 - 6 * u)
            p.strokeWidth = 3.2f * u
            if (!isGear) { for (y in listOf(12, 22, 32)) c.drawLine(10 * u, y * u, 44 * u, y * u, p); c.drawLine(10 * u, 42 * u, 30 * u, 42 * u, p); return }
            c.drawCircle(27 * u, 27 * u, 8 * u, p); c.drawCircle(27 * u, 27 * u, 16 * u, p)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                c.drawLine((27 + 17 * Math.cos(a)).toFloat() * u, (27 + 17 * Math.sin(a)).toFloat() * u, (27 + 23 * Math.cos(a)).toFloat() * u, (27 + 23 * Math.sin(a)).toFloat() * u, p)
            }
        }
    }

    // ---- drawing ----

    private inner class Scene : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL) }
        private val light = Fonts.of(context, Fonts.LIGHT)
        private val regular = Fonts.of(context, Fonts.REGULAR)
        private val ease = OvershootInterpolator(1.4f)
        private var help: StaticLayout? = null; private var helpFor: String? = null

        override fun onDraw(c: Canvas) {
            val now = System.currentTimeMillis()
            c.save(); c.translate(ox, oy); c.scale(k, k)
            val f = failed()
            // roll: waits a moment (the lamp lights first), then springs to the next card
            val t = ((now - rollAt - 350) / 500f).coerceIn(0f, 1f)
            roll = rollFrom + (rollTo - rollFrom) * ease.getInterpolation(t)
            val on = if (ready) ((now - readyAt) / 500f).coerceIn(0f, 1f) else 0f
            val pulse = (sin(now / 1000.0 * Math.PI * 2) * 0.5 + 0.5).toFloat()

            words(c, "ANDROID AUTO", 80f, 270f, 26f, LABEL, light, 0.24f)
            words(c, BridgeService.phoneName?.let { "Connecting to $it" } ?: "Waiting for your phone", 80f, 344f, 64f, WHITE, light)
            chamfer(c, 80f, 470f, 1785f, 1090f, 38f, OUTLINE)

            // phone
            stroke(5f, DRAW)
            c.drawRoundRect(338f, 851f, 472f, 1069f, 18f, 18f, p); c.drawLine(390f, 867f, 420f, 867f, p)
            // dotted line, red up to where the connection has got
            val frac = if (ready) 1f else (state.step + if (f) 0f else 0.5f) / 5f
            dashes(c, 500f, 1254f, DASH)
            dashes(c, 500f, 500f + 754f * frac, if (f) FAIL else LAMP)
            truck(c, on)

            // the box on the line, and the card rolling inside it
            chamfer(c, 712f, 778f, 520f, 180f, 26f, BOX)
            c.save(); c.clipRect(742f, 798f, 1202f, 938f)
            for (i in 0 until 5) {
                val y = 798f + (i - roll) * 170f
                if (y > 938f || y + 140f < 798f) continue
                card(c, i, y, f, pulse, on)
            }
            c.restore()
            for (i in 0 until 5) {
                p.style = Paint.Style.FILL
                p.color = when { ready || i < state.step -> LAMP; i == state.step && f -> FAIL; i == state.step -> 0xFF8A8E92.toInt(); else -> 0xFF2C2F31.toInt() }
                val x = 972f - 140f + i * 56f
                c.drawRoundRect(x, 990f, x + 44f, 995f, 2.5f, 2.5f, p)
            }

            if (f) helpCard(c) else words(c, if (ready) "Android Auto is ready" else DOING[state.step] + "…", 972f, 1205f, 50f, WHITE, light, center = true)
            logRows(c, now)
            c.restore()
            if (isShown) postInvalidateDelayed(33)  // the lamps pulse while waiting; nothing runs while hidden
        }

        private fun card(c: Canvas, i: Int, y: Float, f: Boolean, pulse: Float, on: Float) {
            val bad = f && i == state.step
            chamfer(c, 742f, y, 460f, 140f, 22f, if (bad) 0xFFB02533.toInt() else OUTLINE)
            val done = ready || i < state.step
            val lamp = when {
                done -> LAMP
                bad -> mix(0xFF4A0710.toInt(), FAIL, pulse)
                i == state.step -> mix(LAMP_OFF, 0xFFA8102A.toInt(), pulse)
                else -> LAMP_OFF
            }
            if (done) { glow.strokeWidth = 8f; glow.color = 0x88FF2A45.toInt(); c.drawLine(762f, y + 128f, 1182f, y + 128f, glow) }
            p.style = Paint.Style.FILL; p.color = lamp; c.drawRoundRect(756f, y + 126f, 1188f, y + 131f, 2.5f, 2.5f, p)
            if (i < 4) { words(c, STEPS[i], 972f, y + 84f, 46f, if (bad) 0xFFFF7F8A.toInt() else WHITE, regular, center = true); return }
            // the last card: Android Auto's badge, grey until the picture comes, then blue
            val label = "Android Auto"
            text.textSize = 46f; text.typeface = regular
            val w = 78f + 22f + text.measureText(label); val x = 972f - w / 2; val cy = y + 64f
            p.style = Paint.Style.FILL; p.color = mix(0xFF4A4D51.toInt(), AA_BLUE, on); c.drawCircle(x + 39f, cy, 38f, p)
            stroke(9f, mix(MUTED, WHITE, on))
            c.drawPath(Path().apply { moveTo(x + 24f, cy + 15f); lineTo(x + 39f, cy - 15f); lineTo(x + 54f, cy + 15f) }, p)
            words(c, label, x + 100f, y + 84f, 46f, mix(MUTED, WHITE, on), regular)
        }

        private fun helpCard(c: Canvas) {
            val why = state.failed ?: return
            chamfer(c, 200f, 1200f, 1545f, 320f, 26f, 0xFFA3222F.toInt())
            val title = if (why.startsWith("Disconnected")) "Android Auto disconnected" else FAILED[state.step]
            words(c, title, 244f, 1284f, 52f, 0xFFFF7F8A.toInt(), regular)
            if (helpFor != why) {
                text.textSize = 40f; text.typeface = light; text.color = 0xFFD6D8DA.toInt()
                help = StaticLayout.Builder.obtain(why, 0, why.length, TextPaint(text), 940).setLineSpacing(0f, 1.25f)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(4).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                helpFor = why
            }
            c.save(); c.translate(244f, 1312f); help?.draw(c); c.restore()
        }

        private fun logRows(c: Canvas, now: Long) {
            val lh = 60f; val slide = lh * (1 - ((now - logAt) / 280f).coerceIn(0f, 1f))
            c.save(); c.clipRect(80f, 1610f, 1865f, 1850f)
            val shown = log.takeLast(5)
            shown.forEachIndexed { idx, e ->
                val age = shown.size - 1 - idx
                val base = 1850f - lh * age + slide - 18f
                val color = if (e.kind == Console.Kind.FAIL) 0xFFFF6B78.toInt() else AGE[min(age, 3)]
                e.at?.let { words(c, "+%.2f s".format(it), 230f, base, 34f, 0xFF6D7277.toInt(), light, right = true) }
                p.style = Paint.Style.FILL
                p.color = when (e.kind) { Console.Kind.OK -> OK; Console.Kind.WAIT -> WAIT; Console.Kind.FAIL -> FAIL; Console.Kind.INFO -> MUTED }
                c.drawCircle(259f, base - 11f, 7f, p)
                text.textSize = 34f; text.typeface = light
                words(c, android.text.TextUtils.ellipsize(e.text, text, 1580f, android.text.TextUtils.TruncateAt.END).toString(), 288f, base, 34f, color, light)
            }
            c.restore()
        }

        /** Sierra EV, side view facing right (the mock's drawing); the lamps light once connected. */
        private fun truck(c: Canvas, on: Float) {
            c.save(); c.translate(1274f, 875f)
            stroke(5f, DRAW)
            c.drawPath(Path().apply {
                moveTo(14f, 128f); lineTo(14f, 74f); lineTo(124f, 74f); lineTo(152f, 30f); lineTo(236f, 30f); lineTo(278f, 64f); lineTo(316f, 69f)
                quadTo(326f, 71f, 326f, 82f); lineTo(326f, 121f); lineTo(321f, 128f); lineTo(300f, 128f)
                arcTo(RectF(238f, 97f, 300f, 159f), 0f, -180f); lineTo(110f, 128f)
                arcTo(RectF(48f, 97f, 110f, 159f), 0f, -180f); close()
            }, p)
            p.strokeWidth = 3.5f; c.drawPath(Path().apply { moveTo(156f, 38f); lineTo(232f, 38f); lineTo(264f, 66f); lineTo(142f, 66f); close() }, p)
            p.strokeWidth = 3f
            for (l in listOf(floatArrayOf(198f, 38f, 198f, 116f), floatArrayOf(142f, 66f, 142f, 112f), floatArrayOf(264f, 66f, 264f, 112f),
                floatArrayOf(210f, 76f, 222f, 76f), floatArrayOf(154f, 76f, 166f, 76f), floatArrayOf(18f, 86f, 126f, 86f))) c.drawLine(l[0], l[1], l[2], l[3], p)
            p.strokeWidth = 5f; c.drawCircle(79f, 128f, 23f, p); c.drawCircle(269f, 128f, 23f, p)
            p.strokeWidth = 3f; c.drawCircle(79f, 128f, 9f, p); c.drawCircle(269f, 128f, 9f, p)
            val head = mix(0xFF3A3D41.toInt(), WHITE, on); val tail = mix(0xFF3A3D41.toInt(), LAMP, on)
            if (on > 0) {
                glow.strokeWidth = 14f
                glow.color = (head and 0xFFFFFF) or ((0x99 * on).toInt() shl 24); c.drawLine(300f, 72f, 318f, 72f, glow); c.drawLine(321f, 78f, 321f, 104f, glow)
                glow.color = (tail and 0xFFFFFF) or ((0x99 * on).toInt() shl 24); c.drawLine(19f, 78f, 19f, 102f, glow)
            }
            stroke(6f, head); c.drawLine(300f, 72f, 318f, 72f, p); c.drawLine(321f, 78f, 321f, 104f, p)
            stroke(6f, tail); c.drawLine(19f, 78f, 19f, 102f, p)
            c.restore()
        }

        private fun dashes(c: Canvas, from: Float, to: Float, color: Int) {
            p.style = Paint.Style.FILL; p.color = color
            var x = from
            while (x < to) { c.drawRect(x, 958f, min(x + 18f, to), 962f, p); x += 34f }
        }

        private fun chamfer(c: Canvas, x: Float, y: Float, w: Float, h: Float, cut: Float, edge: Int) {
            val path = Path().apply { moveTo(x + 1, y + 1); lineTo(x + w - cut, y + 1); lineTo(x + w - 1, y + cut); lineTo(x + w - 1, y + h - 1); lineTo(x + 1, y + h - 1); close() }
            p.style = Paint.Style.FILL; p.color = BLACK; c.drawPath(path, p)
            stroke(2f / k, edge); c.drawPath(path, p)  // a 2-pixel outline at any scale, like the truck's
        }

        private fun stroke(w: Float, color: Int) { p.style = Paint.Style.STROKE; p.strokeWidth = w; p.color = color; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND }

        private fun words(c: Canvas, s: String, x: Float, baseline: Float, size: Float, color: Int, face: android.graphics.Typeface,
                          spacing: Float = 0f, center: Boolean = false, right: Boolean = false) {
            text.textSize = size; text.color = color; text.typeface = face; text.letterSpacing = spacing
            text.textAlign = if (center) Paint.Align.CENTER else if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            c.drawText(s, x, baseline, text)
            text.letterSpacing = 0f; text.textAlign = Paint.Align.LEFT
        }
    }

    companion object {
        private val STEPS = listOf("Bluetooth", "Wi-Fi", "Phone app", "Android Auto", "Picture")
        private val DOING = listOf("Finding your phone over Bluetooth", "Moving your phone to the hotspot", "Looking for AutoBridge Phone",
            "Starting Android Auto", "Waiting for the first picture")
        private val FAILED = listOf("Bluetooth didn't connect", "The phone didn't join the hotspot", "AutoBridge Phone didn't answer",
            "Android Auto didn't start", "No picture from the phone")
        private val AGE = intArrayOf(WHITE, 0xFFB8BCC0.toInt(), 0xFF80858A.toInt(), 0xFF6B7076.toInt())
        private const val LABEL = 0xFFC9CCCF.toInt()
        private const val DRAW = 0xFFE8EAEB.toInt()
        private const val DASH = 0xFF4A4D51.toInt()
        private const val BOX = 0xFF5C6065.toInt()
        private const val LAMP = 0xFFFF2A45.toInt()
        private const val LAMP_OFF = 0xFF33060C.toInt()
        private const val FAIL = 0xFFFF2B3C.toInt()
        private const val OK = 0xFF3CF05A.toInt()
        private const val WAIT = 0xFFFFD21A.toInt()
        private const val AA_BLUE = 0xFF1A73E8.toInt()

        /** Lines that aren't about connecting (screenshots, logs, audio): shown in the log, ignored for the steps. */
        private val ASIDE = listOf("Screenshot", "Log saved", "log to the phone", "logs can't be sent", "took the speakers", "Sharper picture")

        /** Which step the connection is on, and the line that stopped it (null while it's going fine). */
        fun parse(lines: List<String>): State {
            var step = 0; var failed: String? = null
            for (l in lines) {
                if (ASIDE.any { it in l }) continue
                when {
                    l.startsWith("Waiting for phone over Bluetooth") -> { step = 0; failed = null }
                    "asking the phone to rejoin" in l -> { step = 1; failed = null }
                    l.startsWith("Phone ") && l.endsWith("connected over Bluetooth") -> { step = max(step, 1); failed = null }
                    HANDSHAKE.any { l.startsWith(it) } -> { step = max(step, 1); failed = null }
                    l.startsWith("Looking for the AutoBridge Phone app") -> { step = max(step, 2); failed = null }
                    l.startsWith("Found phone at") -> { step = max(step, 3); failed = null }
                    l == "Android Auto connected" -> { step = 4; failed = null }
                    Console.kind(l) == Console.Kind.FAIL -> failed = l
                }
            }
            return State(step, failed)
        }
        private val HANDSHAKE = listOf("Sent version", "Phone version reply", "Sent Wi-Fi", "Phone start response", "Phone Wi-Fi status: 0")

        private fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).roundToInt() shl s
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}
