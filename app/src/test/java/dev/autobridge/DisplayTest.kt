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

    @Test fun bigScreenGetsH265With1080pBehind() {
        val p = Display.plan(2560, 1080, 160, hevcAllowed = true) { _, _ -> true }
        assertEquals(VideoPlan.H265, p.codec)
        assertEquals(listOf(4, 3, 2), p.configs.map { it.resolution })
        // Every tier has the same shape, so touch and cropping from the first one fit them all.
        for (c in p.configs) assertEquals(2560.0 / 1080, c.contentW.toDouble() / c.contentH, 0.01)
        assertEquals(p.configs[0].codecW.toDouble() / p.configs[0].contentW, p.configs[1].codecW.toDouble() / p.configs[1].contentW, 0.01)
    }

    @Test fun squarishScreenUnder2000WideStillGetsH265() {
        // 1080p fills a 1900x1500 screen only 1440 wide (side margins), so it was being stretched.
        assertEquals(VideoPlan.H265, Display.plan(1900, 1500, 160, hevcAllowed = true) { _, _ -> true }.codec)
    }

    @Test fun h264WhenH265IsNotWorthItOrFailed() {
        assertEquals(VideoPlan.H264, Display.plan(1920, 720, 160, hevcAllowed = true) { _, _ -> true }.codec)   // not bigger than 1080p
        assertEquals(VideoPlan.H264, Display.plan(1280, 720, 160, hevcAllowed = true) { _, _ -> true }.codec)   // 720p screen
        assertEquals(VideoPlan.H264, Display.plan(2560, 1080, 160, hevcAllowed = false) { _, _ -> true }.codec) // failed before
        assertEquals(VideoPlan.H264, Display.plan(2560, 1080, 160, hevcAllowed = true) { _, _ -> false }.codec) // car can't decode
        assertEquals(listOf(3), Display.plan(2560, 1080, 160, hevcAllowed = false) { _, _ -> true }.configs.map { it.resolution })
    }

    @Test fun portraitScreenUsesPortraitStream() {
        val c = Display.pick(1200, 1920, 160)
        assertEquals(7, c.resolution)
        assertEquals(1080, c.codecW)
    }

    @Test fun predictsTheLayoutYouGetInTheSierra() {
        // Both bars off: 2012 x 1680 is just taller than 1.2, so the map goes on top...
        assertEquals("Map on top, big cards", Display.layout(2012, 1680, 240))
        // ...and four pixels less height is enough to put it beside the cards: why one stale measurement flipped it.
        assertEquals("Map beside cards", Display.layout(2012, 1676, 240))
        // A higher density makes the screen look smaller: fewer cards.
        assertEquals("Map on top, two cards", Display.layout(2012, 1460, 300))
        // From the truck (truck bar on, status bar off): 400 gave the map on top with two cards, 410 one app at a time.
        assertEquals("Map on top, two cards", Display.layout(2012, 1710, 400))
        assertEquals("One app at a time", Display.layout(2012, 1710, 410))
    }
}
