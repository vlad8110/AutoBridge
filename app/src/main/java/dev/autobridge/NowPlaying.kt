package dev.autobridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.media.MediaBrowserService

/**
 * The phone's now playing, published to the truck as a media source, so GM's dashboard, the Media
 * card and the steering-wheel media buttons work with Android Auto.
 *
 * One MediaSession for the whole process life: GM's dashboard attaches to the session once, and a
 * replaced session leaves it frozen on an old song (SierraLink's finding).
 */
object NowPlaying {
    // Android Auto's key codes (same as Android's): next, previous, play, pause.
    private const val NEXT = 87; private const val PREVIOUS = 88
    private const val PLAY = 126; private const val PAUSE = 127

    private var session: MediaSession? = null
    private var meta = MediaMetadata.Builder()
    private var state = PlaybackState.STATE_NONE
    private var positionSec = 0

    @Synchronized fun session(context: Context): MediaSession = session ?: MediaSession(context.applicationContext, "AutoBridge").also { s ->
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = key(PLAY)
            override fun onPause() = key(PAUSE)
            override fun onSkipToNext() = key(NEXT)
            override fun onSkipToPrevious() = key(PREVIOUS)
            override fun onStop() = key(PAUSE)
        })
        session = s
    }

    private fun key(code: Int) { BridgeService.instance?.key(code) }

    /** Phone connected: claim the truck's media source right away, as OCBM does, before FM can. */
    @Synchronized fun connected(context: Context) {
        val s = session(context)
        meta = MediaMetadata.Builder()  // nothing from the last drive (artist, cover) on the dashboard
        meta.putString(MediaMetadata.METADATA_KEY_TITLE, "Android Auto").putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Android Auto")
        s.setMetadata(meta.build())
        state = PlaybackState.STATE_BUFFERING
        publishState()
        s.isActive = true
    }

    @Synchronized fun disconnected() {
        state = PlaybackState.STATE_STOPPED
        publishState()
    }

    /** Fields the phone left out keep their last value. */
    @Synchronized fun metadata(song: String?, artist: String?, album: String?, art: ByteArray?, durationSec: Int) {
        val s = session ?: return
        song?.let { meta.putString(MediaMetadata.METADATA_KEY_TITLE, it).putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it) }
        artist?.let { meta.putString(MediaMetadata.METADATA_KEY_ARTIST, it).putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it) }
        album?.let { meta.putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
        if (durationSec >= 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, durationSec * 1000L)
        art?.let { bytes ->
            // Small art: full-size covers made GM's cluster show the title without a picture (OCBM).
            // Decoded at reduced size: this runs on the session thread, and a full-size cover stalled sound and picture.
            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
            var sample = 1
            while (minOf(size.outWidth, size.outHeight) / (sample * 2) >= 512) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.let { b ->
                val small = scaled(b, 512)
                meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, small).putBitmap(MediaMetadata.METADATA_KEY_ART, small)
            }
        }
        s.setMetadata(meta.build())
    }

    /** [phoneState]: 1 stopped, 2 playing, 3 paused (Android Auto's MediaPlaybackStatus). */
    @Synchronized fun playback(phoneState: Int, positionSec: Int) {
        state = when (phoneState) {
            2 -> PlaybackState.STATE_PLAYING
            3 -> PlaybackState.STATE_PAUSED
            1 -> PlaybackState.STATE_STOPPED
            else -> state
        }
        if (positionSec >= 0) this.positionSec = positionSec
        publishState()
    }

    private fun publishState() {
        val s = session ?: return
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(state, positionSec * 1000L, if (state == PlaybackState.STATE_PLAYING) 1f else 0f, SystemClock.elapsedRealtime())
            .build())
    }

    internal fun scaled(b: Bitmap, max: Int): Bitmap {
        val big = maxOf(b.width, b.height)
        return if (big <= max) b else Bitmap.createScaledBitmap(b, b.width * max / big, b.height * max / big, true)
    }
}

/** Makes AutoBridge a media source the truck lists (Media card, source switcher, dashboard). Nothing to browse. */
class MediaService : MediaBrowserService() {
    override fun onCreate() {
        super.onCreate()
        sessionToken = NowPlaying.session(this).sessionToken
    }
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) = BrowserRoot("root", null)
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) = result.sendResult(mutableListOf())
}
