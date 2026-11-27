package dev.autobridge

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
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

/**
 * What we offer the phone: one codec and the sizes in that codec, best first. The phone uses the
 * first one it can send. [codec] is Android Auto's MediaCodecType: 3 H.264, 7 H.265.
 */
data class VideoPlan(val codec: Int, val configs: List<VideoConfig>) {
    val hevc get() = codec == H265
    /** Touch and cropping use the first size; every size has the same shape, so the others match. */
    val main get() = configs.first()
    val mime: String get() = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
    fun toArray() = intArrayOf(codec, configs.size) + configs.flatMap { it.toArray().toList() }  // read by bridge.cpp

    companion object { const val H264 = 3; const val H265 = 7 }
}

object Display {
    /**
     * Sharper picture: Android Auto only sends 1440p in H.265 (H.264 stops at 1080p, which is why
     * 0.2.2's 1440p failed). Offered when the screen is wider than 1080p, the car can decode it,
     * and H.265 hasn't already failed in this app version; otherwise the proven single 1080p plan.
     */
    fun plan(viewW: Int, viewH: Int, densityDpi: Int, hevcAllowed: Boolean, canDecodeHevc: (Int, Int) -> Boolean): VideoPlan {
        val single = pick(viewW, viewH, densityDpi)
        val portrait = viewH > viewW
        val big = if (portrait) Triple(8, 1440, 2560) else Triple(4, 2560, 1440)
        // Worth it only when the best H.264 size is stretched to fill the screen (its visible part is narrower than the screen).
        val upscaled = single.resolution in setOf(3, 7) && single.contentW < viewW * 0.95
        if (!hevcAllowed || !upscaled || !canDecodeHevc(big.second, big.third)) return VideoPlan(VideoPlan.H264, listOf(single))
        val tiers = if (portrait) listOf(big, Triple(7, 1080, 1920), Triple(6, 720, 1280))
                    else listOf(big, Triple(3, 1920, 1080), Triple(2, 1280, 720))
        return VideoPlan(VideoPlan.H265, tiers.map { (res, w, h) -> sized(res, w, h, viewW, viewH, densityDpi) })
    }

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
        return sized(res, w, h, viewW, viewH, densityDpi)
    }

    /** Margins so the phone draws in the screen's shape; density keeps the phone's UI the same size at every tier. */
    private fun sized(res: Int, w: Int, h: Int, viewW: Int, viewH: Int, densityDpi: Int): VideoConfig {
        val screen = viewW.toDouble() / viewH
        val (mw, mh) = if (screen > w.toDouble() / h) 0 to (h - w / screen).roundToInt()
                       else (w - h * screen).roundToInt() to 0
        val dpi = (densityDpi.toDouble() * (w - mw) / viewW).roundToInt().coerceIn(100, 600)
        return VideoConfig(res, w, h, mw, mh, dpi)
    }

    /**
     * The layout Android Auto picks for an area of [viewW] x [viewH] pixels at [dpi], by the rule decompiled from the
     * Android Auto app (headunit-revived's DisplayLayoutPolicy; openautolink's notes agree). Google can change these
     * numbers from its servers, so this is a guide shown next to the density setting, not a promise.
     */
    fun layout(viewW: Int, viewH: Int, dpi: Int): String {
        val w = viewW * 160.0 / dpi; val h = viewH * 160.0 / dpi; val ratio = w / h
        val tall = h >= 900 && ratio < 1.2
        return when {
            w >= 1240 && w > h && (ratio >= 1.67 || !tall) -> "Map beside cards"
            tall -> "Map on top"
            w >= 880 && ratio >= 1.67 -> "Map and side bar"
            h >= 680 -> "Map and one card"
            else -> "One app at a time"
        }
    }

    /** The density Android Auto is given for this screen: the setting for these bars, else the truck's own. */
    fun dpi(context: Context) = Settings(context).aaDpi.takeIf { it > 0 } ?: context.resources.displayMetrics.densityDpi

    /** Uses the projection view size the screen last reported, else the whole display. */
    fun config(context: Context): VideoPlan {
        val s = Settings(context)
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = s.viewW.takeIf { it > 0 } ?: bounds.width()
        val h = s.viewH.takeIf { it > 0 } ?: bounds.height()
        val hevc = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities }.getOrNull() }
        return plan(w, h, dpi(context), !s.hevcFailed && s.hevcEnabled) { cw, ch -> hevc.any { it.isSizeSupported(cw, ch) } }
    }
}
