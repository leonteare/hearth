package im.flume.hearth.core.domain

import im.flume.hearth.core.network.AbsPodcastSearchResult
import im.flume.hearth.core.network.TidalAlbum
import im.flume.hearth.core.network.TidalArtist
import im.flume.hearth.core.network.TidalSearchResults
import im.flume.hearth.core.network.TidalTrack
import im.flume.hearth.core.data.spoken.BookEntity
import im.flume.hearth.core.data.spoken.BookRow
import im.flume.hearth.core.data.spoken.EpisodeEntity
import im.flume.hearth.core.data.spoken.EpisodeRow
import im.flume.hearth.core.data.spoken.PodcastEntity
import im.flume.hearth.spoken.SpokenSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import im.flume.hearth.core.data.AlbumEntity
import im.flume.hearth.core.data.ArtistEntity
import im.flume.hearth.core.data.SearchIndex
import im.flume.hearth.core.data.SearchMatch
import im.flume.hearth.core.data.SongEntity

class SearchRankingTest {
    private fun song(id: String, title: String, artist: String = "Someone", album: String = "Album", plays: Long = 0) =
        SongEntity(id, title, album, "al", artist, "ar-$artist", 1, 1, null, null, 200, null, null, 0, false, plays, null)

    private fun album(id: String, name: String, artist: String = "Someone") =
        AlbumEntity(id, name, artist, "ar", null, 10, 100, 2000, null, null, false, 0)

    private fun artist(id: String, name: String) = ArtistEntity(id, name, 3, null, false)

    private fun track(id: String, title: String, artist: String = "Someone") =
        TidalTrack(id, title, null, 200, null, false, listOf(artist), null, null, null)

    private fun podcast(id: String, title: String, author: String? = null, feed: String? = null) =
        PodcastEntity(id, "lib", title, author, null, feed, null, null, false, "", false, 0, 0, 0, 0)

    private fun episode(id: String, title: String, show: String) = EpisodeRow(
        EpisodeEntity(id, "p1", title, null, null, 0, 3600.0, 0, "ino-$id", null, null, null, null, 0),
        show, null, null, false, null, null, null,
    )

    private fun book(id: String, title: String, author: String? = null) = BookRow(
        BookEntity(id, "lib", title, null, author, null, null, null, null, 36000.0, 0, 1, 0, "", 0, 0, 0),
        null, false, null, null, null,
    )

    private fun rank(
        query: String,
        songs: List<SongEntity> = emptyList(),
        albums: List<AlbumEntity> = emptyList(),
        artists: List<ArtistEntity> = emptyList(),
        remote: TidalSearchResults? = null,
        podcasts: List<PodcastEntity> = emptyList(),
        episodes: List<EpisodeRow> = emptyList(),
        books: List<BookRow> = emptyList(),
        directory: List<AbsPodcastSearchResult> = emptyList(),
    ): List<SearchHit> = SearchRanking.merge(
        query,
        SearchIndex(songs, albums, artists).search(query),
        remote,
        SpokenSearch.search(query, podcasts, episodes, books),
        directory,
    )

    @Test
    fun `an exact title beats a partial one whatever kind it is`() {
        val hits = rank(
            "halo",
            songs = listOf(song("s1", "Halo Theme (Remix)", plays = 500), song("s2", "Halo")),
            albums = listOf(album("a1", "Halo Collection")),
            books = listOf(book("b1", "Halo")),
        )
        // Both exact titles first (the book before the song, as a name usually means the bigger thing).
        assertEquals(listOf("book:b1", "song:s:s2"), hits.take(2).map { it.key })
        assertEquals(setOf("album:s:a1", "song:s:s1"), hits.drop(2).map { it.key }.toSet())
    }

    @Test
    fun `the exact artist comes before their songs`() {
        val hits = rank(
            "radiohead",
            songs = listOf(song("s1", "Creep", artist = "Radiohead", plays = 900)),
            artists = listOf(artist("ar", "Radiohead")),
        )
        assertEquals(listOf("artist:s:ar", "song:s:s1"), hits.map { it.key })
    }

    @Test
    fun `owned beats remote at equal relevance`() {
        val hits = rank(
            "money",
            songs = listOf(song("s1", "Money", artist = "Pink Floyd")),
            remote = TidalSearchResults(tracks = listOf(track("t1", "Money", artist = "The Flying Lizards"))),
        )
        assertEquals(listOf("song:s:s1", "song:t:t1"), hits.map { it.key })
        assertTrue(hits[0].owned)
        assertTrue(!hits[1].owned)
    }

    @Test
    fun `a better remote match still beats a weaker owned one`() {
        val hits = rank(
            "homework",
            albums = listOf(album("a1", "Homework Sessions")),
            remote = TidalSearchResults(albums = listOf(TidalAlbum("r1", "Homework", null, 10, "1997", "ALBUM", listOf("Daft Punk")))),
        )
        assertEquals(listOf("album:t:r1", "album:s:a1"), hits.map { it.key })
    }

    @Test
    fun `remote music already in the library shows once, as the library's`() {
        val hits = rank(
            "get lucky",
            songs = listOf(song("s1", "Get Lucky", artist = "Daft Punk")),
            remote = TidalSearchResults(
                tracks = listOf(track("t1", "Get Lucky", artist = "Daft Punk")),
                artists = listOf(TidalArtist("x1", "Get Lucky Band", null)),
            ),
        )
        assertEquals(listOf("song:s:s1", "artist:t:x1"), hits.map { it.key })
    }

    @Test
    fun `podcasts on the server hide the same show from the directory`() {
        val hits = rank(
            "rest is history",
            podcasts = listOf(podcast("p1", "The Rest Is History", feed = "https://feeds.example/rih")),
            episodes = listOf(episode("e1", "Napoleon", "The Rest Is History")),
            directory = listOf(
                AbsPodcastSearchResult(title = "The Rest Is History", feedUrl = "http://feeds.example/rih/"),
                AbsPodcastSearchResult(title = "The Rest Is History Club", feedUrl = "https://other.example/club"),
            ),
        )
        assertEquals(listOf("pod:p1", "dir:https://other.example/club", "ep:e1"), hits.map { it.key })
    }

    @Test
    fun `filters keep only their kind, with episodes under podcasts`() {
        val hits = rank(
            "rome",
            songs = listOf(song("s1", "Rome")),
            albums = listOf(album("a1", "Rome")),
            artists = listOf(artist("ar", "Rome")),
            podcasts = listOf(podcast("p1", "Rome Weekly")),
            episodes = listOf(episode("e1", "Rome falls", "History Show")),
            books = listOf(book("b1", "Rome")),
        )
        assertEquals(6, SearchRanking.filter(hits, SearchFilter.ALL).size)
        assertEquals(listOf("song:s:s1"), SearchRanking.filter(hits, SearchFilter.SONGS).map { it.key })
        assertEquals(listOf("album:s:a1"), SearchRanking.filter(hits, SearchFilter.ALBUMS).map { it.key })
        assertEquals(listOf("artist:s:ar"), SearchRanking.filter(hits, SearchFilter.ARTISTS).map { it.key })
        assertEquals(listOf("pod:p1", "ep:e1"), SearchRanking.filter(hits, SearchFilter.PODCASTS).map { it.key })
        assertEquals(listOf("book:b1"), SearchRanking.filter(hits, SearchFilter.BOOKS).map { it.key })
    }

    @Test
    fun `a blank query finds nothing`() {
        assertTrue(rank("  ", songs = listOf(song("s1", "Anything"))).isEmpty())
    }

    @Test
    fun `match levels go exact, starts with, contains, every word, then close`() {
        val q = SearchMatch.Query("blue")
        val levels = listOf(
            SearchMatch.score(q, "blue", ""),
            SearchMatch.score(q, "blue monday", ""),
            SearchMatch.score(q, "true blue", ""),
            SearchMatch.score(q, "sky", "blue"),
            SearchMatch.score(q, "bleu", ""),
            SearchMatch.score(q, "red", ""),
        )
        assertEquals(levels.sortedDescending(), levels)
        assertEquals(SearchMatch.NONE, levels.last())
        assertTrue(levels.dropLast(1).all { it > SearchMatch.NONE })
    }
}
