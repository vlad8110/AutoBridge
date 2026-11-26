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
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * H.264 decoder onto the projection surface, on its own thread so a busy screen (e.g. pulling down
 * the shade) can't stall the connection or the audio. Frames are never dropped mid-stream: losing one
 * corrupts every frame after it. If decoding falls far behind, it skips to the next key frame instead
 * and asks the phone for one ([onNeedKeyFrame]).
 */
class VideoDecoder(private val mime: String, private val width: Int, private val height: Int, private val onNeedKeyFrame: () -> Unit) {
    private val hevc = mime == MediaFormat.MIMETYPE_VIDEO_HEVC
    private class Frame(val data: ByteArray, val timestampUs: Long, val config: Boolean)

    private val thread = HandlerThread("ab-video").apply { start() }
    private val handler = Handler(thread.looper)
    // Touched only on [thread].
    private var codec: MediaCodec? = null
    private var config: ByteArray? = null  // SPS/PPS, replayed when a new surface appears
    private val frames = ArrayDeque<Frame>()
    private val freeInputs = ArrayDeque<Int>()
    private var waitForKeyFrame = true
    private var surface: Surface? = null  // the last one attached, to restart on after a decoder error
    /** A decoder ran on a screen, and whether it ever put a picture out: tells a failed H.265 from a hidden screen. */
    @Volatile var shown = false
        private set
    @Volatile var decoded = false
        private set

    fun attach(surface: Surface) = handler.post { start(surface) }

    private fun start(surface: Surface) {
        closeCodec()
        this.surface = surface
        try {
            codec = MediaCodec.createDecoderByType(mime).apply {
                setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(c: MediaCodec, index: Int) { if (c === codec) { freeInputs.addLast(index); feed() } }
                    override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        if (c === codec) { decoded = true; runCatching { c.releaseOutputBuffer(index, true) } }
                    }
                    override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                        Log.w(TAG, "decoder error", e)
                        // A dead decoder would freeze the picture while sound and pings carry on: start a new one.
                        if (c === codec && !e.isTransient && !e.isRecoverable) this@VideoDecoder.surface?.let { start(it); onNeedKeyFrame() }
                    }
                    override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
                }, handler)
                val format = MediaFormat.createVideoFormat(mime, width, height)
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                configure(format, surface, null, 0)
                start()
            }
            shown = true
        } catch (e: Exception) {
            // The screen went away while the decoder was being made (the surface is already released): wait for the next one.
            Log.w(TAG, "decoder start failed", e)
            codec?.runCatching { release() }
            codec = null
            return
        }
        waitForKeyFrame = true
        config?.let { frames.addLast(Frame(it, 0, true)) }
    }

    fun detach() = handler.post { closeCodec(); surface = null }

    /** Ends the decoder and its thread; the instance can't be used afterwards. */
    fun release() = handler.post { closeCodec(); thread.quitSafely() }

    /** Called on the connection thread; only hands the frame over. */
    fun frame(data: ByteArray, timestampUs: Long) = handler.post {
        val types = nalTypes(data, hevc)  // one scan per frame, up to 60 a second
        val isConfig = isConfig(types, hevc)
        if (isConfig) config = data
        if (codec == null) return@post
        val key = hasKeyFrame(types, hevc)
        if (waitForKeyFrame) {
            if (!isConfig && !key) return@post  // a P-frame without its reference would smear
            if (key) waitForKeyFrame = false
        }
        // SPS/PPS sent together with an IDR is a normal frame with in-band config, not config-only.
        frames.addLast(Frame(data, timestampUs, isConfig && !key))
        if (frames.size > MAX_BACKLOG) {  // ~half a second behind: catch up at the next key frame
            frames.clear()
            waitForKeyFrame = true
            onNeedKeyFrame()
        }
        feed()
    }

    private fun feed() {
        val c = codec ?: return
        while (frames.isNotEmpty() && freeInputs.isNotEmpty()) {
            val f = frames.removeFirst()
            val i = freeInputs.removeFirst()
            try {
                c.getInputBuffer(i)!!.apply { clear(); put(f.data) }
                c.queueInputBuffer(i, 0, f.data.size, f.timestampUs, if (f.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
            } catch (e: RuntimeException) {  // also a key frame bigger than the input buffer
                Log.w(TAG, "decoder input error", e)
                return
            }
        }
    }

    private fun closeCodec() {
        codec?.runCatching { stop(); release() }
        codec = null
        frames.clear()
        freeInputs.clear()
    }

    companion object {
        private const val MAX_BACKLOG = 30

        /** NAL unit types in an Annex-B buffer (00 00 01 / 00 00 00 01 start codes). H.265 keeps the type in bits 1-6. */
        fun nalTypes(d: ByteArray, hevc: Boolean = false): List<Int> {
            val types = mutableListOf<Int>()
            var i = 0
            while (i + 3 < d.size) {
                if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                    val b = d[i + 3].toInt()
                    types += if (hevc) (b shr 1) and 0x3f else b and 0x1f
                    i += 3
                } else i++
            }
            return types
        }

        /** Starts with the stream's setup: SPS in H.264, VPS in H.265. */
        fun isConfig(types: List<Int>, hevc: Boolean = false) = types.firstOrNull() == (if (hevc) 32 else 7)
        /** Contains a frame that needs no earlier one: IDR in H.264, any IRAP (16-21) in H.265. */
        fun hasKeyFrame(types: List<Int>, hevc: Boolean = false) = types.any { if (hevc) it in 16..21 else it == 5 }
    }
}

/**
 * One player per Android Auto stream, each with its own writer thread and ~250 ms of buffer, so a
 * hiccup elsewhere doesn't empty the speaker buffer (the crackle). Players live for the whole session
 * (creating/destroying them at every sound also clicked). Car audio focus is taken once per session
 * and held: giving it back lets the car switch to its own source. AAOS mixes by usage.
 */
class AudioOut(private val audio: AudioManager, private val onFocusLost: () -> Unit) {
    companion object {
        /** Android Auto's music stream rate (the settings page shows it). */
        const val MUSIC_HZ = 48000
    }
    private class Stream(val rate: Int, val stereo: Boolean, val usage: Int, val music: Boolean)
    private val streams = arrayOf(
        Stream(MUSIC_HZ, true, AudioAttributes.USAGE_MEDIA, true),
        Stream(16000, false, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, false),
        Stream(16000, false, AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, false),
    )

    /**
     * Music plays for the whole session. Voice directions and system sounds stop between prompts:
     * the truck's volume knob follows whatever sound is playing, so an always-open navigation
     * stream made the knob change navigation volume instead of music.
     */
    private class Player(s: Stream) {
        val queue = java.util.concurrent.LinkedBlockingDeque<ByteArray>()
        private val queued = java.util.concurrent.atomic.AtomicInteger()  // bytes waiting in [queue]
        private val alwaysOn = s.music
        private val second = s.rate * (if (s.stereo) 2 else 1) * 2
        // Music waits until 0.4 s is queued before playing (and again if it ever runs dry), so a short
        // hiccup on the phone (pulling down its notification shade, Wi-Fi) is absorbed instead of heard.
        private val reserve = if (s.music) second * 2 / 5 else 0
        @Volatile private var wanted = true
        private var written = 0L  // music frames written to [track]; writer thread only
        private val stamp = android.media.AudioTimestamp()
        private val track: AudioTrack
        private val writer: Thread

        init {
            val mask = if (s.stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val quarterSecond = s.rate * (if (s.stereo) 2 else 1) * 2 / 4
            val min = AudioTrack.getMinBufferSize(s.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(s.usage)
                    .setContentType(if (s.music) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(s.rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 2, if (s.music) second else quarterSecond)).build().apply { play() }
            writer = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)  // busy UI or video can't delay a write
                var playing = true
                var filling = reserve > 0
                var underruns = 0
                try {
                    while (true) {
                        if (filling) {
                            if (queued.get() < reserve) { Thread.sleep(10); continue }
                            filling = false
                            underruns = track.underrunCount  // the empty wait itself doesn't count as running dry
                        }
                        val b = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (b != null) {
                            queued.addAndGet(-b.size)
                            // Ran dry (a stall, or the phone paused the music): build the reserve up again before
                            // playing this, rather than play a sliver now and then stall again.
                            if (reserve > 0 && track.underrunCount > underruns) {
                                underruns = track.underrunCount
                                android.util.Log.i(TAG, "music ran dry; building up 0.4 s again")
                                queue.offerFirst(b); queued.addAndGet(b.size)
                                filling = true
                                continue
                            }
                            if (!playing) { track.play(); playing = true }
                            if (alwaysOn) Equalizer.music.process(b)  // music only: directions stay clear
                            track.write(b, 0, b.size)
                            if (alwaysOn) {
                                written += b.size / 4
                                // Tells the EQ lights what's coming out of the speakers now (only while its page is open).
                                if (Equalizer.music.metering && track.getTimestamp(stamp)) Equalizer.music.heard(written - stamp.framePosition, stamp.nanoTime)
                            }
                        } else if (playing && !wanted && !alwaysOn) {
                            track.stop()  // plays out what's buffered, then the truck sees this sound as finished
                            playing = false
                        }
                    }
                } catch (_: InterruptedException) {}
            }, "ab-audio").apply { start() }
        }

        /** The phone started or stopped this stream. */
        fun want(on: Boolean) { wanted = on }

        fun offer(pcm: ByteArray) {
            wanted = true
            while (queued.get() > second) queue.poll()?.let { queued.addAndGet(-it.size) } ?: break  // over 1 s behind: drop the oldest rather than lag
            queued.addAndGet(pcm.size)
            queue.offer(pcm)
        }

        fun close() {
            writer.interrupt()
            writer.join(500)
            runCatching { track.stop(); track.release() }
        }

    }

    private val players = arrayOfNulls<Player>(3)
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

    /** Start/stop from the phone. Players stay open between sounds; voice and system sounds pause when stopped. */
    fun state(stream: Int, playing: Boolean) { player(stream).want(playing) }

    /** Called on the connection thread; only queues the samples. */
    fun write(stream: Int, pcm: ByteArray) = player(stream).offer(pcm)

    @Synchronized private fun player(stream: Int) = players[stream] ?: Player(streams[stream]).also { players[stream] = it }

    /** End of session: close the players and give focus back. */
    @Synchronized fun release() {
        players.forEachIndexed { i, p -> p?.close(); players[i] = null }
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
        if (thread?.isAlive == true) return  // a mic that failed to start may be opened again
        thread = Thread {
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, 16000).also { r -> runCatching { r.startRecording() }.onFailure { r.release(); throw it } }
            }.getOrElse { Log.w(TAG, "mic unavailable", it); return@Thread }
            val buf = ByteArray(640)
            while (!Thread.currentThread().isInterrupted) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) { Log.w(TAG, "mic read failed: $n"); break }  // e.g. the audio server restarted: don't spin
                if (n > 0) send(buf, n)
            }
            rec.stop(); rec.release()
        }.apply { start() }
    }
}

const val TAG = "AutoBridge"
