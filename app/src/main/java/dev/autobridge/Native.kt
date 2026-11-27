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
        fun onNowPlaying(song: String?, artist: String?, album: String?, art: ByteArray?, durationSec: Int)  // null/-1: unchanged
        fun onPlayback(state: Int, positionSec: Int)        // state: 1 stopped, 2 playing, 3 paused
        fun onNavStatus(status: Int)                         // 0 unavailable, 1 active, 2 inactive, 3 rerouting
        fun onNavStep(type: Int, exit: Int, road: String?, cue: String?, destination: String?)
        fun onNavLegacyTurn(event: Int, side: Int, exit: Int, road: String?)
        fun onNavDistance(meters: Int, e3: Int, unit: Int, sec: Int, destMeters: Int, destE3: Int, destUnit: Int, destSec: Int, currentRoad: String?)
    }

    /** Takes ownership of [fd], a connected TCP socket. cfg = [VideoPlan.toArray].
     * truck = [Truck.toArray]; what is known at start is what Android Auto is told the car has.
     * gps = the truck's location will follow through [location]. */
    /** [hiddenUi]: Settings.HIDE_* bits. [car]: make, model, year from the truck ("" = AutoBridge's defaults). */
    external fun start(fd: Int, cfg: IntArray, truck: IntArray, gps: Boolean, hiddenUi: Int, car: Array<String>, listener: Listener): Long
    /** fix = [lat e7, lon e7, accuracy e3 m (-1 unknown), altitude e2 m (Int.MIN_VALUE unknown), speed e3 m/s (-1), bearing e6 deg (-1)] */
    external fun location(handle: Long, fix: IntArray)
    external fun stop(handle: Long)
    /** ms since the phone's last ping, or -1 before its first one. */
    external fun sincePingMs(handle: Long): Long
    external fun touch(handle: Long, action: Int, actionIndex: Int, ids: IntArray, xs: IntArray, ys: IntArray)
    external fun mic(handle: Long, pcm: ByteArray, length: Int)
    external fun sensors(handle: Long, moving: Boolean, night: Boolean, truck: IntArray)
    external fun videoFocus(handle: Long, projected: Boolean)
    /** Press and release an Android key code (media keys from the steering wheel). */
    external fun key(handle: Long, keycode: Int)
}
