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
import java.io.File

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

class QueueStore(dir: File) {
    private val file = File(dir, "queue.json")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(q: SavedQueue) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "queue.json.tmp")
            tmp.writeText(json.encodeToString(SavedQueue.serializer(), q))
            tmp.renameTo(file)
        }
    }

    suspend fun load(): SavedQueue? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(SavedQueue.serializer(), file.readText()) }.getOrNull()
    }

    fun clear() { file.delete() }
}
