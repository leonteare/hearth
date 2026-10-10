package im.flume.hearth.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    // --- songs ---
    @Query("SELECT COUNT(*) FROM songs")
    fun songCount(): Flow<Int>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun song(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :id")
    fun songFlow(id: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun songsByIds(ids: List<String>): List<SongEntity>

    @Query("SELECT id FROM songs")
    suspend fun allSongIds(): List<String>

    @Query("SELECT * FROM songs")
    suspend fun allSongsOnce(): List<SongEntity>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun allSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM albums") suspend fun allAlbumsOnce(): List<AlbumEntity>
    @Query("SELECT * FROM artists") suspend fun allArtistsOnce(): List<ArtistEntity>

    @Query(
        """SELECT h.songId AS songId, h.playedAt AS playedAt, s.title AS title, s.artist AS artist, s.artistId AS artistId,
                  s.album AS album, s.albumId AS albumId, s.durationSec AS durationSec, s.coverArt AS coverArt
           FROM play_history h JOIN songs s ON s.id = h.songId
           WHERE h.playedAt >= :from AND h.playedAt < :to ORDER BY h.playedAt"""
    )
    suspend fun history(from: Long, to: Long): List<HistoryRow>

    @Query("SELECT songId FROM play_history WHERE playedAt >= :since")
    suspend fun recentHistorySongIds(since: Long): List<String>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY disc, track, title")
    fun albumSongs(albumId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY disc, track, title")
    suspend fun albumSongsOnce(albumId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE artistId = :artistId OR albumId IN (SELECT id FROM albums WHERE artistId = :artistId) ORDER BY playCount DESC, title")
    fun artistSongs(artistId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE artistId = :artistId OR albumId IN (SELECT id FROM albums WHERE artistId = :artistId) ORDER BY year, album, disc, track")
    suspend fun artistSongsOnce(artistId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE genre = :genre ORDER BY artist, album, disc, track")
    fun genreSongs(genre: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE genre = :genre ORDER BY artist, album, disc, track")
    suspend fun genreSongsOnce(genre: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE starred = 1 ORDER BY artist, album, disc, track")
    fun starredSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE starred = 1 ORDER BY artist, album, disc, track")
    suspend fun starredSongsOnce(): List<SongEntity>

    @Query("SELECT s.* FROM playlist_songs p JOIN songs s ON s.id = p.songId WHERE p.playlistId = :playlistId ORDER BY p.position")
    fun playlistSongs(playlistId: String): Flow<List<SongEntity>>

    @Query("SELECT s.* FROM playlist_songs p JOIN songs s ON s.id = p.songId WHERE p.playlistId = :playlistId ORDER BY p.position")
    suspend fun playlistSongsOnce(playlistId: String): List<SongEntity>

    /** Every entry with its server position, including songs this phone hasn't synced yet (song is null). */
    @Query("SELECT p.position, p.songId, s.* FROM playlist_songs p LEFT JOIN songs s ON s.id = p.songId WHERE p.playlistId = :playlistId ORDER BY p.position")
    fun playlistEntries(playlistId: String): Flow<List<PlaylistEntry>>

    @Query("SELECT p.position, p.songId, s.* FROM playlist_songs p LEFT JOIN songs s ON s.id = p.songId WHERE p.playlistId = :playlistId ORDER BY p.position")
    suspend fun playlistEntriesOnce(playlistId: String): List<PlaylistEntry>

    /** Adds songs this phone doesn't know yet without touching ones it does (keeps local stars and counts). */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMissingSongs(items: List<SongEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMissingAlbums(items: List<AlbumEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMissingArtists(items: List<ArtistEntity>)

    @Query("SELECT * FROM albums WHERE id = :id") suspend fun albumOnce(id: String): AlbumEntity?

    @Query("SELECT s.* FROM downloads d JOIN songs s ON s.id = d.songId WHERE d.state = 'DONE' ORDER BY s.artist, s.album, s.disc, s.track")
    suspend fun downloadedSongsOnce(): List<SongEntity>

    @Query("SELECT s.* FROM downloads d JOIN songs s ON s.id = d.songId WHERE d.state = 'DONE' ORDER BY s.artist, s.album, s.disc, s.track")
    fun downloadedSongs(): Flow<List<SongEntity>>

    /** Songs that finished downloading since [since], newest first. */
    @Query(
        """SELECT s.* FROM downloads d JOIN songs s ON s.id = d.songId
           WHERE d.state = 'DONE' AND d.completedAt >= :since ORDER BY d.completedAt DESC LIMIT :limit"""
    )
    fun recentlyDownloadedSongs(since: Long, limit: Int): Flow<List<SongEntity>>

    /** Not-yet-finished downloads: the one in progress first, then the queue in order, failures last. */
    @Query(
        """SELECT s.* FROM downloads d JOIN songs s ON s.id = d.songId WHERE d.state != 'DONE'
           ORDER BY CASE d.state WHEN 'DOWNLOADING' THEN 0 WHEN 'QUEUED' THEN 1 ELSE 2 END, d.addedAt"""
    )
    fun pendingDownloadSongs(): Flow<List<SongEntity>>

    @Query("UPDATE songs SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("UPDATE songs SET playCount = playCount + 1 WHERE id = :id")
    suspend fun incrementPlayCount(id: String)

    @Query("SELECT genre, COUNT(*) AS songCount FROM songs WHERE genre IS NOT NULL GROUP BY genre ORDER BY songCount DESC")
    fun genres(): Flow<List<GenreCount>>

    // --- albums ---
    @Query("SELECT * FROM albums WHERE id = :id")
    fun album(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums ORDER BY name COLLATE NOCASE")
    fun albums(): Flow<List<AlbumEntity>>

    /** Albums this user saved (starred) to Your Library. */
    @Query("SELECT * FROM albums WHERE starred = 1 ORDER BY name COLLATE NOCASE")
    fun savedAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT id FROM albums WHERE starred = 1")
    suspend fun savedAlbumIds(): List<String>

    @Query("UPDATE albums SET starred = :starred WHERE id = :id")
    suspend fun setAlbumStarred(id: String, starred: Boolean)

    @Query("UPDATE albums SET starred = 0") suspend fun clearAlbumStars()
    @Query("UPDATE albums SET starred = 1 WHERE id IN (:ids)") suspend fun starAlbums(ids: List<String>)

    /** Liked songs plus every song on a saved album: "my library" for shuffling. */
    @Query("SELECT * FROM songs WHERE starred = 1 OR albumId IN (SELECT id FROM albums WHERE starred = 1) ORDER BY artist, album, disc, track")
    suspend fun myLibrarySongsOnce(): List<SongEntity>

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year DESC, name")
    fun artistAlbums(artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id IN (:ids)")
    suspend fun albumsByIds(ids: List<String>): List<AlbumEntity>


    /** Albums from local play history, most recent first. */
    @Query(
        """SELECT a.* FROM albums a JOIN (SELECT albumId, MAX(playedAt) AS lastPlayed FROM play_history GROUP BY albumId) h
           ON h.albumId = a.id ORDER BY h.lastPlayed DESC LIMIT :limit"""
    )
    fun recentlyPlayedAlbums(limit: Int): Flow<List<AlbumEntity>>

    // --- artists ---
    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE")
    fun artists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    fun artist(id: String): Flow<ArtistEntity?>

    @Query("UPDATE artists SET starred = :starred WHERE id = :id")
    suspend fun setArtistStarred(id: String, starred: Boolean)

    @Query("UPDATE artists SET starred = 0") suspend fun clearArtistStars()
    @Query("UPDATE artists SET starred = 1 WHERE id IN (:ids)") suspend fun starArtists(ids: List<String>)

    /** Replaces album and artist stars with the server's list in one go, so the Library never flickers empty. */
    @Transaction
    suspend fun applyStars(albumIds: List<String>, artistIds: List<String>) {
        clearAlbumStars(); albumIds.chunked(900).forEach { starAlbums(it) }
        clearArtistStars(); artistIds.chunked(900).forEach { starArtists(it) }
    }

    // --- playlists ---
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun playlist(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlistOnce(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists") suspend fun playlistsOnce(): List<PlaylistEntity>

    /** Brings the playlist list in line with the server: [changed] rows get their songs replaced, missing ones go. */
    @Transaction
    suspend fun applyPlaylists(all: List<PlaylistEntity>, changed: Map<String, List<PlaylistSongEntity>>, removed: List<String>) {
        insertPlaylists(all)
        changed.forEach { (id, songs) -> clearPlaylistSongs(id); insertPlaylistSongs(songs) }
        removed.forEach { clearPlaylistSongs(it); deletePlaylistRow(it) }
    }

    // --- bulk sync ---
    @Query("DELETE FROM songs") suspend fun clearSongs()
    @Query("DELETE FROM albums") suspend fun clearAlbums()
    @Query("DELETE FROM artists") suspend fun clearArtists()
    @Query("DELETE FROM playlists") suspend fun clearPlaylists()
    @Query("DELETE FROM playlist_songs") suspend fun clearPlaylistSongs()
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId") suspend fun clearPlaylistSongs(playlistId: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylistRow(id: String)

    @Transaction
    suspend fun replacePlaylist(playlist: PlaylistEntity, songs: List<PlaylistSongEntity>) {
        insertPlaylists(listOf(playlist))
        clearPlaylistSongs(playlist.id)
        insertPlaylistSongs(songs)
    }

    @Query("SELECT * FROM songs WHERE (artistId = :artistId OR genre = :genre) AND id != :exclude ORDER BY RANDOM() LIMIT :limit")
    suspend fun radioFallback(artistId: String?, genre: String?, exclude: String, limit: Int): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSongs(items: List<SongEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAlbums(items: List<AlbumEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertArtists(items: List<ArtistEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPlaylists(items: List<PlaylistEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPlaylistSongs(items: List<PlaylistSongEntity>)

    @Transaction
    suspend fun replaceLibrary(
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        playlists: List<PlaylistEntity>,
        playlistSongs: List<PlaylistSongEntity>,
    ) {
        clearSongs(); insertSongs(songs)
        clearAlbums(); insertAlbums(albums)
        clearArtists(); insertArtists(artists)
        clearPlaylists(); insertPlaylists(playlists)
        clearPlaylistSongs(); insertPlaylistSongs(playlistSongs)
    }

    // --- history / scrobbles ---
    @Insert suspend fun insertHistory(item: PlayHistoryEntity)
    @Insert suspend fun insertPendingScrobble(item: PendingScrobbleEntity)
    @Query("SELECT * FROM pending_scrobbles ORDER BY time") suspend fun pendingScrobbles(): List<PendingScrobbleEntity>
    @Query("DELETE FROM pending_scrobbles WHERE rowId = :rowId") suspend fun deletePendingScrobble(rowId: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads") fun all(): Flow<List<DownloadEntity>>
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

@Dao
interface RequestDao {
    @Query("SELECT * FROM requests ORDER BY requestedAt DESC") fun all(): Flow<List<RequestEntity>>
    @Query("SELECT * FROM requests ORDER BY requestedAt DESC") suspend fun allOnce(): List<RequestEntity>
    @Query("SELECT * FROM requests WHERE status IN ('REQUESTED', 'DOWNLOADING', 'ADDING') ORDER BY requestedAt")
    suspend fun active(): List<RequestEntity>
    @Query("SELECT * FROM requests WHERE type = :type AND tidalId = :tidalId") suspend fun get(type: String, tidalId: String): RequestEntity?
    @Upsert suspend fun upsert(item: RequestEntity)
    @Query("DELETE FROM requests WHERE type = :type AND tidalId = :tidalId") suspend fun delete(type: String, tidalId: String)
    @Query("DELETE FROM requests WHERE status = 'DONE'") suspend fun clearDone()
}

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

class Converters {
    @TypeConverter fun fromState(s: DownloadState): String = s.name
    @TypeConverter fun toState(s: String): DownloadState = DownloadState.valueOf(s)
    @TypeConverter fun fromRequestStatus(s: RequestStatus): String = s.name
    @TypeConverter fun toRequestStatus(s: String): RequestStatus =
        RequestStatus.entries.firstOrNull { it.name == s } ?: RequestStatus.FAILED
}

@Database(
    entities = [
        SongEntity::class, AlbumEntity::class, ArtistEntity::class, PlaylistEntity::class,
        PlaylistSongEntity::class, DownloadEntity::class, PinnedEntity::class,
        PlayHistoryEntity::class, PendingScrobbleEntity::class, LyricsEntity::class,
        SongDownloadPrefEntity::class, RequestEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun downloads(): DownloadDao
    abstract fun lyrics(): LyricsDao
    abstract fun requests(): RequestDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `lyrics` (`songId` TEXT NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN trackGain REAL")
                db.execSQL("ALTER TABLE songs ADD COLUMN albumGain REAL")
                db.execSQL("ALTER TABLE songs ADD COLUMN trackPeak REAL")
            }
        }

        /** Playlist comment (holds sharing details) and public flag. Must match [PlaylistEntity] exactly. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach(db::execSQL)
            }
        }

        val MIGRATION_3_4_SQL = listOf(
            "ALTER TABLE playlists ADD COLUMN comment TEXT",
            "ALTER TABLE playlists ADD COLUMN isPublic INTEGER NOT NULL DEFAULT 0",
        )

        /** When each download finished, and per-song download choices. Must match the entities exactly. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_5_SQL.forEach(db::execSQL)
            }
        }

        val MIGRATION_4_5_SQL = listOf(
            "ALTER TABLE downloads ADD COLUMN completedAt INTEGER NOT NULL DEFAULT 0",
            "CREATE TABLE IF NOT EXISTS `song_download_prefs` (`songId` TEXT NOT NULL, `wanted` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
        )

        /** Song ISRCs (to spot music already on the server) and the Tidarr requests table. Must match the entities exactly. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_5_6_SQL.forEach(db::execSQL)
            }
        }

        val MIGRATION_5_6_SQL = listOf(
            "ALTER TABLE songs ADD COLUMN isrc TEXT",
            "CREATE TABLE IF NOT EXISTS `requests` (`tidalId` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `coverUuid` TEXT, `isrc` TEXT, `albumTitle` TEXT, `durationSec` INTEGER, `requestedAt` INTEGER NOT NULL, `requestedBy` TEXT NOT NULL, `status` TEXT NOT NULL, `errorMessage` TEXT, `matchedIds` TEXT, `updatedAt` INTEGER NOT NULL, `seenInQueue` INTEGER NOT NULL, `finishedAt` INTEGER, `lookupAttempts` INTEGER NOT NULL, PRIMARY KEY(`type`, `tidalId`))",
        )

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "hearth.db")
                // No destructive fallback: a missing migration should fail loudly, not wipe downloads and history.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
