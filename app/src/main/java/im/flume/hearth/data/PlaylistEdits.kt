package im.flume.hearth.data

import androidx.room.Embedded

/** A playlist entry at its server [position]; [song] is null when this phone hasn't synced that song yet. */
data class PlaylistEntry(
    val position: Int,
    val songId: String,
    @Embedded val song: SongEntity?,
)

/**
 * Playlist edits that stay correct while the other phone edits the same playlist. Playlists may hold
 * the same song more than once, so an entry is identified by its song id plus which occurrence of
 * that id it is (0 for the first, 1 for the second, ...). Free of Android so it can be unit tested.
 */
object PlaylistEdits {
    /** One entry: the [n]th occurrence of [songId] in a list. */
    data class Key(val songId: String, val n: Int)

    /** Each id paired with its occurrence number, in list order. */
    fun keyed(ids: List<String>): List<Key> {
        val seen = HashMap<String, Int>()
        return ids.map { id -> val n = seen[id] ?: 0; seen[id] = n + 1; Key(id, n) }
    }

    /** Which occurrence of its id the entry at [index] is. */
    fun occurrenceAt(ids: List<String>, index: Int): Int = ids.subList(0, index).count { it == ids[index] }

    /** Position of the [occurrence]th [songId] in [ids]; the last one if there are fewer; null if it's gone. */
    fun indexOf(ids: List<String>, songId: String, occurrence: Int): Int? {
        var seen = 0
        var last: Int? = null
        ids.forEachIndexed { i, id ->
            if (id == songId) {
                if (seen == occurrence) return i
                seen++
                last = i
            }
        }
        return last
    }

    /**
     * Merges your edit with what happened on the server meanwhile. [original] is the server list when
     * editing began, [edited] your new list, [latestServer] the server list now.
     * - Your order (and your removals and re-additions) win for songs still on the server.
     * - Songs the other person removed since [original] stay removed.
     * - Songs the other person added since [original] go at the end, in their server order.
     */
    fun mergePlaylistEdit(original: List<String>, edited: List<String>, latestServer: List<String>): List<String> {
        val originalCount = original.groupingBy { it }.eachCount()
        val latestCount = latestServer.groupingBy { it }.eachCount()
        // Occurrences n with latest <= n < original were removed by the other person.
        fun removedByOther(k: Key) = k.n >= (latestCount[k.songId] ?: 0) && k.n < (originalCount[k.songId] ?: 0)
        val kept = keyed(edited).filterNot(::removedByOther).map { it.songId }
        // Occurrences n with original <= n < latest were added by the other person.
        val added = keyed(latestServer).filter { it.n >= (originalCount[it.songId] ?: 0) }.map { it.songId }
        return kept + added
    }

    /**
     * The smallest updatePlaylist call turning [current] into [target]: everything after the common
     * start is removed (indexes into [current]) and the rest of [target] appended. Removing every index
     * after a point and appending works whichever order Navidrome applies the two in.
     */
    fun replaceOps(current: List<String>, target: List<String>): Pair<List<Int>, List<String>> {
        var same = 0
        while (same < current.size && same < target.size && current[same] == target[same]) same++
        return (same until current.size).toList() to target.drop(same)
    }
}
