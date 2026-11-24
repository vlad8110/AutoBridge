package dev.autobridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class WirelessTest {
    @Test fun encodesStartRequestLikeProtoc() {
        // protoc --encode of WifiStartRequest{ip_address:"10.0.0.1" port:5288}
        val expected = byteArrayOf(0x0a, 8) + "10.0.0.1".toByteArray() + byteArrayOf(0x10, 0xa8.toByte(), 0x29)
        assertArrayEquals(expected, Wireless.Proto().string(1, "10.0.0.1").varint(2, 5288).bytes())
    }

    @Test fun versionFirstThenStartThenWifi() {
        val wifi = Wireless.Wifi("MyTruck", "secret", "02:1A:2B:3C:4D:5E")
        val phone = ByteArrayOutputStream().also {
            Wireless.send(it, Wireless.VERSION_RESPONSE, ByteArray(0))
            Wireless.send(it, Wireless.INFO_REQUEST, ByteArray(0))
        }
        val car = ByteArrayOutputStream()
        try {
            Wireless.handshake(ByteArrayInputStream(phone.toByteArray()), car, "10.0.0.1", wifi) {}
        } catch (_: EOFException) { /* phone stream ended */ }

        val frames = mutableListOf<Pair<Int, ByteArray>>()
        val out = car.toByteArray()
        var i = 0
        while (i < out.size) {
            val len = ((out[i].toInt() and 0xff) shl 8) or (out[i + 1].toInt() and 0xff)
            frames += out[i + 3].toInt() to out.copyOfRange(i + 4, i + 4 + len)
            i += 4 + len
        }
        assertEquals(listOf(Wireless.VERSION_REQUEST, Wireless.START_REQUEST, Wireless.INFO_RESPONSE), frames.map { it.first })
        assertArrayEquals(Wireless.versionRequest("10.0.0.1"), frames[0].second)
        assertArrayEquals(Wireless.startRequest("10.0.0.1", wifi), frames[1].second)
        assertArrayEquals(Wireless.infoResponse(wifi), frames[2].second)
    }

    @Test fun versionRequestCarriesEndpointInField6() {
        val endpoint = byteArrayOf(0x0a, 8) + "10.0.0.1".toByteArray() + byteArrayOf(0x10, 0xa8.toByte(), 0x29)
        val expected = byteArrayOf(0x08, 4, 0x10, 1, 0x32, endpoint.size.toByte()) + endpoint
        assertArrayEquals(expected, Wireless.versionRequest("10.0.0.1"))
    }

    @Test fun readsNegativeStatus() {
        // int32 -3 encodes as a 10-byte varint
        val p = byteArrayOf(0x08) + byteArrayOf(-3, -1, -1, -1, -1, -1, -1, -1, -1, 1)
        assertEquals(-3L, Wireless.firstVarint(p))
    }
}
