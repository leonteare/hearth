// Downloaded albums, playlists and Liked Songs: which songs they keep, and what removing one would delete.
package im.flume.hearth.domain.music.download

import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.core.data.PinnedEntity
import im.flume.hearth.core.data.SongDownloadPrefEntity
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.domain.music.download.DownloadRepository.Companion.KIND_ALBUM
import im.flume.hearth.domain.music.download.DownloadRepository.Companion.KIND_LIKED
import im.flume.hearth.domain.music.download.DownloadRepository.Companion.KIND_PLAYLIST
import im.flume.hearth.domain.music.download.DownloadRepository.RemovalPlan

/**
 * Reads pins and the user's per-song choices for [DownloadRepository]; the deciding is done by
 * [DownloadPolicy]. Holds no state of its own: everything is in the database.
 */
internal class DownloadPins(private val db: AppDatabase) {
    private val dao = db.downloads()

    /** Records that the user wants (or doesn't want) these songs on the phone, whatever their collections say. */
    suspend fun setPrefs(songIds: List<String>, wanted: Boolean) {
        val now = System.currentTimeMillis()
        songIds.distinct().chunked(500).forEach { chunk -> dao.upsertPrefs(chunk.map { SongDownloadPrefEntity(it, wanted, now) }) }
    }

    private suspend fun prefMap(): Map<String, Boolean> = dao.prefs().associate { it.songId to it.wanted }

    private suspend fun songsFor(p: PinnedEntity): List<SongEntity> {
        val lib = db.library()
        return when (p.kind) {
            KIND_PLAYLIST -> lib.playlistSongsOnce(p.id)
            KIND_ALBUM -> lib.albumSongsOnce(p.id)
            KIND_LIKED -> lib.starredSongsOnce()
            else -> emptyList()
        }
    }

    /** The body of [DownloadRepository.removalPlan]. */
    suspend fun removalPlan(kind: String?, id: String?, songIds: List<String>): RemovalPlan {
        val rows = dao.allOnce().mapTo(HashSet()) { it.songId }
        // No collection (a genre, a mix): the user is removing these songs themselves, so all of them go.
        val ids = if (kind == null) songIds.distinct().filter { it in rows } else {
            val others = dao.pinned().filterNot { it.kind == kind && it.id == id }
            val stillWanted = others.flatMapTo(HashSet()) { p -> songsFor(p).map { it.id } }
            DownloadPolicy.removeOnUnpin(songIds, stillWanted, prefMap()).filter { it in rows }
        }
        val bytes = ids.chunked(500).sumOf { dao.bytesOf(it) }
        return RemovalPlan(ids, bytes)
    }

    /**
     * Songs of downloaded albums, playlists and Liked Songs that aren't on the phone or on their way
     * yet, in collection order. Songs the user removed one by one are left out.
     */
    suspend fun missingSongs(): List<SongEntity> {
        val pins = dao.pinned()
        if (pins.isEmpty()) return emptyList()
        val wanted = LinkedHashMap<String, SongEntity>()
        pins.forEach { p -> songsFor(p).forEach { wanted.putIfAbsent(it.id, it) } }
        val existing = dao.allOnce().mapTo(HashSet()) { it.songId }
        return DownloadPolicy.toQueue(wanted.keys, existing, prefMap()).map(wanted::getValue)
    }
}
