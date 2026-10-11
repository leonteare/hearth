package im.flume.hearth.spoken

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Podcasts and audiobooks from Audiobookshelf, kept on the phone for an instant UI and offline use.
// Ids are Audiobookshelf's: a podcast or book is a "library item"; episodes have their own ids. Where a
// key needs an episode id, books use "" (Room can't have NULL in a primary key).

/** A podcast in the Podcasts library. Who follows it lives in [tags] ("hearth-follower:<username>"). */
@Entity(tableName = "abs_podcasts")
data class PodcastEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val title: String,
    val author: String?,
    val description: String?,
    val feedUrl: String?,
    val imageUrl: String?,
    val language: String?,
    val explicit: Boolean,
    /** The server's tags, one per line. */
    val tags: String,
    val autoDownloadEpisodes: Boolean,
    val numEpisodes: Int,
    val addedAt: Long,
    /** The server's updatedAt; episodes are fetched again when it changes. */
    val updatedAt: Long,
    /** When the episode list was last fetched (0 = never). */
    val episodesFetchedAt: Long,
) {
    val tagList: List<String> get() = tags.split('\n').filter { it.isNotBlank() }
}

/** An episode the server has downloaded (only those can be played). */
@Entity(tableName = "abs_episodes", indices = [Index("libraryItemId"), Index("publishedAt")])
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val libraryItemId: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    /** Epoch ms; falls back to when the server added it. */
    val publishedAt: Long,
    val durationSec: Double,
    val sizeBytes: Long,
    /** The audio file's id on the server (null if the episode has no audio file). */
    val ino: String?,
    val mimeType: String?,
    val season: String?,
    val episode: String?,
    val episodeType: String?,
    val addedAt: Long,
)

@Entity(tableName = "abs_books")
data class BookEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val title: String,
    val subtitle: String?,
    val author: String?,
    val narrator: String?,
    val series: String?,
    val description: String?,
    val publishedYear: String?,
    val durationSec: Double,
    val sizeBytes: Long,
    val numTracks: Int,
    val numChapters: Int,
    val tags: String,
    val addedAt: Long,
    val updatedAt: Long,
    /** When tracks and chapters were last fetched (0 = never). */
    val detailsFetchedAt: Long,
)

/** One audio file of a book, in play order. [startOffsetSec] is where it starts on the book's timeline. */
@Entity(tableName = "abs_tracks", primaryKeys = ["libraryItemId", "trackIndex"])
data class TrackEntity(
    val libraryItemId: String,
    val trackIndex: Int,
    val ino: String,
    val startOffsetSec: Double,
    val durationSec: Double,
    val mimeType: String?,
    val sizeBytes: Long,
)

/** A chapter of a book ([episodeId] "") or episode. Times are on the item's whole timeline. */
@Entity(tableName = "abs_chapters", primaryKeys = ["libraryItemId", "episodeId", "chapterIndex"])
data class ChapterEntity(
    val libraryItemId: String,
    val episodeId: String,
    val chapterIndex: Int,
    val startSec: Double,
    val endSec: Double,
    val title: String,
)

/**
 * This user's progress on a book ([episodeId] "") or episode. [lastUpdate] is on the server's clock
 * (see ServerClock), so it compares fairly with the server's and other phones' timestamps.
 */
@Entity(tableName = "abs_progress", primaryKeys = ["libraryItemId", "episodeId"])
data class SpokenProgressEntity(
    val libraryItemId: String,
    val episodeId: String,
    val currentTime: Double,
    val duration: Double,
    val isFinished: Boolean,
    val lastUpdate: Long,
    val finishedAt: Long?,
    /** Changed on this phone and not yet confirmed by the server. */
    val dirty: Boolean,
    /** The user marked it unplayed on this phone; beats "finished" from elsewhere until it's synced. */
    val markedUnplayed: Boolean,
)

/**
 * Progress updates waiting to reach the server, sent in [seq] order. One row per listening session
 * ("session:<id>", replaced as the session grows) or per explicit change ("progress:<item>/<episode>").
 */
@Entity(tableName = "abs_outbox")
data class OutboxEntity(
    @PrimaryKey val key: String,
    val seq: Long,
    /** Whose update this is; only sent while that user is signed in. */
    val userId: String,
    /** [OutboxKind] name. */
    val kind: String,
    val libraryItemId: String,
    val episodeId: String,
    /** JSON body for the server. */
    val payload: String,
    val attempts: Int,
    val createdAt: Long,
)

enum class OutboxKind { SESSION, PROGRESS }

enum class SpokenDownloadState { QUEUED, DOWNLOADING, DONE, FAILED, REMOVED }

/** An episode or whole book kept on this phone. [state] REMOVED remembers "don't auto-download this again". */
@Entity(tableName = "abs_downloads", primaryKeys = ["libraryItemId", "episodeId"])
data class SpokenDownloadEntity(
    val libraryItemId: String,
    val episodeId: String,
    val state: SpokenDownloadState,
    val bytes: Long,
    val totalBytes: Long,
    /** Downloaded automatically (followed show) rather than by hand. */
    val auto: Boolean,
    val addedAt: Long,
    val completedAt: Long,
    val error: String?,
) {
    val isBook: Boolean get() = episodeId.isEmpty()
}

/** Playback speed chosen for a podcast or book, remembered per show. */
@Entity(tableName = "abs_play_prefs")
data class SpokenPlayPrefEntity(
    @PrimaryKey val libraryItemId: String,
    val speed: Float,
)

/** An episode with its show, this user's progress and the phone's download state. */
data class EpisodeRow(
    @Embedded val episode: EpisodeEntity,
    val podcastTitle: String,
    val podcastAuthor: String?,
    val progressSec: Double?,
    val finished: Boolean,
    val progressUpdatedAt: Long?,
    val downloadState: SpokenDownloadState?,
    val downloadedBytes: Long?,
) {
    val started: Boolean get() = (progressSec ?: 0.0) > 0.0 && !finished
}

/** A book with this user's progress and the phone's download state. */
data class BookRow(
    @Embedded val book: BookEntity,
    val progressSec: Double?,
    val finished: Boolean,
    val progressUpdatedAt: Long?,
    val downloadState: SpokenDownloadState?,
    val downloadedBytes: Long?,
)

/** A download with its episode / book title and show / author, for the Downloads page. */
data class SpokenDownloadRow(
    @Embedded val download: SpokenDownloadEntity,
    val title: String?,
    val subtitle: String?,
)

@Dao
interface SpokenDao {
    // --- podcasts ---
    @Query("SELECT * FROM abs_podcasts ORDER BY title COLLATE NOCASE") fun podcasts(): Flow<List<PodcastEntity>>
    @Query("SELECT * FROM abs_podcasts") suspend fun podcastsOnce(): List<PodcastEntity>
    @Query("SELECT * FROM abs_podcasts WHERE id = :id") fun podcast(id: String): Flow<PodcastEntity?>
    @Query("SELECT * FROM abs_podcasts WHERE id = :id") suspend fun podcastOnce(id: String): PodcastEntity?

    /** Podcasts whose tags hold [needle] as a whole line, e.g. "hearth-follower:leon". */
    @Query("SELECT * FROM abs_podcasts WHERE instr(char(10) || tags || char(10), char(10) || :needle || char(10)) > 0 ORDER BY title COLLATE NOCASE")
    fun podcastsTagged(needle: String): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM abs_podcasts WHERE instr(char(10) || tags || char(10), char(10) || :needle || char(10)) > 0")
    suspend fun podcastsTaggedOnce(needle: String): List<PodcastEntity>

    @Upsert suspend fun upsertPodcasts(items: List<PodcastEntity>)
    @Query("UPDATE abs_podcasts SET tags = :tags WHERE id = :id") suspend fun setPodcastTags(id: String, tags: String)
    @Query("UPDATE abs_podcasts SET autoDownloadEpisodes = :enabled WHERE id = :id") suspend fun setPodcastAutoDownload(id: String, enabled: Boolean)
    @Query("UPDATE abs_podcasts SET episodesFetchedAt = :at, updatedAt = :updatedAt WHERE id = :id") suspend fun markEpisodesFetched(id: String, at: Long, updatedAt: Long)
    @Query("DELETE FROM abs_podcasts WHERE id IN (:ids)") suspend fun deletePodcasts(ids: List<String>)

    // --- episodes ---
    @Query(EPISODE_ROW + " WHERE e.libraryItemId = :podcastId ORDER BY e.publishedAt DESC")
    fun episodes(podcastId: String): Flow<List<EpisodeRow>>

    @Query(EPISODE_ROW + " WHERE e.id = :episodeId")
    fun episode(episodeId: String): Flow<EpisodeRow?>

    @Query(EPISODE_ROW + " WHERE e.id = :episodeId")
    suspend fun episodeOnce(episodeId: String): EpisodeRow?

    /** Newest episodes of the shows tagged [needle] (followed), newest first. */
    @Query(EPISODE_ROW + " WHERE instr(char(10) || p.tags || char(10), char(10) || :needle || char(10)) > 0 ORDER BY e.publishedAt DESC LIMIT :limit")
    fun latestEpisodesTagged(needle: String, limit: Int): Flow<List<EpisodeRow>>

    @Query(EPISODE_ROW + " WHERE e.libraryItemId = :podcastId ORDER BY e.publishedAt DESC")
    suspend fun episodesOnce(podcastId: String): List<EpisodeRow>

    @Query(EPISODE_ROW + " ORDER BY e.publishedAt DESC")
    suspend fun allEpisodesOnce(): List<EpisodeRow>

    @Query(
        """SELECT d.*, COALESCE(e.title, b.title) AS title, COALESCE(p.title, b.author) AS subtitle
        FROM abs_downloads d
        LEFT JOIN abs_episodes e ON d.episodeId != '' AND e.id = d.episodeId
        LEFT JOIN abs_podcasts p ON d.episodeId != '' AND p.id = d.libraryItemId
        LEFT JOIN abs_books b ON d.episodeId = '' AND b.id = d.libraryItemId
        WHERE d.state != 'REMOVED' ORDER BY d.addedAt"""
    )
    fun downloadRows(): Flow<List<SpokenDownloadRow>>

    @Query("SELECT id FROM abs_episodes WHERE libraryItemId = :podcastId") suspend fun episodeIds(podcastId: String): List<String>
    @Upsert suspend fun upsertEpisodes(items: List<EpisodeEntity>)
    @Query("DELETE FROM abs_episodes WHERE id IN (:ids)") suspend fun deleteEpisodes(ids: List<String>)
    @Query("DELETE FROM abs_episodes WHERE libraryItemId IN (:podcastIds)") suspend fun deleteEpisodesOf(podcastIds: List<String>)

    // --- books ---
    @Query(BOOK_ROW + " ORDER BY b.title COLLATE NOCASE") fun books(): Flow<List<BookRow>>
    @Query(BOOK_ROW + " WHERE b.id = :id") fun book(id: String): Flow<BookRow?>
    @Query("SELECT * FROM abs_books") suspend fun booksOnce(): List<BookEntity>
    @Query("SELECT * FROM abs_books WHERE id = :id") suspend fun bookOnce(id: String): BookEntity?
    @Upsert suspend fun upsertBooks(items: List<BookEntity>)
    @Query("DELETE FROM abs_books WHERE id IN (:ids)") suspend fun deleteBooks(ids: List<String>)
    @Query("UPDATE abs_books SET detailsFetchedAt = :at, updatedAt = :updatedAt WHERE id = :id") suspend fun markBookFetched(id: String, at: Long, updatedAt: Long)

    @Query("SELECT * FROM abs_tracks WHERE libraryItemId = :id ORDER BY trackIndex") suspend fun tracks(id: String): List<TrackEntity>
    @Query("DELETE FROM abs_tracks WHERE libraryItemId IN (:ids)") suspend fun deleteTracks(ids: List<String>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTracks(items: List<TrackEntity>)

    @Query("SELECT * FROM abs_chapters WHERE libraryItemId = :id AND episodeId = :episodeId ORDER BY chapterIndex")
    suspend fun chapters(id: String, episodeId: String): List<ChapterEntity>
    @Query("SELECT * FROM abs_chapters WHERE libraryItemId = :id AND episodeId = :episodeId ORDER BY chapterIndex")
    fun chaptersFlow(id: String, episodeId: String): Flow<List<ChapterEntity>>
    @Query("DELETE FROM abs_chapters WHERE libraryItemId = :id AND episodeId = :episodeId") suspend fun deleteChapters(id: String, episodeId: String)
    @Query("DELETE FROM abs_chapters WHERE libraryItemId IN (:ids)") suspend fun deleteChaptersOf(ids: List<String>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChapters(items: List<ChapterEntity>)

    @Transaction
    suspend fun replaceBookDetails(book: BookEntity, tracks: List<TrackEntity>, chapters: List<ChapterEntity>) {
        upsertBooks(listOf(book))
        deleteTracks(listOf(book.id)); insertTracks(tracks)
        deleteChapters(book.id, ""); insertChapters(chapters)
    }

    @Transaction
    suspend fun replaceEpisodes(podcastId: String, episodes: List<EpisodeEntity>, chapters: List<ChapterEntity>, removed: List<String>, fetchedAt: Long, updatedAt: Long) {
        if (removed.isNotEmpty()) { deleteEpisodes(removed); removed.forEach { deleteChapters(podcastId, it) } }
        upsertEpisodes(episodes)
        episodes.forEach { deleteChapters(podcastId, it.id) }
        insertChapters(chapters)
        markEpisodesFetched(podcastId, fetchedAt, updatedAt)
    }

    // --- progress ---
    @Query("SELECT * FROM abs_progress WHERE libraryItemId = :id AND episodeId = :episodeId") suspend fun progress(id: String, episodeId: String): SpokenProgressEntity?
    @Query("SELECT * FROM abs_progress WHERE libraryItemId = :id AND episodeId = :episodeId") fun progressFlow(id: String, episodeId: String): Flow<SpokenProgressEntity?>
    @Query("SELECT * FROM abs_progress") suspend fun allProgress(): List<SpokenProgressEntity>
    @Upsert suspend fun upsertProgress(item: SpokenProgressEntity)
    @Upsert suspend fun upsertProgress(items: List<SpokenProgressEntity>)
    @Query("DELETE FROM abs_progress") suspend fun clearProgress()

    /** Started, unfinished episodes, most recently played first. */
    @Query(EPISODE_ROW + " WHERE pr.currentTime > 0 AND pr.isFinished = 0 ORDER BY pr.lastUpdate DESC LIMIT :limit")
    fun episodesInProgress(limit: Int): Flow<List<EpisodeRow>>

    @Query(BOOK_ROW + " WHERE pr.currentTime > 0 AND pr.isFinished = 0 ORDER BY pr.lastUpdate DESC LIMIT :limit")
    fun booksInProgress(limit: Int): Flow<List<BookRow>>

    // --- outbox ---
    @Query("SELECT * FROM abs_outbox WHERE userId = :userId ORDER BY seq") suspend fun outbox(userId: String): List<OutboxEntity>
    @Query("SELECT COUNT(*) FROM abs_outbox WHERE userId = :userId") fun outboxCount(userId: String): Flow<Int>
    @Query("SELECT COALESCE(MAX(seq), 0) FROM abs_outbox") suspend fun maxSeq(): Long
    @Query("SELECT * FROM abs_outbox WHERE `key` = :key") suspend fun outboxRow(key: String): OutboxEntity?
    @Upsert suspend fun upsertOutbox(item: OutboxEntity)
    @Query("DELETE FROM abs_outbox WHERE `key` = :key AND seq = :seq") suspend fun deleteOutbox(key: String, seq: Long)
    @Query("UPDATE abs_outbox SET attempts = attempts + 1 WHERE `key` = :key") suspend fun bumpOutboxAttempts(key: String)
    @Query("SELECT COUNT(*) FROM abs_outbox WHERE libraryItemId = :id AND episodeId = :episodeId") suspend fun outboxCountFor(id: String, episodeId: String): Int
    @Query("UPDATE abs_progress SET dirty = 0, markedUnplayed = 0 WHERE libraryItemId = :id AND episodeId = :episodeId") suspend fun markProgressSynced(id: String, episodeId: String)

    /** Adds or replaces an outbox row, moving it to the back of the line (it's the newest change). */
    @Transaction
    suspend fun enqueueOutbox(row: OutboxEntity) {
        val existing = outboxRow(row.key)
        upsertOutbox(row.copy(seq = maxSeq() + 1, createdAt = existing?.createdAt ?: row.createdAt))
    }

    // --- downloads ---
    @Query("SELECT * FROM abs_downloads") fun downloads(): Flow<List<SpokenDownloadEntity>>
    @Query("SELECT * FROM abs_downloads") suspend fun downloadsOnce(): List<SpokenDownloadEntity>
    @Query("SELECT * FROM abs_downloads WHERE libraryItemId = :id AND episodeId = :episodeId") suspend fun download(id: String, episodeId: String): SpokenDownloadEntity?
    @Query("SELECT * FROM abs_downloads WHERE state = 'QUEUED' ORDER BY addedAt LIMIT 1") suspend fun nextQueuedDownload(): SpokenDownloadEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM abs_downloads WHERE state = 'QUEUED')") suspend fun hasQueuedDownloads(): Boolean
    @Upsert suspend fun upsertDownload(item: SpokenDownloadEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDownloadIfAbsent(item: SpokenDownloadEntity): Long
    @Query("DELETE FROM abs_downloads WHERE libraryItemId = :id AND episodeId = :episodeId") suspend fun deleteDownload(id: String, episodeId: String)
    @Query("UPDATE abs_downloads SET state = 'QUEUED' WHERE state = 'DOWNLOADING'") suspend fun requeueInterruptedDownloads()
    @Query("UPDATE abs_downloads SET state = 'QUEUED', error = NULL WHERE state = 'FAILED'") suspend fun requeueFailedDownloads()
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM abs_downloads WHERE state = 'DONE'") fun downloadedBytes(): Flow<Long>

    // --- play prefs ---
    @Query("SELECT speed FROM abs_play_prefs WHERE libraryItemId = :id") suspend fun speed(id: String): Float?
    @Upsert suspend fun setSpeed(item: SpokenPlayPrefEntity)

    companion object {
        const val EPISODE_ROW = """SELECT e.*, p.title AS podcastTitle, p.author AS podcastAuthor, pr.currentTime AS progressSec,
            COALESCE(pr.isFinished, 0) AS finished, pr.lastUpdate AS progressUpdatedAt, d.state AS downloadState, d.bytes AS downloadedBytes
            FROM abs_episodes e JOIN abs_podcasts p ON p.id = e.libraryItemId
            LEFT JOIN abs_progress pr ON pr.libraryItemId = e.libraryItemId AND pr.episodeId = e.id
            LEFT JOIN abs_downloads d ON d.libraryItemId = e.libraryItemId AND d.episodeId = e.id"""

        const val BOOK_ROW = """SELECT b.*, pr.currentTime AS progressSec, COALESCE(pr.isFinished, 0) AS finished,
            pr.lastUpdate AS progressUpdatedAt, d.state AS downloadState, d.bytes AS downloadedBytes
            FROM abs_books b
            LEFT JOIN abs_progress pr ON pr.libraryItemId = b.id AND pr.episodeId = ''
            LEFT JOIN abs_downloads d ON d.libraryItemId = b.id AND d.episodeId = ''"""
    }
}
