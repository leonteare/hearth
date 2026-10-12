package im.flume.hearth.spoken

import im.flume.hearth.core.network.AbsPodcastSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import im.flume.hearth.core.data.spoken.BookEntity
import im.flume.hearth.core.data.spoken.BookRow
import im.flume.hearth.core.data.spoken.ChapterEntity
import im.flume.hearth.core.data.spoken.EpisodeEntity
import im.flume.hearth.core.data.spoken.EpisodeRow
import im.flume.hearth.core.data.spoken.PodcastEntity

class SpokenPresentationTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private fun podcast(id: String, title: String, author: String? = null, feed: String? = null, tags: String = "", addedAt: Long = 0) =
        PodcastEntity(id, "lib", title, author, null, feed, null, null, false, tags, false, 0, addedAt, 0, 0)

    private fun episode(
        id: String, podcastId: String = "p1", title: String = "Episode $id", published: Long = 0, duration: Double = 3600.0,
        progress: Double? = null, finished: Boolean = false, show: String = "Show", ino: String? = "ino-$id",
    ) = EpisodeRow(
        EpisodeEntity(id, podcastId, title, null, null, published, duration, 0, ino, null, null, null, null, 0),
        show, null, progress, finished, null, null, null,
    )

    private fun book(id: String, title: String, author: String? = null, narrator: String? = null, addedAt: Long = 0) = BookRow(
        BookEntity(id, "lib", title, null, author, narrator, null, null, null, 36000.0, 0, 1, 0, "", addedAt, 0, 0),
        null, false, null, null, null,
    )

    private fun chapter(i: Int, start: Double, end: Double) = ChapterEntity("b", "", i, start, end, "Chapter ${i + 1}")

    // ------------------------------------------------------------------ formatting

    @Test
    fun `lengths read like a person would say them`() {
        assertEquals("45 min", SpokenFormat.length(45 * 60.0))
        assertEquals("1 hr", SpokenFormat.length(3600.0))
        assertEquals("1 hr 5 min", SpokenFormat.length(3900.0))
        assertEquals("1 min", SpokenFormat.length(10.0))
        assertEquals("", SpokenFormat.length(0.0))
    }

    @Test
    fun `time left rounds up and allows for speed`() {
        assertEquals("23 min left", SpokenFormat.remaining(22 * 60 + 5.0))
        assertEquals("1 hr 5 min left", SpokenFormat.remaining(65 * 60.0))
        assertEquals("Less than a minute left", SpokenFormat.remaining(30.0))
        assertEquals("30 min left", SpokenFormat.remaining(60 * 60.0, speed = 2f))
        assertEquals("Less than a minute left", SpokenFormat.remaining(-5.0))
    }

    @Test
    fun `speeds drop trailing zeros`() {
        assertEquals("1×", SpokenFormat.speed(1f))
        assertEquals("1.25×", SpokenFormat.speed(1.25f))
        assertEquals("0.8×", SpokenFormat.speed(0.8f))
        assertEquals("2×", SpokenFormat.speed(2f))
        assertEquals("1.5×", SpokenFormat.speed(1.5f))
    }

    @Test
    fun `dates count calendar days`() {
        val now = at(2026, 10, 11, h = 9)
        assertEquals("Today", SpokenFormat.date(at(2026, 10, 11, h = 1), now, utc))
        assertEquals("Yesterday", SpokenFormat.date(at(2026, 10, 10, h = 23), now, utc))
        assertEquals("3 days ago", SpokenFormat.date(at(2026, 10, 8), now, utc))
        assertEquals("1 Oct", SpokenFormat.date(at(2026, 10, 1), now, utc))
        assertEquals("12 Dec 2025", SpokenFormat.date(at(2025, 12, 12), now, utc))
        assertEquals("", SpokenFormat.date(0, now, utc))
    }

    @Test
    fun `episode status says played, time left or length`() {
        assertEquals("Played", SpokenFormat.episodeStatus(3600.0, 3600.0, finished = true))
        assertEquals("30 min left", SpokenFormat.episodeStatus(3600.0, 1800.0, finished = false))
        assertEquals("1 hr", SpokenFormat.episodeStatus(3600.0, null, finished = false))
    }

    @Test
    fun `listened fraction only while part-way through`() {
        assertEquals(0.5f, SpokenFormat.fraction(1800.0, 3600.0, false)!!, 0.0001f)
        assertNull(SpokenFormat.fraction(null, 3600.0, false))
        assertNull(SpokenFormat.fraction(0.0, 3600.0, false))
        assertNull(SpokenFormat.fraction(1800.0, 3600.0, true))
    }

    @Test
    fun `clock positions`() {
        assertEquals("0:00", SpokenFormat.clock(0.0))
        assertEquals("12:34", SpokenFormat.clock(754.0))
        assertEquals("1:02:03", SpokenFormat.clock(3723.0))
    }

    // ------------------------------------------------------------------ chapters

    @Test
    fun `chapter ticks leave out the start and the end`() {
        val chapters = listOf(chapter(0, 0.0, 600.0), chapter(1, 600.0, 1500.0), chapter(2, 1500.0, 3000.0))
        assertEquals(listOf(0.2f, 0.5f), ChapterMarks.ticks(chapters, 3000.0))
        assertEquals(emptyList<Float>(), ChapterMarks.ticks(listOf(chapter(0, 0.0, 3000.0)), 3000.0))
        assertEquals(emptyList<Float>(), ChapterMarks.ticks(chapters, 0.0))
    }

    @Test
    fun `current chapter and time left in it`() {
        val chapters = listOf(chapter(0, 0.0, 600.0), chapter(1, 600.0, 1500.0), chapter(2, 1500.0, 3000.0))
        assertEquals(0, ChapterMarks.current(chapters, 10.0))
        assertEquals(1, ChapterMarks.current(chapters, 600.0))
        assertEquals(2, ChapterMarks.current(chapters, 2999.0))
        assertEquals(-1, ChapterMarks.current(emptyList(), 10.0))
        assertEquals(500.0, ChapterMarks.leftInChapter(chapters, 1000.0, 3000.0)!!, 0.001)
        assertNull(ChapterMarks.leftInChapter(emptyList(), 1000.0, 3000.0))
        // A chapter without a proper end runs to the next one.
        val loose = listOf(chapter(0, 0.0, 0.0), chapter(1, 900.0, 0.0))
        assertEquals(800.0, ChapterMarks.leftInChapter(loose, 100.0, 3000.0)!!, 0.001)
        assertEquals(2000.0, ChapterMarks.leftInChapter(loose, 1000.0, 3000.0)!!, 0.001)
    }

    // ------------------------------------------------------------------ search

    @Test
    fun `search matches shows, episodes and books ignoring accents and case`() {
        val podcasts = listOf(podcast("p1", "The Rest Is History", "Goalhanger"), podcast("p2", "Café Society"))
        val episodes = listOf(
            episode("e1", title = "Napoleon part one", published = 1, show = "The Rest Is History"),
            episode("e2", title = "Napoleon part two", published = 2, show = "The Rest Is History"),
            episode("e3", title = "Something else", show = "Cafe Society"),
        )
        val books = listOf(book("b1", "Napoleon the Great", "Andrew Roberts"), book("b2", "Dune", "Frank Herbert"))
        val r = SpokenSearch.search("napoleon", podcasts, episodes, books)
        assertEquals(emptyList<PodcastEntity>(), r.podcasts)
        // Same rank: newest first.
        assertEquals(listOf("e2", "e1"), r.episodes.map { it.episode.id })
        assertEquals(listOf("b1"), r.books.map { it.book.id })

        assertEquals(listOf("p2"), SpokenSearch.search("cafe", podcasts, episodes, books).podcasts.map { it.id })
        // The show's name finds its episodes; a leading "the" doesn't matter.
        assertEquals(setOf("e1", "e2"), SpokenSearch.search("rest is history", podcasts, episodes, books).episodes.map { it.episode.id }.toSet())
        assertEquals(listOf("b2"), SpokenSearch.search("herbert", podcasts, episodes, books).books.map { it.book.id })
        assertTrue(SpokenSearch.search("   ", podcasts, episodes, books).isEmpty)
    }

    @Test
    fun `titles starting with the query come before ones that merely contain it`() {
        val books = listOf(book("b1", "A history of Rome", addedAt = 5), book("b2", "Rome", addedAt = 1), book("b3", "Rome burning", addedAt = 3))
        assertEquals(listOf("b2", "b3", "b1"), SpokenSearch.search("rome", emptyList(), emptyList(), books).books.map { it.book.id })
    }

    // ------------------------------------------------------------------ discovery

    @Test
    fun `directory results know what's already on the server`() {
        val library = listOf(
            podcast("p1", "Followed", feed = "https://feeds.example.com/a/", tags = "hearth-follower:leon"),
            podcast("p2", "Not followed", feed = "http://feeds.example.com/b"),
        )
        fun r(feed: String?) = AbsPodcastSearchResult(title = "x", feedUrl = feed)
        assertEquals(DirectoryState.FOLLOWING, SpokenDiscovery.state(r("https://FEEDS.example.com/a"), library, "Leon").first)
        assertEquals(DirectoryState.IN_LIBRARY, SpokenDiscovery.state(r("https://feeds.example.com/b"), library, "leon").first)
        assertEquals("p2", SpokenDiscovery.state(r("https://feeds.example.com/b"), library, "leon").second?.id)
        assertEquals(DirectoryState.IN_LIBRARY, SpokenDiscovery.state(r("https://feeds.example.com/a"), library, "sadie").first)
        assertEquals(DirectoryState.NEW, SpokenDiscovery.state(r("https://feeds.example.com/c"), library, "leon").first)
        assertEquals(DirectoryState.NEW, SpokenDiscovery.state(r(null), library, "leon").first)
        assertEquals(listOf("https://a"), SpokenDiscovery.usable(listOf(r("https://a"), r(null), r("http://a/"), r(" "))).map { it.feedUrl })
    }

    // ------------------------------------------------------------------ lists

    @Test
    fun `new episodes are unstarted, playable and newest first`() {
        val rows = listOf(
            episode("old", published = 1),
            episode("new", published = 5),
            episode("started", published = 4, progress = 60.0),
            episode("played", published = 3, finished = true),
            episode("no-audio", published = 6, ino = null),
        )
        assertEquals(listOf("new", "old"), SpokenLists.newEpisodes(rows).map { it.episode.id })
        assertEquals(listOf("new"), SpokenLists.newEpisodes(rows, limit = 1).map { it.episode.id })
    }

    @Test
    fun `new counts per show only cover recent unplayed episodes`() {
        val now = at(2026, 10, 11)
        val day = 86_400_000L
        val rows = listOf(
            episode("a", "p1", published = now - day),
            episode("b", "p1", published = now - 3 * day),
            episode("c", "p1", published = now - 30 * day),
            episode("d", "p2", published = now - day, finished = true),
            episode("e", "p2", published = now - day, progress = 10.0),
        )
        assertEquals(mapOf("p1" to 2), SpokenLists.newCounts(rows, now))
    }

    @Test
    fun `play latest picks the newest unfinished episode`() {
        val rows = listOf(episode("a", published = 3, finished = true), episode("b", published = 2), episode("c", published = 1))
        assertEquals("b", SpokenLists.playLatest(rows)?.episode?.id)
        assertEquals("a", SpokenLists.playLatest(listOf(episode("a", published = 3, finished = true)))?.episode?.id)
        assertNull(SpokenLists.playLatest(emptyList()))
    }

    @Test
    fun `up next skips the current and played episodes`() {
        val rows = listOf(episode("a", published = 3), episode("b", published = 2, finished = true), episode("c", published = 1))
        assertEquals(listOf("c"), SpokenLists.upNext(rows, "a").map { it.episode.id })
    }
}
