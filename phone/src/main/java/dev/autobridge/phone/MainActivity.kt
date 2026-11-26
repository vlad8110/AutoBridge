package dev.autobridge.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The relay's screen, in the truck's own look (black, boxy panels with one cut corner): first whether the
 * truck is connected and set up, then the log buttons, then the hotspot details for when something's off.
 */
class MainActivity : Activity() {
    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var statusPanel: LinearLayout
    private lateinit var passwordButton: TextView
    private lateinit var detailsText: TextView
    private lateinit var activityText: TextView
    private lateinit var logText: TextView
    private var details: WifiDetails? = null
    private var wifiProblem: String? = null
    private var relayStatus = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, color: Int = WHITE, light: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color)
        typeface = when { mono -> Typeface.MONOSPACE; light -> Typeface.create("sans-serif-light", Typeface.NORMAL); else -> Typeface.DEFAULT }
    }
    /** The truck's small spaced section names, so both apps read as one product. */
    private fun section(title: String) = text(12f, MUTED).apply { text = title; letterSpacing = 0.2f; setPadding(0, dp(26), 0, dp(10)) }
    private fun panel(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = Chamfer(BLACK, OUTLINE, dp(14).toFloat(), dp(1).toFloat())
        setPadding(dp(18), dp(16), dp(18), dp(16)); views.forEach { addView(it) }
    }
    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = text(15f, if (primary) BLUE else WHITE).apply {
        text = label; gravity = Gravity.CENTER; setPadding(dp(16), dp(13), dp(16), dp(13))
        background = Chamfer(if (primary) NAVY else BLACK, if (primary) BLUE else OUTLINE, dp(10).toFloat(), dp(1).toFloat())
        setOnClickListener { onClick() }
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The one loud thing: the status line, big and coloured, readable from across the cab.
        headline = text(30f, light = true)
        subline = text(15f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        passwordButton = button("Hotspot password") { editPassword() }.also { it.layoutParams = lp(16) }
        statusPanel = panel(headline, subline, passwordButton)
        detailsText = text(13f, MUTED, mono = true).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        activityText = text(13f, LIGHT).apply { setLineSpacing(dp(4).toFloat(), 1f) }
        logText = text(13f, MUTED)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_bridge); setColorFilter(MUTED) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(text(15f, MUTED).apply { text = "AutoBridge Phone"; setPadding(dp(8), 0, 0, 0) })
            })
            addView(statusPanel, lp(18))

            addView(section("TRUCK"))
            addView(button("Send log from truck", primary = true) {
                ask(RelayService::requestLog, "Asked the truck; the log arrives in a few seconds")
            })
            addView(button("Screenshot of the truck screen") {
                ask(RelayService::requestScreenshot, "Asked the truck; the screenshot arrives in a few seconds")
            }, lp(10))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(logText, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(13f, BLUE).apply { text = "Change"; setPadding(dp(12), dp(8), 0, dp(8)); setOnClickListener { pickFolder() } })
            }, lp(8))

            addView(section("HOTSPOT DETAILS"))
            addView(panel(detailsText, button("Refresh") { showWifi() }.also { it.layoutParams = lp(12) }))

            addView(section("RECENT"))
            addView(activityText)
            addView(text(12f, DIM).apply { text = "Starts by itself when the phone boots."; setPadding(0, dp(24), 0, 0) })
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(BLACK); addView(content) })

        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)  // Android only reveals the hotspot name and BSSID with it
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        RelayService.start(this)
    }

    private fun ask(request: () -> Boolean, ok: String) = Thread {
        val sent = request()
        runOnUiThread { Toast.makeText(this, if (sent) ok else "The truck isn't connected to this phone", Toast.LENGTH_SHORT).show() }
    }.start()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = showWifi()

    override fun onResume() {
        super.onResume()
        RelayService.onStatus = { s -> runOnUiThread { relayStatus = s; render() } }
        relayStatus = RelayService.status
        showWifi()
        showLogFolder()
    }

    override fun onPause() {
        RelayService.onStatus = null
        super.onPause()
    }

    /** Running if the last session start comes after the last stop. */
    private fun connected(): Boolean {
        val lines = relayStatus.lines()
        val started = lines.indexOfLast { RelayService.RUNNING in it }
        val stopped = lines.indexOfLast { "stopped" in it || "Waiting for the truck" in it }
        return started >= 0 && started > stopped
    }

    /** The status panel: one state, worded as what to do next. */
    private fun render() {
        val d = details
        val pwSaved = d?.let { WifiDetails.password(this, it.ssid) != null } == true
        val (title, color, sub) = when {
            connected() -> Triple("Connected to the truck", GREEN,
                (d?.let { "${it.ssid}, " } ?: "") + if (pwSaved) "password saved" else "password not saved here")
            wifiProblem != null -> Triple("Can't see the hotspot", AMBER, wifiProblem!!)
            d != null && !pwSaved -> Triple("Add the hotspot password", AMBER,
                "On ${d.ssid}. A new truck gets its Wi-Fi details from here, so type it exactly as the truck shows it.")
            else -> Triple("Waiting for the truck", WHITE,
                (d?.let { "On ${it.ssid}, password saved. " } ?: "") + "Open AutoBridge on the truck.")
        }
        headline.text = title; headline.setTextColor(color); subline.text = sub
        passwordButton.visibility = if (d != null) View.VISIBLE else View.GONE  // nothing to save it for until the hotspot shows
        (statusPanel.background as Chamfer).stroke = if (color == WHITE) OUTLINE else color
        statusPanel.background.invalidateSelf()
        activityText.text = relayStatus.lines().filter { it.isNotBlank() }.takeLast(4)
            .joinToString("\n") { it.replace(Regex("^(\\d\\d:\\d\\d):\\d\\d"), "$1") }
    }

    private fun showWifi() {
        details = null
        wifiProblem = when {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                "Allow Location for AutoBridge Phone, so it can read the hotspot name."
            else -> {
                val d = WifiDetails.current(getSystemService(WifiManager::class.java))
                when {
                    d == null -> "Join the truck's Wi-Fi hotspot, then tap Refresh."
                    !d.usable -> "Turn on Location on the phone, so it can read the hotspot name."
                    else -> { details = d; WifiDetails.remember(this, d); null }
                }
            }
        }
        detailsText.text = details?.let { "${it.ssid}\nBSSID ${it.bssid}\nChannel ${it.channel}, ${it.band}, ${it.frequencyMhz} MHz" }
            ?: "No hotspot details yet."
        render()
    }

    private fun showLogFolder() {
        val folder = LogSaver.folder(this)?.let { DocumentsContract.getTreeDocumentId(it).substringAfter(':').ifEmpty { "storage root" } }
        logText.text = "Logs save to: ${folder ?: "Download/AutoBridge"}"
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

    /** Saved per hotspot name; a truck with no Wi-Fi details gets it from here (Android never shows apps saved passwords). */
    private fun editPassword() {
        val d = details ?: return Toast.makeText(this, "Join the truck's hotspot first", Toast.LENGTH_SHORT).show()
        val field = android.widget.EditText(this).apply {
            hint = "Hotspot password"; setText(WifiDetails.password(context, d.ssid) ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this).setTitle("${d.ssid} password")
            .setMessage("Type it exactly as the truck's hotspot settings show it. It's case sensitive.")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                val pw = field.text.toString()
                if (pw.length !in 8..63) Toast.makeText(this, "A hotspot password is 8 to 63 characters", Toast.LENGTH_SHORT).show()
                else { WifiDetails.setPassword(this, d.ssid, pw); showWifi() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** The truck's panel shape: square, top-right corner cut on a diagonal, thin outline (as TruckBar.Chamfer). */
    private class Chamfer(private val fill: Int, var stroke: Int, private val cut: Float, private val w: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds; val h = w / 2
            val path = Path().apply {
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

    private companion object {
        const val PICK_FOLDER = 2
        // The truck app's palette (SettingsPanel / TruckBar), so both read as one product.
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFF1F3F4.toInt()
        const val LIGHT = 0xFFBDC1C6.toInt()
        const val MUTED = 0xFF9AA0A6.toInt()
        const val DIM = 0xFF5F6368.toInt()
        const val OUTLINE = 0xFF8E939B.toInt()
        const val BLUE = 0xFF8AB4F8.toInt()
        const val NAVY = 0xFF0D1B2E.toInt()
        const val GREEN = 0xFF81C995.toInt()
        const val AMBER = 0xFFF2BD64.toInt()
    }
}
