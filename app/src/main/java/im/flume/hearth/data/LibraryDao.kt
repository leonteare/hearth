// Room queries for the music library: songs, albums, artists, playlists, play history and pending scrobbles.
package im.flume.hearth.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
    @Query("SELECT songId FROM playlist_songs WHERE playlistId IN (:ids)") suspend fun songIdsInPlaylists(ids: List<String>): List<String>
    @Query("SELECT id FROM songs WHERE starred = 1") suspend fun starredSongIds(): List<String>

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
