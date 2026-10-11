package im.flume.hearth.data

import im.flume.hearth.api.TidalAlbum
import im.flume.hearth.api.TidalArtist
import im.flume.hearth.api.TidalSearchResults
import im.flume.hearth.api.TidalTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityTest {
    private fun song(id: String, title: String, artist: String = "Daft Punk", disc: Int = 1, track: Int = 1, isrc: String? = null) = SongEntity(
        id = id, title = title, album = "A", albumId = "al", artist = artist, artistId = null, track = track, disc = disc,
        year = null, genre = null, durationSec = 200, coverArt = null, suffix = null, sizeBytes = 0,
        starred = false, playCount = 0, created = null, isrc = isrc,
    )

    private fun album(id: String, name: String, year: Int?, artist: String = "Daft Punk") =
        AlbumEntity(id, name, artist, "ar", null, 10, 100, year, null, null, false, 0)

    private fun track(id: String, title: String, artists: List<String> = listOf("Daft Punk"), disc: Int = 1, number: Int = 1, isrc: String? = null, version: String? = null) =
        TidalTrack(id, title, version, 200, isrc, false, artists, null, null, null, trackNumber = number, volumeNumber = disc)

    private fun remoteAlbum(id: String, title: String, date: String?, type: String = "ALBUM", artists: List<String> = listOf("Daft Punk"), tracks: Int = 10) =
        TidalAlbum(id, title, null, tracks, date, type, artists)

    private fun keys(items: List<Any>) = items.map {
        when (it) {
            is SongItem -> it.key
            is AlbumItem -> it.key
            is ArtistItem -> it.key
            else -> error("unexpected")
        }
    }

    @Test
    fun `search puts the library first and appends what isn't on the server`() {
        val sections = Availability.mergeSearch(
            songs = listOf(song("s1", "Get Lucky")),
            albums = listOf(album("a1", "Discovery", 2001)),
            artists = listOf(ArtistEntity("ar", "Daft Punk", 4, null, false)),
            remote = TidalSearchResults(
                tracks = listOf(track("t1", "Get Lucky"), track("t2", "One More Time"), track("t3", "One More Time")),
                albums = listOf(remoteAlbum("r1", "Discovery (Deluxe)", "2001-03-12"), remoteAlbum("r2", "Homework", "1997-01-20")),
                artists = listOf(TidalArtist("x1", "daft punk", null), TidalArtist("x2", "Thomas Bangalter", null)),
            ),
        )
        // The library's own copy wins; duplicates among the remote results show once.
        assertEquals(listOf("s:s1", "t:t2"), keys(sections.songs))
        assertEquals(listOf("s:a1", "t:r2"), keys(sections.albums))
        assertEquals(listOf("s:ar", "t:x2"), keys(sections.artists))
    }

    @Test
    fun `search without remote results is just the library`() {
        val sections = Availability.mergeSearch(listOf(song("s1", "A")), emptyList(), emptyList(), null)
        assertEquals(listOf("s:s1"), keys(sections.songs))
        assertTrue(sections.albums.isEmpty())
        assertTrue(Availability.mergeSearch(emptyList(), emptyList(), emptyList(), null).isEmpty)
    }

    @Test
    fun `discography adds missing releases, newest first, one per edition`() {
        val owned = listOf(album("a1", "Discovery", 2001), album("a2", "Random Access Memories", 2013))
        val remoteAlbums = listOf(
            remoteAlbum("r1", "Random Access Memories (10th Anniversary Edition)", "2023-05-12"),
            remoteAlbum("r2", "Homework", "1997-01-20"),
            remoteAlbum("r3", "Homework (25th Anniversary Edition)", "2022-01-01"),
            remoteAlbum("r4", "Human After All", "2005-03-14"),
            remoteAlbum("r5", "Discovery", "2001-03-12"),
        )
        val singles = listOf(remoteAlbum("s1", "Get Lucky", "2013-04-19", "SINGLE"), remoteAlbum("s2", "Homework", "1997-01-01", "SINGLE"))
        val d = Availability.discography(owned, remoteAlbums, singles)
        // RAM and Discovery are owned (editions included); Homework appears once; sorted by year.
        assertEquals(listOf("s:a2", "t:r4", "s:a1", "t:r2"), keys(d.albums))
        // A single that is also an album shows with the albums only.
        assertEquals(listOf("t:s1"), keys(d.singles))
    }

    @Test
    fun `owned singles move to the singles carousel`() {
        val owned = listOf(album("a1", "Get Lucky", 2013), album("a2", "Discovery", 2001))
        val d = Availability.discography(owned, listOf(remoteAlbum("r1", "Discovery", "2001")), listOf(remoteAlbum("s1", "Get Lucky (Radio Edit)", "2013", "SINGLE")))
        assertEquals(listOf("s:a2"), keys(d.albums))
        assertEquals(listOf("s:a1"), keys(d.singles))
    }

    @Test
    fun `discography without remote data is the owned albums`() {
        val owned = listOf(album("a1", "Old", 1999), album("a2", "New", 2020), album("a3", "Undated", null))
        assertEquals(listOf("s:a2", "s:a1", "s:a3"), keys(Availability.discography(owned, emptyList(), emptyList()).albums))
    }

    @Test
    fun `album tracks interleave missing songs in disc and track order`() {
        val owned = listOf(song("s1", "Intro", track = 1), song("s3", "Third Song (Remastered)", track = 3), song("d2", "Bonus", disc = 2, track = 1))
        val remote = listOf(
            track("t1", "Intro", number = 1),
            track("t2", "Second Song", number = 2),
            track("t3", "Third Song", number = 3, version = "2009 Remaster"),
            track("t4", "Fourth Song", number = 4),
            track("t5", "Bonus", disc = 2, number = 1),
            track("t6", "Hidden", disc = 2, number = 2),
        )
        val items = Availability.albumTracks(owned, remote)
        assertEquals(listOf("s:s1", "t:t2", "s:s3", "t:t4", "s:d2", "t:t6"), keys(items))
    }

    @Test
    fun `album tracks match by isrc before title, and each song only once`() {
        val owned = listOf(song("s1", "Something Else", isrc = "GBAAA0000001"), song("s2", "Same", track = 2))
        val remote = listOf(track("t1", "Real Name", number = 1, isrc = "gbaaa0000001"), track("t2", "Same", number = 2), track("t3", "Same", number = 3))
        assertEquals(listOf("s:s1", "s:s2", "t:t3"), keys(Availability.albumTracks(owned, remote)))
        assertEquals(listOf("s:s1"), keys(Availability.albumTracks(listOf(song("s1", "X")), emptyList())))
    }

    @Test
    fun `picks the remote album and artist by name`() {
        val candidates = listOf(
            remoteAlbum("1", "Discovery", "2001", artists = listOf("Someone Else")),
            remoteAlbum("2", "Discovery (Deluxe)", "2001", tracks = 18),
            remoteAlbum("3", "Discovery", "2001"),
        )
        assertEquals("3", Availability.pickAlbum(candidates, "Discovery", "Daft Punk")?.id)
        assertEquals("2", Availability.pickAlbum(candidates.take(2), "Discovery", "Daft Punk")?.id)
        assertNull(Availability.pickAlbum(candidates, "Homework", "Daft Punk"))
        val artists = listOf(TidalArtist("1", "Daft Punk Tribute", null), TidalArtist("2", "Daft Punk", null))
        assertEquals("2", Availability.pickArtist(artists, "daft punk")?.id)
        assertNull(Availability.pickArtist(artists.take(1), "Daft Punk"))
    }

    @Test
    fun `preview window starts a third of the way in and always fits`() {
        // 35% of 60 s is 21 s, and 30 s fit after it.
        assertEquals(21_000L to 30_000L, PreviewWindow.of(60_000))
        // 35% of 45 s is 15.75 s, but then 30 s wouldn't fit: it starts at 15 s.
        assertEquals(15_000L to 30_000L, PreviewWindow.of(45_000))
        // 35% of 70 s = 24.5 s.
        assertEquals(24_500L to 30_000L, PreviewWindow.of(70_000))
        // Long songs start 30 s in at most.
        assertEquals(30_000L to 30_000L, PreviewWindow.of(300_000))
        // Short songs play from the start, all of it.
        assertEquals(0L to 20_000L, PreviewWindow.of(20_000))
        assertEquals(0L to 30_000L, PreviewWindow.of(30_000))
        assertEquals(10_000L to 30_000L, PreviewWindow.of(40_000))
        // Unknown length: from the start.
        assertEquals(0L to 30_000L, PreviewWindow.of(0))
    }

    @Test
    fun `preview fades out over the last second`() {
        assertEquals(1f, PreviewWindow.volume(0, 30_000), 0f)
        assertEquals(1f, PreviewWindow.volume(29_000, 30_000), 0f)
        assertEquals(0.5f, PreviewWindow.volume(29_500, 30_000), 0.001f)
        assertEquals(0f, PreviewWindow.volume(30_000, 30_000), 0f)
        assertEquals(0f, PreviewWindow.volume(31_000, 30_000), 0f)
    }
}
