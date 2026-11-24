package dev.autobridge.phone

import android.Manifest
import android.app.Activity
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Just starts the relay and shows what it's doing. */
class MainActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var overlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48) }
        overlay = Button(this).apply {
            text = "Allow AutoBridge to open Android Auto"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text)
            addView(overlay)
        })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        RelayService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // "Appear on top" is what lets the relay open Android Auto while the phone is in a pocket.
        overlay.visibility = if (Settings.canDrawOverlays(this)) android.view.View.GONE else android.view.View.VISIBLE
        RelayService.onStatus = { s -> runOnUiThread { show(s) } }
        show(RelayService.status)
    }

    override fun onPause() {
        RelayService.onStatus = null
        super.onPause()
    }

    private fun show(s: String) {
        text.text = "AutoBridge Phone\n\n$s\n\nLeave this installed; it starts by itself when the phone boots."
    }
}
