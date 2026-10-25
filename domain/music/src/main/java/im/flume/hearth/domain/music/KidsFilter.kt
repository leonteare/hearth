// Kids mode: what the kids' player leaves out of the library, decided by the parents' "Not for kids"
// playlists on the server. Pure rules; sync/LibrarySync applies them.
package im.flume.hearth.domain.music

/**
 * A parent marks a public playlist with [MARKER] in its comment (the homelab has one called "Not for
 * kids") and adds songs to it from their own phone. On a kiosk phone in kids mode those songs never
 * reach the library index, so they are not in search, shuffle, albums, mixes or playlists, and an
 * album with nothing left in it goes too. Other phones ignore the marker: for them it is just a playlist.
 */
object KidsFilter {
    const val MARKER = "[hearth-not-for-kids]"

    fun isBlockList(comment: String?): Boolean = comment?.contains(MARKER) == true

    /** Every song on any block list. */
    fun blocked(blockLists: Collection<List<String>>): Set<String> = blockLists.flatMapTo(HashSet()) { it }

    /** The albums that still have a song once the blocked ones are gone. */
    fun keptAlbumIds(keptSongAlbumIds: Iterable<String?>): Set<String> = keptSongAlbumIds.filterNotNullTo(HashSet())
}
