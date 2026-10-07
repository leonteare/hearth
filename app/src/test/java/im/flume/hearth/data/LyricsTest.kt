package im.flume.hearth.data

import im.flume.hearth.api.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    private val synced = Lyrics(
        true,
        listOf(
            LyricLine(8_000, "first"),
            LyricLine(10_000, "second"),
            LyricLine(12_000, ""),
            LyricLine(20_000, "after the solo"),
        ),
    )

    @Test
    fun `long intro and blank lines become breaks`() {
        val rows = synced.rows()
        assertEquals(listOf(true, false, false, true, false), rows.map { it.isBreak })
        assertEquals(0L to 8_000L, rows[0].start to rows[0].end)
        assertEquals(12_000L to 20_000L, rows[3].start to rows[3].end)
    }

    @Test
    fun `short intro gets no break`() {
        val rows = Lyrics(true, listOf(LyricLine(1_000, "go"))).rows()
        assertEquals(1, rows.size)
        assertFalse(rows[0].isBreak)
    }

    @Test
    fun `consecutive blank lines make a single break`() {
        val rows = Lyrics(true, listOf(LyricLine(0, "a"), LyricLine(2_000, ""), LyricLine(3_000, ""), LyricLine(9_000, "b"))).rows()
        assertEquals(listOf(false, true, false), rows.map { it.isBreak })
        assertEquals(3_000L, rows[1].end)
    }

    @Test
    fun `current row follows playback position`() {
        val rows = synced.rows()
        assertEquals(0, rows.currentIndex(0))
        assertEquals(1, rows.currentIndex(8_500))
        assertEquals(3, rows.currentIndex(15_000))
        assertEquals(4, rows.currentIndex(60_000))
    }

    @Test
    fun `plain lyrics never highlight a row`() {
        assertEquals(-1, Lyrics(false, listOf(LyricLine(null, "x"))).rows().currentIndex(10_000))
    }

    @Test
    fun `blank lyrics count as empty`() {
        assertTrue(Lyrics(false, listOf(LyricLine(null, " "))).isEmpty)
    }
}
