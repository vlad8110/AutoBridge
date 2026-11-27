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
    /** The truck's 0.8 button: black, white text, a red lamp that lights while pressed, and a spring. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")  // the listener only animates; clicks still go through
    private fun button(label: String, small: Boolean = false, bold: Boolean = false, onClick: () -> Unit) = text(if (small) 13f else 15f).apply {
        text = label; gravity = Gravity.CENTER; maxLines = 1
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (small) setPadding(dp(14), dp(8), dp(14), dp(11)) else setPadding(dp(16), dp(14), dp(16), dp(17))
        val d = Chamfer(BLACK, OUTLINE, dp(if (small) 8 else 10).toFloat(), dp(1).toFloat()).apply { lamp = dp(if (small) 2 else 3).toFloat() /* thin, like the truck's */ }
        background = d
        val off = Runnable { d.lit = false; d.invalidateSelf() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { v.removeCallbacks(off); d.lit = true; d.invalidateSelf(); v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).setInterpolator(null).start() }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.postDelayed(off, 120)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(android.view.animation.OvershootInterpolator(5f)).start()
                }
            }
            false
        }
        setOnClickListener { onClick() }
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The one loud thing: the status line, big and coloured, readable from across the cab.
        headline = text(30f, light = true)
        subline = text(15f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        passwordButton = button("Hotspot password") { editPassword() }.also { it.layoutParams = lp(16) }
        val statusPanel = panel(headline, subline, passwordButton)
        detailsText = text(13f, MUTED, mono = true).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        activityText = text(12f, LIGHT, mono = true).apply { setLineSpacing(dp(4).toFloat(), 1f) }
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
            addView(LinearLayout(context).apply {
                addView(button("Send log") { ask(RelayService::requestLog, "Asked the truck; the log arrives in a few seconds") },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("Screenshot") { ask(RelayService::requestScreenshot, "Asked the truck; the screenshot arrives in a few seconds") },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(logText, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("Change", small = true) { pickFolder() })
            }, lp(10))

            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(section("HOTSPOT DETAILS"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("Refresh", small = true) { showWifi() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(panel(detailsText))

            addView(section("RECENT"))
            // The newest 3; tap for the last hour's 10.
            addView(panel(activityText, text(10f, MUTED).apply { text = "Tap for more"; gravity = Gravity.END }).apply {
                contentDescription = "Recent events. Tap for the last hour."
                setOnClickListener { showRecent() }
            })
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

    /** The status panel: one state, worded as what to do next. */
    private fun render() {
        val d = details
        val pwSaved = d?.let { WifiDetails.password(this, it.ssid) != null } == true
        val trucks = WifiDetails.savedHotspots(this)
        val (title, color, sub) = when {
            RelayService.aaRunning -> Triple("Connected to the truck", GREEN,
                (d?.let { "${it.ssid}, " } ?: "") + if (pwSaved) "password saved" else "password not saved here")
            wifiProblem != null -> Triple("Can't see the hotspot", AMBER, wifiProblem!!)
            // Home or work Wi-Fi once a truck is set up: nothing to fix, so no amber.
            d != null && !pwSaved && trucks.isNotEmpty() -> Triple("Not on the truck's hotspot", WHITE,
                "On ${d.ssid}. Join ${trucks.joinToString(" or ")} to connect.\nNew truck? Join its hotspot, then add its password.")
            d != null && !pwSaved -> Triple("Add the hotspot password", AMBER,
                "On ${d.ssid}. A new truck gets its Wi-Fi details from here, so type it exactly as the truck shows it.")
            else -> Triple("Waiting for the truck", WHITE,
                (d?.let { "On ${it.ssid}, password saved. " } ?: "") + "Open AutoBridge on the truck.")
        }
        headline.text = title; headline.setTextColor(color); subline.text = sub
        passwordButton.visibility = if (d != null) View.VISIBLE else View.GONE  // nothing to save it for until the hotspot shows
        activityText.text = events().takeLast(3).joinToString("\n")
    }

    /** The relay's steps, as "07:58  Log saved". */
    private fun events() = relayStatus.lines().filter { it.isNotBlank() }.map { it.replace(Regex("^(\\d\\d:\\d\\d):\\d\\d"), "$1") }

    /** Full screen: the last 10 events from the past hour, newest first, scrollable. */
    private fun showRecent() {
        val hourAgo = System.currentTimeMillis() - 3_600_000
        val hour = RelayService.events.filter { it.first >= hourAgo }.map { it.second.replace(Regex("^(\\d\\d:\\d\\d):\\d\\d"), "$1") }.takeLast(10).reversed()
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (hour.isEmpty()) addView(text(15f, MUTED).apply { text = "Nothing in the last hour." })
            hour.forEach { addView(text(15f, LIGHT, mono = true).apply { text = it; setPadding(0, dp(12), 0, dp(12)) }) }
        }
        d.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BLACK); setPadding(dp(18), dp(40), dp(18), dp(24))
            addView(LinearLayout(context).apply {
                gravity = Gravity.BOTTOM
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(12f, MUTED).apply { text = "RECENT"; letterSpacing = 0.2f })
                    addView(text(30f, light = true).apply { text = "Last hour" })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("Close") { d.dismiss() })
            })
            addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(20) })
        })
        d.show()
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
            setText(WifiDetails.password(context, d.ssid) ?: ""); textSize = 20f; setTextColor(WHITE); setSingleLine()
            backgroundTintList = android.content.res.ColorStateList.valueOf(OUTLINE)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val error = text(13f)  // white, like the truck's warnings, so it can't be missed
        val dialog = android.app.Dialog(this)
        dialog.setContentView(panel(
            text(24f).apply { text = "${d.ssid} password" },
            text(14f, MUTED).apply { text = "Type it exactly as the truck's hotspot settings show it. It's case sensitive."; setPadding(0, dp(8), 0, dp(14)) },
            text(12f, MUTED).apply { text = "Hotspot password" }, field, error,
            LinearLayout(this).apply {
                setPadding(0, dp(18), 0, 0)
                addView(button("Cancel") { dialog.dismiss() })
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(button("Save", bold = true) {
                    val pw = field.text.toString()
                    if (pw.length !in 8..63) error.text = "⚠ A hotspot password is 8 to 63 characters."
                    else { WifiDetails.setPassword(this@MainActivity, d.ssid, pw); dialog.dismiss(); showWifi() }
                })
            },
        ).apply { setPadding(dp(22), dp(22), dp(22), dp(18)) })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); setDimAmount(0.8f)
            setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /** The truck's panel shape: square, top-right corner cut on a diagonal, thin outline (as TruckBar.Chamfer). */
    private class Chamfer(private val fill: Int, private val stroke: Int, private val cut: Float, private val w: Float) : Drawable() {
        /** Height of the red lamp strip inside the bottom edge; 0 for a plain panel. */
        var lamp = 0f
        var lit = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds; val h = w / 2
            val path = Path().apply {
                moveTo(b.left + h, b.top + h); lineTo(b.right - cut, b.top + h); lineTo(b.right - h, b.top + cut)
                lineTo(b.right - h, b.bottom - h); lineTo(b.left + h, b.bottom - h); close()
            }
            p.style = Paint.Style.FILL; p.color = fill; c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = w; p.color = stroke; c.drawPath(path, p)
            if (lamp > 0f) {
                val inset = lamp * 2
                val color = if (lit) LAMP_ON else LAMP_OFF
                val l = b.left + inset; val r = b.right - inset
                // A real lamp dims where its curved lens turns away at each end (as the truck app's lamps).
                val dim = (color and 0xFFFFFF) or 0x55000000
                p.style = Paint.Style.FILL
                p.shader = android.graphics.LinearGradient(l, 0f, r, 0f, intArrayOf(dim, color, color, dim), floatArrayOf(0f, 0.2f, 0.8f, 1f), android.graphics.Shader.TileMode.CLAMP)
                c.drawRoundRect(l, b.bottom - inset - lamp, r, b.bottom - inset, lamp / 2, lamp / 2, p)
                p.shader = null
            }
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
        const val LAMP_OFF = 0xFF2B0A0F.toInt()
        const val LAMP_ON = 0xFFE8243C.toInt()  // a tad below full red, as the truck app
        const val GREEN = 0xFF81C995.toInt()
        const val AMBER = 0xFFF2BD64.toInt()
    }
}
