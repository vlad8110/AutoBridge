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
            Wireless.handshake(ByteArrayInputStream(phone.toByteArray()), car, "10.0.0.1", { wifi }, Wireless.PHONE_AA_PORT) {}
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
        assertArrayEquals(Wireless.versionRequest("10.0.0.1", Wireless.PHONE_AA_PORT), frames[0].second)
        assertArrayEquals(Wireless.startRequest("10.0.0.1", wifi, Wireless.PHONE_AA_PORT), frames[1].second)
        assertArrayEquals(Wireless.infoResponse(wifi), frames[2].second)
    }

    @Test fun inviteResendsTheStartRequestOnTheSameLink() {
        val wifi = Wireless.Wifi("MyTruck", "secret", "02:1A:2B:3C:4D:5E")
        val car = ByteArrayOutputStream()
        var invite: (() -> Unit)? = null
        try {
            Wireless.handshake(ByteArrayInputStream(ByteArray(0)), car, "10.0.0.1", { wifi }, Wireless.PHONE_AA_PORT, onLink = { invite = it }) {}
        } catch (_: EOFException) { /* phone stream ended */ }
        car.reset()
        invite!!()  // e.g. 10 s after the phone's Wi-Fi dropped
        val out = car.toByteArray()
        assertEquals(Wireless.START_REQUEST, out[3].toInt())
        assertArrayEquals(Wireless.startRequest("10.0.0.1", wifi, Wireless.PHONE_AA_PORT), out.copyOfRange(4, out.size))
    }

    @Test fun versionRequestCarriesEndpointInField6() {
        val endpoint = byteArrayOf(0x0a, 8) + "10.0.0.1".toByteArray() + byteArrayOf(0x10, 0xa0.toByte(), 0x29)  // 5280
        val expected = byteArrayOf(0x08, 4, 0x10, 1, 0x32, endpoint.size.toByte()) + endpoint
        assertArrayEquals(expected, Wireless.versionRequest("10.0.0.1", Wireless.PHONE_AA_PORT))
    }

    @Test fun readsNegativeStatus() {
        // int32 -3 encodes as a 10-byte varint
        val p = byteArrayOf(0x08) + byteArrayOf(-3, -1, -1, -1, -1, -1, -1, -1, -1, 1)
        assertEquals(-3L, Wireless.firstVarint(p))
    }

    @Test fun channelListCoversEveryBand() {
        val c = Wireless.CHANNELS
        assert(2412 in c && 2472 in c)                    // 2.4 GHz channels 1 and 13
        assert(5240 in c && 5500 in c && 5885 in c)       // 5 GHz 48, DFS 100, 177
        assert(5955 in c && 7115 in c)                    // 6 GHz 1 and 233
        assert(c.toSet().size == c.size)
        // the start request must still fit in one frame (u16 length)
        assert(Wireless.startRequest("127.0.0.1", Wireless.Wifi("MyTruck", "x", "02:1a:2b:3c:4d:5e"), Wireless.PHONE_AA_PORT).size < 65535)
    }
}

class SetupMessageTest {
    @Test fun phoneDetailsAreAccepted() {
        assertEquals(Wireless.Wifi("MyTruck", "hunter2pass", "02:1a:2b:3c:4d:5e"),
            Wireless.parseSetup("AUTOBRIDGE-WIFI\nMyTruck\n02:1a:2b:3c:4d:5e\nhunter2pass\n"))
    }

    @Test fun badMessagesAreRefused() {
        for (bad in listOf("", "HELLO\nMyTruck\n02:1a:2b:3c:4d:5e\nhunter2pass",
                           "AUTOBRIDGE-WIFI\nMyTruck\nnot-a-mac\nhunter2pass",
                           "AUTOBRIDGE-WIFI\nMyTruck\n02:1a:2b:3c:4d:5e\nshort",
                           "AUTOBRIDGE-WIFI\n \n02:1a:2b:3c:4d:5e\nhunter2pass",
                           "AUTOBRIDGE-WIFI\nMyTruck\n02:00:00:00:00:00\nhunter2pass",  // Location off: not the real BSSID
                           "AUTOBRIDGE-WIFI\n<unknown ssid>\n02:1a:2b:3c:4d:5e\nhunter2pass"))
            org.junit.Assert.assertNull(bad, Wireless.parseSetup(bad))
    }
}

class WifiCheckTest {
    private val ok = "AUTOBRIDGE-WIFI\nMyTruck\n02:1a:2b:3c:4d:5e\nhunter2pass\n"
    private val phone = Wireless.Wifi("MyTruck", "hunter2pass", "02:1a:2b:3c:4d:5e")

    @Test fun newTruckTakesThePhonesDetails() = assertEquals(phone, Wireless.checkSetup(ok, null).first)

    @Test fun matchingDetailsAreKept() {
        val (fix, line) = Wireless.checkSetup(ok, Wireless.Wifi("MyTruck", "hunter2pass", "02:1A:2B:3C:4D:5E"))
        org.junit.Assert.assertNull(fix)
        assert("matches" in line) { line }
    }

    @Test fun aWrongPasswordIsFixedAndNamed() {
        val (fix, line) = Wireless.checkSetup(ok, Wireless.Wifi("MyTruck", "hunter2pas", "02:1a:2b:3c:4d:5e"))
        assertEquals(phone, fix)
        assert("password" in line && "name" !in line) { line }
    }

    @Test fun phoneProblemsAreShownAndNothingIsSaved() {
        val (fix, line) = Wireless.checkSetup("AUTOBRIDGE-WIFI-ERROR\nTurn on Location\n", phone)
        org.junit.Assert.assertNull(fix)
        assert("Turn on Location" in line) { line }
        org.junit.Assert.assertNull(Wireless.checkSetup("", phone).first)  // older phone app: hangs up with nothing
        org.junit.Assert.assertNull(Wireless.checkSetup(null, phone).first)
    }

    @org.junit.Test fun bssidGetsAColonEveryTwoCharacters() {
        org.junit.Assert.assertEquals("02", Wireless.colonize("02"))
        org.junit.Assert.assertEquals("02:1a", Wireless.colonize("021A"))
        org.junit.Assert.assertEquals("02:1a:2b:3c:4d:5e", Wireless.colonize("021a2b3c4d5e"))
        org.junit.Assert.assertEquals("02:1a:2b:3c:4d:5e", Wireless.colonize("02:1a:2b:3c:4d:5e"))  // already formatted: unchanged
        org.junit.Assert.assertEquals("02:1a:2b:3c:4d:5e", Wireless.colonize("02-1a-2b-3c-4d-5e-ff"))  // junk and a 13th byte dropped
        org.junit.Assert.assertEquals("02:1", Wireless.colonize("02:1"))  // deleting back over a colon works
    }

    @org.junit.Test fun bssidCursorStaysAfterTheSameCharacters() {
        // "02:1a:2b": the cursor after 0, 2, 3 and 6 typed characters
        org.junit.Assert.assertEquals(listOf(0, 2, 4, 8), listOf(0, 2, 3, 6).map(Wireless::cursorAfter))
    }
}
