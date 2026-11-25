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
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Starts the relay, shows what it's doing, and shows the Wi-Fi the phone is on, so a new truck's
 * hotspot details (name, BSSID, channel) can be copied into AutoBridge on that truck.
 */
class MainActivity : Activity() {
    private lateinit var wifiText: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private var details: WifiDetails? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun label(size: Float) = TextView(this).apply { textSize = size; gravity = Gravity.CENTER; setPadding(0, 24, 0, 24) }
        wifiText = label(18f)
        statusText = label(15f)
        val refresh = Button(this).apply { text = "Refresh Wi-Fi details"; setOnClickListener { showWifi() } }
        val copy = Button(this).apply { text = "Copy Wi-Fi details"; setOnClickListener { copyWifi() } }
        logText = label(15f)
        val pick = Button(this).apply { text = "Choose log folder"; setOnClickListener { pickFolder() } }
        val request = Button(this).apply {
            text = "Send log from truck"
            setOnClickListener {
                Thread {
                    val ok = RelayService.requestLog()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, if (ok) "Asked the truck; the log arrives in a few seconds" else "The truck isn't connected to this phone", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(48, 48, 48, 48)
                addView(label(22f).apply { text = "Wi-Fi this phone is on now" })
                addView(label(14f).apply { text = "Join the truck's hotspot to see the details AutoBridge on the truck needs." })
                addView(wifiText)
                addView(refresh)
                addView(copy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
                addView(label(22f).apply { text = "Relay" })
                addView(statusText)
                addView(label(22f).apply { text = "Logs from the truck" })
                addView(logText)
                addView(request)
                addView(pick)
                addView(label(14f).apply { text = "Leave this installed; it starts by itself when the phone boots." })
            })
        })
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)  // Android only reveals SSID/BSSID with it
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        RelayService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = showWifi()

    override fun onResume() {
        super.onResume()
        RelayService.onStatus = { s -> runOnUiThread { statusText.text = s } }
        statusText.text = RelayService.status
        showWifi()
        showLogFolder()
    }

    override fun onPause() {
        RelayService.onStatus = null
        super.onPause()
    }

    private fun showWifi() {
        details = null
        wifiText.text = when {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                "Allow Location for AutoBridge Phone to see the Wi-Fi name and BSSID."
            else -> {
                val d = WifiDetails.current(getSystemService(WifiManager::class.java))
                when {
                    d == null -> "Not on Wi-Fi. Connect the phone to the truck's hotspot, then tap Refresh."
                    d.bssid.isEmpty() || d.bssid == "02:00:00:00:00:00" || d.ssid == "<unknown ssid>" ->
                        "Turn on Location (Settings > Location) so Android shows the Wi-Fi details."
                    else -> { details = d; d.asText() + "\n\nPassword: the one you set for the hotspot" }
                }
            }
        }
    }

    private fun showLogFolder() {
        val folder = LogSaver.folder(this)?.let { DocumentsContract.getTreeDocumentId(it).substringAfter(':').ifEmpty { "(storage root)" } }
        logText.text = "Saved to: " + (folder ?: "Download/AutoBridge — tap below and choose Phone Sync") +
            (LogSaver.lastSaved?.let { "\nLast: $it" } ?: "")
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

    private companion object { const val PICK_FOLDER = 2 }

    private fun copyWifi() {
        val d = details ?: return Toast.makeText(this, "No Wi-Fi details to copy yet", Toast.LENGTH_SHORT).show()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Car Wi-Fi", d.asText()))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
