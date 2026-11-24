package dev.autobridge

/** The aasdk session in libautobridge.so. One handle per connected phone. */
object Native {
    init { System.loadLibrary("autobridge") }

    /** Called on the native session thread; keep implementations short. */
    interface Listener {
        fun onVideo(data: ByteArray, timestampUs: Long)
        fun onAudio(stream: Int, pcm: ByteArray)            // stream: 0 media, 1 guidance, 2 system
        fun onAudioState(stream: Int, playing: Boolean)
        fun onMic(open: Boolean)
        fun onVideoFocus(projected: Boolean)                 // false: phone asked to show the car's own UI
        fun onEnded(reason: String)
    }

    /** Takes ownership of [fd], a connected TCP socket. cfg = [resolution, codecW, codecH, marginW, marginH, dpi]. */
    external fun start(fd: Int, cfg: IntArray, listener: Listener): Long
    external fun stop(handle: Long)
    external fun touch(handle: Long, action: Int, actionIndex: Int, ids: IntArray, xs: IntArray, ys: IntArray)
    external fun mic(handle: Long, pcm: ByteArray, length: Int)
    external fun sensors(handle: Long, moving: Boolean, night: Boolean)
    external fun videoFocus(handle: Long, projected: Boolean)
}
