package im.flume.hearth.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioAnalysisTest {

    private fun sine(hz: Double, rate: Int, amp: Float = 0.8f, n: Int = SpectrumAnalyzer.FFT_SIZE) =
        FloatArray(n) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

    @Test
    fun fftFindsTheSineBin() {
        val n = 1024
        val fft = Fft(n)
        val re = FloatArray(n) { sin(2 * PI * 37 * it / n).toFloat() }
        val im = FloatArray(n)
        fft.transform(re, im)
        val mags = FloatArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(37, mags.indices.maxBy { mags[it] })
        // A pure bin-centred sine has magnitude n/2 there.
        assertEquals(n / 2.0, kotlin.math.sqrt(mags[37].toDouble()), 1.0)
    }

    @Test
    fun fullScaleSineNormalisesToAboutOne() {
        val s = SpectrumAnalyzer()
        s.process(sine(1000.0, 48_000, amp = 1f), 48_000)
        val peak = s.magnitudes.max()
        assertTrue("peak $peak", peak in 0.8f..1.05f)
    }

    @Test
    fun sinePeaksInItsBand() {
        val s = SpectrumAnalyzer()
        for ((hz, rate) in listOf(1000.0 to 44_100, 120.0 to 48_000, 6000.0 to 44_100, 300.0 to 44_100)) {
            s.process(sine(hz, rate), rate)
            val loudest = s.bands.indices.maxBy { s.bands[it] }
            val expected = s.bandForHz(hz.toFloat(), rate)
            assertTrue("$hz Hz: loudest band $loudest, expected ~$expected", abs(loudest - expected) <= 1)
        }
    }

    @Test
    fun bandsAreLogSpacedAndOrdered() {
        val s = SpectrumAnalyzer()
        s.process(FloatArray(SpectrumAnalyzer.FFT_SIZE), 44_100)
        assertEquals(0, s.bandForHz(20f, 44_100))
        assertEquals(63, s.bandForHz(20_000f, 44_100))
        var last = 0f
        for (i in 0 until 64) {
            assertTrue(s.centreHz(i) > last)
            last = s.centreHz(i)
        }
        // Each octave gets roughly the same number of bands.
        val a = s.bandForHz(200f, 44_100) - s.bandForHz(100f, 44_100)
        val b = s.bandForHz(4000f, 44_100) - s.bandForHz(2000f, 44_100)
        assertTrue(abs(a - b) <= 1)
    }

    @Test
    fun silenceGivesEmptyBands() {
        val s = SpectrumAnalyzer()
        s.process(FloatArray(SpectrumAnalyzer.FFT_SIZE), 44_100)
        assertTrue(s.bands.all { it == 0f })
        assertEquals(0f, s.rms)
    }

    @Test
    fun steadyEnergyIsNotABeat() {
        val d = BeatDetector()
        var t = 0.0
        var beats = 0
        repeat(300) {
            t += 1 / 60.0
            if (d.process(0.1f + (it % 3) * 0.005f, t, 1 / 60f)) beats++
        }
        assertEquals(0, beats)
    }

    @Test
    fun spikesAreBeatsWithCooldown() {
        val d = BeatDetector(cooldownSec = 0.28f)
        var t = 0.0
        val beatTimes = mutableListOf<Double>()
        // 2 s of quiet, then a kick every 0.5 s for 4 s.
        repeat(120) { t += 1 / 60.0; d.process(0.05f, t, 1 / 60f) }
        for (frame in 0 until 240) {
            t += 1 / 60.0
            val kick = frame % 30 == 0
            if (d.process(if (kick) 0.4f else 0.05f, t, 1 / 60f)) beatTimes += t
        }
        assertEquals(8, beatTimes.size)
        // Two spikes 0.1 s apart: only the first counts.
        val d2 = BeatDetector(cooldownSec = 0.28f)
        t = 0.0
        repeat(120) { t += 1 / 60.0; d2.process(0.05f, t, 1 / 60f) }
        var count = 0
        for (frame in 0 until 20) {
            t += 1 / 60.0
            if (d2.process(if (frame == 0 || frame == 6) 0.4f else 0.05f, t, 1 / 60f)) count++
        }
        assertEquals(1, count)
    }

    @Test
    fun quietSpikesAreIgnored() {
        val d = BeatDetector(minEnergy = 0.01f)
        var t = 0.0
        var beats = 0
        repeat(200) {
            t += 1 / 60.0
            if (d.process(if (it % 30 == 0) 0.008f else 0.0001f, t, 1 / 60f)) beats++
        }
        assertEquals(0, beats)
    }

    @Test
    fun autoGainLiftsQuietSignalsSlowlyAndNeverCuts() {
        val g = AutoGain(target = 0.8f, maxGain = 2.5f)
        // A loud signal: no boost, and never a cut.
        repeat(120) { g.update(0.95f, 1 / 60f) }
        assertEquals(1f, g.gain)
        // Goes quiet: the boost creeps up over seconds, not at once.
        g.update(0.4f, 1 / 60f)
        assertTrue(g.gain < 1.1f)
        repeat(60 * 30) { g.update(0.4f, 1 / 60f) }
        assertEquals(2f, g.gain, 0.05f)
        // Near silence: clamped.
        repeat(60 * 60) { g.update(0.01f, 1 / 60f) }
        assertEquals(2.5f, g.gain)
        // Loud again: back to unity quickly.
        repeat(60) { g.update(0.9f, 1 / 60f) }
        assertEquals(1f, g.gain)
    }

    @Test
    fun quietTrackStillMovesTheBands() {
        val loud = AudioPipeline()
        val quiet = AudioPipeline()
        val a = sine(200.0, 44_100, amp = 0.8f)
        val b = sine(200.0, 44_100, amp = 0.05f)
        repeat(60 * 20) { loud.update(a, 44_100, 1 / 60f); quiet.update(b, 44_100, 1 / 60f) }
        val l = loud.frame.bands.max()
        val q = quiet.frame.bands.max()
        // The quiet one is lifted to the auto-gain target (0.8); the loud one is at the top already.
        assertTrue("loud $l quiet $q", l > 0.9f && q > 0.7f)
        assertTrue(quiet.frame.level > 0.4f)
        assertTrue(quiet.frame.waveform.maxOf { abs(it) } > 0.3f)
    }

    @Test
    fun pipelineDecaysToZeroWhenPaused() {
        val p = AudioPipeline()
        val tone = sine(80.0, 44_100)
        repeat(30) { p.update(tone, 44_100, 1 / 60f) }
        assertTrue(p.frame.bass > 0.2f)
        assertTrue(p.frame.level > 0.3f)
        repeat(240) { p.update(null, 44_100, 1 / 60f) }
        assertTrue(p.frame.bass < 0.01f)
        assertTrue(p.frame.bands.all { it < 0.01f })
        assertTrue(p.frame.waveform.all { abs(it) < 0.01f })
        assertTrue(p.frame.beat < 0.01f)
    }
}
