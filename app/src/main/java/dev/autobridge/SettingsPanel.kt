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
 * left with Equalizer / Android Auto and the system details, the battery and settings on the right.
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

    private val phone = fact()
    private val version = sysValue(); private val picture = sysValue(); private val sound = sysValue(); private val aaState = sysValue()
    private lateinit var eqButton: View
    private lateinit var aaGlyph: GlyphView
    private val hotspot = text(30f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val hotspotDetail = text(22f, MUTED, mono = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val voiceDetail = text(26f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val tiles = mutableListOf<() -> Unit>()
    private val iconLamps = mutableListOf<() -> Unit>()
    private val sizeDetail = text(26f, MUTED)
    private val batteryPanel = BatteryPanel(context, k)
    private val eqPanel: EqPanel = EqPanel(context, k) { eqPanel.close(); this@SettingsPanel.close() }

    init {
        setBackgroundColor(BLACK)
        isClickable = true  // nothing underneath gets the touches
        visibility = GONE
        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(40), px(56), px(40), px(40))
            background = TruckBar.chamfer(BLACK, OUTLINE, px(44).toFloat())
            addView(text(26f, MUTED).apply { text = "AUTOBRIDGE"; letterSpacing = 0.25f })
            addView(ImageView(context).apply { setImageResource(R.drawable.sierra_truck); adjustViewBounds = true },
                LinearLayout.LayoutParams(px(620), -2).apply { topMargin = px(16) })
            addView(text(76f, light = true).apply {
                text = SpannableStringBuilder("SIERRA EV").apply { setSpan(StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                letterSpacing = 0.26f
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(28) })
            addView(text(26f, MUTED).apply { text = "WIRELESS ANDROID AUTO"; letterSpacing = 0.25f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(10) })
            addView(LinearLayout(context).apply {
                eqButton = bigButton(Glyph.EQ, "Equalizer", momentary = false) { eqPanel.open() }
                addView(eqButton, LinearLayout.LayoutParams(0, px(250), 1f))
                addView(bigButton(Glyph.AA, "Android Auto") { close() }, LinearLayout.LayoutParams(0, px(250), 1f).apply { marginStart = px(22) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(50) })
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            // Below: the phone, the hotspot it joins (set once, so kept quiet), then system details only needed when something isn't working.
            addView(phone, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(24) })
            addView(text(22f, MUTED).apply { text = "Truck hotspot" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
            addView(hotspot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2) })
            addView(hotspotDetail, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(context).apply {
                addView(small("Test") {
                    val service = BridgeService.instance ?: return@small toast("AutoBridge isn't running")
                    toast("Checking the hotspot details with your phone…")
                    service.testWifi { toast(it); refresh() }
                }, LinearLayout.LayoutParams(-2, px(72)))
                addView(small("Edit") { editWifi() }, LinearLayout.LayoutParams(-2, px(72)).apply { marginStart = px(16) })
                addView(small("Forget") { confirmForget() }, LinearLayout.LayoutParams(-2, px(72)).apply { marginStart = px(16) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
            addView(View(context).apply { setBackgroundColor(0xFF2A2D31.toInt()) }, LinearLayout.LayoutParams(-1, px(2)).apply { topMargin = px(18) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                for ((key, value) in listOf("Version" to version, "Picture" to picture, "Sound" to sound, "Android Auto" to aaState)) {
                    addView(LinearLayout(context).apply {
                        addView(text(22f, MUTED).apply { text = key }, LinearLayout.LayoutParams(px(190), -2))
                        addView(value, LinearLayout.LayoutParams(0, -2, 1f))
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
            addView(text(22f, MUTED).apply { text = "Built by Vladimyr Merci\nfacebook.com/share/1CDtk9WNpE" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22) })
        }
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(batteryPanel, LinearLayout.LayoutParams(-1, -2))  // no heading: it's plainly the battery
            addView(text(34f).apply { text = "DISPLAY"; letterSpacing = 0.22f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(26) })
            addView(text(26f, MUTED).apply { text = "Changes restart Android Auto when you go back to it." },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(2); bottomMargin = px(16) })
            addView(row(text(40f).apply { text = "Screen" }, text(26f, MUTED).apply { text = "Lit: on" },
                toggle("Truck bar", "Battery, range, speed and power under Android Auto", { s.barEnabled }) { s.barEnabled = it },
                toggle("Status bar", "Time, phone and alerts at the top. Off gives Android Auto the space", { s.statusBarShown }) { s.statusBarShown = it },
                toggle("HD picture", "1440p (H.265), drops to 1080p by itself", { s.hevcEnabled }) { s.hevcEnabled = it }))
            // Android Auto's density, kept separately for each truck bar / status bar combination.
            addView(row(text(40f).apply { text = "Android Auto size" }, sizeDetail,
                button("−") { setDpi(Display.dpi(context) - DPI_STEP) },
                button("Auto") { s.aaDpi = 0; shapeChanged = true; refresh() },
                button("+") { setDpi(Display.dpi(context) + DPI_STEP) }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22) })
            addView(row(text(40f).apply { text = "Android Auto icons" }, text(26f, MUTED).apply { text = "Lit: shown on Android Auto's bar" },
                *listOf(Settings.HIDE_CLOCK to "Clock", Settings.HIDE_SIGNAL to "Signal", Settings.HIDE_BATTERY to "Battery").map { (bit, label) ->
                    Lamp.button(context, k, label, momentary = false) { s.aaHidden = s.aaHidden xor bit; shapeChanged = true; refresh() }.apply {
                        minWidth = px(150); setPadding(px(26), 0, px(26), px(6))
                        iconLamps += { Lamp.lit(this, s.aaHidden and bit == 0) }
                    }
                }.toTypedArray()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22) })
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))  // spare height goes here, so Close app ends level with the left card
            header("PHONE")
            addView(LinearLayout(context).apply {
                addView(row(text(40f).apply { text = "Voice button" }, voiceDetail, button("Change") {
                    VoiceAssistant.picker(context)?.let { runCatching { context.startActivity(it) } } ?: toast("This truck has no assistant setting")
                }), LinearLayout.LayoutParams(0, -2, 1f))
                addView(row(text(40f).apply { text = "Send log" }, text(26f, MUTED).apply { text = "Saved on the phone" }, button("Send") {
                    BridgeService.instance?.sendLogs("settings") ?: toast("AutoBridge isn't running yet")
                }), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(22) })
            })
            // Exit sits under Send log, away from the way back to Android Auto.
            addView(LinearLayout(context).apply {
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(row(text(40f).apply { text = "Close app" }, text(26f, MUTED).apply { text = "Until you open it again" },
                    button("Exit") { onExit() }), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(22) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22) })
        }
        addView(LinearLayout(context).apply {
            setPadding(px(64), px(70), px(64), px(40))
            addView(hero, LinearLayout.LayoutParams(px(820), -1))
            addView(ScrollView(context).apply { isFillViewport = true; addView(right) }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = px(56) })
        }, LayoutParams(-1, -1))
        addView(eqPanel, LayoutParams(-1, -1))  // on top of the settings page when open
        eqPanel.onClosed = ::refresh  // the Equalizer light follows the EQ switch
    }

    fun open() { shapeAtOpen = shape(); visibility = VISIBLE; refresh(); showTruck(BridgeService.latestTruck) }

    /** The settings that change Android Auto's shape; a tile tapped on and back off changes nothing. */
    private fun shape() = listOf<Any>(s.barEnabled, s.statusBarShown, s.hevcEnabled, s.aaDpi, s.aaHidden)
    private var shapeAtOpen = emptyList<Any>()

    private fun setDpi(dpi: Int) { s.aaDpi = dpi.coerceIn(DPI_MIN, DPI_MAX); shapeChanged = true; refresh() }

    /** Live truck data while the page is open. */
    fun showTruck(t: Truck) {
        if (visibility != VISIBLE) return
        batteryPanel.show(t)
    }

    /** True if the page was open (so Back closes it instead of leaving AutoBridge). */
    /** See [EqPanel.shown]. */
    fun shown(visible: Boolean) = eqPanel.shown(visible && visibility == VISIBLE)

    fun close(): Boolean = eqPanel.close() || (visibility == VISIBLE).also {
        visibility = GONE
        if (shapeChanged && shape() != shapeAtOpen) onShapeChanged()
        shapeChanged = false
    }

    /** Also called when Android Auto starts or stops and after the hotspot details change, while the page is open. */
    fun refresh() {
        if (visibility != VISIBLE) return
        tiles.forEach { it() }
        iconLamps.forEach { it() }
        // What these bars give Android Auto, and the layout it should pick from that (the size is the last measured one).
        val dpi = Display.dpi(context)
        val w = s.viewW.takeIf { it > 0 }; val h = s.viewH.takeIf { it > 0 }
        sizeDetail.text = (if (s.aaDpi > 0) "$dpi" else "Auto $dpi") +
            (if (w != null && h != null) " · ${Display.layout(w, h, dpi)}" else "")
        hotspot.text = s.ssid.ifEmpty { "Not set" }
        hotspotDetail.text = if (s.bssid.isEmpty()) "Open AutoBridge Phone, or Edit" else "${s.bssid} · password ${if (s.password.isEmpty()) "missing" else "saved"}"
        voiceDetail.text = VoiceAssistant.status(context).removePrefix("Voice button: ").replaceFirstChar { it.uppercase() }
        version.text = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
        val service = BridgeService.instance
        val video = service?.video
        phone.text = BridgeService.phoneName ?: "No phone yet"
        picture.text = video?.let { "${it.codecH}p, ${if (service.hevc) "H.265" else "H.264"}" } ?: "Not connected"
        val eqWord = "EQ ${if (Equalizer.music.enabled) "on" else "off"}"
        sound.text = if (video != null) "${AudioOut.MUSIC_HZ / 1000} kHz stereo, $eqWord" else eqWord  // no rate with nothing playing
        aaState.text = if (video != null) "Running" else "Not connected"
        Lamp.lit(eqButton, Equalizer.music.enabled)  // lit while the EQ is on
        aaGlyph.running = video != null  // Android Auto blue while it runs, grey when not
        aaGlyph.invalidate(); (aaGlyph.parent as View).contentDescription = "Android Auto, ${if (video != null) "running" else "not connected"}"
    }

    /** Forgetting is the one destructive action here, so it asks first and says what happens next. */
    private var forgetDialog: android.app.Dialog? = null

    override fun onDetachedFromWindow() { forgetDialog?.dismiss(); super.onDetachedFromWindow() }  // app closing: no leaked window

    private fun confirmForget() {
        val name = s.ssid.ifEmpty { return toast("No hotspot details to forget") }
        forgetDialog = TruckDialog.show(context, "Forget $name?", "Android Auto stops connecting until the phone sends the details again, or you type them in Edit.",
            ok = "Forget", width = 1100) { s.forget(); refresh(); toast("Forgot $name"); true }
    }

    // ---- building blocks, in the truck's boxy style ----

    private fun px(v: Int) = (v * k).roundToInt()

    private fun text(size: Float, color: Int = WHITE, mono: Boolean = false, light: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k); setTextColor(color)
        typeface = when { mono -> Typeface.MONOSPACE; light -> Typeface.create("sans-serif-light", Typeface.NORMAL); else -> Typeface.create("sans-serif", Typeface.NORMAL) }
    }

    /** One line, cut with "…" so a long phone name can't run into the next column. */
    private fun fact() = text(40f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }

    private fun sysValue() = text(22f, LIGHT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }

    private fun LinearLayout.header(title: String) =
        addView(text(28f, MUTED).apply { text = title; letterSpacing = 0.22f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(24); bottomMargin = px(12) })

    private fun row(title: TextView, detail: TextView, vararg actions: View) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(px(36), px(24), px(32), px(24))
        for (t in listOf(title, detail)) { t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END }
        background = TruckBar.chamfer(BLACK, OUTLINE, px(24).toFloat())
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(detail) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.forEach { addView(it, LinearLayout.LayoutParams(-2, px(96)).apply { marginStart = px(24) }) }
    }

    private fun button(label: String, onClick: () -> Unit) = Lamp.button(context, k, label, onClick = onClick).apply {
        minWidth = px(150); setPadding(px(26), 0, px(26), px(6))
    }

    /** A switch in a row; its lamp is lit while on. Tap to switch. */
    private fun toggle(title: String, detail: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        Lamp.button(context, k, title, momentary = false) { set(!get()); shapeChanged = true; refresh() }.apply {  // the size row follows the bars
            minWidth = px(150); setPadding(px(24), 0, px(24), px(6))
            tiles += { Lamp.lit(this, get()); contentDescription = "$title. $detail" }
        }

    /** A quiet button for set-once things like the hotspot. */
    private fun small(label: String, onClick: () -> Unit) = Lamp.button(context, k, label, size = 24f, cut = 14f, onClick = onClick).apply {
        minWidth = px(120); setPadding(px(22), 0, px(22), px(6))
    }

    private enum class Glyph { EQ, AA }

    private companion object {
        const val DPI_STEP = 10
        const val DPI_MIN = 80
        const val DPI_MAX = 480
    }

    /** A square button: icon over its label, in the truck's boxy style. */
    private fun bigButton(glyph: Glyph, label: String, momentary: Boolean = true, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, px(14))
        val g = GlyphView(context, glyph)
        if (glyph == Glyph.AA) aaGlyph = g
        addView(g, LinearLayout.LayoutParams(px(96), px(96)))
        addView(text(32f).apply { text = label }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(22) })
        Lamp.style(this, k, px(26).toFloat(), momentary)
        setOnClickListener { onClick() }
    }

    /** The button pictures: EQ sliders, or Android Auto's round badge (blue while it runs, grey when not). */
    private class GlyphView(c: Context, private val glyph: Glyph) : View(c) {
        var running = false
            set(v) { if (field != v) { field = v; invalidate() } }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val u = width / 48f
            p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            if (glyph == Glyph.EQ) {  // three sliders, knobs at different heights
                p.strokeWidth = 3 * u
                for ((x, y) in listOf(10f to 30f, 24f to 15f, 38f to 25f)) {
                    p.style = Paint.Style.STROKE; p.color = WHITE; c.drawLine(x * u, 6 * u, x * u, 42 * u, p)
                    p.style = Paint.Style.FILL; p.color = BLACK; c.drawCircle(x * u, y * u, 5 * u, p)
                    p.style = Paint.Style.STROKE; p.color = WHITE; c.drawCircle(x * u, y * u, 5 * u, p)
                }
                return
            }
            p.style = Paint.Style.FILL; p.color = if (running) AA_BLUE else AA_GREY; c.drawCircle(24 * u, 24 * u, 22 * u, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 5 * u; p.color = if (running) WHITE else 0xFF9AA0A6.toInt()
            c.drawPath(android.graphics.Path().apply { moveTo(14 * u, 33 * u); lineTo(24 * u, 13 * u); lineTo(34 * u, 33 * u) }, p)  // the "A" road arrow
        }
        private companion object { const val AA_BLUE = 0xFF1A73E8.toInt(); const val AA_GREY = 0xFF3C4043.toInt() }
    }

}
