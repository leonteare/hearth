package im.flume.hearth.playback

import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.data.LibraryDao
import im.flume.hearth.data.PendingScrobbleEntity
import im.flume.hearth.data.PlayHistoryEntity
import im.flume.hearth.api.SubsonicException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
                val error = runCatching { api.scrobble(id, submission = true, timeMs = time) }.exceptionOrNull()
                when {
                    error == null -> flushPending()
                    !isRejected(error) -> dao.insertPendingScrobble(PendingScrobbleEntity(songId = id, time = time))
                }
            }
        }
    }

    /** Sends queued plays, oldest first. Stops at a network problem (try again later). */
    suspend fun flushPending() = flushLock.withLock {
        for (p in dao.pendingScrobbles()) {
            try {
                api.scrobble(p.songId, submission = true, timeMs = p.time)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One the server refuses (e.g. the song was deleted) must not block the rest forever.
                if (!isRejected(e)) return@withLock
            }
            dao.deletePendingScrobble(p.rowId)
        }
    }

    companion object {
        // A single lock across instances, so two flushes never send the same play twice.
        private val flushLock = Mutex()

        /** Subsonic auth codes: every scrobble would fail the same way, so keep them for later. */
        private val AUTH_ERRORS = setOf(40, 41, 42, 43, 44, 50)

        /**
         * True when the server answered and refused this particular scrobble (a Subsonic error such as
         * 70 "not found", or HTTP 404/410), so retrying it is pointless. Network failures, server
         * errors and login problems are false: those plays are kept and sent later.
         */
        fun isRejected(e: Throwable): Boolean {
            if (e !is SubsonicException) return false
            return when (e.code) {
                404, 410 -> true
                in 0..99 -> e.code !in AUTH_ERRORS
                else -> false
            }
        }
    }
}
