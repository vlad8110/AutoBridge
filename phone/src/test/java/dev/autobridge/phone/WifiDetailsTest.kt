package dev.autobridge.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiDetailsTest {
    @Test fun channelsFromFrequency() {
        assertEquals(48, WifiDetails.channelOf(5240))   // a 5 GHz hotspot
        assertEquals(11, WifiDetails.channelOf(2462))   // a 2.4 GHz hotspot
        assertEquals(1, WifiDetails.channelOf(2412))
        assertEquals(14, WifiDetails.channelOf(2484))
        assertEquals(149, WifiDetails.channelOf(5745))
        assertEquals(5, WifiDetails.channelOf(5975))    // 6 GHz
    }

    @Test fun bandAndChannel() {
        val d = WifiDetails("MyTruck", "02:1a:2b:3c:4d:5e", 5240)
        assertEquals("5 GHz", d.band); assertEquals(48, d.channel)
    }
}

class LogSaverTest {
    private fun header(text: String) = LogSaver.header(text.byteInputStream())

    @Test fun acceptsTheCarsHeader() {
        assertEquals("autobridge-2026-11-24_17-05-31-auto.txt" to 1234, header("AUTOBRIDGE-LOG\nautobridge-2026-11-24_17-05-31-auto.txt\n1234\n"))
        assertEquals("autobridge-2026-11-25_13-10-00-screen.png" to 99, header("AUTOBRIDGE-LOG\nautobridge-2026-11-25_13-10-00-screen.png\n99\n"))
    }

    @Test fun refusesAnythingElse() {
        for (bad in listOf("HELLO\nx.txt\n1\n", "AUTOBRIDGE-LOG\n../evil.txt\n1\n", "AUTOBRIDGE-LOG\nlog.apk\n1\n",
                           "AUTOBRIDGE-LOG\nlog.txt\n999999999\n", "AUTOBRIDGE-LOG\nlog.txt\n")) {
            assertEquals(bad, true, runCatching { header(bad) }.isFailure)
        }
    }
}

class SealedLogTest {
    private val code = "K7QM-3XRP-9TWD"

    @Test fun withAPairingCodeOnlyAnEncryptedLogIsTaken() {
        val sealedHeader = "AUTOBRIDGE-LOG2\nlog.txt\n${20 * 1024 * 1024 + Pairing.SEAL_EXTRA}\n"
        assertEquals("log.txt" to 20 * 1024 * 1024 + Pairing.SEAL_EXTRA, LogSaver.header(sealedHeader.byteInputStream(), code))
        // Plain (anyone on the hotspot could send it) is refused once a code is saved, and LOG2 without one.
        assertEquals(true, runCatching { LogSaver.header("AUTOBRIDGE-LOG\nlog.txt\n1\n".byteInputStream(), code) }.isFailure)
        assertEquals(true, runCatching { LogSaver.header(sealedHeader.byteInputStream(), null) }.isFailure)
    }

    @Test fun theTrucksSealOpensHere() {
        val log = "hello".toByteArray()
        assertEquals("hello", Pairing.open(code, "log.txt", Pairing.seal(code, "log.txt", log)).decodeToString())
    }
}

class SetupMessageTest {
    @Test fun matchesWhatTheTruckParses() {
        // Same text as SetupMessageTest in the car app expects.
        assertEquals("AUTOBRIDGE-WIFI\nMyTruck\n02:1a:2b:3c:4d:5e\nhunter2pass\n",
            WifiDetails("MyTruck", "02:1a:2b:3c:4d:5e", 5240).setupMessage("hunter2pass"))
    }

    @Test fun setupReplyExplainsEachProblem() {
        val d = WifiDetails("MyTruck", "02:1a:2b:3c:4d:5e", 5240)
        assertEquals(d.setupMessage("hunter2pass"), WifiDetails.setupReply(true, d, "hunter2pass").first)
        for ((reply, hint) in listOf(
            WifiDetails.setupReply(false, d, "hunter2pass") to "Location",
            WifiDetails.setupReply(true, null, null) to "Open AutoBridge Phone",
            WifiDetails.setupReply(true, WifiDetails("<unknown ssid>", "02:00:00:00:00:00", 0), "x") to "Open AutoBridge Phone",
            WifiDetails.setupReply(true, d, null) to "Password",
        )) assert(reply.first.startsWith("AUTOBRIDGE-WIFI-ERROR\n") && hint in reply.first) { reply.first }
    }

    @Test fun inTheBackgroundTheLastSeenHotspotIsUsedOnlyOnTheSameNetwork() {
        val last = WifiDetails("MyTruck", "02:1a:2b:3c:4d:5e", 5805, gateway = 0x0101a8c0)
        val hidden = WifiDetails("<unknown ssid>", "02:00:00:00:00:00", 5805, gateway = 0x0101a8c0)
        assertEquals(last, WifiDetails.pick(hidden, last))
        val shown = WifiDetails("Other", "02:1a:2b:3c:4d:5e", 2437)
        assertEquals(shown, WifiDetails.pick(shown, last))
        // Home Wi-Fi on the same channel but another router: the truck's password must not be handed out there.
        org.junit.Assert.assertNull(WifiDetails.pick(hidden.copy(gateway = 0x0100a8c0), last))
        org.junit.Assert.assertNull(WifiDetails.pick(hidden.copy(frequencyMhz = 2437), last))
        org.junit.Assert.assertNull(WifiDetails.pick(hidden.copy(gateway = 0), last.copy(gateway = 0)))  // unknown router
        org.junit.Assert.assertNull(WifiDetails.pick(null, last))
        org.junit.Assert.assertNull(WifiDetails.pick(hidden, null))
    }
}
