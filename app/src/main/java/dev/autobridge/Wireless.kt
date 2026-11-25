package dev.autobridge

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The Bluetooth half of wireless Android Auto: the phone's Android Auto connects to our RFCOMM
 * service, we tell it our IP/port and the Wi-Fi credentials, and it then joins the Wi-Fi and
 * opens a TCP connection to us. Each frame is [u16 payload length][u16 message id][protobuf].
 */
object Wireless {
    const val UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"
    const val PORT = 5288

    // The car's hotspot blocks phone -> car connections, so the AutoBridge Phone app relays:
    // Android Auto connects to the relay on its own phone (127.0.0.1:PHONE_AA_PORT) and the car
    // connects out to the relay on PHONE_CAR_PORT. Must match phone/.../RelayService.kt.
    const val PHONE_AA_PORT = 5280  // the phone may remember this port for the truck from earlier SierraLink use
    const val PHONE_CAR_PORT = 5300
    /** The phone app saves logs the car sends here. Must match LogReceiver in the phone app. */
    const val PHONE_LOG_PORT = 5301
    /** The car holds a line open here so the phone app's "Send log" can ask for one. Must match RelayService. */
    const val PHONE_LOG_REQUEST_PORT = 5302
    /** A truck with no Wi-Fi details asks the phone app for them here. Must match RelayService.SETUP_PORT. */
    const val PHONE_SETUP_PORT = 5303
    private val MAC = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")

    /**
     * The phone app's answer: "AUTOBRIDGE-WIFI", SSID, BSSID, password, one per line.
     * Null for anything else, so a bad message is never saved (WPA2 passwords are 8-63 characters).
     */
    fun parseSetup(text: String): Wifi? {
        val l = text.split("\n")
        if (l.size < 4 || l[0] != "AUTOBRIDGE-WIFI") return null
        val (ssid, bssid, pw) = Triple(l[1], l[2].trim(), l[3])
        if (ssid.isBlank() || ssid.length > 32 || ssid == "<unknown ssid>" || !MAC.matches(bssid) || bssid == HIDDEN_MAC || pw.length !in 8..63) return null
        return Wifi(ssid, pw, bssid)
    }

    /** What Android reports instead of the real BSSID when Location is off. */
    const val HIDDEN_MAC = "02:00:00:00:00:00"

    /**
     * Compares the phone app's answer ([reply]: null if the app wasn't found) with what the truck has
     * [saved]. Returns the details to save (null: keep what's saved) and a line for the owner.
     * The phone is on the hotspot right now, so its network name and BSSID are the real ones.
     */
    fun checkSetup(reply: String?, saved: Wifi?): Pair<Wifi?, String> {
        if (reply == null) return null to "Wi-Fi check: AutoBridge Phone not found. Is the phone on the truck's hotspot with the app open?"
        val l = reply.split("\n")
        if (l[0] == "AUTOBRIDGE-WIFI-ERROR") return null to "Wi-Fi check: ${l.getOrNull(1) ?: "the phone app couldn't read the hotspot"}"
        val w = parseSetup(reply) ?: return null to "Wi-Fi check: the phone app has no hotspot password saved. Tap Password in AutoBridge Phone."
        if (saved == null) return w to "Got the hotspot details from your phone: ${w.ssid}"
        val wrong = listOfNotNull(
            "name".takeIf { w.ssid != saved.ssid },
            "BSSID".takeIf { !w.bssid.equals(saved.bssid, ignoreCase = true) },
            "password".takeIf { w.password != saved.password },
        )
        if (wrong.isEmpty()) return null to "Wi-Fi check: ${w.ssid} matches your phone"
        return w to "Wi-Fi check: fixed the hotspot ${wrong.joinToString(" and ")} to match your phone (${w.ssid})"
    }

    const val START_REQUEST = 1
    const val INFO_REQUEST = 2
    const val INFO_RESPONSE = 3
    const val VERSION_REQUEST = 4
    const val VERSION_RESPONSE = 5
    const val CONNECTION_STATUS = 6
    const val START_RESPONSE = 7

    /**
     * Every legal 20 MHz Wi-Fi channel (as MHz). The phone intersects this with what it supports and
     * gives up if nothing matches, so listing them all means any truck's hotspot channel works with
     * no setting: 2.4 GHz 1-13, 5 GHz 36-177 (incl. DFS), 6 GHz 1-233.
     */
    val CHANNELS: List<Int> = (1..13).map { 2407 + 5 * it } +
        (listOf(36, 40, 44, 48, 52, 56, 60, 64) + (100..144 step 4) + (149..177 step 4)).map { 5000 + 5 * it } +
        (1..233 step 4).map { 5950 + 5 * it }

    private const val WPA2_PERSONAL = 5
    private const val AP_STATIC = 0  // the car hotspot is a fixed access point (matches what works on GM)

    data class Wifi(val ssid: String, val password: String, val bssid: String)

    /**
     * Runs the handshake until the phone hangs up. [log] gets one line per step.
     * Order matters: the version request is the only place the phone reads our TCP address from
     * (without it the phone joins the Wi-Fi but never connects), and the start request follows its reply.
     */
    fun handshake(
        input: InputStream, output: OutputStream, ip: String, wifi: () -> Wifi, port: Int = PORT,
        onPhoneOnWifi: () -> Unit = {}, onLink: (invite: () -> Unit) -> Unit = {},
        log: (String) -> Unit,
    ) {
        val src = DataInputStream(input)
        // Asks the phone to (re)join the hotspot and start Android Auto, as a car does after a Wi-Fi drop.
        // wifi is read each time, so details fixed by a Wi-Fi check reach the phone without a new Bluetooth link.
        val invite = { send(output, START_REQUEST, startRequest(ip, wifi(), port)) }
        onLink(invite)
        send(output, VERSION_REQUEST, versionRequest(ip, port))
        log("Sent version and car address $ip:$port")
        while (true) {
            val len = src.readUnsignedShort()
            val id = src.readUnsignedShort()
            val payload = ByteArray(len).also { src.readFully(it) }
            when (id) {
                VERSION_RESPONSE -> {
                    log("Phone version reply (${payload.size} bytes)")
                    invite()
                }
                INFO_REQUEST -> {
                    val w = wifi()
                    send(output, INFO_RESPONSE, infoResponse(w))
                    log("Sent Wi-Fi ${w.ssid}")
                }
                START_RESPONSE -> log("Phone start response: status ${firstVarint(payload)}")
                CONNECTION_STATUS -> {
                    val status = firstVarint(payload)
                    log("Phone Wi-Fi status: $status (0 = connected)")
                    if (status == 0L) onPhoneOnWifi()
                    else log("The phone couldn't join ${wifi().ssid}. Check the hotspot password, then tap TEST in AutoBridge settings")
                }
                else -> log("Phone message $id (${payload.size} bytes)")
            }
        }
    }

    private fun endpoint(ip: String, port: Int) = Proto().string(1, ip).varint(2, port.toLong()).bytes()

    fun versionRequest(ip: String, port: Int = PORT): ByteArray = Proto().varint(1, 4).varint(2, 1).message(6, endpoint(ip, port)).bytes()

    fun startRequest(ip: String, wifi: Wifi, port: Int = PORT): ByteArray {
        val ap = Proto().string(1, wifi.ssid).string(2, wifi.password).string(3, wifi.bssid.lowercase())
            .varint(4, WPA2_PERSONAL.toLong())
        CHANNELS.forEach { ap.varint(6, it.toLong()) }
        return Proto().string(1, ip).varint(2, port.toLong()).varint(3, 0)
            .message(4, endpoint(ip, port)).message(5, ap.bytes()).bytes()
    }

    fun infoResponse(wifi: Wifi): ByteArray = Proto()
        .string(1, wifi.ssid).string(2, wifi.password).string(3, wifi.bssid.lowercase())
        .varint(4, WPA2_PERSONAL.toLong()).varint(5, AP_STATIC.toLong()).bytes()

    fun send(out: OutputStream, id: Int, payload: ByteArray) = synchronized(out) {  // invites come from another thread
        val frame = byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte(), (id shr 8).toByte(), id.toByte()) + payload
        out.write(frame)
        out.flush()
    }

    /** Value of the first varint field (status messages carry one field); signed per proto int32. */
    fun firstVarint(p: ByteArray): Long? {
        if (p.isEmpty() || p[0].toInt() and 7 != 0) return null
        var result = 0L
        var shift = 0
        for (i in 1 until p.size) {
            result = result or ((p[i].toLong() and 0x7f) shl shift)
            if (p[i].toInt() and 0x80 == 0) return result.toInt().toLong()
            shift += 7
        }
        return null
    }

    /** Just enough protobuf encoding for these messages: strings and varints. */
    class Proto {
        private val out = ByteArrayOutputStream()
        fun string(field: Int, s: String) = apply {
            val b = s.toByteArray()
            raw((field shl 3) or 2); raw(b.size); out.write(b)
        }
        fun message(field: Int, b: ByteArray) = apply { raw((field shl 3) or 2); raw(b.size); out.write(b) }
        fun varint(field: Int, v: Long) = apply { raw(field shl 3); rawLong(v) }
        fun bytes(): ByteArray = out.toByteArray()
        private fun raw(v: Int) = rawLong(v.toLong())
        private fun rawLong(value: Long) {
            var v = value
            while (v and 0x7fL.inv() != 0L) { out.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
    }
}
