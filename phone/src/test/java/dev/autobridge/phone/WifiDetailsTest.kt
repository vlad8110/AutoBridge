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

    @Test fun bandAndText() {
        val d = WifiDetails("MyTruck", "02:1a:2b:3c:4d:5e", 5240)
        assertEquals("5 GHz", d.band)
        assertEquals("Network (SSID): MyTruck\nBSSID: 02:1a:2b:3c:4d:5e\nChannel: 48 (5 GHz, 5240 MHz)", d.asText())
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

    @Test fun inTheBackgroundTheLastSeenHotspotIsUsed() {
        val last = WifiDetails("MyTruck", "02:1a:2b:3c:4d:5e", 5805)
        val hidden = WifiDetails("<unknown ssid>", "02:00:00:00:00:00", 5805)
        assertEquals(last, WifiDetails.pick(hidden, last))
        assertEquals(last, WifiDetails.pick(null, last))
        val shown = WifiDetails("Other", "02:1a:2b:3c:4d:5e", 2437)
        assertEquals(shown, WifiDetails.pick(shown, last))
        org.junit.Assert.assertNull(WifiDetails.pick(hidden.copy(frequencyMhz = 2437), last))  // another network
        org.junit.Assert.assertNull(WifiDetails.pick(hidden, null))
    }
}
