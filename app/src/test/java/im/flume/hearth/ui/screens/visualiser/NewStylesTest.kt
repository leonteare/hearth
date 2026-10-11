package im.flume.hearth.ui.screens.visualiser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RidgesTest {
    @Test
    fun envelopeIsFullInTheMiddleThirdAndFlatAtTheEnds() {
        assertEquals(1f, ridgeEnvelope(0f), 0f)
        assertEquals(1f, ridgeEnvelope(0.3f), 0f)
        assertEquals(1f, ridgeEnvelope(-1f / 3f), 0f)
        assertEquals(0f, ridgeEnvelope(0.8f), 0f)
        assertEquals(0f, ridgeEnvelope(-1f), 0f)
        assertEquals(0f, ridgeEnvelope(1f), 0f)
        // Symmetric, and never rising as you move outwards.
        var prev = 1f
        var x = 0f
        while (x <= 1f) {
            val e = ridgeEnvelope(x)
            assertEquals(e, ridgeEnvelope(-x), 1e-6f)
            assertTrue(e in 0f..1f)
            assertTrue("x=$x", e <= prev + 1e-6f)
            prev = e
            x += 0.01f
        }
    }

    @Test
    fun noiseIsBoundedAndContinuous() {
        var x = -20f
        var prev = valueNoise(x, 3)
        while (x < 20f) {
            x += 0.01f
            val v = valueNoise(x, 3)
            assertTrue(v in 0f..1f)
            assertTrue(abs(v - prev) < 0.05f)
            prev = v
        }
    }

    @Test
    fun intervalFollowsTrustedTempoInRange() {
        assertEquals(0.1f, ridgeInterval(0f, 1f), 0f)
        assertEquals(0.1f, ridgeInterval(120f, 0.2f), 0f)
        assertEquals(0.125f, ridgeInterval(120f, 0.9f), 1e-6f)
        for (bpm in 40..240) {
            val s = ridgeInterval(bpm.toFloat(), 1f)
            assertTrue("bpm $bpm → $s", s >= 1f / 14f - 1e-6f && s <= 1f / 6f + 1e-6f)
        }
    }

    @Test
    fun historyKeepsNewestFirstAndDropsTheOldest() {
        val h = RidgeHistory(lines = 3, points = 2)
        assertEquals(0f, h.get(0, 0), 0f)
        for (i in 1..5) h.push(floatArrayOf(i.toFloat(), i * 10f))
        assertEquals(3, h.size)
        assertEquals(5f, h.get(0, 0), 0f)
        assertEquals(50f, h.get(0, 1), 0f)
        assertEquals(4f, h.get(1, 0), 0f)
        assertEquals(3f, h.get(2, 0), 0f)
        assertEquals(0f, h.get(3, 0), 0f)
    }
}
