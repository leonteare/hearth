package im.flume.hearth.core.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import im.flume.hearth.core.data.SongEntity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import im.flume.hearth.domain.music.playback.SavedQueue

const val EXTRA_MANUAL = "manual"
const val EXTRA_ALBUM_ID = "albumId"
const val EXTRA_ARTIST_ID = "artistId"
const val EXTRA_COVER_ART = "coverArt"
const val EXTRA_TRACK_GAIN = "trackGain"
const val EXTRA_ALBUM_GAIN = "albumGain"
const val EXTRA_TRACK_PEAK = "trackPeak"
/** Identifies one entry in the queue (the same song can be queued twice), so the UI can address it safely. */
const val EXTRA_QUEUE_KEY = "queueKey"

private val queueKeys = AtomicLong(System.currentTimeMillis())

fun newQueueKey(): String = queueKeys.incrementAndGet().toString(36)

/** Live queue details the UI shows ("12 of 300", the full upcoming list). Service and UI share a process. */
data class QueueInfo(val pending: List<String> = emptyList(), val trimmed: Int = 0)

fun SongEntity.toMediaItem(manual: Boolean = false, queueKey: String = newQueueKey()): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(SongDataSource.uriFor(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(artist)
                .setGenre(genre)
                .setTrackNumber(track)
                .setDurationMs(durationSec * 1000L)
                // Never the real cover URL: it carries the login token (see CoverArt).
                .setArtworkUri(CoverArt.uriFor(coverArt))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply {
                    putBoolean(EXTRA_MANUAL, manual)
                    putString(EXTRA_QUEUE_KEY, queueKey)
                    putString(EXTRA_ALBUM_ID, albumId)
                    putString(EXTRA_ARTIST_ID, artistId)
                    putString(EXTRA_COVER_ART, coverArt)
                    trackGain?.let { putDouble(EXTRA_TRACK_GAIN, it) }
                    albumGain?.let { putDouble(EXTRA_ALBUM_GAIN, it) }
                    trackPeak?.let { putDouble(EXTRA_TRACK_PEAK, it) }
                })
                .build()
        )
        .build()

val MediaItem.isManual: Boolean get() = mediaMetadata.extras?.getBoolean(EXTRA_MANUAL) == true

val MediaItem.queueKey: String? get() = mediaMetadata.extras?.getString(EXTRA_QUEUE_KEY)

/** A copy of this item with the "added by you" flag set to [manual]. */
fun MediaItem.withManual(manual: Boolean): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY).apply { putBoolean(EXTRA_MANUAL, manual) }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

/**
 * Where playback had got to in the queue saved as [revision]. Written on its own every few seconds
 * while playing, so the (possibly very long) queue isn't rewritten just because the song moved on.
 */
@Serializable
data class SavedPosition(val revision: Long, val positionMs: Long, val spokenPositionSec: Double? = null)

/** Decides how much of the queue needs writing, and puts the two saved halves back together. */
internal object QueueSaves {
    enum class Write { NOTHING, POSITION, FULL }

    /** [last] is what's on disk now (null: unknown, so write everything). Only the position moving needs just the small file. */
    fun plan(last: SavedQueue?, next: SavedQueue): Write = when {
        last == null || shape(last) != shape(next) -> Write.FULL
        last.positionMs != next.positionMs || last.spokenPositionSec != next.spokenPositionSec -> Write.POSITION
        else -> Write.NOTHING
    }

    /** The saved queue at the newer position, when that position was taken from this very save. */
    fun merge(saved: SavedQueue, position: SavedPosition?): SavedQueue =
        if (position == null || position.revision != saved.revision) saved
        else saved.copy(
            positionMs = position.positionMs.coerceAtLeast(0),
            spokenPositionSec = position.spokenPositionSec ?: saved.spokenPositionSec,
        )

    fun positionOf(q: SavedQueue) = SavedPosition(q.revision, q.positionMs, q.spokenPositionSec)

    /** A revision newer than [previous] (time-based, so it keeps increasing across restarts). */
    fun nextRevision(previous: Long, nowMs: Long): Long = maxOf(nowMs, previous + 1)

    private fun shape(q: SavedQueue) = q.copy(positionMs = 0, spokenPositionSec = null, revision = 0)
}

/**
 * Saves the queue to queue.json. Writes go through a temp file and an atomic rename, and the previous
 * good save is kept as queue.json.bak in case the main file is ever unreadable.
 *
 * A shuffle of the whole library is hundreds of KB, so it's only rewritten when the queue itself
 * changes (another song, an edit, shuffle). While a song plays, just the position goes to the tiny
 * queue-position.json, and [load] puts the two back together.
 */
class QueueStore(dir: File) {
    private val file = File(dir, "queue.json")
    private val tmp = File(dir, "queue.json.tmp")
    private val backup = File(dir, "queue.json.bak")
    private val positionFile = File(dir, "queue-position.json")
    private val positionTmp = File(dir, "queue-position.json.tmp")
    private val json = Json { ignoreUnknownKeys = true }

    /** Records [q] as the newest snapshot. Call in order (e.g. on the main thread), then [flush]. */
    fun offer(q: SavedQueue) = latest.set(q)

    /** Writes the newest offered snapshot, if any. Older snapshots that were overtaken are never written. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        lock.withLock {
            val offered = latest.getAndSet(null) ?: return@withLock
            val last = onDisk
            when (QueueSaves.plan(last, offered)) {
                QueueSaves.Write.NOTHING -> Unit
                QueueSaves.Write.POSITION -> {
                    val q = offered.copy(revision = last!!.revision)
                    // Not synced to disk: losing the last few seconds of position in a power cut is fine,
                    // and a torn file is just ignored (the full save has its own position).
                    runCatching {
                        positionTmp.writeText(json.encodeToString(SavedPosition.serializer(), QueueSaves.positionOf(q)))
                        Files.move(positionTmp.toPath(), positionFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        onDisk = q
                    }
                }
                QueueSaves.Write.FULL -> {
                    val q = offered.copy(revision = QueueSaves.nextRevision(last?.revision ?: 0, System.currentTimeMillis()))
                    runCatching {
                        FileOutputStream(tmp).use { out ->
                            out.write(json.encodeToString(SavedQueue.serializer(), q).toByteArray())
                            out.fd.sync()
                        }
                        if (file.exists()) Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        // The old position file names the previous revision, so it's ignored from now on.
                        onDisk = q
                    }.onFailure { onDisk = null }
                }
            }
        }
    }

    suspend fun save(q: SavedQueue) {
        offer(q)
        flush()
    }

    suspend fun load(): SavedQueue? = withContext(Dispatchers.IO) {
        lock.withLock {
            val saved = (read(file) ?: read(backup))?.let { QueueSaves.merge(it, readPosition()) }
            onDisk = saved
            saved
        }
    }

    private fun readPosition(): SavedPosition? =
        runCatching { json.decodeFromString(SavedPosition.serializer(), positionFile.readText()) }.getOrNull()

    private fun read(f: File): SavedQueue? =
        runCatching { json.decodeFromString(SavedQueue.serializer(), f.readText()) }.getOrNull()

    fun clear() {
        latest.set(null)
        onDisk = null
        file.delete()
        backup.delete()
        positionFile.delete()
        musicFile.delete()
    }

    // The music queue set aside while spoken word plays ("Back to music").
    private val musicFile = File(dir, "music-queue.json")

    suspend fun saveMusic(q: SavedQueue) = withContext(Dispatchers.IO) {
        runCatching { musicFile.writeText(json.encodeToString(SavedQueue.serializer(), q)) }
    }

    suspend fun loadMusic(): SavedQueue? = withContext(Dispatchers.IO) { read(musicFile) }

    fun clearMusic() { musicFile.delete() }

    fun hasMusic(): Boolean = musicFile.exists()

    private companion object {
        // Process-wide: the service and Settings each make their own QueueStore for the same file.
        val lock = Mutex()
        val latest = AtomicReference<SavedQueue?>(null)
        /** What queue.json plus queue-position.json hold now (null: unknown). Only touched under [lock]. */
        @Volatile var onDisk: SavedQueue? = null
    }
}
