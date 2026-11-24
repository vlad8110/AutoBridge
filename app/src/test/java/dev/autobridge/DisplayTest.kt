package dev.autobridge

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTest {
    @Test fun wideScreenGetsTopAndBottomMargins() {
        val c = Display.pick(1920, 720, 160)  // 2.67:1, wider than 16:9; 1080p covers it
        assertEquals(3, c.resolution)
        assertEquals(0, c.marginW)
        assertEquals(1080 - 720, c.marginH)   // 1920 / 2.667 = 720 rows of content
        // touch space is the visible 1920x720 area: corners map to its corners
        assertEquals(Pair(0, 0), c.toTouch(0f, 0f, 1920, 720))
        assertEquals(Pair(1920, 720), c.toTouch(1920f, 720f, 1920, 720))
    }

    @Test fun squarishScreenGetsSideMargins() {
        val c = Display.pick(1400, 1050, 160) // 4:3
        assertEquals(3, c.resolution)
        assertEquals(1920 - 1440, c.marginW)
        assertEquals(0, c.marginH)
        assertEquals(Pair(720, 540), c.toTouch(700f, 525f, 1400, 1050))  // centre of the 1440x1080 visible area
    }

    @Test fun bigScreenGetsSharperStream() {
        assertEquals(4, Display.pick(2400, 900, 160).resolution)            // 1080p would be stretched: use 1440p
        assertEquals(5, Display.pick(3600, 1600, 160).resolution)           // 4K
        assertEquals(3, Display.pick(2400, 900, 160) { w, _ -> w <= 1920 }.resolution)  // decoder tops out at 1080p
    }

    @Test fun portraitScreenUsesPortraitStream() {
        val c = Display.pick(1200, 1920, 160)
        assertEquals(8, c.resolution)   // content must cover 1200 px wide: 1440x2560
        assertEquals(1440, c.codecW)
    }
}
