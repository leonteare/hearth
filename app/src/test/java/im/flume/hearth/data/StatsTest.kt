package im.flume.hearth.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StatsTest {
    private fun row(song: String, artist: String, at: Long, minutes: Int = 4) =
        HistoryRow(song, at, "T-$song", artist, artist, "Album-$artist", "al-$artist", minutes * 60, null)

    @Test
    fun `a song left on repeat only counts three times in a row`() {
        val rows = (0 until 10).map { row("loop", "A", it * 1000L) } + row("other", "B", 20_000) + row("loop", "A", 30_000)
        val s = StatsCalculator.summarize(rows, ZoneOffset.UTC)
        assertEquals(5, s.plays)
        assertEquals(4, s.topSongs.first().plays)
        assertEquals(20L, s.minutes)
    }

    @Test
    fun `top artists ranked by plays`() {
        val rows = listOf(row("1", "A", 1), row("2", "B", 2), row("3", "B", 3), row("4", "C", 4), row("5", "B", 5))
        val s = StatsCalculator.summarize(rows, ZoneOffset.UTC)
        assertEquals(listOf("B", "A", "C").first(), s.topArtists.first().id)
        assertEquals(3, s.artistCount)
    }

    @Test
    fun `periods shown change through the year`() {
        assertEquals(listOf("2026-09"), StatsPeriod.current(LocalDate.of(2026, 10, 8)).map { it.key })
        assertEquals(listOf("2026", "2026-11"), StatsPeriod.current(LocalDate.of(2026, 12, 1)).map { it.key })
        assertEquals(listOf("2026", "2026-12"), StatsPeriod.current(LocalDate.of(2027, 1, 15)).map { it.key })
    }
}
