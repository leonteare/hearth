package im.flume.hearth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {
    private fun song(id: String, title: String, artist: String, album: String = "Album") =
        SongEntity(id, title, album, "al", artist, "ar-$artist", 1, 1, null, null, 200, null, null, 0, false, 0, null)

    private val index = SearchIndex(
        songs = listOf(
            song("1", "Halo", "Beyoncé"),
            song("2", "Hey Jude", "The Beatles"),
            song("3", "Karma Police", "Radiohead"),
            song("4", "Uprising", "Muse"),
        ),
        albums = emptyList(),
        artists = listOf(ArtistEntity("ar-b", "The Beatles", 3, null, false), ArtistEntity("ar-r", "Radiohead", 9, null, false)),
    )

    @Test
    fun `accents capitals and a leading the are ignored`() {
        assertEquals("1", index.search("beyonce").songs.single().id)
        assertEquals("ar-b", index.search("beatles").artists.single().id)
        assertFalse(index.search("BEATLES").fuzzy)
    }

    @Test
    fun `typos fall back to near matches`() {
        val r = index.search("radiohed")
        assertTrue(r.fuzzy)
        assertEquals("ar-r", r.artists.single().id)
        assertEquals("3", index.search("karma polise").songs.single().id)
    }

    @Test
    fun `short words must match exactly`() {
        assertTrue(index.search("mux").songs.isEmpty())
    }
}
