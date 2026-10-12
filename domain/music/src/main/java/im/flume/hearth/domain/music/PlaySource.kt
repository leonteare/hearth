// PlaySource: what the user pressed play on, as sent to the playback service.
package im.flume.hearth.domain.music

import android.os.Bundle

/**
 * Something the user can press play on. Converted to a Bundle to travel to the playback service, so
 * big lists are described ([kind] + [order]) and resolved by the service rather than sent as [songIds].
 */
data class PlaySource(
    val kind: Kind,
    val id: String? = null,
    val label: String = "",
    val songIds: List<String> = emptyList(),
    /**
     * For [Kind.ALL]: every song in this order (All songs' sort) instead of the plain library order.
     * For [Kind.LIKED]: Liked Songs' sort.
     */
    val order: SongOrder? = null,
) {
    enum class Kind { ALL, MY_LIBRARY, ALBUM, ARTIST, PLAYLIST, GENRE, LIKED, DOWNLOADS, SONGS }

    fun toBundle() = Bundle().apply {
        putString("kind", kind.name)
        putString("id", id)
        putString("label", label)
        putStringArrayList("songIds", ArrayList(songIds))
        putString("order", order?.name)
    }

    companion object {
        fun fromBundle(b: Bundle) = PlaySource(
            kind = Kind.entries.firstOrNull { it.name == b.getString("kind") } ?: Kind.ALL,
            id = b.getString("id"),
            label = b.getString("label").orEmpty(),
            songIds = b.getStringArrayList("songIds").orEmpty(),
            order = SongOrder.entries.firstOrNull { it.name == b.getString("order") },
        )

        val All = PlaySource(Kind.ALL, label = "Everything")
        /** Liked songs and saved albums; falls back to everything when nothing's been saved yet. */
        val MyLibrary = PlaySource(Kind.MY_LIBRARY, label = "Your library")
        val Liked = PlaySource(Kind.LIKED, label = "Liked Songs")
        val Downloads = PlaySource(Kind.DOWNLOADS, label = "Downloads")
    }
}
