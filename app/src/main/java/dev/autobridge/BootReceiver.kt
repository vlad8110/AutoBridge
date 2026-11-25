package dev.autobridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts AutoBridge with the truck, so the phone can connect before anyone opens the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BridgeService.instance != null) return
        runCatching {
            context.startForegroundService(Intent(context, BridgeService::class.java).putExtra(BridgeService.EXTRA_BOOT, true))
        }.onFailure { android.util.Log.w("AutoBridge", "start at boot refused: $it") }
    }
}
