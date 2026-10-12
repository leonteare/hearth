package im.flume.hearth.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderTest {
    private fun artist(id: String, name: String, albums: Int = 1) = ArtistEntity(id, name, albums, null, false)

    private fun album(id: String, name: String, artist: String = "Someone", year: Int? = null) =
        AlbumEntity(id, name, artist, "ar", null, 10, 100, year, null, null, false, 0)

    private fun song(id: String, title: String, artist: String = "Someone", album: String = "Album", disc: Int = 1, track: Int = 1) =
        SongEntity(id, title, album, "al", artist, "ar", track, disc, null, null, 200, null, null, 0, true, 0, null)

    @Test
    fun `artists a to z ignore case and a leading the`() {
        val artists = listOf(artist("1", "Radiohead"), artist("2", "the Beatles"), artist("3", "ABBA"), artist("4", "Björk"), artist("5", "The Cure"))
        assertEquals(listOf("ABBA", "the Beatles", "Björk", "The Cure", "Radiohead"), LibraryOrder.artists(artists, 0).map { it.name })
    }

    @Test
    fun `saved artist and album sorts still work`() {
        val artists = listOf(artist("1", "A", albums = 1), artist("2", "B", albums = 9))
        assertEquals(listOf("B", "A"), LibraryOrder.artists(artists, 1).map { it.name })
        val albums = listOf(album("1", "Zed", year = 1999), album("2", "alpha", year = 2020))
        assertEquals(listOf("2", "1"), LibraryOrder.albums(albums, 4).map { it.id })
        assertEquals(listOf("2", "1"), LibraryOrder.albums(albums, 0).map { it.id })
    }

    @Test
    fun `albums a to z ignore case and a leading the`() {
        val albums = listOf(album("1", "The Wall"), album("2", "abbey Road"), album("3", "OK Computer"))
        assertEquals(listOf("abbey Road", "OK Computer", "The Wall"), LibraryOrder.albums(albums, 0).map { it.name })
    }

    @Test
    fun `liked songs sort by title by default, or by artist or album`() {
        val songs = listOf(
            song("1", "zebra", artist = "Alpha", album = "Middle"),
            song("2", "Apple", artist = "charlie", album = "Zoo"),
            song("3", "mango", artist = "Bravo", album = "aardvark", track = 2),
            song("4", "Kiwi", artist = "Bravo", album = "aardvark", track = 1),
        )
        assertEquals(listOf("2", "4", "3", "1"), SongOrder.liked(0).sort(songs).map { it.id })
        assertEquals(listOf("1", "4", "3", "2"), SongOrder.liked(1).sort(songs).map { it.id })
        assertEquals(listOf("4", "3", "1", "2"), SongOrder.liked(2).sort(songs).map { it.id })
        // An unknown saved choice falls back to titles.
        assertEquals(SongOrder.TITLE, SongOrder.liked(7))
    }

    @Test
    fun `playlists, shows and books sort a to z the same way`() {
        val names = listOf("the Weekend Mix", "abba Hits", "Zebra", "The High Performance Podcast", "Help I Sexted My Boss")
        assertEquals(
            listOf("abba Hits", "Help I Sexted My Boss", "The High Performance Podcast", "the Weekend Mix", "Zebra"),
            LibraryOrder.alphabetical(names) { it },
        )
    }
}
