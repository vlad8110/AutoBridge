package dev.autobridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {
    /** One second of a stereo sine at [f] Hz and amplitude [amp], as 16-bit PCM. */
    private fun sine(f: Double, amp: Double): ByteArray {
        val out = ByteArray(48_000 * 4)
        for (n in 0 until 48_000) {
            val v = (amp * sin(2 * PI * f * n / 48_000)).toInt()
            for (c in 0..1) { val i = n * 4 + c * 2; out[i] = v.toByte(); out[i + 1] = (v shr 8).toByte() }
        }
        return out
    }

    /** RMS of the left channel, skipping the first 0.1 s while the filters settle. */
    private fun rms(pcm: ByteArray): Double {
        var sum = 0.0; var n = 0
        for (i in 4800 * 4 until pcm.size step 4) {
            val v = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toDouble()
            sum += v * v; n++
        }
        return sqrt(sum / n)
    }

    private fun db(after: Double, before: Double) = 20 * kotlin.math.log10(after / before)

    @Test fun flatLeavesTheMusicUntouched() {
        val pcm = sine(1000.0, 8000.0); val copy = pcm.copyOf()
        Equalizer().process(pcm)
        assertArrayEquals(copy, pcm)
    }

    @Test fun aBandMovesItsOwnFrequencyNotOthers() {
        val eq = Equalizer().apply { gains = FloatArray(10).also { it[5] = -6f } }  // 1 kHz down 6 dB
        val at1k = sine(1000.0, 8000.0); val before = rms(at1k)
        eq.process(at1k)
        assertEquals(-6.0, db(rms(at1k), before), 0.3)
        val eq2 = Equalizer().apply { gains = FloatArray(10).also { it[5] = -6f } }
        val at8k = sine(8000.0, 8000.0); val before8 = rms(at8k)
        eq2.process(at8k)
        assertEquals(0.0, db(rms(at8k), before8), 0.5)  // three octaves away: barely touched
    }

    @Test fun boostsNeverClip() {
        val eq = Equalizer().apply { gains = FloatArray(10) { 12f } }  // everything +12 dB
        val loud = sine(1000.0, 30000.0); val before = rms(loud)
        eq.process(loud)
        // The preamp takes 12 dB off first, so a near-full-scale tone stays the same level instead of clipping.
        assertEquals(0.0, db(rms(loud), before), 1.0)
    }

    @Test fun offIsBypassAndSettingsSurviveEncoding() {
        val eq = Equalizer().apply { gains = FloatArray(10) { 6f }; enabled = false }
        val pcm = sine(100.0, 8000.0); val copy = pcm.copyOf()
        eq.process(pcm)
        assertArrayEquals(copy, pcm)
        val g = floatArrayOf(1f, -2f, 3f, 0f, 0f, 0f, 0f, 0f, 12f, -12f)
        assertArrayEquals(g, Equalizer.decode(Equalizer.encode(g)), 0f)
        assertTrue(Equalizer.decode("junk").all { it == 0f })
    }

    @Test fun theMeterLightsTheBandThatIsPlaying() {
        val eq = Equalizer().apply { metering = true }
        eq.process(sine(1000.0, 16000.0))
        val loudest = eq.levels.indices.maxByOrNull { eq.levels[it] }
        assertEquals(5, loudest)  // the 1 kHz band
        assertTrue(eq.levels[0] < eq.levels[5] - 0.2f)  // 32 Hz stays far lower
    }

    @Test fun theLightsWaitForTheSpeakers() {
        val eq = Equalizer().apply { metering = true }
        eq.process(sine(1000.0, 16000.0))  // 1 s of music, then 1 s of silence
        eq.process(ByteArray(48_000 * 4))
        eq.heard(unheard = 48_000, atNanos = 0)  // the speakers are a second behind: still on the music
        val playing = eq.levelsHeard(0)!!
        assertEquals(5, playing.indices.maxByOrNull { playing[it] })
        eq.heard(unheard = 0, atNanos = 0)  // caught up: silence
        assertTrue(eq.levelsHeard(0)!!.all { it < 0.05f })
        assertEquals(null, eq.levelsHeard(1_000_000_000))  // a second past anything processed: the music stopped
    }

    @Test fun airShelfLiftsTheTopEndAndLeavesTheMids() {
        val up = { f: Double -> Equalizer().apply { gains = FloatArray(10).also { it[9] = 6f } }.let { eq ->
            val pcm = sine(f, 4000.0); val before = rms(pcm); eq.process(pcm); db(rms(pcm), before) } }
        // Measured after the automatic preamp (-6 dB here), so the lift shows as the gap between 16 kHz and 1 kHz.
        assertEquals(6.0, up(16_000.0) - up(1000.0), 1.0)
        assertEquals(0.0, up(1000.0) - up(250.0), 0.3)
    }

    @Test fun limiterEasesPeaksInsteadOfClipping() {
        assertEquals(1000, Equalizer.softClip(1000.0))  // untouched below -1 dBFS
        assertEquals(-29000, Equalizer.softClip(-29000.0))
        val hot = listOf(30000.0, 40000.0, 100000.0).map(Equalizer::softClip)
        assertTrue(hot == hot.sorted() && hot.all { it <= 32767 } && hot[0] < 30000)  // rising, never past full scale
    }

    @Test fun presetsStayGentle() {
        assertTrue(Equalizer.PRESETS.all { (_, g) -> g.size == 10 && g.all { it <= 6f } })
    }
}
