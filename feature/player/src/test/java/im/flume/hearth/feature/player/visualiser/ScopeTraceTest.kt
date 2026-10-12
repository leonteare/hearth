package im.flume.hearth.feature.player.visualiser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class ScopeTraceTest {
    private val n = 2048

    /** A sine of [amp] with a 64-sample period, as left and right with the given balance. */
    private fun stereo(leftAmp: Float, rightAmp: Float): Pair<FloatArray, FloatArray> {
        val mid = FloatArray(n)
        val side = FloatArray(n)
        for (i in 0 until n) {
            val s = sin(2 * PI * i / 64).toFloat()
            val l = leftAmp * s
            val r = rightAmp * s
            mid[i] = (l + r) / 2
            side[i] = (l - r) / 2
        }
        return mid to side
    }

    private fun maxReach(t: ScopeTrace): Float {
        var m = 0f
        for (i in 0 until t.count) m = max(m, max(abs(t.x[i]), abs(t.y[i])))
        return m
    }

    @Test
    fun `mono audio lies on the rising diagonal`() {
        val t = ScopeTrace()
        val (mid, side) = stereo(0.5f, 0.5f)
        t.build(mid, side, 1f / 60f)
        assertEquals(n, t.count)
        var spread = 0f
        for (i in 0 until t.count) {
            assertEquals(t.x[i], t.y[i], 1e-6f)
            spread = max(spread, t.x[i])
        }
        assertTrue("the line should actually extend, got $spread", spread > 0.2f)
    }

    @Test
    fun `left only lands on the horizontal axis and right only on the vertical`() {
        val left = ScopeTrace()
        val (lm, ls) = stereo(0.6f, 0f)
        left.build(lm, ls, 1f / 60f)
        for (i in 0 until left.count) assertEquals(0f, left.y[i], 1e-6f)
        assertTrue(maxReach(left) > 0.2f)

        val right = ScopeTrace()
        val (rm, rs) = stereo(0f, 0.6f)
        right.build(rm, rs, 1f / 60f)
        for (i in 0 until right.count) assertEquals(0f, right.x[i], 1e-6f)
        assertTrue(maxReach(right) > 0.2f)
    }

    @Test
    fun `silence collapses to the centre`() {
        val t = ScopeTrace()
        repeat(300) { t.build(FloatArray(n), FloatArray(n), 1f / 60f) }
        assertEquals(n, t.count)
        assertEquals(0f, maxReach(t), 0f)
    }

    @Test
    fun `a quiet track is scaled up to fill most of the screen and no further`() {
        val t = ScopeTrace()
        val (mid, side) = stereo(0.2f, 0.16f)
        repeat(60 * 15) { t.build(mid, side, 1f / 60f) }
        val reach = maxReach(t)
        assertTrue("reach $reach", reach in 0.7f..ScopeTrace.FILL + 0.01f)
    }

    @Test
    fun `a very quiet track is boosted only up to the limit`() {
        val t = ScopeTrace()
        val (mid, side) = stereo(0.01f, 0.01f)
        repeat(60 * 10) { t.build(mid, side, 1f / 60f) }
        assertTrue(t.scale <= ScopeTrace.MAX_GAIN * ScopeTrace.FILL + 1e-4f)
        assertTrue(maxReach(t) <= 0.01f * ScopeTrace.MAX_GAIN * ScopeTrace.FILL + 1e-4f)
    }

    @Test
    fun `clipping audio stays inside the frame`() {
        val t = ScopeTrace()
        val (mid, side) = stereo(1.6f, -1.4f)
        repeat(30) { t.build(mid, side, 1f / 60f) }
        for (i in 0 until t.count) {
            assertTrue(t.x[i] in -1f..1f)
            assertTrue(t.y[i] in -1f..1f)
        }
    }

    @Test
    fun `only the newest samples are used when given more than fit`() {
        val t = ScopeTrace(capacity = 4)
        val mid = floatArrayOf(0.9f, 0.9f, 0f, 0f, 0f, 0f)
        t.build(mid, FloatArray(6), 1f / 60f)
        assertEquals(4, t.count)
        for (i in 0 until 4) assertEquals(0f, t.x[i], 0f)
    }
}

class VisualiserStyleTest {
    @Test
    fun `a saved style that has been removed opens the default instead`() {
        for (id in listOf("pipes", "mystify", "magnetosphere", "halo", "", null)) {
            assertEquals(VisualiserStyle.MEMORY_CARD, VisualiserStyle.byId(id))
        }
        assertEquals(VisualiserStyle.MEMORY_CARD.id, VisualiserStyle.DEFAULT_ID)
        assertEquals(VisualiserStyle.SOUND_SCOPE, VisualiserStyle.byId("sound-scope"))
    }
}
