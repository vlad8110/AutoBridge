package dev.autobridge

import org.junit.Assert.assertEquals
import org.junit.Test

class StartScreenTest {
    private fun at(vararg lines: String) = StartScreen.parse(lines.toList())

    @Test fun stepsFollowTheRealLog() {
        assertEquals(StartScreen.State(0, null), at("Waiting for phone over Bluetooth… (phone: Bluetooth on)"))
        assertEquals(1, at("Waiting for phone over Bluetooth…", "Phone Z Fold8 connected over Bluetooth", "Sent Wi-Fi MyTruck").step)
        assertEquals(2, at("Phone Wi-Fi status: 0 (0 = connected)", "Looking for the AutoBridge Phone app on the hotspot…").step)
        assertEquals(3, at("Looking for the AutoBridge Phone app on the hotspot…", "Found phone at 10.0.0.19").step)
        assertEquals(4, at("Found phone at 10.0.0.19", "Android Auto connected").step)
    }

    @Test fun aFailureStopsAtItsStepUntilTheNextTry() {
        val notFound = "Phone app not found. Is AutoBridge Phone running, with this truck's pairing code (Settings) typed in?"
        assertEquals(StartScreen.State(2, notFound), at("Sent Wi-Fi MyTruck", "Looking for the AutoBridge Phone app on the hotspot…", notFound))
        assertEquals(StartScreen.State(1, null), at(notFound, "Retrying: asking the phone to rejoin the hotspot"))
        // A drop after Android Auto was up; the next invite starts again from Wi-Fi.
        assertEquals("Disconnected: phone stopped answering", at("Android Auto connected", "Disconnected: phone stopped answering").failed)
        assertEquals(StartScreen.State(1, null), at("Disconnected: x", "No Android Auto: asking the phone to rejoin the hotspot"))
    }

    @Test fun sideEventsDontCountAsFailures() {
        assertEquals(StartScreen.State(4, null), at("Android Auto connected", "The car took the speakers (another audio source started)",
            "Screenshot not sent: timeout", "Couldn't send the log to the phone: timeout"))
    }
}
