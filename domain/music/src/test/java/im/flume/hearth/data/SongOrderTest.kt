package im.flume.hearth.data

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random
import im.flume.hearth.core.data.SongEntity

class SongOrderTest {
    private fun song(id: String, title: String, artist: String = "Someone", album: String = "Album", disc: Int = 1, track: Int = 1) =
        SongEntity(id, title, album, "al", artist, "ar", track, disc, null, null, 200, null, null, 0, true, 0, null)

    /** The comparators the precomputed keys replaced: the order must not change. */
    private val oldOrders = mapOf(
        SongOrder.TITLE to compareBy(String.CASE_INSENSITIVE_ORDER, SongEntity::title),
        SongOrder.ALBUM to compareBy(String.CASE_INSENSITIVE_ORDER, SongEntity::album).thenBy { it.disc }.thenBy { it.track },
        SongOrder.ARTIST to compareBy(String.CASE_INSENSITIVE_ORDER, SongEntity::artist).thenBy { it.album.lowercase() }.thenBy { it.disc }.thenBy { it.track },
    )

    private fun randomText(r: Random): String {
        val alphabet = "aAbBzZ _-.0é ÉßİıÅåΣσς'"
        return String(CharArray(r.nextInt(0, 6)) { alphabet[r.nextInt(alphabet.length)] })
    }

    @Test
    fun `folded text compares exactly like the case-insensitive comparator`() {
        val r = Random(7)
        repeat(5000) {
            val a = randomText(r)
            val b = randomText(r)
            assertEquals(
                "$a vs $b",
                Integer.signum(String.CASE_INSENSITIVE_ORDER.compare(a, b)),
                Integer.signum(SongOrder.foldCase(a).compareTo(SongOrder.foldCase(b))),
            )
        }
    }

    @Test
    fun `sorting with precomputed keys gives the same order as before, ties included`() {
        val r = Random(11)
        val songs = List(2000) { i ->
            song("$i", randomText(r), artist = randomText(r), album = randomText(r), disc = r.nextInt(1, 3), track = r.nextInt(1, 4))
        }
        for ((order, comparator) in oldOrders) {
            assertEquals(order.name, songs.sortedWith(comparator).map { it.id }, order.sort(songs).map { it.id })
        }
    }

    @Test
    fun `artist order groups an artist's albums and plays them in disc and track order`() {
        val songs = listOf(
            song("1", "x", artist = "beta", album = "B", disc = 1, track = 2),
            song("2", "x", artist = "Alpha", album = "Z", disc = 1, track = 1),
            song("3", "x", artist = "beta", album = "a", disc = 2, track = 1),
            song("4", "x", artist = "Beta", album = "A", disc = 1, track = 1),
            song("5", "x", artist = "beta", album = "B", disc = 1, track = 1),
        )
        assertEquals(listOf("2", "4", "3", "5", "1"), SongOrder.ARTIST.sort(songs).map { it.id })
    }
}
