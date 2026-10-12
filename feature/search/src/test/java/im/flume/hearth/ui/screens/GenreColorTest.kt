package im.flume.hearth.ui.screens

import androidx.compose.ui.graphics.Color
import im.flume.hearth.core.ui.theme.HearthColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreColorTest {
    private val palette = HearthColors().genrePalette

    @Test
    fun `a genre always gets the same tile colour`() {
        assertEquals(genreColor("Jazz", palette), genreColor("Jazz", palette))
    }

    @Test
    fun `every genre gets a colour from the palette, even one whose hash is negative`() {
        listOf("Jazz", "Folk", "", "Ambient", "Drum & Bass", "Классика").forEach { assertTrue(genreColor(it, palette) in palette) }
    }

    @Test
    fun `the default palette keeps today's ten colours`() {
        assertEquals(10, palette.size)
        assertEquals(Color(0xFF8C1932), palette.first())
        assertEquals(Color(0xFF777777), palette.last())
    }
}
