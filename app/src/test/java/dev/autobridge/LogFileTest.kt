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

    @Test fun savedLogIsOlderFileFirstAndCapped() {
        File(d, "autobridge.1.log").writeText("1\n2\n")
        File(d, "autobridge.log").writeText("3\n4\n")
        assertEquals("1\n2\n3\n4", Logs.saved(d))
        assertEquals("3\n4", Logs.saved(d, max = 2))
    }
}
