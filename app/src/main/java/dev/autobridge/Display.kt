package dev.autobridge

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Android Auto only streams fixed sizes (800x480, 720p, 1080p, 1440p, 4K). We pick one and add margins so the
 * phone draws its UI in exactly the car screen's shape; the margins are then cropped off-screen.
 */
data class VideoConfig(val resolution: Int, val codecW: Int, val codecH: Int, val marginW: Int, val marginH: Int, val dpi: Int) {
    val contentW get() = codecW - marginW
    val contentH get() = codecH - marginH
    fun toArray() = intArrayOf(resolution, codecW, codecH, marginW, marginH, dpi)  // read by bridge.cpp in this order

    /**
     * Screen point -> phone touch coordinate. The touchscreen we advertise is the visible content
     * area (the frame minus margins), like a real car's touch panel, so no margin offset applies.
     */
    fun toTouch(x: Float, y: Float, viewW: Int, viewH: Int) =
        Pair((x * contentW / viewW).roundToInt(), (y * contentH / viewH).roundToInt())
}

object Display {
    private class Tier(val id: Int, val w: Int, val h: Int)  // id = VideoCodecResolutionType in the Android Auto protocol
    private val landscape = listOf(Tier(1, 800, 480), Tier(2, 1280, 720), Tier(3, 1920, 1080), Tier(4, 2560, 1440), Tier(5, 3840, 2160))
    private val portrait = listOf(Tier(6, 720, 1280), Tier(7, 1080, 1920), Tier(8, 1440, 2560), Tier(9, 2160, 3840))

    /**
     * Picks the smallest stream whose visible area covers the screen (so nothing is stretched and
     * blurry), among the sizes the car's decoder [canDecode]; falls back to the largest it can.
     */
    fun pick(viewW: Int, viewH: Int, densityDpi: Int, canDecode: (Int, Int) -> Boolean = { _, _ -> true }): VideoConfig {
        val screen = viewW.toDouble() / viewH
        val configs = (if (viewH > viewW) portrait else landscape).filter { it.id == 1 || it.id == 6 || canDecode(it.w, it.h) }.map { t ->
            val (mw, mh) = if (screen > t.w.toDouble() / t.h) 0 to (t.h - t.w / screen).roundToInt()
                           else (t.w - t.h * screen).roundToInt() to 0
            val dpi = (densityDpi.toDouble() * (t.w - mw) / viewW).roundToInt().coerceIn(100, 600)
            VideoConfig(t.id, t.w, t.h, mw, mh, dpi)
        }
        // 5% tolerance: a hair of upscaling beats quadrupling the pixels to decode.
        return configs.firstOrNull { it.contentW >= viewW * 0.95 } ?: configs.last()
    }

    /** Uses the projection view size the screen last reported, else the whole display. */
    fun config(context: Context): VideoConfig {
        val s = Settings(context)
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = s.viewW.takeIf { it > 0 } ?: bounds.width()
        val h = s.viewH.takeIf { it > 0 } ?: bounds.height()
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
        return pick(w, h, context.resources.displayMetrics.densityDpi) { cw, ch -> decoders.any { it.isSizeSupported(cw, ch) } }
    }
}
