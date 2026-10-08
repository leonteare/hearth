package im.flume.hearth.data

import android.os.Bundle
import im.flume.hearth.api.NavidromeNativeApi
import im.flume.hearth.api.SubsonicClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import im.flume.hearth.download.DownloadRepository

/** Something the user can press play on. Converted to a Bundle to travel to the playback service. */
data class PlaySource(val kind: Kind, val id: String? = null, val label: String = "", val songIds: List<String> = emptyList()) {
    enum class Kind { ALL, MY_LIBRARY, ALBUM, ARTIST, PLAYLIST, GENRE, LIKED, DOWNLOADS, SONGS }

    fun toBundle() = Bundle().apply {
        putString("kind", kind.name)
        putString("id", id)
        putString("label", label)
        putStringArrayList("songIds", ArrayList(songIds))
    }

    companion object {
        fun fromBundle(b: Bundle) = PlaySource(
            kind = Kind.entries.firstOrNull { it.name == b.getString("kind") } ?: Kind.ALL,
            id = b.getString("id"),
            label = b.getString("label").orEmpty(),
            songIds = b.getStringArrayList("songIds").orEmpty(),
        )

        val All = PlaySource(Kind.ALL, label = "Everything")
        /** Liked songs and saved albums; falls back to everything when nothing's been saved yet. */
        val MyLibrary = PlaySource(Kind.MY_LIBRARY, label = "Your library")
        val Liked = PlaySource(Kind.LIKED, label = "Liked Songs")
        val Downloads = PlaySource(Kind.DOWNLOADS, label = "Downloads")
    }
}

class LibraryRepository(
    private val db: AppDatabase,
    private val api: SubsonicClient,
    private val downloads: DownloadRepository,
    private val session: SessionStore,
    private val native: NavidromeNativeApi,
) {
    val dao = db.library()

    /** Every playlist with what you may do with it; screens filter with [PlaylistRules]. */
    val playlistItems: Flow<List<PlaylistItem>> =
        combine(dao.playlists(), session.credentials, session.playlistDecisions) { all, creds, decisions ->
            PlaylistRules.items(all, creds?.username, decisions)
        }

    fun playlistItem(id: String): Flow<PlaylistItem?> =
        combine(dao.playlist(id), session.credentials, session.playlistDecisions) { p, creds, decisions ->
            p?.let { PlaylistItem(it, PlaylistRules.access(it, creds?.username, decisions)) }
        }

    /** Songs for [source] in their natural order. When [offlineOnly], keeps just downloaded songs. */
    suspend fun resolve(source: PlaySource, offlineOnly: Boolean): List<SongEntity> {
        val songs = when (source.kind) {
            PlaySource.Kind.ALL -> if (offlineOnly) dao.downloadedSongsOnce() else songsByIds(dao.allSongIds())
            PlaySource.Kind.MY_LIBRARY -> {
                val mine = dao.myLibrarySongsOnce().let { if (offlineOnly) it.filter { s -> downloads.isDownloaded(s.id) } else it }
                return mine.ifEmpty { resolve(PlaySource.All, offlineOnly) }
            }
            PlaySource.Kind.ALBUM -> dao.albumSongsOnce(source.id!!)
            PlaySource.Kind.ARTIST -> dao.artistSongsOnce(source.id!!)
            PlaySource.Kind.PLAYLIST -> dao.playlistSongsOnce(source.id!!)
            PlaySource.Kind.GENRE -> dao.genreSongsOnce(source.id!!)
            PlaySource.Kind.LIKED -> dao.starredSongsOnce()
            PlaySource.Kind.DOWNLOADS -> dao.downloadedSongsOnce()
            PlaySource.Kind.SONGS -> songsByIds(source.songIds)
        }
        return if (offlineOnly) songs.filter { downloads.isDownloaded(it.id) } else songs
    }

    private var index: Pair<Long, SearchIndex>? = null

    /** Search index over the local library, rebuilt after each sync. */
    suspend fun searchIndex(syncedAt: Long): SearchIndex {
        index?.takeIf { it.first == syncedAt }?.let { return it.second }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            SearchIndex(dao.allSongsOnce(), dao.allAlbumsOnce(), dao.allArtistsOnce())
        }.also { index = syncedAt to it }
    }

    /** Looks up songs keeping the order of [ids]. Chunked to stay under SQLite's variable limit. */
    suspend fun songsByIds(ids: List<String>): List<SongEntity> {
        val byId = HashMap<String, SongEntity>(ids.size)
        ids.chunked(900).forEach { chunk -> dao.songsByIds(chunk).forEach { byId[it.id] = it } }
        return ids.mapNotNull(byId::get)
    }

    suspend fun setStarred(songId: String, starred: Boolean) {
        dao.setStarred(songId, starred)
        runCatching { if (starred) api.star(songId) else api.unstar(songId) }
            .onFailure { dao.setStarred(songId, !starred); throw it }
        if (starred) downloads.refreshPinned()
    }

    /** Saves an album to (or removes it from) Your Library. Updates the row first; reverts and throws on failure. */
    suspend fun setAlbumSaved(albumId: String, saved: Boolean) {
        dao.setAlbumStarred(albumId, saved)
        runCatching { if (saved) api.starAlbum(albumId) else api.unstarAlbum(albumId) }
            .onFailure { dao.setAlbumStarred(albumId, !saved); throw it }
    }

    /** Follows or unfollows an artist. Same optimistic update and revert as [setAlbumSaved]. */
    suspend fun setArtistFollowed(artistId: String, followed: Boolean) {
        dao.setArtistStarred(artistId, followed)
        runCatching { if (followed) api.starArtist(artistId) else api.unstarArtist(artistId) }
            .onFailure { dao.setArtistStarred(artistId, !followed); throw it }
    }

    /**
     * One-off when this version first runs: albums kept for offline become saved albums, so the new
     * personal Albums tab doesn't open empty. Returns false (try again next launch) if any star failed.
     */
    suspend fun saveDownloadedAlbums(): Boolean {
        val saved = dao.savedAlbumIds().toHashSet()
        val pinned = db.downloads().pinned().filter { it.kind == DownloadRepository.KIND_ALBUM }.map { it.id }
        var ok = true
        for (id in pinned) {
            if (id in saved) continue
            runCatching { setAlbumSaved(id, true) }.onFailure { ok = false }
        }
        return ok
    }

    /**
     * Songs for a radio station: the seed first, then similar songs from Navidrome (via Last.fm),
     * falling back to the same artist and genre from the local library when there are none.
     */
    suspend fun radio(seed: SongEntity?, artistId: String?, online: Boolean): List<String> {
        val remote = if (online) runCatching {
            if (seed != null) api.similarSongs(seed.id) else api.similarSongsForArtist(artistId!!)
        }.getOrDefault(emptyList()) else emptyList()
        val known = songsByIds(remote.map { it.id }).map { it.id }
        val ids = if (known.size >= 10) known else {
            val local = dao.radioFallback(seed?.artistId ?: artistId, seed?.genre, seed?.id ?: "", 60)
            (known + local.map { it.id }).distinct()
        }
        return (listOfNotNull(seed?.id) + ids.filter { it != seed?.id }).let {
            if (online) it else it.filter(downloads::isDownloaded)
        }
    }

    suspend fun refreshPlaylist(id: String) {
        val pl = api.playlist(id) ?: return
        dao.replacePlaylist(pl.toEntity(), pl.entry.mapIndexed { i, s -> PlaylistSongEntity(id, i, s.id) })
        downloads.refreshPinned()
    }

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>) {
        api.addToPlaylist(playlistId, songIds)
        refreshPlaylist(playlistId)
    }

    suspend fun createPlaylist(name: String, songIds: List<String>) {
        val id = api.createPlaylist(name, songIds) ?: api.playlists().firstOrNull { it.name == name }?.id ?: return
        refreshPlaylist(id)
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        api.renamePlaylist(playlistId, name)
        refreshPlaylist(playlistId)
    }

    suspend fun setPlaylistSongs(playlistId: String, songIds: List<String>) {
        api.setPlaylistSongs(playlistId, songIds)
        refreshPlaylist(playlistId)
    }

    suspend fun deletePlaylist(playlistId: String) {
        api.deletePlaylist(playlistId)
        dao.clearPlaylistSongs(playlistId)
        dao.deletePlaylistRow(playlistId)
    }

    suspend fun removeFromPlaylist(playlistId: String, index: Int) {
        api.removeFromPlaylist(playlistId, index)
        refreshPlaylist(playlistId)
    }

    /**
     * Changes who a playlist is shared with. Re-reads the comment first so a change made on the other
     * phone a moment ago isn't overwritten, then writes it back with public on while anyone's invited.
     */
    suspend fun updateSharing(playlistId: String, change: (Sharing) -> Sharing) {
        val pl = api.playlist(playlistId) ?: throw java.io.IOException("Playlist not found")
        val parsed = PlaylistSharing.parse(pl.comment)
        val current = parsed.sharing ?: Sharing()
        val next = change(current.copy(owner = current.owner ?: pl.owner?.lowercase()))
        api.setPlaylistComment(playlistId, PlaylistSharing.serialize(parsed.text, next), public = !next.isEmpty)
        refreshPlaylist(playlistId)
    }

    /**
     * Accepts or declines an invite (declining also covers leaving). The answer is kept on this phone
     * first, so it sticks even if the server won't take it (e.g. the account isn't an admin).
     * Returns false when only the local copy could be saved.
     */
    suspend fun answerInvite(playlistId: String, accept: Boolean): Boolean {
        val me = session.credentials.value?.username ?: return false
        session.setPlaylistDecision(playlistId, accept)
        val saved = runCatching { updateSharing(playlistId) { if (accept) it.accept(me) else it.remove(me) } }.isSuccess
        // Once the server has the answer the local copy isn't needed, so a later re-invite still shows up.
        if (saved) session.setPlaylistDecision(playlistId, null)
        return saved
    }

    suspend fun setPlaylistPhoto(playlistId: String, jpeg: ByteArray) {
        native.uploadPlaylistImage(playlistId, jpeg)
        // The cover id changes with the photo, so re-reading the playlist also refreshes cached covers.
        refreshPlaylist(playlistId)
    }

    suspend fun removePlaylistPhoto(playlistId: String) {
        native.removePlaylistImage(playlistId)
        refreshPlaylist(playlistId)
    }

    /** Server's "recently played" albums, mapped onto local rows so covers and counts are consistent. */
    suspend fun serverRecentAlbums(limit: Int): List<AlbumEntity> {
        val remote = api.albumList("recent", limit)
        val local = dao.albumsByIds(remote.map { it.id }).associateBy { it.id }
        return remote.map { local[it.id] ?: it.toEntity() }
    }
}

/** The personal side of the library. Kept free of Android so it can be unit tested. */
object YourLibrary {
    /**
     * Artists on the Library tab: ones you follow plus the artists of albums you saved, A–Z.
     * Artists of liked songs are left out on purpose; that would be nearly everyone.
     */
    fun artists(all: List<ArtistEntity>, savedAlbums: List<AlbumEntity>): List<ArtistEntity> {
        val fromAlbums = savedAlbums.mapNotNullTo(HashSet()) { it.artistId }
        return all.filter { it.starred || it.id in fromAlbums }
    }
}
