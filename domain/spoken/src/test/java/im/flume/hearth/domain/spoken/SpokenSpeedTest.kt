package im.flume.hearth.domain.spoken

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenSpeedTest {
    @Test
    fun `any speed snaps to the nearest 0_05 within range`() {
        assertEquals(1.35f, SpokenSpeed.snap(1.3499999f), 0f)
        assertEquals(1.35f, SpokenSpeed.snap(1.37f), 0f)
        assertEquals(0.5f, SpokenSpeed.snap(0.1f), 0f)
        assertEquals(3f, SpokenSpeed.snap(4f), 0f)
    }

    @Test
    fun `nudging moves one step and stops at the ends`() {
        assertEquals(1.05f, SpokenSpeed.step(1f, up = true), 0f)
        assertEquals(0.95f, SpokenSpeed.step(1f, up = false), 0f)
        assertEquals(3f, SpokenSpeed.step(3f, up = true), 0f)
        assertEquals(0.5f, SpokenSpeed.step(0.5f, up = false), 0f)
    }

    @Test
    fun `speeds read naturally`() {
        assertEquals("1.35×", SpokenFormat.speed(1.35f))
        assertEquals("2×", SpokenFormat.speed(2f))
    }
}
