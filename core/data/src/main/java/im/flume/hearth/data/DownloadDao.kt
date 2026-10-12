// Room queries for song downloads, per-song download choices and pinned collections,
// plus the small rows those queries return.
package im.flume.hearth.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads") fun all(): Flow<List<DownloadEntity>>
    /** Just what rows show: Room re-runs this on every write to the table, progress included. */
    @Query("SELECT songId, state FROM downloads") fun states(): Flow<List<DownloadStateRow>>
    @Query("SELECT * FROM downloads") suspend fun allOnce(): List<DownloadEntity>
    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY addedAt LIMIT 1") suspend fun nextQueued(): DownloadEntity?
    @Query("SELECT * FROM downloads WHERE state = 'FAILED' ORDER BY addedAt") suspend fun failed(): List<DownloadEntity>
    @Query("SELECT * FROM downloads WHERE songId = :songId") suspend fun get(songId: String): DownloadEntity?
    @Upsert suspend fun upsert(item: DownloadEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(items: List<DownloadEntity>)
    @Query("DELETE FROM downloads WHERE songId IN (:ids)") suspend fun delete(ids: List<String>)
    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE state = 'QUEUED')") suspend fun hasQueued(): Boolean
    @Query("UPDATE downloads SET state = 'QUEUED' WHERE state = 'FAILED'") suspend fun requeueFailed()
    @Query("UPDATE downloads SET state = 'QUEUED' WHERE state = 'FAILED' AND songId IN (:ids)") suspend fun requeueFailed(ids: List<String>)
    /** Only safe when no download lane is running: otherwise two lanes could fetch the same song. */
    @Query("UPDATE downloads SET state = 'QUEUED' WHERE state = 'DOWNLOADING'") suspend fun requeueInterrupted()
    @Query("SELECT songId FROM downloads WHERE state != 'DONE'") suspend fun pendingIds(): List<String>
    @Query("DELETE FROM downloads WHERE state != 'DONE'") suspend fun deletePending()
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM downloads WHERE state = 'DONE'") fun totalBytes(): Flow<Long>
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM downloads WHERE state = 'DONE' AND songId IN (:ids)") suspend fun bytesOf(ids: List<String>): Long

    /** How many songs are waiting, downloading and failed: drives the Downloads entry points. */
    @Query(
        """SELECT COALESCE(SUM(CASE WHEN state = 'QUEUED' THEN 1 ELSE 0 END), 0) AS queued,
                  COALESCE(SUM(CASE WHEN state = 'DOWNLOADING' THEN 1 ELSE 0 END), 0) AS downloading,
                  COALESCE(SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed
           FROM downloads"""
    )
    fun counts(): Flow<DownloadCounts>

    /** Downloads of songs the server no longer has (no row in songs). */
    @Query("SELECT songId FROM downloads WHERE songId NOT IN (SELECT id FROM songs)") suspend fun orphanIds(): List<String>

    // --- per-song preferences (see SongDownloadPrefEntity) ---
    @Query("SELECT * FROM song_download_prefs") suspend fun prefs(): List<SongDownloadPrefEntity>
    @Upsert suspend fun upsertPrefs(items: List<SongDownloadPrefEntity>)
    @Query("DELETE FROM song_download_prefs WHERE songId IN (:ids)") suspend fun deletePrefs(ids: List<String>)
    @Query("DELETE FROM song_download_prefs WHERE wanted = 0 AND songId IN (:ids)") suspend fun clearRemoved(ids: List<String>)
    @Query("DELETE FROM song_download_prefs") suspend fun clearPrefs()

    @Query("SELECT * FROM pinned") suspend fun pinned(): List<PinnedEntity>
    @Query("SELECT * FROM pinned") fun pinnedFlow(): Flow<List<PinnedEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun pin(item: PinnedEntity)
    @Query("DELETE FROM pinned WHERE kind = :kind AND id = :id") suspend fun unpin(kind: String, id: String)
    @Query("DELETE FROM pinned") suspend fun clearPinned()
}

data class DownloadStateRow(val songId: String, val state: DownloadState)

data class DownloadCounts(val queued: Int = 0, val downloading: Int = 0, val failed: Int = 0) {
    /** Songs still to come (waiting or on their way). */
    val left: Int get() = queued + downloading
    /** True while there's something to look at on the Downloads page. */
    val active: Boolean get() = left > 0 || failed > 0
    /** Short label for the Downloads shortcut, e.g. "Downloading · 34 left". */
    val summary: String get() = when {
        left > 0 -> "Downloading · $left left"
        failed > 0 -> "Downloads · $failed couldn't download"
        else -> "Downloads"
    }
}
