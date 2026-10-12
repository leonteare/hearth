package im.flume.hearth.ui.screens.visualiser

import im.flume.hearth.core.playback.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    /** How many of one second's vsyncs at [hz] get drawn. */
    private fun drawnPerSecond(hz: Double, jitterNanos: Long = 0): Int {
        val pacer = FramePacer()
        var drawn = 0
        for (i in 0 until hz.toInt()) {
            val t = 1_000_000_000L + (i * 1e9 / hz).toLong() + if (i % 2 == 0) jitterNanos else -jitterNanos
            if (pacer.shouldDraw(t)) drawn++
        }
        return drawn
    }

    @Test
    fun `a 60 Hz screen draws every frame, as before`() {
        assertEquals(60, drawnPerSecond(60.0))
        assertEquals(60, drawnPerSecond(60.0, jitterNanos = 1_500_000))
    }

    @Test
    fun `a 120 Hz screen draws every other frame`() {
        assertEquals(60, drawnPerSecond(120.0))
    }

    @Test
    fun `a 90 Hz screen still gets 60 fps, not 45`() {
        assertEquals(60, drawnPerSecond(90.0))
    }

    @Test
    fun `a long stall doesn't bank a burst of frames`() {
        val pacer = FramePacer()
        assertTrue(pacer.shouldDraw(1_000_000_000L))
        assertTrue(pacer.shouldDraw(2_000_000_000L))
        // The next 12 vsyncs of a 120 Hz screen (100 ms): no more than 60 fps worth.
        val drawn = (1..12).count { pacer.shouldDraw(2_000_000_000L + it * 8_333_333L) }
        assertTrue("drew $drawn", drawn <= 7)
    }

    @Test
    fun `sleeps only after a second paused and still`() {
        val pacer = FramePacer()
        repeat(59) { assertFalse(pacer.sleep(1f / 60f, playing = false, still = true)) }
        assertTrue(pacer.sleep(1f / 60f + 0.001f, playing = false, still = true))
    }

    @Test
    fun `never sleeps while playing or while anything still moves`() {
        val pacer = FramePacer()
        repeat(300) { assertFalse(pacer.sleep(1f / 60f, playing = true, still = true)) }
        repeat(300) { assertFalse(pacer.sleep(1f / 60f, playing = false, still = false)) }
    }

    @Test
    fun `movement restarts the wait before sleeping`() {
        val pacer = FramePacer()
        repeat(50) { pacer.sleep(1f / 60f, playing = false, still = true) }
        pacer.sleep(1f / 60f, playing = false, still = false)
        repeat(50) { assertFalse(pacer.sleep(1f / 60f, playing = false, still = true)) }
    }

    @Test
    fun `after waking the next frame is drawn straight away`() {
        val pacer = FramePacer()
        pacer.shouldDraw(1_000_000_000L)
        pacer.wake()
        assertTrue(pacer.shouldDraw(1_001_000_000L))
    }

    @Test
    fun `silence is quiet, any visible level isn't`() {
        val audio = AudioFrame()
        assertTrue(FramePacer.isQuiet(audio))
        audio.bands[3] = 0.2f
        assertFalse(FramePacer.isQuiet(audio))
        audio.bands[3] = 0f
        audio.waveform[10] = -0.3f
        assertFalse(FramePacer.isQuiet(audio))
        audio.waveform[10] = 0.001f
        audio.kick = 0.004f
        assertTrue(FramePacer.isQuiet(audio))
    }
}
