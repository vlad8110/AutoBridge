package dev.autobridge

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A 10-band graphic EQ for Android Auto's music (48 kHz, 16-bit, stereo), done in software so it
 * works on any car. Nine bands are peaking biquads and the top one is an "Air" high shelf (RBJ cookbook).
 * A preamp lowers the level by the peak of the whole curve, and a soft limiter above -1 dBFS catches
 * what still overshoots (filter ringing on transients), so boosting never hard-clips.
 */
class Equalizer(private val rate: Int = 48_000) {
    /** dB per band, -12..12. Replaced as a whole, so the audio thread always sees a consistent set. */
    @Volatile var gains: FloatArray = FloatArray(BANDS.size)
        set(v) { field = v.copyOf(); dirty = true }
    @Volatile var enabled = true

    @Volatile private var dirty = true
    private var active = IntArray(0)  // bands not at 0 dB; kept here so the audio thread allocates nothing
    private var coef = Array(BANDS.size) { DoubleArray(5) }  // b0 b1 b2 a1 a2, normalised
    private val state = Array(2) { Array(BANDS.size) { DoubleArray(4) } }  // per channel: x1 x2 y1 y2
    private var preamp = 1.0

    /** Processes interleaved stereo 16-bit little-endian PCM in place, then meters it while the EQ page is open. */
    fun process(pcm: ByteArray) {
        if (enabled) equalize(pcm)
        if (metering) meter(pcm)
        processed += pcm.size / 4
    }

    // ---- the EQ page's light meter ----

    /** Set while the EQ page is open; otherwise metering costs nothing. */
    @Volatile var metering = false
    /** The newest loudness per band, 0..1 (-54..0 dBFS), after the EQ. Written by the audio thread; the UI shows [levelsHeard]. */
    val levels = FloatArray(BANDS.size)
    /** Frames through [process]; the speaker track gets the same frames in the same order. Audio thread only. */
    private var processed = 0L
    // Each ~33 ms reading, tagged with the frame it ends on, so the lights can wait for the speakers.
    private val ring = Array(RING) { FloatArray(BANDS.size) }
    private val ringFrame = LongArray(RING)
    @Volatile private var ringHead = 0
    // The frame the speakers were playing at [playNanos] (System.nanoTime), from the track's timestamp.
    @Volatile private var playFrame = -1L
    @Volatile private var playNanos = 0L

    /**
     * Called by the music writer after each write: [unheard] frames it has written are not out of the
     * speakers yet as of [atNanos]. Same thread as [process].
     */
    fun heard(unheard: Long, atNanos: Long) { playFrame = processed - unheard; playNanos = atNanos }

    /**
     * The levels for what the speakers play at [nowNanos], or null when nothing is playing then.
     * Readings are taken before the music goes into the truck's ~1 s speaker buffer, so showing the
     * newest one put the lights a second ahead of the beat (0.8.0).
     */
    fun levelsHeard(nowNanos: Long): FloatArray? {
        val head = ringHead
        if (head == 0) return null
        val at = if (playFrame < 0) ringFrame[(head - 1) % RING]  // no timestamp yet: newest
                 else playFrame + (nowNanos - playNanos) * rate / 1_000_000_000
        for (n in 1..minOf(head, RING)) {
            val i = (head - n) % RING
            if (ringFrame[i] <= at) return ring[i].takeIf { at - ringFrame[i] < rate / 4 }  // a gap: the music stopped
        }
        return ring[head % RING].takeIf { head >= RING }  // the speakers are further behind than we keep: show the oldest
    }

    private val meterCoef = Array(BANDS.size) { bandPass(BANDS[it], Q) }
    private val meterState = Array(BANDS.size) { DoubleArray(4) }
    private val meterSum = DoubleArray(BANDS.size)
    private var meterFrames = 0

    /** Band-pass each band on the mono mix (after the EQ, so the lights show what you hear), RMS every ~33 ms. */
    private fun meter(pcm: ByteArray) {
        var i = 0
        while (i + 3 < pcm.size) {
            val l = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val r = (pcm[i + 2].toInt() and 0xff) or (pcm[i + 3].toInt() shl 8)
            val x = (l + r) / 65536.0
            for (b in meterCoef.indices) {
                val c = meterCoef[b]; val s = meterState[b]
                val y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
                s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = y
                meterSum[b] += y * y
            }
            i += 4
            if (++meterFrames == rate / 30) {
                for (b in levels.indices) {
                    val db = 10 * kotlin.math.log10(meterSum[b] / meterFrames * 2 + 1e-12)  // *2: a full-scale sine reads 0 dB
                    levels[b] = ((db + 54) / 54).toFloat().coerceIn(0f, 1f)
                    meterSum[b] = 0.0
                }
                meterFrames = 0
                val h = ringHead % RING
                levels.copyInto(ring[h]); ringFrame[h] = processed + i / 4
                ringHead++
            }
        }
    }

    private fun bandPass(f: Double, q: Double): DoubleArray {  // RBJ band-pass, 0 dB at the centre
        val w = 2 * PI * f / rate
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha
        return doubleArrayOf(alpha / a0, 0.0, -alpha / a0, -2 * cos(w) / a0, (1 - alpha) / a0)
    }

    private fun equalize(pcm: ByteArray) {
        if (dirty) rebuild()
        if (active.isEmpty()) return
        var i = 0
        var ch = 0
        while (i + 1 < pcm.size) {
            var x = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toDouble() * preamp
            val st = state[ch]
            for (b in active) {
                val c = coef[b]; val s = st[b]
                val y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
                s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = y
                x = y
            }
            val out = softClip(x)
            pcm[i] = out.toByte(); pcm[i + 1] = (out shr 8).toByte()
            i += 2; ch = ch xor 1
        }
    }

    private fun rebuild() {
        dirty = false
        val g = gains
        coef = Array(BANDS.size) { if (it == AIR) highShelf(BANDS[it], g[it].toDouble()) else peaking(BANDS[it], g[it].toDouble(), Q) }
        active = g.indices.filter { g[it] != 0f }.toIntArray()
        // Neighbouring boosts add up, so the headroom comes from the real peak of the whole curve.
        val peak = (0..240).maxOf { k -> gainAt(20.0 * 1000.0.pow(k / 240.0)) }
        preamp = 1 / peak.coerceAtLeast(1.0)
    }

    /** Linear gain of the whole EQ at [f] Hz. */
    private fun gainAt(f: Double): Double {
        val w = 2 * PI * f / rate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        return coef.fold(1.0) { acc, c ->
            val nr = c[0] + c[1] * c1 + c[2] * c2; val ni = -(c[1] * s1 + c[2] * s2)
            val dr = 1 + c[3] * c1 + c[4] * c2; val di = -(c[3] * s1 + c[4] * s2)
            acc * kotlin.math.sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        }
    }

    private fun peaking(f: Double, db: Double, q: Double): DoubleArray {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f / rate
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha / a
        return doubleArrayOf((1 + alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha / a) / a0)
    }

    /** RBJ high shelf, slope [S]: the "Air" band. A 16 kHz peak barely works at 48 kHz (+12 dB gave +2 dB at 20 kHz). */
    private fun highShelf(f: Double, db: Double, s: Double = 0.7): DoubleArray {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f / rate; val cw = cos(w)
        val alpha = sin(w) / 2 * kotlin.math.sqrt((a + 1 / a) * (1 / s - 1) + 2)
        val sa = 2 * kotlin.math.sqrt(a) * alpha
        val a0 = (a + 1) - (a - 1) * cw + sa
        return doubleArrayOf(
            a * ((a + 1) + (a - 1) * cw + sa) / a0, -2 * a * ((a - 1) + (a + 1) * cw) / a0, a * ((a + 1) + (a - 1) * cw - sa) / a0,
            2 * ((a - 1) - (a + 1) * cw) / a0, ((a + 1) - (a - 1) * cw - sa) / a0,
        )
    }

    companion object {
        /** Band centres; the last is the Air shelf's corner. */
        val BANDS = doubleArrayOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 10000.0)
        val LABELS = listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "Air")
        const val MAX_DB = 12f
        private const val Q = 1.41  // one octave wide, so neighbouring bands blend smoothly
        private const val AIR = 9

        /** Soft limit above -1 dBFS: unchanged below it, then eased into full scale instead of clipped flat. */
        fun softClip(x: Double): Int {
            val m = kotlin.math.abs(x)
            if (m <= KNEE) return x.roundToInt()
            val y = KNEE + (32767 - KNEE) * kotlin.math.tanh((m - KNEE) / (32767 - KNEE))
            return (if (x < 0) -y else y).roundToInt().coerceIn(-32768, 32767)
        }
        private const val KNEE = 29204.0  // -1 dBFS

        /**
         * Small moves on top of the truck's own Bose tuning (see the research report): no preset boosts more than +6 dB.
         * Band order: 32, 64, 125, 250, 500, 1k, 2k, 4k, 8k, Air. A dragged band becomes "Custom" (no preset lit).
         */
        val PRESETS: List<Pair<String, FloatArray>> = listOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Bass" to floatArrayOf(3f, 5f, 3.5f, -1f, -1f, 0f, 0f, 0f, 0.5f, 1f),
            "Rock" to floatArrayOf(1.5f, 3f, 2f, -1f, -1.5f, -0.5f, 1f, 2f, 2f, 2f),
            "Pop" to floatArrayOf(1f, 2.5f, 2f, -1f, -1f, 0f, 1f, 1.5f, 2f, 2f),
            "Hip-hop" to floatArrayOf(4f, 5.5f, 3f, -1f, -1.5f, -0.5f, 0.5f, 1f, 1f, 1.5f),
            "Podcast" to floatArrayOf(-6f, -4f, -1f, -1.5f, 0f, 1f, 2.5f, 2f, 0f, -1f),
        )

        /** The one EQ on Android Auto's music; set from the settings page. Declared after BANDS, which it needs. */
        val music by lazy { Equalizer() }

        private const val RING = 64  // ~2 s of readings: more than the speaker buffer
        fun encode(g: FloatArray) = g.joinToString(",") { it.toString() }
        fun decode(s: String?): FloatArray =
            s?.split(',')?.mapNotNull { it.toFloatOrNull()?.coerceIn(-MAX_DB, MAX_DB) }?.takeIf { it.size == BANDS.size }?.toFloatArray()
                ?: FloatArray(BANDS.size)
    }
}
