package dev.autobridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * AutoBridge settings, shown over Android Auto when the owner taps the bar's charge door (or the
 * waiting screen's Settings button). Black and boxy like the truck's own screens: the Sierra on the
 * left with Android Auto / Exit, the settings as tiles and buttons on the right.
 */
class SettingsPanel(
    context: Context,
    private val editWifi: () -> Unit,
    private val toast: (String) -> Unit,
    private val onShapeChanged: () -> Unit,
    private val onExit: () -> Unit,
) : FrameLayout(context) {
    private val s = Settings(context)
    /** Set when a tile changed Android Auto's shape; applied (with a restart if connected) on close. */
    private var shapeChanged = false
    /** Sizes are taken from the 2012 px wide mockup and scaled to this screen. */
    private val k = resources.displayMetrics.widthPixels / 2012f

    private val phone = fact(); private val version = fact(); private val picture = fact()
    private val hotspot = text(40f)
    private val hotspotDetail = text(26f, MUTED, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val voiceDetail = text(26f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val tiles = mutableListOf<() -> Unit>()
    private val batteryPanel = BatteryPanel(context, k)
    private val eqPanel: EqPanel = EqPanel(context, k) { eqPanel.close(); this@SettingsPanel.close() }

    init {
        setBackgroundColor(BLACK)
        isClickable = true  // nothing underneath gets the touches
        visibility = GONE
        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(40), px(56), px(40), px(44))
            background = TruckBar.chamfer(BLACK, OUTLINE, px(44).toFloat())
            addView(text(26f, MUTED).apply { text = "AUTOBRIDGE"; letterSpacing = 0.25f })
            addView(ImageView(context).apply { setImageResource(R.drawable.sierra_truck); adjustViewBounds = true },
                LinearLayout.LayoutParams(px(740), -2).apply { topMargin = px(60) })
            addView(text(84f, light = true).apply {
                text = SpannableStringBuilder("SIERRA EV").apply { setSpan(StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                letterSpacing = 0.26f
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(50) })
            addView(text(26f, MUTED).apply { text = "WIRELESS ANDROID AUTO"; letterSpacing = 0.25f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(10) })
            addView(View(context).apply { setBackgroundColor(0xFF2A2D31.toInt()) }, LinearLayout.LayoutParams(-1, px(2)).apply { topMargin = px(80) })
            addView(LinearLayout(context).apply {
                addView(facts("PHONE", phone, "PICTURE", picture), LinearLayout.LayoutParams(0, -2, 1f))
                addView(facts("VERSION", version), LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(28) })
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(button("Equalizer") { eqPanel.open() }, LinearLayout.LayoutParams(-1, px(120)).apply { bottomMargin = px(22) })
            addView(button("Android Auto", primary = true) { close() }, LinearLayout.LayoutParams(-1, px(120)))
            addView(button("Exit") { onExit() }, LinearLayout.LayoutParams(-1, px(120)).apply { topMargin = px(22) })
        }
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            header("EV BATTERY", first = true)
            addView(batteryPanel, LinearLayout.LayoutParams(-1, -2))
            header("DISPLAY")
            addView(text(26f, MUTED).apply { text = "Changes restart Android Auto when you go back to it." },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = -px(6); bottomMargin = px(16) })
            addView(LinearLayout(context).apply {
                addView(tile(Glyph.BAR, "Truck bar", "Battery, range, speed and power under Android Auto", { s.barEnabled }) { s.barEnabled = it }, LinearLayout.LayoutParams(0, px(200), 1f))
                addView(tile(Glyph.STATUS, "Status bar", "Time, phone and alerts at the top. Off gives Android Auto the space", { s.statusBarShown }) { s.statusBarShown = it },
                    LinearLayout.LayoutParams(0, px(200), 1f).apply { marginStart = px(22) })
                addView(tile(Glyph.HD, "HD picture", "1440p (H.265), drops to 1080p by itself", { s.hevcEnabled }) { s.hevcEnabled = it },
                    LinearLayout.LayoutParams(0, px(200), 1f).apply { marginStart = px(22) })
            })
            header("TRUCK HOTSPOT")
            addView(row(hotspot, hotspotDetail, button("Test") {
                val service = BridgeService.instance ?: return@button toast("AutoBridge isn't running")
                toast("Checking the hotspot details with your phone…")
                service.testWifi { toast(it); refresh() }
            }, button("Edit") { editWifi() }, button("Forget") { confirmForget() }))
            header("PHONE")
            addView(LinearLayout(context).apply {
                addView(row(text(40f).apply { text = "Voice button" }, voiceDetail, button("Change") {
                    VoiceAssistant.picker(context)?.let { runCatching { context.startActivity(it) } } ?: toast("This truck has no assistant setting")
                }), LinearLayout.LayoutParams(0, -2, 1f))
                addView(row(text(40f).apply { text = "Send log" }, text(26f, MUTED).apply { text = "Saved on the phone" }, button("Send") {
                    BridgeService.instance?.sendLogs("settings") ?: toast("AutoBridge isn't running yet")
                }), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(22) })
            })
        }
        addView(LinearLayout(context).apply {
            setPadding(px(64), px(90), px(64), px(40))
            addView(hero, LinearLayout.LayoutParams(px(820), -1))
            addView(ScrollView(context).apply { isFillViewport = true; addView(right) }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = px(56) })
        }, LayoutParams(-1, -1))
        addView(eqPanel, LayoutParams(-1, -1))  // on top of the settings page when open
    }

    fun open() { shapeAtOpen = shape(); refresh(); visibility = VISIBLE; showTruck(BridgeService.latestTruck) }

    /** The settings that change Android Auto's shape; a tile tapped on and back off changes nothing. */
    private fun shape() = listOf(s.barEnabled, s.statusBarShown, s.hevcEnabled)
    private var shapeAtOpen = emptyList<Boolean>()

    /** Live truck data while the page is open. */
    fun showTruck(t: Truck) {
        if (visibility != VISIBLE) return
        batteryPanel.show(t)
    }

    /** True if the page was open (so Back closes it instead of leaving AutoBridge). */
    fun close(): Boolean = eqPanel.close() || (visibility == VISIBLE).also {
        visibility = GONE
        if (shapeChanged && shape() != shapeAtOpen) onShapeChanged()
        shapeChanged = false
    }

    private fun refresh() {
        tiles.forEach { it() }
        hotspot.text = s.ssid.ifEmpty { "Not set" }
        hotspotDetail.text = if (s.bssid.isEmpty()) "Open AutoBridge Phone, or Edit" else "${s.bssid} · password ${if (s.password.isEmpty()) "missing" else "saved"}"
        voiceDetail.text = VoiceAssistant.status(context).removePrefix("Voice button: ").replaceFirstChar { it.uppercase() }
        version.text = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
        val video = BridgeService.instance?.video
        phone.text = BridgeService.phoneName ?: "No phone yet"
        phone.setTextColor(if (video != null) GREEN else WHITE)  // green while Android Auto is connected
        picture.text = video?.let { "${it.codecH}p" } ?: "Not connected"
    }

    /** Forgetting is the one destructive action here, so it asks first and says what happens next. */
    private fun confirmForget() {
        val name = s.ssid.ifEmpty { return toast("No hotspot details to forget") }
        android.app.AlertDialog.Builder(context).setTitle("Forget $name?")
            .setMessage("AutoBridge will ask AutoBridge Phone for the hotspot details again, or you can enter them with Edit.")
            .setPositiveButton("Forget") { _, _ -> s.forget(); refresh(); toast("Forgot $name") }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- building blocks, in the truck's boxy style ----

    private fun px(v: Int) = (v * k).roundToInt()

    private fun text(size: Float, color: Int = WHITE, mono: Boolean = false, light: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k); setTextColor(color)
        typeface = when { mono -> Typeface.MONOSPACE; light -> Typeface.create("sans-serif-light", Typeface.NORMAL); else -> Typeface.create("sans-serif", Typeface.NORMAL) }
    }

    /** One line, cut with "…" so a long phone name can't run into the next column. */
    private fun fact() = text(40f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, px(24), 0) }

    private fun facts(k1: String, v1: TextView, k2: String? = null, v2: TextView? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(22f, MUTED).apply { text = k1; letterSpacing = 0.14f }); addView(v1)
        if (k2 != null && v2 != null) {
            addView(text(22f, MUTED).apply { text = k2; letterSpacing = 0.14f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(30) }); addView(v2)
        }
    }

    private fun LinearLayout.header(title: String, first: Boolean = false) =
        addView(text(28f, MUTED).apply { text = title; letterSpacing = 0.22f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(if (first) 0 else 32); bottomMargin = px(16) })

    private fun row(title: TextView, detail: TextView, vararg actions: View) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(px(40), px(36), px(40), px(36))
        background = TruckBar.chamfer(BLACK, OUTLINE, px(24).toFloat())
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(detail) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.forEach { addView(it, LinearLayout.LayoutParams(-2, px(96)).apply { marginStart = px(24) }) }
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = text(if (primary) 34f else 30f, if (primary) BLUE else WHITE).apply {
        text = label; gravity = Gravity.CENTER; letterSpacing = 0.02f; minWidth = px(200); setPadding(px(34), 0, px(34), 0)
        background = TruckBar.chamfer(if (primary) NAVY else BLACK, if (primary) BLUE else OUTLINE, px(20).toFloat())
        setOnClickListener { onClick() }
    }

    /** A setting tile, lit blue while on; tap to switch. */
    private fun tile(glyph: Glyph, title: String, detail: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val icon = GlyphView(context, glyph)
        val state = text(24f).apply { letterSpacing = 0.12f }
        val box = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(26), px(20), px(20), px(20))
            addView(icon, LinearLayout.LayoutParams(px(84), px(84)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(34f).apply { text = title; maxLines = 1 })
                addView(state, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(8) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(22) })
        }
        val paint = {
            val on = get()
            box.background = TruckBar.chamfer(if (on) NAVY else BLACK, if (on) BLUE else OUTLINE, px(30).toFloat())
            state.text = if (on) "ON" else "OFF"; state.setTextColor(if (on) BLUE else MUTED)
            icon.color = if (on) BLUE else MUTED; icon.invalidate()
            box.contentDescription = "$title, ${if (on) "on" else "off"}. $detail"
        }
        tiles += paint
        box.setOnClickListener { set(!get()); shapeChanged = true; paint() }
        return box
    }

    private enum class Glyph { BAR, STATUS, HD }

    /** The tile pictures: a screen with the bar at the bottom, the status bar at the top, or "HD". */
    private class GlyphView(c: Context, private val glyph: Glyph) : View(c) {
        var color = MUTED
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val u = width / 48f
            p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = 3 * u
            c.drawRect(RectF(4 * u, 8 * u, 44 * u, 40 * u), p)
            p.style = Paint.Style.FILL
            when (glyph) {
                Glyph.BAR -> c.drawRect(8 * u, 30 * u, 40 * u, 36 * u, p)
                Glyph.STATUS -> c.drawRect(8 * u, 12 * u, 40 * u, 17 * u, p)
                Glyph.HD -> { p.textSize = 13 * u; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD; c.drawText("HD", 24 * u, 29 * u, p) }
            }
        }
    }

    private companion object {
        const val WHITE = 0xFFF1F3F4.toInt()
        const val MUTED = 0xFF9AA0A6.toInt()
        const val BLUE = 0xFF8AB4F8.toInt()
        const val GREEN = 0xFF81C995.toInt()
        const val NAVY = 0xFF0D1B2E.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val OUTLINE = 0xFF8E939B.toInt()
    }
}
