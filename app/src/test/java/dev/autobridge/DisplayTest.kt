package dev.autobridge

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTest {
    @Test fun wideScreenGetsTopAndBottomMargins() {
        val c = Display.pick(2400, 900, 160)  // 2.67:1, wider than 16:9
        assertEquals(3, c.resolution)
        assertEquals(0, c.marginW)
        assertEquals(1080 - 720, c.marginH)   // 1920 / 2.667 = 720 rows of content
        // touch space is the visible 1920x720 area: corners map to its corners
        assertEquals(Pair(0, 0), c.toTouch(0f, 0f, 2400, 900))
        assertEquals(Pair(1920, 720), c.toTouch(2400f, 900f, 2400, 900))
    }

    @Test fun squarishScreenGetsSideMargins() {
        val c = Display.pick(1600, 1200, 160) // 4:3
        assertEquals(1920 - 1440, c.marginW)
        assertEquals(0, c.marginH)
        assertEquals(Pair(720, 540), c.toTouch(800f, 600f, 1600, 1200))  // centre of the 1440x1080 visible area
    }

    @Test fun portraitScreenUsesPortraitStream() {
        val c = Display.pick(1200, 1920, 160)
        assertEquals(7, c.resolution)
        assertEquals(1080, c.codecW)
    }
}
