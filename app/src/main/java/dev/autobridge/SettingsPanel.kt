package dev.autobridge

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * AutoBridge settings, shown over Android Auto when the owner taps the bar's port chip (or the
 * waiting screen's Settings button). Android Auto keeps running underneath.
 */
class SettingsPanel(context: Context, private val editWifi: () -> Unit, private val toast: (String) -> Unit, private val onStatusBar: () -> Unit) : FrameLayout(context) {
    private val s = Settings(context)
    private val hotspot = text(18f)
    private val hotspotDetail = text(13f, MUTED, mono = true)
    private val about = text(18f)
    private val aboutDetail = text(13f, MUTED)
    private val voiceDetail = text(13f, MUTED)

    init {
        setBackgroundColor(0xFF000000.toInt())
        isClickable = true  // nothing underneath gets the touches
        visibility = GONE
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(26f, bold = true).apply { text = "AutoBridge settings" }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("Close") { visibility = GONE })
            })
            section("Display")
            addView(card(
                toggle("Truck bar", "Battery, range, speed and power under Android Auto. Applies next time the phone connects.", s.barEnabled) { s.barEnabled = it },
                toggle("Truck status bar", "The truck's own bar at the top (time, phone, notifications). Off gives Android Auto that space (fits properly from the next connection); swipe down from the top to see it.", s.statusBarShown) { s.statusBarShown = it; onStatusBar() },
                toggle("Sharper picture", "1440p (H.265); falls back to 1080p by itself. Applies next time the phone connects.", s.hevcEnabled) { s.hevcEnabled = it },
            ))
            section("Truck hotspot")
            addView(card(
                row(hotspot, hotspotDetail),
                LinearLayout(context).apply {
                    setPadding(dp(16), 0, dp(16), dp(14))
                    addView(button("Edit") { editWifi() })
                    addView(button("Forget") { s.forget(); refresh(); toast("Hotspot details forgotten") }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                },
            ))
            section("Phone")
            addView(card(
                row(text(18f).apply { text = "Voice button" }, voiceDetail, button("Change") {
                    VoiceAssistant.picker(context)?.let { runCatching { context.startActivity(it) } } ?: toast("This truck has no assistant setting")
                }),
                row(text(18f).apply { text = "Send log to phone" }, text(13f, MUTED).apply { text = "Saved in your log folder" }, button("Send") {
                    BridgeService.instance?.sendLogs("settings") ?: toast("AutoBridge isn't running yet")
                }),
            ))
            section("About")
            addView(card(row(about, aboutDetail)))
        }
        addView(ScrollView(context).apply { addView(page) })
    }

    fun open() { refresh(); visibility = VISIBLE }

    private fun refresh() {
        hotspot.text = s.ssid.ifEmpty { "Not set" }
        hotspotDetail.text = if (s.bssid.isEmpty()) "Tap Edit, or open AutoBridge Phone on the hotspot" else "${s.bssid} · password ${if (s.password.isEmpty()) "missing" else "saved"}"
        voiceDetail.text = VoiceAssistant.status(context).removePrefix("Voice button: ").replaceFirstChar { it.uppercase() }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        about.text = "AutoBridge $version"
        val phone = BridgeService.phoneName
        aboutDetail.text = (phone ?: "No phone yet") + if (BridgeService.instance?.video != null) " · Android Auto connected" else ""
    }

    // ---- building blocks, in the truck's button style ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(sp: Float, color: Int = WHITE, bold: Boolean = false, mono: Boolean = false) = TextView(context).apply {
        textSize = sp; setTextColor(color)
        typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL); else -> Typeface.create("sans-serif", Typeface.NORMAL) }
    }

    private fun LinearLayout.section(title: String) = addView(text(13f, MUTED).apply { text = title; setPadding(dp(2), dp(22), 0, dp(8)) })

    private fun card(vararg rows: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = TruckBar.chamfer(0xFF181C24.toInt(), 0xFF5F6368.toInt(), dp(14).toFloat())
        rows.forEach { addView(it) }
    }

    private fun row(title: TextView, detail: TextView, action: View? = null) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(detail) }, LinearLayout.LayoutParams(0, -2, 1f))
        action?.let { addView(it) }
    }

    private fun toggle(title: String, detail: String, on: Boolean, set: (Boolean) -> Unit) =
        row(text(18f).apply { text = title }, text(13f, MUTED).apply { text = detail },
            Switch(context).apply { isChecked = on; setOnCheckedChangeListener { _, v -> set(v) } })

    private fun button(label: String, onClick: () -> Unit) = text(16f).apply {
        text = label; gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(10))
        background = TruckBar.chamfer(0xFF000000.toInt(), 0xFF8E939B.toInt(), dp(8).toFloat())
        setOnClickListener { onClick() }
    }

    private companion object {
        const val WHITE = 0xFFF1F3F4.toInt()
        const val MUTED = 0xFF9AA0A6.toInt()
    }
}
