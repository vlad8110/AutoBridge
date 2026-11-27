package dev.autobridge

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The whole truck screen (status bar and GM's climate bar too) through Android's screen capture, after the owner
 * taps Start once. One capture session lasts until AutoBridge closes; between shots its display has no surface, so it
 * costs nothing. If the truck refuses or the owner taps Cancel, screenshots stay AutoBridge-only for this run.
 */
object ScreenCapture {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val main = Handler(Looper.getMainLooper())
    /** Asked and refused (or the truck can't): don't ask again until AutoBridge restarts. */
    @Volatile var declined = false

    val ready get() = display != null

    /** Call after the owner tapped Start, once the service runs as a mediaProjection foreground service. */
    fun start(c: Context, resultCode: Int, data: Intent): Boolean = runCatching {
        val p = c.getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data) ?: return false
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop(); Log.i(TAG, "full-screen capture ended") }
        }, main)
        val b = c.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val r = ImageReader.newInstance(b.width(), b.height(), PixelFormat.RGBA_8888, 2)
        projection = p; reader = r
        display = p.createVirtualDisplay("AutoBridge screenshot", b.width(), b.height(), c.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, main)
        Log.i(TAG, "full-screen capture ready: ${b.width()}x${b.height()}")
        true
    }.getOrElse { Log.w(TAG, "full-screen capture refused by the truck", it); stop(); declined = true; false }

    /** One frame of the whole screen, or null (then the caller falls back to AutoBridge's own picture). */
    fun grab(done: (Bitmap?) -> Unit) {
        val d = display; val r = reader
        if (d == null || r == null) return done(null)
        var finished = false
        fun end(b: Bitmap?) { if (finished) return; finished = true; r.setOnImageAvailableListener(null, null); d.surface = null; done(b) }
        r.setOnImageAvailableListener({ ir ->
            val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val bmp = runCatching {
                val plane = img.planes[0]
                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
            }.getOrNull()
            img.close()
            end(bmp)
        }, main)
        d.surface = r.surface
        main.postDelayed({ end(null) }, 2000)  // no frame in 2 s: use the fallback
    }

    fun stop() {
        runCatching { display?.release() }; runCatching { reader?.close() }; runCatching { projection?.stop() }
        display = null; reader = null; projection = null
    }
}
