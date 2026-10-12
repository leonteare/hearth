// Sort orders: All songs and Liked Songs (SongOrder), Your Library and Browse A–Z (LibraryOrder), Your Library's artists.
package im.flume.hearth.domain.music

import im.flume.hearth.core.data.AlbumEntity
import im.flume.hearth.core.data.ArtistEntity
import im.flume.hearth.core.data.SearchIndex
import im.flume.hearth.core.data.SongEntity

/** All songs' sort orders. The screen and the service sort the same way, so only the order is sent. */
enum class SongOrder {
    /** By title, ignoring case (the order All songs already comes in from the database). */
    TITLE,
    ARTIST,
    RECENT,
    PLAYS,
    /** By album, then disc and track: Liked Songs' "Album" sort (not on All songs' menu). */
    ALBUM;

    /**
     * The songs in this order. Slow for a whole library (thousands of songs): call it off the main
     * thread. Text keys are worked out once per song rather than in every comparison, which keeps
     * a sort of ~7k songs to a few milliseconds.
     */
    fun sort(songs: List<SongEntity>): List<SongEntity> = when (this) {
        TITLE -> songs.sortedByKeys { SortKey(it, foldCase(it.title)) }
        ALBUM -> songs.sortedByKeys { SortKey(it, foldCase(it.album), disc = it.disc, track = it.track) }
        ARTIST -> songs.sortedByKeys { SortKey(it, foldCase(it.artist), it.album.lowercase(), it.disc, it.track) }
        RECENT -> songs.sortedByDescending { it.created.orEmpty() }
        PLAYS -> songs.sortedByDescending { it.playCount }
    }

    /** One song's precomputed sort keys, compared in order. */
    private class SortKey(val song: SongEntity, val text: String, val text2: String = "", val disc: Int = 0, val track: Int = 0) :
        Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int {
            text.compareTo(other.text).let { if (it != 0) return it }
            text2.compareTo(other.text2).let { if (it != 0) return it }
            disc.compareTo(other.disc).let { if (it != 0) return it }
            return track.compareTo(other.track)
        }
    }

    companion object {
        /**
         * [text] folded so that plain comparison orders it exactly as [String.CASE_INSENSITIVE_ORDER]
         * does (which compares each character's upper-then-lower case), without redoing it per comparison.
         */
        fun foldCase(text: String): String {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (Character.toLowerCase(Character.toUpperCase(c)) != c) break
                i++
            }
            if (i == text.length) return text
            val out = CharArray(text.length)
            for (j in text.indices) out[j] = Character.toLowerCase(Character.toUpperCase(text[j]))
            return String(out)
        }

        /** Stable, like the comparator sorts it replaces: equal keys keep the database's order. */
        private inline fun List<SongEntity>.sortedByKeys(key: (SongEntity) -> SortKey): List<SongEntity> =
            map(key).sorted().map { it.song }

        /** The All songs sort menu position (see SONG_SORTS). */
        fun fromMenu(index: Int): SongOrder = entries.getOrElse(index) { TITLE }

        /** Liked Songs' sort menu, in order. There's no "recently liked": the server's like dates aren't kept. */
        val LIKED = listOf(TITLE to "Title A–Z", ARTIST to "Artist", ALBUM to "Album")

        /** The Liked Songs sort menu position; anything unknown is Title A–Z. */
        fun liked(index: Int): SongOrder = LIKED.getOrNull(index)?.first ?: TITLE
    }
}

/** The A–Z orders and sort menus of Your Library and the Browse pages. Pure, so it's unit tested. */
object LibraryOrder {
    /** Sorting name for A–Z: ignores case, accents and a leading "The", so "The Beatles" sits under B. */
    fun nameKey(name: String): String = SearchIndex.norm(name).ifEmpty { name.lowercase() }

    private fun <T> byName(name: (T) -> String): Comparator<T> = compareBy<T> { nameKey(name(it)) }.thenBy { name(it) }

    /** A–Z by name, ignoring case, accents and a leading "The": playlists, shows, books. */
    fun <T> alphabetical(items: List<T>, name: (T) -> String): List<T> = items.sortedWith(byName(name))

    /** Menu: A–Z, Most albums. */
    fun artists(artists: List<ArtistEntity>, sort: Int): List<ArtistEntity> =
        if (sort == 1) artists.sortedByDescending { it.albumCount } else artists.sortedWith(byName { it.name })

    /** Menu: A–Z, Recently added, Artist, Most played, Year. */
    fun albums(albums: List<AlbumEntity>, sort: Int): List<AlbumEntity> = when (sort) {
        1 -> albums.sortedByDescending { it.created }
        2 -> albums.sortedWith(compareBy<AlbumEntity> { nameKey(it.artist) }.thenBy { it.year ?: 0 })
        3 -> albums.sortedByDescending { it.playCount }
        4 -> albums.sortedByDescending { it.year ?: 0 }
        else -> albums.sortedWith(byName { it.name })
    }
}

/** The personal side of the library. Kept free of Android so it can be unit tested. */
object YourLibrary {
    /**
     * Artists on the Library tab: ones you follow plus the artists of albums you saved, A–Z.
     * Artists of liked songs are left out on purpose; that would be nearly everyone.
     */
    fun artists(all: List<ArtistEntity>, savedAlbums: List<AlbumEntity>): List<ArtistEntity> {
        val fromAlbums = savedAlbums.mapNotNullTo(HashSet()) { it.artistId }
        return all.filter { it.starred || it.id in fromAlbums }
    }
}
