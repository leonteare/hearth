package im.flume.hearth.data

import org.junit.Assert.assertEquals
import org.junit.Test

class YourLibraryTest {
    private fun artist(id: String, starred: Boolean = false) = ArtistEntity(id, "Artist $id", 1, null, starred)
    private fun album(id: String, artistId: String?) =
        AlbumEntity(id, "Album $id", "", artistId, null, 10, 2400, null, null, null, starred = true, playCount = 0)

    @Test
    fun `followed artists and artists of saved albums both count`() {
        val all = listOf(artist("a", starred = true), artist("b"), artist("c"))
        val result = YourLibrary.artists(all, listOf(album("x", "c")))
        assertEquals(listOf("a", "c"), result.map { it.id })
    }

    @Test
    fun `an artist is listed once even with several saved albums`() {
        val all = listOf(artist("a", starred = true), artist("b"))
        val result = YourLibrary.artists(all, listOf(album("x", "a"), album("y", "a"), album("z", "b")))
        assertEquals(listOf("a", "b"), result.map { it.id })
    }

    @Test
    fun `nothing saved means no artists, and albums without an artist are ignored`() {
        val all = listOf(artist("a"), artist("b"))
        assertEquals(emptyList<ArtistEntity>(), YourLibrary.artists(all, emptyList()))
        assertEquals(emptyList<ArtistEntity>(), YourLibrary.artists(all, listOf(album("x", null))))
    }

    @Test
    fun `keeps the incoming order`() {
        val all = listOf(artist("z", starred = true), artist("m", starred = true), artist("a", starred = true))
        assertEquals(listOf("z", "m", "a"), YourLibrary.artists(all, emptyList()).map { it.id })
    }
}
