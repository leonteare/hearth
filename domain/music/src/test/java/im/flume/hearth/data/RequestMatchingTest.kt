package im.flume.hearth.data

import im.flume.hearth.api.SongDto
import im.flume.hearth.api.TidalAlbum
import im.flume.hearth.api.TidalArtist
import im.flume.hearth.api.TidalSearchResults
import im.flume.hearth.api.TidalTrack
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestMatchingTest {
    private fun song(id: String, title: String, artist: String, isrc: String? = null, duration: Int = 200) = SongEntity(
        id = id, title = title, album = "A", albumId = "al", artist = artist, artistId = null, track = 1, disc = 1,
        year = null, genre = null, durationSec = duration, coverArt = null, suffix = null, sizeBytes = 0,
        starred = false, playCount = 0, created = null, isrc = isrc,
    )

    private fun album(id: String, name: String, artist: String) = AlbumEntity(
        id, name, artist, null, null, 10, 100, null, null, null, false, 0,
    )

    private fun track(title: String, artists: List<String>, isrc: String? = null, version: String? = null, id: String = "1") =
        TidalTrack(id, title, version, 200, isrc, false, artists, null, null, null)

    private fun tidalAlbum(title: String, artists: List<String>) = TidalAlbum("9", title, null, 10, "2017-01-01", "ALBUM", artists)

    @Test
    fun `catalog spots tracks by isrc first`() {
        val cat = RequestMatching.LocalCatalog(listOf(song("s1", "Totally Different Name", "X", isrc = "USQX91300108,GBXXX0000001")), emptyList())
        assertTrue(cat.hasTrack(track("Get Lucky", listOf("Daft Punk"), isrc = "usqx91300108")))
        assertFalse(cat.hasTrack(track("Get Lucky", listOf("Daft Punk"), isrc = "ZZZ")))
    }

    @Test
    fun `catalog falls back to normalised title and artist`() {
        val cat = RequestMatching.LocalCatalog(
            listOf(song("s1", "Beyoncé - Halo", "Beyoncé"), song("s2", "Get Lucky", "Daft Punk feat. Pharrell Williams"), song("s3", "Song (2001 Remaster)", "The Band")),
            emptyList(),
        )
        assertTrue(cat.hasTrack(track("beyonce halo", listOf("Beyonce"))))
        // A featured artist on the server's credit still counts as the same artist.
        assertTrue(cat.hasTrack(track("Get Lucky", listOf("Daft Punk", "Pharrell Williams"), version = "feat. Pharrell Williams")))
        // "Remaster" in brackets on one side and as Tidal's version on the other.
        assertTrue(cat.hasTrack(track("Song", listOf("Band"), version = "2001 Remaster")))
        // A different version is not the same recording.
        assertFalse(cat.hasTrack(track("Song", listOf("The Band"), version = "Live")))
        assertFalse(cat.hasTrack(track("Get Lucky", listOf("Someone Else"))))
    }

    @Test
    fun `artist matching is whole words`() {
        assertTrue(RequestMatching.artistMatches(listOf("Muse"), "Muse"))
        assertTrue(RequestMatching.artistMatches(listOf("Nile Rodgers"), "Daft Punk & Nile Rodgers"))
        assertFalse(RequestMatching.artistMatches(listOf("Muse"), "Museum"))
        assertFalse(RequestMatching.artistMatches(listOf("Muse"), ""))
    }

    @Test
    fun `catalog filters albums by name and artist, keeps artists`() {
        val cat = RequestMatching.LocalCatalog(emptyList(), listOf(album("a1", "Random Access Memories", "Daft Punk")))
        val r = cat.filter(
            TidalSearchResults(
                albums = listOf(tidalAlbum("Random Access Memories", listOf("Daft Punk")), tidalAlbum("Discovery", listOf("Daft Punk"))),
                artists = listOf(TidalArtist("3", "Daft Punk", null)),
            )
        )
        assertEquals(listOf("Discovery"), r.albums.map { it.title })
        assertEquals(1, r.artists.size)
    }

    @Test
    fun `pick track prefers isrc, then title artist and length`() {
        val candidates = listOf(
            song("live", "Song (Live)", "Band", duration = 260),
            song("other", "Song", "Other Band"),
            song("right", "Song", "Band", duration = 202),
            song("isrc", "Song [Radio Edit]", "Band", isrc = "AAA", duration = 180),
        )
        assertEquals("isrc", RequestMatching.pickTrack(candidates, "Song", "Band", "aaa", 200)?.id)
        assertEquals("right", RequestMatching.pickTrack(candidates, "Song", "Band", null, 200)?.id)
        // Too far off in length.
        assertNull(RequestMatching.pickTrack(listOf(song("x", "Song", "Band", duration = 300)), "Song", "Band", null, 200))
        // Unknown length doesn't block a match; a bracketed version still matches loosely.
        assertEquals("live", RequestMatching.pickTrack(listOf(candidates[0]), "Song", "Band", null, null)?.id)
    }

    @Test
    fun `pick album and artist`() {
        val albums = listOf(album("a", "Discovery", "Someone"), album("b", "Discovery (Deluxe)", "Daft Punk"), album("c", "Discovery", "Daft Punk"))
        assertEquals("c", RequestMatching.pickAlbum(albums, "Discovery", "Daft Punk")?.id)
        assertEquals("b", RequestMatching.pickAlbum(albums.take(2), "Discovery", "Daft Punk, Pharrell Williams")?.id)
        assertEquals("2", RequestMatching.pickArtist(listOf(ArtistEntity("1", "Daft Punks", 1, null, false), ArtistEntity("2", "Daft Punk", 1, null, false)), "daft punk")?.id)
    }

    @Test
    fun `search title drops bracketed extras`() {
        assertEquals("Song", RequestMatching.searchTitle("Song (2001 Remaster)"))
        assertEquals("Song", RequestMatching.searchTitle("Song - Remastered 2011"))
        assertEquals("(Untitled)", RequestMatching.searchTitle("(Untitled)"))
    }

    @Test
    fun `navidrome isrc list is parsed onto songs`() {
        val json = Json { ignoreUnknownKeys = true }
        val dto = json.decodeFromString(SongDto.serializer(), """{"id":"1","title":"T","isrc":["usqx91300108","GB1"]}""")
        assertEquals("USQX91300108,GB1", dto.toEntity().isrc)
        val single = json.decodeFromString(SongDto.serializer(), """{"id":"1","title":"T","isrc":"AB1"}""")
        assertEquals("AB1", single.toEntity().isrc)
        assertNull(json.decodeFromString(SongDto.serializer(), """{"id":"1","title":"T"}""").toEntity().isrc)
        assertNull(json.decodeFromString(SongDto.serializer(), """{"id":"1","title":"T","isrc":[]}""").toEntity().isrc)
    }

    @Test
    fun `catalog finds the server copies for tidal pages`() {
        val lucky = song("s1", "Get Lucky", "Daft Punk feat. Pharrell Williams", isrc = "USQX91300108")
        val time = song("s2", "One More Time", "Daft Punk")
        val cat = RequestMatching.LocalCatalog(
            listOf(lucky, time),
            listOf(album("a1", "Discovery", "Daft Punk")),
            listOf(ArtistEntity("ar1", "Daft Punk", 2, null, false)),
        )
        assertEquals("s1", cat.localTrack(track("Something Else", listOf("X"), isrc = "USQX91300108"))?.id)
        assertEquals("s2", cat.localTrack(track("One More Time", listOf("Daft Punk")))?.id)
        assertNull(cat.localTrack(track("Aerodynamic", listOf("Daft Punk"))))
        assertEquals("a1", cat.localAlbum(tidalAlbum("Discovery", listOf("Daft Punk")))?.id)
        assertNull(cat.localAlbum(tidalAlbum("Homework", listOf("Daft Punk"))))
        assertEquals("ar1", cat.localArtist("daft punk")?.id)
        assertNull(cat.localArtist("Justice"))
    }
}
