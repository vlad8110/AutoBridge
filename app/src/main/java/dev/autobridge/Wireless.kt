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
    const val PORT = 5288  // default only; the car may already use it, so BridgeService advertises the port it actually got

    const val START_REQUEST = 1
    const val INFO_REQUEST = 2
    const val INFO_RESPONSE = 3
    const val VERSION_REQUEST = 4
    const val VERSION_RESPONSE = 5
    const val CONNECTION_STATUS = 6
    const val START_RESPONSE = 7

    // Wi-Fi channels (MHz) we tell the phone the hotspot may use; it gives up if none match its own.
    val CHANNELS = listOf(2412, 2437, 2462, 5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825)

    private const val WPA2_PERSONAL = 5
    private const val AP_DYNAMIC = 1

    data class Wifi(val ssid: String, val password: String, val bssid: String)

    /**
     * Runs the handshake until the phone hangs up. [log] gets one line per step.
     * Order matters: the version request is the only place the phone reads our TCP address from
     * (without it the phone joins the Wi-Fi but never connects), and the start request follows its reply.
     */
    fun handshake(input: InputStream, output: OutputStream, ip: String, wifi: Wifi, port: Int = PORT, log: (String) -> Unit) {
        val src = DataInputStream(input)
        send(output, VERSION_REQUEST, versionRequest(ip, port))
        log("Sent version and car address $ip:$port")
        while (true) {
            val len = src.readUnsignedShort()
            val id = src.readUnsignedShort()
            val payload = ByteArray(len).also { src.readFully(it) }
            when (id) {
                VERSION_RESPONSE -> {
                    log("Phone version reply (${payload.size} bytes)")
                    send(output, START_REQUEST, startRequest(ip, wifi, port))
                }
                INFO_REQUEST -> {
                    send(output, INFO_RESPONSE, infoResponse(wifi))
                    log("Sent Wi-Fi ${wifi.ssid}")
                }
                START_RESPONSE -> log("Phone start response: status ${firstVarint(payload)}")
                CONNECTION_STATUS -> log("Phone on Wi-Fi (status ${firstVarint(payload)}), waiting for it to connect to $ip:$port")
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
        .varint(4, WPA2_PERSONAL.toLong()).varint(5, AP_DYNAMIC.toLong()).bytes()

    fun send(out: OutputStream, id: Int, payload: ByteArray) {
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
