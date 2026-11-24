package dev.autobridge.phone

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Just starts the relay and shows what it's doing. */
class MainActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48) }
        setContentView(text)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        RelayService.start(this)
    }

    override fun onResume() {
        super.onResume()
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
