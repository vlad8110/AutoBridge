package dev.autobridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts AutoBridge with the truck, so the phone can connect before anyone opens the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BridgeService.instance != null) return
        // Without Bluetooth permission (never opened, or denied) the service's start is refused and the app
        // crashes at every boot and update; wait until the owner opens it and grants it.
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching {
            context.startForegroundService(Intent(context, BridgeService::class.java).putExtra(BridgeService.EXTRA_BOOT, true))
        }.onFailure { android.util.Log.w("AutoBridge", "start at boot refused: $it") }
    }
}
