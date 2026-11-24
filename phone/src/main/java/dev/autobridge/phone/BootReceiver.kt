package dev.autobridge.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the relay after a reboot or an app update, so it's always ready in the truck. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { RelayService.start(context) }
}
