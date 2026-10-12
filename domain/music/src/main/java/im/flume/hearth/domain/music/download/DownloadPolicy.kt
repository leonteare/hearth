package im.flume.hearth.domain.music.download

import java.io.IOException

/** The worker's decisions that don't need Android, kept apart so they can be unit tested. */
internal object DownloadPolicy {
    const val MAX_CONNECTION_TRIES = 6
    const val BACKOFF_MS = 10_000L
    const val MAX_BACKOFF_MS = 120_000L
    /** Below this much free space, don't start another song. */
    const val MIN_FREE_BYTES = 500L * 1024 * 1024

    enum class Failure {
        /** Dropped connection: back of the queue, try again soon. */
        Transient,
        /** Phone is out of space: stop everything until the user acts. */
        StorageFull,
        /** This song won't download: mark it failed. */
        SongFailed,
        /** The user removed it: forget it, not a failure. */
        Cancelled,
    }

    /** [connectionTries] counts dropped connections for this song, including this one. */
    fun classify(e: Throwable, removedByUser: Boolean, connectionTries: Int): Failure = when {
        removedByUser -> Failure.Cancelled
        isStorageFull(e) -> Failure.StorageFull
        e is IOException && connectionTries < MAX_CONNECTION_TRIES -> Failure.Transient
        else -> Failure.SongFailed
    }

    fun isStorageFull(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any { t ->
            val msg = t.message.orEmpty()
            msg.contains("ENOSPC") || msg.contains("No space left", ignoreCase = true)
        }

    /** How long every lane waits after [failuresInRow] dropped connections: none for the first two, then 10s doubling to 2 min. */
    fun backoffMs(failuresInRow: Int): Long =
        if (failuresInRow < 3) 0 else (BACKOFF_MS shl (failuresInRow - 3).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)

    /** What an idle lane (no queued song) should do: keep waiting while other lanes are busy, since they may requeue songs. */
    fun idleLaneShouldExit(busyLanes: Int): Boolean = busyLanes <= 0

    /** Unique per attempt, so two fetches of the same song can never write into one file. */
    fun partName(songId: String, attempt: Long): String = "$songId.$attempt.part"

    /**
     * Whether a song should be on the phone. The user's choice for the song itself ([songPref]: true =
     * downloaded it, false = removed it, null = never said) wins over any downloaded album, playlist or
     * Liked Songs that holds it ([inDownloadedCollection]). Re-downloading a collection, or liking a
     * song while Liked Songs is downloaded, clears a "removed" choice so the collection applies again.
     */
    fun shouldKeep(songPref: Boolean?, inDownloadedCollection: Boolean): Boolean = songPref ?: inDownloadedCollection

    /** Songs of downloaded collections to queue: not already on the phone or on the way, and not removed by the user. */
    fun toQueue(collectionSongs: Collection<String>, existing: Set<String>, prefs: Map<String, Boolean>): List<String> =
        collectionSongs.filter { it !in existing && shouldKeep(prefs[it], inDownloadedCollection = true) }

    /** Which of a collection's songs to delete when its download is removed; [stillWanted] = songs other downloads hold. */
    fun removeOnUnpin(songIds: List<String>, stillWanted: Set<String>, prefs: Map<String, Boolean>): List<String> =
        songIds.distinct().filter { !shouldKeep(prefs[it], it in stillWanted) }
}
