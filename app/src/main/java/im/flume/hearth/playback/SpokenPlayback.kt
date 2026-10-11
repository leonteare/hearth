package im.flume.hearth.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import im.flume.hearth.spoken.BookEntity
import im.flume.hearth.spoken.EpisodeRow
import im.flume.hearth.spoken.LocalSessionPayload
import im.flume.hearth.spoken.SpokenProgress
import im.flume.hearth.spoken.SpokenTimeline
import im.flume.hearth.spoken.TimelineChapter
import im.flume.hearth.spoken.TimelineTrack
import im.flume.hearth.spoken.TrackEntity
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Podcast episodes and audiobooks in the player. Each audio file is a MediaItem with a
 * `hearth-abs://file/{item}/{audio file id}` URI that [SpokenDataSource] resolves to the downloaded
 * file or the server (token in a header, never in the URI); artwork is `abs-cover://item/{item}`.
 */
object SpokenMedia {
    const val SCHEME = "hearth-abs"
    const val COVER_SCHEME = "abs-cover"
    const val EXTRA_KIND = "kind"
    const val KIND_SPOKEN = "spoken"
    const val EXTRA_ITEM_ID = "absItemId"
    const val EXTRA_EPISODE_ID = "absEpisodeId"
    const val EXTRA_TRACK_START_MS = "absTrackStartMs"
    const val EXTRA_TOTAL_MS = "absTotalMs"

    fun uriFor(libraryItemId: String, ino: String): Uri =
        Uri.Builder().scheme(SCHEME).authority("file").appendPath(libraryItemId).appendPath(ino).build()

    /** (library item id, audio file id) of a spoken-word URI. */
    fun parse(uri: Uri): Pair<String, String>? {
        if (uri.scheme != SCHEME) return null
        val segs = uri.pathSegments
        return if (segs.size == 2) segs[0] to segs[1] else null
    }

    fun coverUri(libraryItemId: String): Uri = Uri.Builder().scheme(COVER_SCHEME).authority("item").appendPath(libraryItemId).build()

    fun coverItemId(uri: Uri): String? = if (uri.scheme == COVER_SCHEME) uri.lastPathSegment?.takeIf { it.isNotEmpty() } else null

    fun mediaId(libraryItemId: String, episodeId: String?, index: Int) = "abs:$libraryItemId:${episodeId.orEmpty()}:$index"

    private fun item(
        libraryItemId: String, episodeId: String?, index: Int, ino: String, title: String, artist: String?, album: String?,
        durationMs: Long, startMs: Long, totalMs: Long, mediaType: Int,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId(libraryItemId, episodeId, index))
        .setUri(uriFor(libraryItemId, ino))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(artist)
                .setDurationMs(durationMs)
                .setArtworkUri(coverUri(libraryItemId))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(mediaType)
                .setExtras(Bundle().apply {
                    putString(EXTRA_KIND, KIND_SPOKEN)
                    putString(EXTRA_ITEM_ID, libraryItemId)
                    putString(EXTRA_EPISODE_ID, episodeId)
                    putLong(EXTRA_TRACK_START_MS, startMs)
                    putLong(EXTRA_TOTAL_MS, totalMs)
                    putString(EXTRA_QUEUE_KEY, newQueueKey())
                })
                .build()
        )
        .build()

    fun episodeItems(row: EpisodeRow): List<MediaItem> {
        val ino = row.episode.ino ?: return emptyList()
        val ms = (row.episode.durationSec * 1000).toLong()
        return listOf(item(row.episode.libraryItemId, row.episode.id, 0, ino, row.episode.title, row.podcastTitle, row.podcastTitle, ms, 0, ms, MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE))
    }

    fun bookItems(book: BookEntity, tracks: List<TrackEntity>): List<MediaItem> {
        val timeline = SpokenTimeline(tracks.map { TimelineTrack(it.ino, it.durationSec) })
        val totalMs = (timeline.durationSec * 1000).toLong()
        return tracks.mapIndexed { i, t ->
            val title = if (tracks.size > 1) "${book.title} (${i + 1}/${tracks.size})" else book.title
            item(book.id, null, i, t.ino, title, book.author, book.title, (t.durationSec * 1000).toLong(), (timeline.starts[i] * 1000).toLong(), totalMs, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
        }
    }
}

val MediaItem.isSpoken: Boolean get() = mediaMetadata.extras?.getString(SpokenMedia.EXTRA_KIND) == SpokenMedia.KIND_SPOKEN
val MediaItem.spokenItemId: String? get() = mediaMetadata.extras?.getString(SpokenMedia.EXTRA_ITEM_ID)
val MediaItem.spokenEpisodeId: String? get() = mediaMetadata.extras?.getString(SpokenMedia.EXTRA_EPISODE_ID)
/** Where this file starts on the whole episode/book timeline. */
val MediaItem.spokenTrackStartMs: Long get() = mediaMetadata.extras?.getLong(SpokenMedia.EXTRA_TRACK_START_MS) ?: 0
val MediaItem.spokenTotalMs: Long get() = mediaMetadata.extras?.getLong(SpokenMedia.EXTRA_TOTAL_MS) ?: 0

/** What's loaded when the player holds spoken word: the item, its whole timeline and chapters. */
data class SpokenNow(
    val libraryItemId: String,
    val episodeId: String?,
    val libraryId: String,
    val title: String,
    val author: String,
    val timeline: SpokenTimeline,
) {
    val isBook: Boolean get() = episodeId == null

    companion object {
        fun chaptersOf(rows: List<im.flume.hearth.spoken.ChapterEntity>) = rows.map { TimelineChapter(it.startSec, it.endSec, it.title) }
    }
}

/**
 * Picks the data source by URI scheme: spoken-word files go to [spoken], everything else (songs) to
 * [default]. Lets music and spoken word share one player.
 */
@UnstableApi
class SchemeRoutingDataSourceFactory(private val default: DataSource.Factory, private val routes: Map<String, DataSource.Factory>) : DataSource.Factory {
    override fun createDataSource(): DataSource = Routing()

    private inner class Routing : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null
        override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
        override fun open(dataSpec: DataSpec): Long {
            val source = (routes[dataSpec.uri.scheme] ?: default).createDataSource()
            listeners.forEach(source::addTransferListener)
            delegate = source
            return source.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = delegate?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
        override fun close() { try { delegate?.close() } finally { delegate = null } }
    }
}

/**
 * Resolves `hearth-abs://` URIs: the downloaded file when there is one, otherwise the server's file URL
 * through [httpFactory] (whose OkHttp client adds the token header). Offline and not downloaded fails
 * at once, like songs do.
 */
@UnstableApi
class SpokenDataSource(
    private val fileFactory: DataSource.Factory,
    private val httpFactory: DataSource.Factory,
    private val localFile: (String, String) -> File?,
    private val remoteUrl: (String, String) -> String?,
    private val cannotStream: () -> Boolean,
) : DataSource {
    class Factory(
        private val fileFactory: DataSource.Factory,
        private val httpFactory: DataSource.Factory,
        private val localFile: (String, String) -> File?,
        private val remoteUrl: (String, String) -> String?,
        private val cannotStream: () -> Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SpokenDataSource(fileFactory, httpFactory, localFile, remoteUrl, cannotStream)
    }

    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }

    override fun open(dataSpec: DataSpec): Long {
        val (itemId, ino) = SpokenMedia.parse(dataSpec.uri) ?: throw IOException("Not a spoken-word URI")
        val file = localFile(itemId, ino)
        val (source, spec) = when {
            file != null -> fileFactory.createDataSource() to dataSpec.buildUpon().setUri(Uri.fromFile(file)).build()
            cannotStream() -> throw SongDataSource.OfflineException("$itemId/$ino")
            else -> {
                val url = remoteUrl(itemId, ino) ?: throw IOException("Not signed in to Audiobookshelf")
                httpFactory.createDataSource() to dataSpec.buildUpon().setUri(url).build()
            }
        }
        listeners.forEach(source::addTransferListener)
        delegate = source
        return source.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { try { delegate?.close() } finally { delegate = null } }
}

/**
 * Records listening on this phone while spoken word plays: the position every few seconds (and on
 * pause), and a listening session for the server's stats. A new session starts per item, or after a
 * long pause. Real time heard is counted, not media time (2× speed for a minute = one minute).
 */
class ListeningTracker(private val progress: SpokenProgress) {
    private var now: SpokenNow? = null
    private var sessionId: String? = null
    private var sessionStart = 0.0
    private var sessionStartedAt = 0L
    private var listenedMs = 0L
    private var lastPosSec = 0.0
    private var finished = false
    private var lastSaveAt = 0L
    private var lastSessionAt = 0L
    private var pausedAt = 0L
    private var lastFlushAt = 0L

    fun begin(item: SpokenNow, positionSec: Double, alreadyFinished: Boolean) {
        now = item
        sessionId = null
        lastPosSec = positionSec
        finished = alreadyFinished
    }

    fun end() { now = null; sessionId = null }

    val current: SpokenNow? get() = now

    /** Called about once a second while playing. Returns true when the item just reached its end. */
    suspend fun tick(positionSec: Double, elapsedMs: Long) {
        val item = now ?: return
        val wallNow = System.currentTimeMillis()
        if (sessionId == null || (pausedAt > 0 && wallNow - pausedAt > NEW_SESSION_AFTER_PAUSE_MS)) {
            sessionId = UUID.randomUUID().toString()
            sessionStart = positionSec
            sessionStartedAt = progress.serverNow()
            listenedMs = 0
        }
        pausedAt = 0
        listenedMs += elapsedMs
        lastPosSec = positionSec
        if (!finished && im.flume.hearth.spoken.ProgressMerge.reachedEnd(positionSec, item.timeline.durationSec)) {
            finished = true
            save(item)
        } else if (wallNow - lastSaveAt >= SAVE_EVERY_MS) save(item)
        if (wallNow - lastSessionAt >= SESSION_EVERY_MS) {
            saveSession(item)
            lastSessionAt = wallNow
            // While online, keep the server (and so the other phone) roughly up to date.
            if (wallNow - lastFlushAt >= FLUSH_EVERY_MS) { lastFlushAt = wallNow; progress.flushSoon() }
        }
    }

    /** Paused, stopped or skipped: save everything now and try to send it. */
    suspend fun pause(positionSec: Double?, send: Boolean = true) {
        val item = now ?: return
        positionSec?.let { lastPosSec = it }
        pausedAt = System.currentTimeMillis()
        save(item)
        saveSession(item)
        lastSessionAt = pausedAt
        if (send) progress.flushSoon()
    }

    /** A seek while paused should still be remembered. */
    suspend fun seeked(positionSec: Double) {
        val item = now ?: return
        lastPosSec = positionSec
        save(item)
    }

    private suspend fun save(item: SpokenNow) {
        lastSaveAt = System.currentTimeMillis()
        progress.record(item.libraryItemId, item.episodeId, lastPosSec, item.timeline.durationSec)
        if (im.flume.hearth.spoken.ProgressMerge.reachedEnd(lastPosSec, item.timeline.durationSec)) finished = true
    }

    private suspend fun saveSession(item: SpokenNow) {
        val id = sessionId ?: return
        val serverNow = progress.serverNow()
        val local = Date()
        progress.recordSession(
            LocalSessionPayload.Session(
                id = id, libraryId = item.libraryId, libraryItemId = item.libraryItemId, episodeId = item.episodeId,
                mediaType = if (item.isBook) "book" else "podcast", displayTitle = item.title, displayAuthor = item.author,
                duration = item.timeline.durationSec, startTime = sessionStart, currentTime = lastPosSec,
                timeListening = listenedMs / 1000.0, startedAt = sessionStartedAt, updatedAt = serverNow,
                date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(local),
                dayOfWeek = SimpleDateFormat("EEEE", Locale.US).format(local),
            ),
            finished,
        )
    }

    private companion object {
        const val SAVE_EVERY_MS = 5_000L
        const val SESSION_EVERY_MS = 30_000L
        const val NEW_SESSION_AFTER_PAUSE_MS = 10 * 60_000L
        const val FLUSH_EVERY_MS = 60_000L
    }
}
