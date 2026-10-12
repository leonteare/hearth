package im.flume.hearth.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SongFilterTest {
    private fun song(id: String, title: String, artist: String, album: String) =
        SongEntity(id, title, album, "al", artist, "ar", 1, 1, null, null, 200, null, null, 0, true, 0, null)

    private val songs = listOf(
        song("1", "Mr. Brightside", "The Killers", "Hot Fuss"),
        song("2", "Beyoncé Medley", "Someone", "Live"),
        song("3", "Somebody Told Me", "The Killers", "Hot Fuss"),
    )

    @Test
    fun `every word must be in the title, artist or album`() {
        assertEquals(listOf("1", "3"), songs.filter { SongFilter.matches(it, "killers") }.map { it.id })
        assertEquals(listOf("3"), songs.filter { SongFilter.matches(it, "killers told") }.map { it.id })
        assertEquals(listOf("2"), songs.filter { SongFilter.matches(it, "beyonce") }.map { it.id })
        assertEquals(3, songs.count { SongFilter.matches(it, "  ") })
    }
}
