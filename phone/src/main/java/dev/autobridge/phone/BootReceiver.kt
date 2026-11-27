package dev.autobridge.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the relay after a reboot or an app update, so it's always ready in the truck. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the system's broadcasts: any other app could send this receiver one by name.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) RelayService.start(context)
    }
}
