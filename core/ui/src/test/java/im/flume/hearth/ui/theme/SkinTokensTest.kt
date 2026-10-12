package im.flume.hearth.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import im.flume.hearth.ui.components.CoverColorFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The default skin's extra roles, icon slots and text contrast, so a token swap can't quietly break the look. */
class SkinTokensTest {
    private val c = HearthColors()

    @Test
    fun `every icon slot has an icon`() {
        val icons = HearthIcons()
        val slots = HearthIcons::class.java.methods.filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java && it.name.startsWith("get") }
        assertTrue("found only ${slots.size} slots", slots.size > 80)
        slots.forEach { assertNotNull(it.name, it.invoke(icons)) }
    }

    @Test
    fun `main and secondary text are readable on every background`() {
        for (bg in listOf(c.background, c.surface, c.surfaceHigh)) {
            assertTrue(ratio(c.text, bg) >= Contrast.MIN_TEXT)
            assertTrue(ratio(c.textSecondary, bg) >= Contrast.MIN_TEXT)
        }
    }

    @Test
    fun `dimmed text is readable on the page and cards and still legible on sheets`() {
        assertTrue(ratio(c.textDim, c.background) >= Contrast.MIN_TEXT)
        assertTrue(ratio(c.textDim, c.surface) >= Contrast.MIN_TEXT)
        assertTrue(ratio(c.textDim, c.surfaceHigh) >= Contrast.MIN_LARGE)
    }

    @Test
    fun `every built-in accent keeps black content on it`() {
        AccentChoices.forEach { (name, argb) -> assertEquals(name, Color.Black, readableOn(Color(argb))) }
    }

    @Test
    fun `a dark custom accent gets white content`() {
        assertEquals(Color.White, readableOn(Color(0xFF1A237E)))
        assertEquals(Color.Black, readableOn(Color.White))
    }

    @Test
    fun `the added roles default to today's look`() {
        assertTrue(c.isDark)
        assertEquals(CoverColorFallback, c.coverFallback)
        assertEquals(Color.White, c.playButton)
        assertEquals(Color.Black, c.onPlayButton)
        assertEquals(
            listOf(0xFF4A3B6B, 0xFF2F5D62, 0xFF6B3B3B, 0xFF3B4F6B, 0xFF5E5A2E, 0xFF3E6B3B, 0xFF6B4A2E, 0xFF55305E).map { Color(it) },
            c.coverPlaceholders,
        )
    }

    /** Contrast of [fg] (with its alpha, as drawn) over the opaque [bg]. */
    private fun ratio(fg: Color, bg: Color): Double {
        val bgArgb = bg.toArgb()
        return Contrast.ratio(Contrast.over(fg.copy(alpha = 1f).toArgb(), fg.alpha, bgArgb), bgArgb)
    }
}
