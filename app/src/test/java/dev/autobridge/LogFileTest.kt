package dev.autobridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LogFileTest {
    private val d: File = Files.createTempDirectory("logs").toFile()

    @Test fun rotatesAtTheLimitAndKeepsOnePreviousFile() {
        File(d, "autobridge.log").writeText("old\n")
        assertFalse(Logs.rotate(d, maxBytes = 100))
        File(d, "autobridge.log").writeText("a".repeat(100) + "\n")
        File(d, "autobridge.1.log").writeText("oldest\n")
        assertTrue(Logs.rotate(d, maxBytes = 100))
        assertFalse(File(d, "autobridge.log").exists())
        assertEquals("a".repeat(100), File(d, "autobridge.1.log").readText().trim())  // the oldest is gone
    }

    @Test fun savedLogIsAllOfItNewestAppStartFirst() {
        File(d, "autobridge.1.log").writeText("tail of an older start\n==== AutoBridge 0.8.5 started\na\n")
        File(d, "autobridge.log").writeText("b\n==== AutoBridge 0.8.6 started\nc\n==== log capture restarted\nd\n")
        assertEquals("==== AutoBridge 0.8.6 started\nc\n==== log capture restarted\nd\n==== AutoBridge 0.8.5 started\na\nb\ntail of an older start",
            Logs.saved(d))
    }
}
