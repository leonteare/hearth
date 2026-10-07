package im.flume.hearth.playback

import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.data.LibraryDao
import im.flume.hearth.data.PendingScrobbleEntity
import im.flume.hearth.data.PlayHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reports plays to Navidrome so play counts and "Most played" stay accurate. A song counts once
 * it has actually played for half its length or 4 minutes, whichever comes first (Last.fm rule).
 * Plays made offline are queued and sent later.
 */
class Scrobbler(
    private val api: SubsonicClient,
    private val dao: LibraryDao,
    private val scope: CoroutineScope,
) {
    private var songId: String? = null
    private var albumId: String? = null
    private var startedAt = 0L
    private var durationMs = 0L
    private var listenedMs = 0L
    private var submitted = false

    fun onTrackStarted(id: String, album: String?, duration: Long) {
        songId = id
        albumId = album
        startedAt = System.currentTimeMillis()
        durationMs = duration
        listenedMs = 0
        submitted = false
        scope.launch { runCatching { api.scrobble(id, submission = false) } }
    }

    /** Called periodically while audio is actually playing. */
    fun onListened(deltaMs: Long) {
        val id = songId ?: return
        if (submitted) return
        listenedMs += deltaMs
        val threshold = if (durationMs > 0) minOf(durationMs / 2, 240_000L) else 240_000L
        if (listenedMs >= threshold) {
            submitted = true
            val time = startedAt
            val album = albumId
            scope.launch {
                dao.insertHistory(PlayHistoryEntity(songId = id, albumId = album, playedAt = time))
                dao.incrementPlayCount(id)
                val ok = runCatching { api.scrobble(id, submission = true, timeMs = time) }.isSuccess
                if (ok) flushPending() else dao.insertPendingScrobble(PendingScrobbleEntity(songId = id, time = time))
            }
        }
    }

    suspend fun flushPending() {
        for (p in dao.pendingScrobbles()) {
            if (runCatching { api.scrobble(p.songId, submission = true, timeMs = p.time) }.isFailure) return
            dao.deletePendingScrobble(p.rowId)
        }
    }
}
