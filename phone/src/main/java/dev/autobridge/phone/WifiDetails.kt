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

    /** For a new truck: what AutoBridge on the truck saves (Wireless.parseSetup in the car app). */
    fun setupMessage(password: String) = "AUTOBRIDGE-WIFI\n$ssid\n$bssid\n$password\n"

    /** False for what Android reports when it hides the network (Location off, or the app in the background). */
    val usable get() = ssid != "<unknown ssid>" && bssid.isNotEmpty() && bssid != "02:00:00:00:00:00"

    fun asText() = "Network (SSID): $ssid\nBSSID: $bssid\nChannel: $channel ($band, $frequencyMhz MHz)"

    companion object {
        /** The hotspot password the owner typed in, per network name (Android never lets apps read saved ones). */
        fun password(context: android.content.Context, ssid: String): String? =
            context.getSharedPreferences("hotspots", android.content.Context.MODE_PRIVATE).getString(ssid, null)
        fun setPassword(context: android.content.Context, ssid: String, password: String) =
            context.getSharedPreferences("hotspots", android.content.Context.MODE_PRIVATE).edit().putString(ssid, password).apply()

        /**
         * What to tell a truck that asks for its hotspot details, and a line for this app's status.
         * Problems go to the truck as "AUTOBRIDGE-WIFI-ERROR\n<what to do>" so its screen says what to fix.
         */
        fun setupReply(located: Boolean, d: WifiDetails?, password: String?): Pair<String, String> {
            val problem = when {
                !located -> "allow Location for AutoBridge Phone, so it can read the hotspot name"
                d == null -> "the phone isn't on Wi-Fi"
                d.ssid == "<unknown ssid>" || d.bssid.isEmpty() || d.bssid == "02:00:00:00:00:00" -> "open AutoBridge Phone on the phone for a few seconds, so it can read the hotspot name"
                password == null -> "tap Password in AutoBridge Phone and type the ${d.ssid} password"
                else -> null
            }
            return if (problem != null) "AUTOBRIDGE-WIFI-ERROR\n${problem.replaceFirstChar { it.uppercase() }}\n" to "The truck asked for its Wi-Fi details: $problem"
            else d!!.setupMessage(password!!) to "Sent ${d.ssid} details to the truck"
        }

        /** Saved whenever the app is open on a hotspot: Android hides the name from the relay running in the background. */
        fun remember(context: android.content.Context, d: WifiDetails) =
            context.getSharedPreferences("lastHotspot", android.content.Context.MODE_PRIVATE).edit()
                .putString("ssid", d.ssid).putString("bssid", d.bssid).putInt("mhz", d.frequencyMhz).apply()
        fun remembered(context: android.content.Context): WifiDetails? =
            context.getSharedPreferences("lastHotspot", android.content.Context.MODE_PRIVATE).let { p ->
                WifiDetails(p.getString("ssid", null) ?: return null, p.getString("bssid", "")!!, p.getInt("mhz", 0))
            }

        /**
         * The hotspot to tell a truck about: what Android shows now, else the one last seen with the app open,
         * unless the radio is plainly on another network (its frequency, which Android doesn't hide, differs).
         */
        fun pick(now: WifiDetails?, last: WifiDetails?): WifiDetails? = when {
            now != null && now.usable -> now
            last == null -> null
            now != null && now.frequencyMhz > 0 && now.frequencyMhz != last.frequencyMhz -> null
            else -> last
        }

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
