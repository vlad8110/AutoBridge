package dev.autobridge

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A 10-band graphic EQ for Android Auto's music (48 kHz, 16-bit, stereo), done in software so it
 * works on any car. Each band is a peaking biquad (RBJ cookbook). A preamp lowers the level by the
 * peak of the whole curve, so boosting never clips.
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
    }

    // ---- the EQ page's light meter ----

    /** Set while the EQ page is open; otherwise metering costs nothing. */
    @Volatile var metering = false
    /** Loudness per band, 0..1 (-54..0 dBFS), from what actually plays. Written by the audio thread; read by the UI. */
    val levels = FloatArray(BANDS.size)
    /** When [levels] last changed (System.nanoTime ms): the UI lets the lights fall once the music stops. */
    @Volatile var levelsAt = 0L
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
                levelsAt = System.nanoTime() / 1_000_000
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
            val out = x.roundToInt().coerceIn(-32768, 32767)
            pcm[i] = out.toByte(); pcm[i + 1] = (out shr 8).toByte()
            i += 2; ch = ch xor 1
        }
    }

    private fun rebuild() {
        dirty = false
        val g = gains
        coef = Array(BANDS.size) { peaking(BANDS[it], g[it].toDouble(), Q) }
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

    companion object {
        val BANDS = doubleArrayOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val LABELS = listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        const val MAX_DB = 12f
        private const val Q = 1.41  // one octave wide, so neighbouring bands blend smoothly

        /** Starting points tuned for a truck cabin; any slider change becomes "Custom". */
        val PRESETS: List<Pair<String, FloatArray>> = listOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Bass" to floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Rock" to floatArrayOf(4f, 3f, 2f, 0f, -1f, -1f, 0f, 2f, 3f, 4f),
            "Pop" to floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 0f, 1f),
            "Hip-hop" to floatArrayOf(6f, 5f, 2f, 0f, -1f, -1f, 1f, 0f, 2f, 3f),
            "Vocal" to floatArrayOf(-2f, -2f, -1f, 1f, 3f, 4f, 4f, 3f, 1f, 0f),
            "Podcast" to floatArrayOf(-6f, -4f, -2f, 0f, 2f, 4f, 4f, 2f, 0f, -2f),
            "Highway" to floatArrayOf(3f, 3f, 2f, 0f, 0f, 1f, 2f, 3f, 3f, 2f),  // lifts lows and highs over road noise
        )

        /** The one EQ on Android Auto's music; set from the settings page. Declared after BANDS, which it needs. */
        val music by lazy { Equalizer() }

        fun encode(g: FloatArray) = g.joinToString(",") { it.toString() }
        fun decode(s: String?): FloatArray =
            s?.split(',')?.mapNotNull { it.toFloatOrNull()?.coerceIn(-MAX_DB, MAX_DB) }?.takeIf { it.size == BANDS.size }?.toFloatArray()
                ?: FloatArray(BANDS.size)
    }
}
