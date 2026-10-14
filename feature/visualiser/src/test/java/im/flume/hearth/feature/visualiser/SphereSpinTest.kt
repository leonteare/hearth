package im.flume.hearth.feature.visualiser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class SphereSpinTest {
    private val turn = (2.0 * PI).toFloat()

    @Test
    fun `the ball keeps turning with no music at all`() {
        var yaw = 0f
        repeat(60) { yaw = SphereSpin.step(yaw, 1f / 60f, level = 0f) }
        assertEquals(SphereSpin.REST.toDouble(), yaw.toDouble(), 1e-4)
    }

    @Test
    fun `music turns it faster, and never slower than at rest`() {
        val rest = SphereSpin.step(0f, 0.5f, level = 0f)
        val loud = SphereSpin.step(0f, 0.5f, level = 1f)
        assertTrue("$loud vs $rest", loud > rest)
        assertEquals(((SphereSpin.REST + SphereSpin.WITH_MUSIC) * 0.5f).toDouble(), loud.toDouble(), 1e-5)
        // A stray negative level can't stall or reverse the turn.
        assertEquals(rest.toDouble(), SphereSpin.step(0f, 0.5f, level = -1f).toDouble(), 1e-6)
    }

    @Test
    fun `the angle stays inside one turn however long it idles`() {
        var yaw = 0f
        // An hour paused at the idle frame rate.
        repeat(30 * 60 * 60) { yaw = SphereSpin.step(yaw, 1f / 30f, level = 0f) }
        assertTrue("$yaw", yaw >= 0f && yaw < turn)
    }

    @Test
    fun `wrapping round does not jump`() {
        val before = turn - 0.01f
        val after = SphereSpin.step(before, 0.2f, level = 0f)
        // Same place on the circle as the unwrapped angle.
        val plain = before + 0.2f * SphereSpin.REST
        assertEquals(0.0, ((after - plain + turn) % turn).toDouble(), 1e-4)
        assertTrue("$after", after < 0.1f)
    }
}
