package dev.autobridge

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface

/** H.264 decoder onto the projection surface. Frames arriving with no surface are dropped. */
class VideoDecoder(private val width: Int, private val height: Int) {
    private var codec: MediaCodec? = null
    private var config: ByteArray? = null  // SPS/PPS, replayed when a new surface appears

    @Synchronized fun attach(surface: Surface) {
        release()
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            configure(format, surface, null, 0)
            start()
        }
        config?.let { queue(it, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
    }

    @Synchronized fun release() {
        codec?.runCatching { stop(); release() }
        codec = null
    }

    @Synchronized fun frame(data: ByteArray, timestampUs: Long) {
        if (isConfig(data)) config = data
        if (codec == null) return
        queue(data, timestampUs, if (isConfig(data)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        drain()
    }

    private fun queue(data: ByteArray, ts: Long, flags: Int) {
        val c = codec ?: return
        try {
            val i = c.dequeueInputBuffer(20_000)
            if (i < 0) return  // decoder busy: drop the frame rather than stall the phone
            c.getInputBuffer(i)!!.apply { clear(); put(data) }
            c.queueInputBuffer(i, 0, data.size, ts, flags)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "decoder error", e)
        }
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val o = c.dequeueOutputBuffer(info, 0)
                if (o >= 0) c.releaseOutputBuffer(o, true) else if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
            }
        } catch (_: IllegalStateException) {}
    }

    /** True when the first NAL unit is an SPS (type 7), i.e. the codec config frame. */
    private fun isConfig(d: ByteArray): Boolean {
        val start = if (d.size > 4 && d[2].toInt() == 1) 3 else 4
        return d.size > start && (d[start].toInt() and 0x1f) == 7
    }
}

/**
 * One AudioTrack per Android Auto stream. Car audio focus is taken once for the whole session and
 * held: giving it back between songs lets the car switch to its own source (e.g. Bluetooth audio),
 * which pauses the phone or silences us. AAOS mixes navigation/system sounds by their usage.
 */
class AudioOut(private val audio: AudioManager, private val onFocusLost: () -> Unit) {
    private class Stream(val rate: Int, val stereo: Boolean, val usage: Int)
    private val streams = arrayOf(
        Stream(48000, true, AudioAttributes.USAGE_MEDIA),
        Stream(16000, false, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE),
        Stream(16000, false, AudioAttributes.USAGE_ASSISTANCE_SONIFICATION),
    )
    private val tracks = arrayOfNulls<AudioTrack>(3)
    private var focus: AudioFocusRequest? = null

    /** Take the car's media focus for the session. */
    fun holdFocus() {
        if (focus != null) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onFocusLost()
            }.build().also { audio.requestAudioFocus(it) }
    }

    @Synchronized fun state(stream: Int, playing: Boolean) {
        if (playing) {
            if (tracks[stream] != null) return
            val s = streams[stream]
            val attrs = AudioAttributes.Builder().setUsage(s.usage).setContentType(
                if (stream == 0) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val mask = if (s.stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val min = AudioTrack.getMinBufferSize(s.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            tracks[stream] = AudioTrack.Builder().setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(s.rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(min * 4).build().apply { play() }
        } else {
            tracks[stream]?.run { stop(); release() }
            tracks[stream] = null
        }
    }

    fun write(stream: Int, pcm: ByteArray) {
        if (tracks[stream] == null) state(stream, true)  // some phones send data before "start"
        tracks[stream]?.write(pcm, 0, pcm.size)
    }

    /** End of session: stop everything and give focus back. */
    fun release() {
        (0..2).forEach { state(it, false) }
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}

/** 16 kHz mono microphone, read in 20 ms chunks while Android Auto has it open. */
class Mic(private val send: (ByteArray, Int) -> Unit) {
    @Volatile private var thread: Thread? = null

    @SuppressLint("MissingPermission")  // RECORD_AUDIO is requested by MainActivity
    fun open(on: Boolean) {
        if (!on) { thread?.run { interrupt(); join(500) }; thread = null; return }
        if (thread != null) return
        thread = Thread {
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, 16000).also { it.startRecording() }
            }.getOrElse { Log.w(TAG, "mic unavailable", it); return@Thread }
            val buf = ByteArray(640)
            while (!Thread.currentThread().isInterrupted) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) send(buf, n)
            }
            rec.stop(); rec.release()
        }.apply { start() }
    }
}

const val TAG = "AutoBridge"
