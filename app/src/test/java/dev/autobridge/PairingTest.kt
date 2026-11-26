package dev.autobridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class PairingTest {
    /** Runs the phone side on a local server and the truck side against it: (truck passed, phone's password). */
    private fun pair(truckPassword: String, phonePasswords: List<String>): Pair<Boolean, String?> =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val phone = Executors.newSingleThreadExecutor().submit<String?> {
                server.accept().use { s -> s.soTimeout = 2000; Pairing.phone(s, phonePasswords) }
            }
            val truck = Socket(server.inetAddress, server.localPort).use { s ->
                s.soTimeout = 2000
                runCatching { Pairing.truck(s, truckPassword) }.getOrDefault(false)
            }
            truck to phone.get()
        }

    @Test fun theTruckAndPhoneWithTheSamePasswordPair() {
        assertEquals(true to "hunter22", pair("hunter22", listOf("other-pass", "hunter22")))
    }

    @Test fun aDeviceWithoutThePasswordIsRefusedBothWays() {
        val (truck, phone) = pair("wrong-pass", listOf("hunter22"))
        assertFalse(truck)
        assertNull(phone)
    }

    @Test fun aPhoneWithNoSavedPasswordRefuses() {
        assertFalse(pair("hunter22", emptyList()).first)
    }

    @Test fun somethingElseOnThePortIsNotThePhone() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val other = Executors.newSingleThreadExecutor().submit { server.accept().use { it.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()) } }
            Socket(server.inetAddress, server.localPort).use { s ->
                s.soTimeout = 2000
                assertTrue(runCatching { !Pairing.truck(s, "hunter22") }.getOrDefault(true))
            }
            other.get()
        }
    }
}
