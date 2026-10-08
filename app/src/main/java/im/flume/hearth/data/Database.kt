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

    @Query("SELECT id FROM songs WHERE id IN (SELECT songId FROM downloads WHERE state = 'DONE')")
    suspend fun downloadedSongIds(): List<String>

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

    @Query("SELECT s.* FROM downloads d JOIN songs s ON s.id = d.songId ORDER BY s.artist, s.album, s.disc, s.track")
    fun downloadedOrQueuedSongs(): Flow<List<SongEntity>>

    @Query("SELECT s.* FROM downloads d JOIN songs s ON s.id = d.songId WHERE d.state = 'DONE' ORDER BY s.artist, s.album, s.disc, s.track")
    suspend fun downloadedSongsOnce(): List<SongEntity>

    @Query("SELECT s.* FROM downloads d JOIN songs s ON s.id = d.songId WHERE d.state = 'DONE' ORDER BY s.artist, s.album, s.disc, s.track")
    fun downloadedSongs(): Flow<List<SongEntity>>

    /** Not-yet-finished downloads: the one in progress first, then the queue in order, failures last. */
    @Query(
        """SELECT s.* FROM downloads d JOIN songs s ON s.id = d.songId WHERE d.state != 'DONE'
           ORDER BY CASE d.state WHEN 'DOWNLOADING' THEN 0 WHEN 'QUEUED' THEN 1 ELSE 2 END, d.addedAt"""
    )
    fun pendingDownloadSongs(): Flow<List<SongEntity>>

    @Query(
        """SELECT * FROM songs
           WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' OR album LIKE '%' || :q || '%'
           ORDER BY CASE WHEN title LIKE :q || '%' THEN 0 WHEN title LIKE '%' || :q || '%' THEN 1 ELSE 2 END, playCount DESC
           LIMIT :limit"""
    )
    suspend fun searchSongs(q: String, limit: Int): List<SongEntity>

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

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year DESC, name")
    fun artistAlbums(artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums ORDER BY created DESC LIMIT :limit")
    fun recentlyAdded(limit: Int): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE playCount > 0 ORDER BY playCount DESC LIMIT :limit")
    fun mostPlayed(limit: Int): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomAlbums(limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id IN (:ids)")
    suspend fun albumsByIds(ids: List<String>): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE name LIKE '%' || :q || '%' ORDER BY playCount DESC LIMIT :limit")
    suspend fun searchAlbums(q: String, limit: Int): List<AlbumEntity>

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

    @Query("SELECT * FROM artists WHERE name LIKE '%' || :q || '%' ORDER BY albumCount DESC LIMIT :limit")
    suspend fun searchArtists(q: String, limit: Int): List<ArtistEntity>

    // --- playlists ---
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun playlist(id: String): Flow<PlaylistEntity?>

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

    @Query("DELETE FROM play_history") suspend fun clearHistory()
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

    @Query("SELECT * FROM pinned") suspend fun pinned(): List<PinnedEntity>
    @Query("SELECT * FROM pinned") fun pinnedFlow(): Flow<List<PinnedEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun pin(item: PinnedEntity)
    @Query("DELETE FROM pinned WHERE kind = :kind AND id = :id") suspend fun unpin(kind: String, id: String)
    @Query("DELETE FROM pinned") suspend fun clearPinned()
}

class Converters {
    @TypeConverter fun fromState(s: DownloadState): String = s.name
    @TypeConverter fun toState(s: String): DownloadState = DownloadState.valueOf(s)
}

@Database(
    entities = [
        SongEntity::class, AlbumEntity::class, ArtistEntity::class, PlaylistEntity::class,
        PlaylistSongEntity::class, DownloadEntity::class, PinnedEntity::class,
        PlayHistoryEntity::class, PendingScrobbleEntity::class, LyricsEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun downloads(): DownloadDao
    abstract fun lyrics(): LyricsDao

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

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "hearth.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
