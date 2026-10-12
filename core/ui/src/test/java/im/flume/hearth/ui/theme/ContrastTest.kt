package im.flume.hearth.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private val background = 0xFF121212.toInt()
    private val textSecondary = 0xFFB3B3B3.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val destructive = 0xFFF2B8B5.toInt()

    @Test
    fun `black on white is the full 21 to 1`() {
        assertEquals(21.0, Contrast.ratio(0xFF000000.toInt(), white), 0.01)
        assertEquals(1.0, Contrast.ratio(background, background), 0.0001)
    }

    @Test
    fun `dimmed secondary text is still readable on the background`() {
        val dimmed = Contrast.over(textSecondary, DimmedTextAlpha, background)
        assertTrue(Contrast.ratio(dimmed, background) >= Contrast.MIN_TEXT)
    }

    @Test
    fun `not on server titles and failure text are still readable`() {
        assertTrue(Contrast.ratio(Contrast.over(white, NotOnServerAlpha, background), background) >= Contrast.MIN_TEXT)
        assertTrue(Contrast.ratio(Contrast.over(destructive, NotOnServerAlpha, background), background) >= Contrast.MIN_TEXT)
    }

    @Test
    fun `the old half alpha was too faint`() {
        assertTrue(Contrast.ratio(Contrast.over(textSecondary, 0.5f, background), background) < Contrast.MIN_TEXT)
    }

    @Test
    fun `dimmed text still looks dimmer than normal secondary text`() {
        val dimmed = Contrast.over(textSecondary, DimmedTextAlpha, background)
        assertTrue(Contrast.ratio(dimmed, background) < Contrast.ratio(textSecondary, background))
    }
}
