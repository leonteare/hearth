package im.flume.hearth.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.data.SongEntity
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

const val EXTRA_MANUAL = "manual"
const val EXTRA_ALBUM_ID = "albumId"
const val EXTRA_ARTIST_ID = "artistId"
const val EXTRA_COVER_ART = "coverArt"
const val EXTRA_TRACK_GAIN = "trackGain"
const val EXTRA_ALBUM_GAIN = "albumGain"
const val EXTRA_TRACK_PEAK = "trackPeak"

/** Live queue details the UI shows ("12 of 300", the full upcoming list). Service and UI share a process. */
data class QueueInfo(val pending: List<String> = emptyList(), val trimmed: Int = 0)

fun SongEntity.toMediaItem(api: SubsonicClient, manual: Boolean = false): MediaItem =
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
                .setArtworkUri(api.coverArtUrl(coverArt)?.toUri())
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply {
                    putBoolean(EXTRA_MANUAL, manual)
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

/** Snapshot of the full queue so it survives the app being killed (and so the car can resume it). */
@Serializable
data class SavedQueue(
    val window: List<String>,
    val manual: List<Boolean>,
    val index: Int,
    val positionMs: Long,
    val pending: List<String>,
    val context: List<String>,
    val shuffled: Boolean,
    val label: String,
    val repeatMode: Int,
)

/**
 * Saves the queue to queue.json. Writes go through a temp file and an atomic rename, and the previous
 * good save is kept as queue.json.bak in case the main file is ever unreadable.
 */
class QueueStore(dir: File) {
    private val file = File(dir, "queue.json")
    private val tmp = File(dir, "queue.json.tmp")
    private val backup = File(dir, "queue.json.bak")
    private val json = Json { ignoreUnknownKeys = true }

    /** Records [q] as the newest snapshot. Call in order (e.g. on the main thread), then [flush]. */
    fun offer(q: SavedQueue) = latest.set(q)

    /** Writes the newest offered snapshot, if any. Older snapshots that were overtaken are never written. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        lock.withLock {
            val q = latest.getAndSet(null) ?: return@withLock
            runCatching {
                FileOutputStream(tmp).use { out ->
                    out.write(json.encodeToString(SavedQueue.serializer(), q).toByteArray())
                    out.fd.sync()
                }
                if (file.exists()) Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    suspend fun save(q: SavedQueue) {
        offer(q)
        flush()
    }

    suspend fun load(): SavedQueue? = withContext(Dispatchers.IO) {
        lock.withLock { read(file) ?: read(backup) }
    }

    private fun read(f: File): SavedQueue? =
        runCatching { json.decodeFromString(SavedQueue.serializer(), f.readText()) }.getOrNull()

    fun clear() {
        latest.set(null)
        file.delete()
        backup.delete()
    }

    private companion object {
        // Process-wide: the service and Settings each make their own QueueStore for the same file.
        val lock = Mutex()
        val latest = AtomicReference<SavedQueue?>(null)
    }
}
