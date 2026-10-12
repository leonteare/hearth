package im.flume.hearth.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The default skin must look exactly like the app did before tokens existed. */
class TokensTest {
    @Test
    fun `default colours are the theme's colours`() {
        val c = HearthColors()
        assertEquals(Color(0xFF121212), c.background)
        assertEquals(Color(0xFF1C1C1C), c.surface)
        assertEquals(Color(0xFF282828), c.surfaceHigh)
        assertEquals(Color.White, c.text)
        assertEquals(Color(0xFFB3B3B3), c.textSecondary)
        assertEquals(TextSecondary.copy(alpha = DimmedTextAlpha), c.textDim)
        assertEquals(Color.White.copy(alpha = 0.08f), c.divider)
        assertEquals(Color(0xFF3E3E3E), c.outline)
        assertEquals(Color(0xF0101010), c.navBar)
        assertEquals(Color(0xFF5038A0), c.liked)
        assertEquals(Color(0xFF1E6B52), c.downloads)
        assertEquals(Color(0xFF1E5A8C), c.podcasts)
        assertEquals(Color(0xFF8C4A1E), c.audiobooks)
        assertEquals(Color(0xFFF2B8B5), c.destructive)
        assertEquals(Color(0xFFB3261E), c.destructiveFill)
        assertEquals(Color.Black, c.onAccent)
    }

    @Test
    fun `default spacing matches Dimens`() {
        val s = HearthSpacing()
        assertEquals(listOf(2.dp, 4.dp, 8.dp, 12.dp, 16.dp, 24.dp, 32.dp), listOf(s.xxs, s.xs, s.s, s.m, s.l, s.xl, s.xxl))
        assertEquals(Dimens.Gutter, s.gutter)
        assertEquals(48.dp, s.rowCover)
        assertEquals(8.dp, s.rowPaddingV)
        assertEquals(140.dp, s.cardWidth)
    }

    @Test
    fun `default shapes are HearthShapes`() {
        val s = HearthShapeSet()
        assertEquals(HearthShapes.Cover, s.cover)
        assertEquals(HearthShapes.Card, s.card)
        assertEquals(HearthShapes.Chip, s.chip)
        assertEquals(HearthShapes.Round, s.round)
    }
}
