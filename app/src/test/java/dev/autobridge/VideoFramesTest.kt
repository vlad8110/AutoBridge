package dev.autobridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFramesTest {
    private fun nal(type: Int, long: Boolean = true) =
        (if (long) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)) + byteArrayOf(type.toByte(), 0x42, 0x11)

    @Test fun findsNalTypes() {
        val frame = nal(7) + nal(8, long = false) + nal(0x65)  // SPS, PPS, IDR (nal_ref_idc bits set)
        assertEquals(listOf(7, 8, 5), VideoDecoder.nalTypes(frame))
        assertTrue(VideoDecoder.isConfig(frame))
        assertTrue(VideoDecoder.hasKeyFrame(frame))
    }

    @Test fun hevcFrames() {
        // H.265 header bytes: type << 1. VPS 32, SPS 33, PPS 34, IDR_W_RADL 19; TRAIL_R 1 is a P-frame.
        val key = nal(32 shl 1) + nal(33 shl 1) + nal(34 shl 1) + nal(19 shl 1)
        assertEquals(listOf(32, 33, 34, 19), VideoDecoder.nalTypes(key, hevc = true))
        assertTrue(VideoDecoder.isConfig(key, hevc = true))
        assertTrue(VideoDecoder.hasKeyFrame(key, hevc = true))
        assertFalse(VideoDecoder.hasKeyFrame(nal(1 shl 1), hevc = true))
    }

    @Test fun pFrameIsNotKey() {
        val p = nal(0x41)  // non-IDR slice
        assertFalse(VideoDecoder.isConfig(p))
        assertFalse(VideoDecoder.hasKeyFrame(p))
    }
}
