package dev.autobridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class PairingTest {
    private val code = "K7QM-3XRP-9TWD"

    /** Runs the phone side on a local server and the truck side against it: (truck's result, phone's secret). */
    private fun pair(truckCode: String, truckPassword: String, byPassword: Boolean, phoneCode: String?, phonePasswords: List<String>): Pair<Pairing.Result, String?> =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val phone = Executors.newSingleThreadExecutor().submit<String?> {
                server.accept().use { s -> s.soTimeout = 2000; runCatching { Pairing.phone(s, phoneCode, phonePasswords) }.getOrNull() }
            }
            val truck = Socket(server.inetAddress, server.localPort).use { s ->
                s.soTimeout = 2000
                runCatching { Pairing.truck(s, truckCode, truckPassword, byPassword) }.getOrDefault(Pairing.Result.REFUSED)
            }
            truck to phone.get()
        }

    @Test fun theTruckAndPhoneWithTheSameCodePair() {
        assertEquals(Pairing.Result.CODE to code, pair(code, "hunter22", byPassword = false, phoneCode = code, phonePasswords = listOf("hunter22")))
    }

    @Test fun beforeTheCodeIsTypedTheHotspotPasswordStillWorks() {
        assertEquals(Pairing.Result.PASSWORD to "hunter22", pair(code, "hunter22", byPassword = true, phoneCode = null, phonePasswords = listOf("other-pass", "hunter22")))
    }

    @Test fun onceTheTruckPairedByCodeThePasswordAloneIsRefused() {
        // Anyone on the hotspot knows its password: that's the device this blocks.
        val (truck, phone) = pair(code, "hunter22", byPassword = false, phoneCode = null, phonePasswords = listOf("hunter22"))
        assertEquals(Pairing.Result.REFUSED, truck); assertNull(phone)
    }

    @Test fun aPhoneWithTheCodeRefusesATruckThatOnlyKnowsThePassword() {
        val (truck, phone) = pair("ABCD-EFGH-JKMN", "hunter22", byPassword = true, phoneCode = code, phonePasswords = listOf("hunter22"))
        assertEquals(Pairing.Result.REFUSED, truck); assertNull(phone)
    }

    @Test fun aDeviceWithoutThePasswordIsRefusedBothWays() {
        val (truck, phone) = pair(code, "wrong-pass", byPassword = true, phoneCode = null, phonePasswords = listOf("hunter22"))
        assertEquals(Pairing.Result.REFUSED, truck); assertNull(phone)
    }

    @Test fun somethingElseOnThePortIsNotThePhone() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val other = Executors.newSingleThreadExecutor().submit { server.accept().use { it.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()) } }
            Socket(server.inetAddress, server.localPort).use { s ->
                s.soTimeout = 2000
                assertEquals(Pairing.Result.REFUSED, runCatching { Pairing.truck(s, code, "hunter22", true) }.getOrDefault(Pairing.Result.REFUSED))
            }
            other.get()
        }
    }

    @Test fun codesAreTwelveEasyLettersAndTypingIsForgiving() {
        val c = Pairing.newCode()
        assertTrue(c, Regex("[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}").matches(c))
        assertEquals(code, Pairing.normalize("k7qm 3xrp9twd"))
        assertNull(Pairing.normalize("K7QM-3XRP-9TW"))   // one short
        assertNull(Pairing.normalize("K7QM-3XRP-9TW0"))  // 0 is never used: it looks like O
    }

    @Test fun onlyAWiFiAnswerSignedWithTheCodeChecksOut() {
        val nonce = Pairing.nonce()
        val msg = "AUTOBRIDGE-WIFI\nMyTruck\n02:1a:2b:3c:4d:5e\nhunter2pass\n"
        assertEquals(msg to true, Pairing.checkSetup(code, nonce, Pairing.signSetup(code, nonce, msg)))
        assertEquals(false, Pairing.checkSetup(code, Pairing.nonce(), Pairing.signSetup(code, nonce, msg)).second)  // replayed
        assertEquals(false, Pairing.checkSetup(code, nonce, Pairing.signSetup("ABCD-EFGH-JKMN", nonce, msg)).second)  // other code
        assertEquals(false, Pairing.checkSetup(code, nonce, Pairing.signSetup(code, nonce, msg).replace("MyTruck", "Evil")).second)
        assertEquals(msg to false, Pairing.checkSetup(code, nonce, msg))  // unsigned
        // Parsed the same as before: the proof line is dropped first.
        assertNotNull(Wireless.parseSetup(Pairing.checkSetup(code, nonce, Pairing.signSetup(code, nonce, msg)).first))
    }

    @Test fun sealedLogsOpenOnlyWithTheSameCodeAndName() {
        val log = "Hotspot: MyTruck / 02:1a:2b:3c:4d:5e".toByteArray()
        val sealed = Pairing.seal(code, "a.txt", log)
        assertEquals(log.size + Pairing.SEAL_EXTRA, sealed.size)
        assertArrayEquals(log, Pairing.open(code, "a.txt", sealed))
        assertTrue(runCatching { Pairing.open("ABCD-EFGH-JKMN", "a.txt", sealed) }.isFailure)
        assertTrue(runCatching { Pairing.open(code, "b.txt", sealed) }.isFailure)
        assertTrue(runCatching { Pairing.open(code, "a.txt", sealed.copyOf(10)) }.isFailure)
    }
}
