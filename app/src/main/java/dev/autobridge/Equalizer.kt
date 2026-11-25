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
    private var coef = Array(BANDS.size) { DoubleArray(5) }  // b0 b1 b2 a1 a2, normalised
    private val state = Array(2) { Array(BANDS.size) { DoubleArray(4) } }  // per channel: x1 x2 y1 y2
    private var preamp = 1.0

    /** Processes interleaved stereo 16-bit little-endian PCM in place. */
    fun process(pcm: ByteArray) {
        if (!enabled) return
        if (dirty) rebuild()
        val active = coef.indices.filter { gains[it] != 0f }
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
