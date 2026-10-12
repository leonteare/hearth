package im.flume.hearth.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class BeatTrackerTest {
    private val rate = 44_100

    /** A drum-machine-ish loop: kicks on every beat, hats on the off-beats, a soft pad underneath. */
    private fun drums(seconds: Double, bpm: Double, keepKick: (Int) -> Boolean = { true }, seed: Int = 1): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed)
        val beat = 60.0 / bpm
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            // Pad: two quiet sines.
            out[i] = (0.08 * sin(2 * PI * 220 * t) + 0.06 * sin(2 * PI * 331 * t)).toFloat()
        }
        var k = 0
        while (k * beat < seconds) {
            val start = (k * beat * rate).toInt()
            if (keepKick(k)) {
                // Kick: a pitch sweep from 120 Hz down to 45 Hz with a fast decay.
                var phase = 0.0
                for (j in 0 until (0.35 * rate).toInt()) {
                    val i = start + j
                    if (i >= n) break
                    val tt = j.toDouble() / rate
                    val f = 45 + 75 * exp(-tt / 0.04)
                    phase += 2 * PI * f / rate
                    out[i] += (0.8 * exp(-tt / 0.12) * sin(phase)).toFloat()
                }
            }
            // Hat on the off-beat: a short burst of noise.
            val hat = start + (beat / 2 * rate).toInt()
            for (j in 0 until (0.04 * rate).toInt()) {
                val i = hat + j
                if (i >= n) break
                out[i] += ((rnd.nextFloat() * 2 - 1) * 0.15f * exp(-j.toDouble() / rate / 0.01)).toFloat()
            }
            k++
        }
        return out
    }

    private class Run(val tracker: BeatTracker, val beatTimes: List<Double>, val kickTimes: List<Double>)

    private fun run(signal: FloatArray): Run {
        val t = BeatTracker()
        val window = FloatArray(BeatTracker.WINDOW)
        val hop = (BeatTracker.HOP_SEC * rate).toInt()
        val beats = ArrayList<Double>()
        val kicks = ArrayList<Double>()
        var end = hop
        while (end <= signal.size) {
            for (i in 0 until BeatTracker.WINDOW) {
                val p = end - BeatTracker.WINDOW + i
                window[i] = if (p >= 0) signal[p] else 0f
            }
            val beatsBefore = t.beatCount
            val kicksBefore = t.counts[BeatTracker.KICK]
            val time = end.toDouble() / rate
            t.process(window, rate, time)
            if (t.beatCount != beatsBefore) beats += time
            if (t.counts[BeatTracker.KICK] != kicksBefore) kicks += time
            end += hop
        }
        return Run(t, beats, kicks)
    }

    @Test
    fun findsTheKicksAndTheTempo() {
        val r = run(drums(12.0, 120.0))
        // A kick every 0.5 s: from 2 s to 12 s there are 20, each found within 60 ms of it.
        val late = r.kickTimes.filter { it > 2.0 }
        assertEquals("kicks $late", 20, late.size)
        for (k in late) {
            val offset = k - Math.round(k / 0.5) * 0.5
            assertTrue("kick at $k", offset in -0.01..0.06)
        }
        assertEquals(20, r.beatTimes.count { it > 2.0 })
        assertEquals(120f, r.tracker.bpm, 3f)
        assertTrue("confidence ${r.tracker.confidence}", r.tracker.confidence > 0.5f)
    }

    @Test
    fun kicksStillFoundInABusyMix() {
        // The same loop under a held bass note and a wash of noise.
        val rnd = Random(3)
        val signal = drums(12.0, 124.0)
        for (i in signal.indices) {
            val t = i.toDouble() / rate
            signal[i] += (0.25 * sin(2 * PI * 55 * t)).toFloat() + (rnd.nextFloat() * 2 - 1) * 0.12f
        }
        val r = run(signal)
        val beat = 60.0 / 124
        val expected = (0 until 30).count { it * beat > 2.0 && it * beat < 11.95 }
        val found = r.beatTimes.count { it > 2.0 }
        assertTrue("found $found of $expected: ${r.beatTimes}", abs(found - expected) <= 2)
        assertEquals(124f, r.tracker.bpm, 4f)
    }

    @Test
    fun otherTemposToo() {
        for (bpm in listOf(90.0, 128.0, 150.0)) {
            val r = run(drums(14.0, bpm))
            assertEquals("at $bpm", bpm.toFloat(), r.tracker.bpm, (bpm * 0.03).toFloat())
        }
    }

    @Test
    fun missedBeatsAreFilledInOnceTheTempoIsKnown() {
        // From 7 s on, every fourth kick is missing.
        val signal = drums(14.0, 120.0, keepKick = { k -> k < 14 || k % 4 != 3 })
        val r = run(signal)
        // Beats at 8.0, 8.5 … 13.5 (three of them without a kick); the last may not be found yet at 14 s.
        val got = r.beatTimes.count { it >= 7.9 }
        assertEquals("beats ${r.beatTimes.filter { it >= 7.9 }}", 12.0, got.toDouble(), 1.0)
        // The filled-in ones land on the beat.
        for (b in r.beatTimes.filter { it >= 7.9 }) {
            val offset = b - Math.round(b / 0.5) * 0.5
            assertTrue("beat at $b", abs(offset) < 0.08)
        }
    }

    @Test
    fun steadyNoiseHasFewFalseBeats() {
        val rnd = Random(7)
        val noise = FloatArray(12 * rate) { (rnd.nextFloat() * 2 - 1) * 0.5f }
        val r = run(noise)
        assertTrue("beats ${r.beatTimes}", r.beatTimes.count { it > 2.0 } <= 2)
        assertTrue("kicks ${r.kickTimes}", r.kickTimes.count { it > 2.0 } <= 2)
    }

    @Test
    fun sustainedChordHasNoBeats() {
        val signal = FloatArray(10 * rate) {
            val t = it.toDouble() / rate
            (0.3 * sin(2 * PI * 55 * t) + 0.2 * sin(2 * PI * 220 * t) + 0.2 * sin(2 * PI * 277 * t) + 0.1 * sin(2 * PI * 3300 * t)).toFloat()
        }
        val r = run(signal)
        assertEquals(0, r.beatTimes.size)
    }

    @Test
    fun silenceIsQuiet() {
        val r = run(FloatArray(6 * rate))
        assertEquals(0, r.beatTimes.size)
        assertEquals(0f, r.tracker.bpm)
        assertEquals(0f, r.tracker.env[BeatTracker.KICK])
    }

    @Test
    fun envelopesAttackAtOnceAndEaseOut() {
        val r = run(drums(4.0, 120.0))
        val t = r.tracker
        val v0 = t.envAfter(BeatTracker.KICK, 0f)
        assertTrue(t.envAfter(BeatTracker.KICK, 0.1f) < v0)
        assertTrue(t.envAfter(BeatTracker.KICK, 1f) < 0.01f)
        // Right after a kick it's high.
        val r2 = run(drums(2.02, 120.0))
        assertTrue("kick env ${r2.tracker.env[BeatTracker.KICK]}", r2.tracker.env[BeatTracker.KICK] > 0.5f)
    }
}
