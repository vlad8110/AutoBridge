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
