package dev.autobridge

import android.content.Context
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Android Auto only streams fixed sizes (800x480, 720p, 1080p). We pick one and add margins so the
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
    fun pick(viewW: Int, viewH: Int, densityDpi: Int): VideoConfig {
        val portrait = viewH > viewW
        val long = maxOf(viewW, viewH)
        // VideoCodecResolutionType values from the Android Auto protocol
        val (res, w, h) = when {
            portrait && long >= 1600 -> Triple(7, 1080, 1920)
            portrait -> Triple(6, 720, 1280)
            long >= 1600 -> Triple(3, 1920, 1080)
            long >= 1100 -> Triple(2, 1280, 720)
            else -> Triple(1, 800, 480)
        }
        val screen = viewW.toDouble() / viewH
        val (mw, mh) = if (screen > w.toDouble() / h) 0 to (h - w / screen).roundToInt()
                       else (w - h * screen).roundToInt() to 0
        val dpi = (densityDpi.toDouble() * (w - mw) / viewW).roundToInt().coerceIn(100, 400)
        return VideoConfig(res, w, h, mw, mh, dpi)
    }

    /** Uses the projection view size the screen last reported, else the whole display. */
    fun config(context: Context): VideoConfig {
        val s = Settings(context)
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = s.viewW.takeIf { it > 0 } ?: bounds.width()
        val h = s.viewH.takeIf { it > 0 } ?: bounds.height()
        return pick(w, h, context.resources.displayMetrics.densityDpi)
    }
}
