package dev.autobridge.phone

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Starts the relay, shows what it's doing, and shows the Wi-Fi the phone is on, so a new truck's
 * hotspot details (name, BSSID, channel) can be copied into AutoBridge on that truck.
 */
class MainActivity : Activity() {
    private lateinit var ssidText: TextView
    private lateinit var bssidText: TextView
    private lateinit var channelText: TextView
    private lateinit var mhzText: TextView
    private lateinit var wifiNote: TextView
    private lateinit var pill: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private var details: WifiDetails? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Smoky glass: a light sheen at the top fading into smoke, with a thin bright edge. */
    private fun glass(radius: Int = 18) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(0x24FFFFFF, 0x8C282C36.toInt(), 0xA614161C.toInt())).apply {
        cornerRadius = dp(radius).toFloat(); setStroke(dp(1), 0x2EFFFFFF)
    }
    private fun flat(color: Int, stroke: Int, radius: Int = 10) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke)
    }
    private fun text(size: Float, color: Int = TEXT, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color)
        typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL); else -> Typeface.DEFAULT }
    }
    private fun card(title: String, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(); setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(text(12f, MUTED).apply { this.text = title; setPadding(0, 0, 0, dp(6)) })
        views.forEach { addView(it) }
    }
    private fun lp(top: Int = 0, weight: Float = 0f, w: Int = -1) = LinearLayout.LayoutParams(if (weight > 0) 0 else w, -2, weight).apply { topMargin = dp(top) }
    private fun chipButton(label: String, onClick: () -> Unit) = text(14f).apply {
        this.text = label; gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
        background = flat(0x1AFFFFFF, 0x1FFFFFFF); setOnClickListener { onClick() }
    }
    /** Big value with a small label above it (channel, MHz). */
    private fun tile(label: String, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = flat(0x47000000, 0x1AFFFFFF); setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(text(11f, MUTED).apply { text = label }); addView(value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ssidText = text(24f, bold = true)
        bssidText = text(15f, mono = true).apply { setPadding(0, dp(4), 0, 0) }
        channelText = text(22f, bold = true)
        mhzText = text(22f, bold = true)
        wifiNote = text(13f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        pill = text(12f).apply { setPadding(dp(10), dp(4), dp(10), dp(4)) }
        statusText = text(13f, 0xFFD3D8E0.toInt(), mono = true).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        logText = text(12f, MUTED)
        val request = text(15f, 0xFF04342C.toInt(), bold = true).apply {
            text = "Send log from truck"; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
            background = flat(0xD91D9E75.toInt(), 0x809FE1CB.toInt(), 12)
            setOnClickListener {
                Thread {
                    val ok = RelayService.requestLog()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, if (ok) "Asked the truck; the log arrives in a few seconds" else "The truck isn't connected to this phone", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        }
        val change = text(12f, ACCENT).apply { text = "Change"; setOnClickListener { pickFolder() } }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_bridge); setColorFilter(ACCENT) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(text(20f, bold = true).apply { text = "AutoBridge"; maxLines = 1; setPadding(dp(8), 0, dp(8), 0) })
                addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))  // pushes the pill right; 0 tall, or a bare View fills the screen
                addView(pill)
            })
            addView(card("Truck hotspot", ssidText, bssidText,
                LinearLayout(context).apply {
                    addView(tile("Channel", channelText), lp(weight = 1f))
                    addView(tile("Frequency", mhzText), lp(weight = 1f).apply { marginStart = dp(8) })
                }.also { it.layoutParams = lp(10) },
                wifiNote,
                LinearLayout(context).apply {
                    addView(chipButton("Refresh") { showWifi() }, lp(weight = 1f))
                    addView(chipButton("Copy") { copyWifi() }, lp(weight = 1f).apply { marginStart = dp(8) })
                    addView(chipButton("Password") { editPassword() }, lp(weight = 1f).apply { marginStart = dp(8) })
                }.also { it.layoutParams = lp(12) }), lp(14))
            addView(card("Relay", statusText), lp(12))
            addView(card("Logs from the truck", request,
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(logText, lp(weight = 1f)); addView(change)
                }.also { it.layoutParams = lp(10) }), lp(12))
            addView(text(11f, 0xFF7A8290.toInt()).apply { text = "Starts by itself when the phone boots"; gravity = Gravity.CENTER }, lp(14))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            // Soft teal, blue and violet glows behind the glass cards.
            background = LayerDrawable(arrayOf(
                ColorDrawable(0xFF0D0F13.toInt()),
                glow(0x661D9E75, 0.1f, 0.05f), glow(0x4D185FA5, 0.3f, 0.55f), glow(0x66534AB7, 0.95f, 0.85f),
            ))
            addView(content)
        })
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)  // Android only reveals SSID/BSSID with it
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        RelayService.start(this)
    }

    private fun glow(color: Int, x: Float, y: Float) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT; colors = intArrayOf(color, 0)
        gradientRadius = dp(260).toFloat(); setGradientCenter(x, y)
    }

    private fun showStatus(all: String) {
        val lines = all.lines().filter { it.isNotBlank() }
        statusText.text = lines.takeLast(3).joinToString("\n") { it.replace(Regex("^(\\d\\d:\\d\\d):\\d\\d"), "$1") }
        val last = lines.lastOrNull().orEmpty()
        val (label, fg, bg) = when {
            "running through the car" in last || "car log" in last || "Saved the car" in last -> Triple("● Connected", ACCENT, 0x2E5DCAA5)
            else -> Triple("○ Waiting", MUTED, 0x1AFFFFFF)
        }
        pill.text = label; pill.setTextColor(fg); pill.background = flat(bg, 0x33FFFFFF, 999)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = showWifi()

    override fun onResume() {
        super.onResume()
        RelayService.onStatus = { s -> runOnUiThread { showStatus(s) } }
        showStatus(RelayService.status)
        showWifi()
        showLogFolder()
    }

    override fun onPause() {
        RelayService.onStatus = null
        super.onPause()
    }

    private fun showWifi() {
        details = null
        val problem = when {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                "Allow Location so Android shows the Wi-Fi name and BSSID."
            else -> {
                val d = WifiDetails.current(getSystemService(WifiManager::class.java))
                when {
                    d == null -> "Not on Wi-Fi. Join the truck's hotspot, then tap Refresh."
                    d.bssid.isEmpty() || d.bssid == "02:00:00:00:00:00" || d.ssid == "<unknown ssid>" ->
                        "Turn on Location so Android shows the Wi-Fi details."
                    else -> { details = d; null }
                }
            }
        }
        val d = details
        ssidText.text = d?.ssid ?: "—"
        bssidText.text = d?.bssid ?: ""
        bssidText.visibility = if (d == null) View.GONE else View.VISIBLE
        channelText.text = d?.channel?.toString() ?: "—"
        mhzText.text = d?.let { "${it.frequencyMhz} MHz" } ?: "—"
        wifiNote.text = problem ?: d?.let { "${it.band} · password " + if (WifiDetails.password(this, it.ssid) != null) "saved, a new truck can set itself up" else "not saved" }
    }

    private fun showLogFolder() {
        val folder = LogSaver.folder(this)?.let { DocumentsContract.getTreeDocumentId(it).substringAfter(':').ifEmpty { "(storage root)" } }
        logText.text = folder ?: "Download/AutoBridge"
    }

    private fun pickFolder() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)

    @Deprecated("Activity result API needs AndroidX; this app has none")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data?.takeIf { requestCode == PICK_FOLDER && resultCode == RESULT_OK } ?: return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        LogSaver.setFolder(this, uri)
        showLogFolder()
    }

    private companion object {
        const val PICK_FOLDER = 2
        const val TEXT = 0xFFEEF0F4.toInt()
        const val MUTED = 0xFFA9B0BC.toInt()
        const val ACCENT = 0xFF9FE1CB.toInt()
    }

    /** Saved per hotspot name; a truck with no Wi-Fi details gets it from here (Android never shows apps saved passwords). */
    private fun editPassword() {
        val d = details ?: return Toast.makeText(this, "Join the truck's hotspot first", Toast.LENGTH_SHORT).show()
        val field = android.widget.EditText(this).apply {
            hint = "Hotspot password"; setText(WifiDetails.password(context, d.ssid) ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this).setTitle("${d.ssid} password").setView(field)
            .setPositiveButton("Save") { _, _ ->
                val pw = field.text.toString()
                if (pw.length !in 8..63) Toast.makeText(this, "A hotspot password is 8 to 63 characters", Toast.LENGTH_SHORT).show()
                else { WifiDetails.setPassword(this, d.ssid, pw); showWifi() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun copyWifi() {
        val d = details ?: return Toast.makeText(this, "No Wi-Fi details to copy yet", Toast.LENGTH_SHORT).show()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Car Wi-Fi", d.asText()))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
