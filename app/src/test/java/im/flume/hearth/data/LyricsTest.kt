package im.flume.hearth.data

import im.flume.hearth.api.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {
    private val synced = Lyrics(true, listOf(LyricLine(500, "a"), LyricLine(3000, "b"), LyricLine(5500, "c")))

    @Test
    fun `current line follows playback position`() {
        assertEquals(-1, synced.currentLine(0))
        assertEquals(0, synced.currentLine(500))
        assertEquals(1, synced.currentLine(4000))
        assertEquals(2, synced.currentLine(60_000))
    }

    @Test
    fun `plain lyrics never highlight a line`() {
        assertEquals(-1, Lyrics(false, listOf(LyricLine(null, "x"))).currentLine(10_000))
    }

    @Test
    fun `blank lyrics count as empty`() {
        assertEquals(true, Lyrics(false, listOf(LyricLine(null, " "))).isEmpty)
    }
}
