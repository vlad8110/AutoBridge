package dev.autobridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleTest {
    @Test fun tagsFollowTheWording() {
        assertEquals(Console.Kind.OK, Console.kind("Found phone at 10.0.0.19"))
        assertEquals(Console.Kind.OK, Console.kind("Android Auto connected"))
        assertEquals(Console.Kind.WAIT, Console.kind("Waiting for phone over Bluetooth…"))
        assertEquals(Console.Kind.WAIT, Console.kind("Looking for the AutoBridge Phone app on the hotspot…"))
        assertEquals(Console.Kind.FAIL, Console.kind("Disconnected: control: AASDK Error: 33"))  // "connected" is inside "Disconnected"
        assertEquals(Console.Kind.FAIL, Console.kind("Phone app not found. Is AutoBridge Phone installed and running on the phone?"))
    }
}
