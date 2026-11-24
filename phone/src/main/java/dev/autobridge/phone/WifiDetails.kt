package dev.autobridge.phone

import android.annotation.SuppressLint
import android.net.wifi.WifiManager

/** What a car's AutoBridge needs about its hotspot (everything except the password). */
data class WifiDetails(val ssid: String, val bssid: String, val frequencyMhz: Int) {
    val channel get() = channelOf(frequencyMhz)
    val band get() = when {
        frequencyMhz in 2400..2500 -> "2.4 GHz"
        frequencyMhz in 4900..5900 -> "5 GHz"
        frequencyMhz in 5925..7125 -> "6 GHz"
        else -> "unknown band"
    }

    fun asText() = "Network (SSID): $ssid\nBSSID: $bssid\nChannel: $channel ($band, $frequencyMhz MHz)"

    companion object {
        fun channelOf(mhz: Int): Int = when {
            mhz == 2484 -> 14
            mhz in 2412..2472 -> (mhz - 2407) / 5
            mhz in 5925..7125 -> (mhz - 5950) / 5
            mhz in 4900..5900 -> (mhz - 5000) / 5
            else -> 0
        }

        /**
         * The Wi-Fi the phone is on right now, or null if it isn't on Wi-Fi.
         * Android hides SSID/BSSID unless the app has location permission and location is on.
         */
        @Suppress("DEPRECATION")  // connectionInfo is still the simplest way to read the current BSSID
        @SuppressLint("MissingPermission")
        fun current(wifi: WifiManager): WifiDetails? {
            val info = wifi.connectionInfo ?: return null
            if (info.networkId == -1 && info.bssid == null) return null
            return WifiDetails(info.ssid.trim('"'), info.bssid ?: "", info.frequency)
        }
    }
}
