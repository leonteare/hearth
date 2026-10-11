package im.flume.hearth.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedRingTest {

    /** Writes [n] samples whose value is their own index in the stream (from [first]), starting at [timeUs]. */
    private fun TimedRing.chunk(timeUs: Long, rate: Int, first: Int, n: Int) {
        beginChunk(timeUs, rate)
        for (i in 0 until n) put((first + i).toFloat())
        commit()
    }

    @Test
    fun readsTheWindowEndingAtAMediaTime() {
        val r = TimedRing(capacity = 1 shl 14)
        // 1000 Hz, so 1 sample = 1 ms. Chunks of 100 samples, back to back from t = 5 s.
        for (k in 0 until 20) r.chunk(5_000_000L + k * 100_000L, 1000, k * 100, 100)
        val out = FloatArray(4)
        // t = 5.25 s is sample 250; the window ends just before it.
        assertTrue(r.read(5_250_000L, out))
        assertArrayEquals(floatArrayOf(246f, 247f, 248f, 249f), out, 0f)
        assertEquals(1000, r.lastRate)
        // Spanning a chunk boundary.
        assertTrue(r.read(5_302_000L, out))
        assertArrayEquals(floatArrayOf(298f, 299f, 300f, 301f), out, 0f)
    }

    @Test
    fun futureAndOverwrittenTimesAreRefused() {
        val r = TimedRing(capacity = 1 shl 11)
        for (k in 0 until 30) r.chunk(k * 100_000L, 1000, k * 100, 100)
        val out = FloatArray(4)
        // Written up to t = 3 s.
        assertFalse(r.read(3_050_000L, out))
        assertTrue(r.read(3_000_000L, out))
        // The ring holds 2048 samples: t = 0.5 s is long gone.
        assertFalse(r.read(500_000L, out))
    }

    @Test
    fun aSeekHidesTheOldAudio() {
        val r = TimedRing(capacity = 1 shl 14)
        for (k in 0 until 10) r.chunk(10_000_000L + k * 100_000L, 1000, k * 100, 100)
        r.discontinuity()
        // Seek back to t = 2 s.
        r.chunk(2_000_000L, 1000, 5000, 100)
        val out = FloatArray(4)
        // The old times are no longer readable…
        assertFalse(r.read(10_500_000L, out))
        // …the new ones are, and a window reaching back before the seek reads silence there.
        assertTrue(r.read(2_002_000L, out))
        assertArrayEquals(floatArrayOf(0f, 0f, 5000f, 5001f), out, 0f)
        // Before the new audio starts: nothing.
        assertFalse(r.read(1_990_000L, out))
    }

    @Test
    fun gaplessTrackChangeKeepsOneTimeline() {
        val r = TimedRing(capacity = 1 shl 14)
        // Track A at 1000 Hz ends at t = 1 s; track B continues at 2000 Hz.
        for (k in 0 until 10) r.chunk(k * 100_000L, 1000, k * 100, 100)
        r.chunk(1_000_000L, 2000, 10_000, 400)
        val out = FloatArray(2)
        assertTrue(r.read(1_100_000L, out))
        // 0.1 s into B at 2 kHz = its 200th sample.
        assertArrayEquals(floatArrayOf(10_198f, 10_199f), out, 0f)
        assertEquals(2000, r.lastRate)
        assertTrue(r.read(900_000L, out))
        assertArrayEquals(floatArrayOf(898f, 899f), out, 0f)
        assertEquals(1000, r.lastRate)
    }
}
