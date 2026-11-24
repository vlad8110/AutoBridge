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

    @Test fun answersInfoRequest() {
        val wifi = Wireless.Wifi("MyTruck", "secret", "02:1A:2B:3C:4D:5E")
        val phone = ByteArrayOutputStream().also { Wireless.send(it, Wireless.INFO_REQUEST, ByteArray(0)) }
        val car = ByteArrayOutputStream()
        try {
            Wireless.handshake(ByteArrayInputStream(phone.toByteArray()), car, "10.0.0.1", wifi) {}
        } catch (_: EOFException) { /* phone stream ended */ }

        val out = car.toByteArray()
        val firstLen = ((out[0].toInt() and 0xff) shl 8) or (out[1].toInt() and 0xff)
        assertEquals(Wireless.START_REQUEST, out[3].toInt())
        val second = out.copyOfRange(4 + firstLen, out.size)
        assertEquals(Wireless.INFO_RESPONSE, second[3].toInt())
        assertArrayEquals(Wireless.infoResponse(wifi), second.copyOfRange(4, second.size))
    }

    @Test fun readsNegativeStatus() {
        // int32 -3 encodes as a 10-byte varint
        val p = byteArrayOf(0x08) + byteArrayOf(-3, -1, -1, -1, -1, -1, -1, -1, -1, 1)
        assertEquals(-3L, Wireless.firstVarint(p))
    }
}
