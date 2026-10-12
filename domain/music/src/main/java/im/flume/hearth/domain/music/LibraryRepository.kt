package im.flume.hearth.domain.music

import im.flume.hearth.core.network.NavidromeNativeApi
import im.flume.hearth.core.network.PlaylistDto
import im.flume.hearth.core.network.SubsonicClient
import im.flume.hearth.core.common.SubsonicException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import im.flume.hearth.domain.music.download.DownloadRepository
import kotlinx.coroutines.withContext
import im.flume.hearth.core.data.AlbumEntity
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.core.data.PlaylistSongEntity
import im.flume.hearth.core.data.SearchIndex
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.data.toEntity

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

    private val keepLock = Mutex()
    /** The kept playlist once made here, so a second download before the next sync doesn't make another. */
    private var keptPlaylistId: String? = null

    /**
     * Mix songs you download stay on the server after the mix ends: they go in a hidden playlist the
     * server's nightly cleanup reads as saved. Liked songs are kept anyway, so they're left out.
     */
    suspend fun keepMixSongs(songIds: List<String>) = keepLock.withLock {
        val me = session.credentials.value?.username ?: return@withLock
        val items = PlaylistRules.items(dao.playlistsOnce(), me, emptyMap())
        val mixes = ServerMixes.mine(items).map { it.playlist.id }
        if (mixes.isEmpty()) return@withLock
        val kept = keptPlaylistId ?: items.firstOrNull { it.access.isOwner && ServerMixes.isKept(it.playlist) }?.playlist?.id
        val alreadyKept = kept?.let { dao.songIdsInPlaylists(listOf(it)).toSet() }.orEmpty()
        val keep = ServerMixes.toKeep(songIds, dao.songIdsInPlaylists(mixes).toSet(), alreadyKept, dao.starredSongIds().toSet())
        if (keep.isEmpty()) return@withLock
        if (kept == null) {
            val id = api.createPlaylist(ServerMixes.KEPT_NAME, keep) ?: return@withLock
            api.setPlaylistComment(id, ServerMixes.KEPT_COMMENT, public = false)
            keptPlaylistId = id
        } else {
            api.addToPlaylist(kept, keep)
        }
    }

    fun playlistItem(id: String): Flow<PlaylistItem?> =
        combine(dao.playlist(id), session.credentials, session.playlistDecisions) { p, creds, decisions ->
            p?.let { PlaylistItem(it, PlaylistRules.access(it, creds?.username, decisions)) }
        }

    /** Songs for [source] in their natural order. When [offlineOnly], keeps just downloaded songs. */
    suspend fun resolve(source: PlaySource, offlineOnly: Boolean): List<SongEntity> {
        val songs = when (source.kind) {
            PlaySource.Kind.ALL -> when {
                source.order != null -> source.order.sort(dao.allSongs().first())
                offlineOnly -> dao.downloadedSongsOnce()
                else -> songsByIds(dao.allSongIds())
            }
            PlaySource.Kind.MY_LIBRARY -> {
                val mine = dao.myLibrarySongsOnce().let { if (offlineOnly) it.filter { s -> downloads.isDownloaded(s.id) } else it }
                return mine.ifEmpty { resolve(PlaySource.All, offlineOnly) }
            }
            PlaySource.Kind.ALBUM -> dao.albumSongsOnce(source.id!!)
            PlaySource.Kind.ARTIST -> dao.artistSongsOnce(source.id!!)
            PlaySource.Kind.PLAYLIST -> dao.playlistSongsOnce(source.id!!)
            PlaySource.Kind.GENRE -> dao.genreSongsOnce(source.id!!)
            // In the order Liked Songs shows them, so tapping a song plays on down the list.
            PlaySource.Kind.LIKED -> dao.starredSongsOnce().let { source.order?.sort(it) ?: it }
            PlaySource.Kind.DOWNLOADS -> dao.downloadedSongsOnce()
            PlaySource.Kind.SONGS -> songsByIds(source.songIds)
        }
        return if (offlineOnly) songs.filter { downloads.isDownloaded(it.id) } else songs
    }

    private var index: Pair<Long, SearchIndex>? = null

    /** Forgets the search index, e.g. when requested music has just been added to the library. */
    fun invalidateSearchIndex() { index = null }

    /** Search index over the local library, rebuilt after each sync. */
    suspend fun searchIndex(syncedAt: Long): SearchIndex {
        index?.takeIf { it.first == syncedAt }?.let { return it.second }
        return withContext(Dispatchers.Default) {
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
        if (starred) downloads.onLiked(songId)
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

    /** Re-reads the playlist from the server into the local copy and returns it (null if it's gone). */
    suspend fun refreshPlaylist(id: String): PlaylistDto? {
        val pl = api.playlist(id) ?: return null
        // Songs the other phone added that this one hasn't synced yet: the entries carry full song details.
        val missing = pl.entry.map { it.id }.distinct().let { ids -> ids - songsByIds(ids).mapTo(HashSet()) { it.id } }
        if (missing.isNotEmpty()) dao.insertMissingSongs(pl.entry.filter { it.id in missing }.distinctBy { it.id }.map { it.toEntity() })
        dao.replacePlaylist(pl.toEntity(), pl.entry.mapIndexed { i, s -> PlaylistSongEntity(id, i, s.id) })
        downloads.refreshPinned()
        return pl
    }

    /** The playlist's song ids as the server has them right now, in order. */
    private suspend fun serverSongIds(playlistId: String): List<String> =
        (api.playlist(playlistId) ?: throw IOException("Playlist not found")).entry.map { it.id }

    /**
     * The song ids to start an edit from: fresh from the server when possible (also updating the local
     * copy), otherwise the local copy, including songs this phone hasn't synced.
     */
    suspend fun playlistSongIdsForEdit(playlistId: String): List<String> =
        runCatching { refreshPlaylist(playlistId)?.entry?.map { it.id } }.getOrNull()
            ?: dao.playlistEntriesOnce(playlistId).map { it.songId }

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>) {
        api.addToPlaylist(playlistId, songIds)
        refreshPlaylist(playlistId)
    }

    /** Returns the new playlist's id, or null if the server didn't say. */
    suspend fun createPlaylist(name: String, songIds: List<String>): String? {
        val id = api.createPlaylist(name, songIds) ?: run {
            // Admins see everyone's playlists, so only look at our own with that name, newest first,
            // preferring one this phone didn't know before.
            val me = session.credentials.value?.username ?: return null
            val known = dao.playlistsOnce().mapTo(HashSet()) { it.id }
            api.playlists()
                .filter { it.name == name && it.owner.equals(me, ignoreCase = true) }
                .sortedWith(compareBy<PlaylistDto> { it.id in known }.thenByDescending { it.changed.orEmpty() })
                .firstOrNull()?.id
        } ?: return null
        refreshPlaylist(id)
        return id
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        api.renamePlaylist(playlistId, name)
        refreshPlaylist(playlistId)
    }

    /**
     * Saves an edit made from [original] (the server list when editing began) to [edited], merged with
     * whatever the other phone changed meanwhile (see [PlaylistEdits.mergePlaylistEdit]). Uses
     * updatePlaylist, which Navidrome allows for admins who don't own the playlist.
     */
    suspend fun savePlaylistEdit(playlistId: String, original: List<String>, edited: List<String>) {
        val latest = serverSongIds(playlistId)
        val merged = PlaylistEdits.mergePlaylistEdit(original, edited, latest)
        val (remove, add) = PlaylistEdits.replaceOps(latest, merged)
        if (remove.isNotEmpty() || add.isNotEmpty()) api.editPlaylistSongs(playlistId, remove, add)
        refreshPlaylist(playlistId)
    }

    suspend fun deletePlaylist(playlistId: String) {
        api.deletePlaylist(playlistId)
        dao.clearPlaylistSongs(playlistId)
        dao.deletePlaylistRow(playlistId)
    }

    /** What a removal changed, so it can be undone with [savePlaylistEdit]. */
    data class Removal(val before: List<String>, val after: List<String>)

    /**
     * Removes the [occurrence]th [songId] (counted on this phone's copy). The position is looked up
     * again on the server first, so a song the other phone added or removed meanwhile doesn't shift it.
     * Returns null when the song was already gone.
     */
    suspend fun removeFromPlaylist(playlistId: String, songId: String, occurrence: Int): Removal? {
        val before = serverSongIds(playlistId)
        val index = PlaylistEdits.indexOf(before, songId, occurrence)
        if (index == null) { refreshPlaylist(playlistId); return null }
        api.removeFromPlaylist(playlistId, index)
        val after = refreshPlaylist(playlistId)?.entry?.map { it.id }
            ?: before.toMutableList().apply { removeAt(index) }
        return Removal(before, after)
    }

    /** Puts a removed song back where it was, keeping whatever the other phone changed since. */
    suspend fun undoRemoval(playlistId: String, removal: Removal) =
        savePlaylistEdit(playlistId, original = removal.after, edited = removal.before)

    /** Message for a failed playlist edit; only mentions admin rights when the account isn't one. */
    suspend fun editError(e: Throwable, owner: String?, fallback: String): String {
        val notAuthorized = e is SubsonicException && e.notAuthorized
        val isAdmin = if (!notAuthorized) null else session.credentials.value?.username?.let { me ->
            runCatching { api.user(me)?.adminRole }.getOrNull()
        }
        return PlaylistRules.editError(e, owner, isAdmin, fallback)
    }

    /**
     * Changes who a playlist is shared with. Re-reads the comment first so a change made on the other
     * phone a moment ago isn't overwritten, then writes it back with public on while anyone's invited.
     * Returns the sharing the server has afterwards (read back, not assumed).
     */
    suspend fun updateSharing(playlistId: String, change: (Sharing) -> Sharing): Sharing? {
        val pl = api.playlist(playlistId) ?: throw IOException("Playlist not found")
        val parsed = PlaylistSharing.parse(pl.comment)
        val current = parsed.sharing ?: Sharing()
        val next = change(current.copy(owner = current.owner ?: pl.owner?.lowercase()))
        api.setPlaylistComment(playlistId, PlaylistSharing.serialize(parsed.text, next), public = !next.isEmpty)
        val fresh = refreshPlaylist(playlistId) ?: throw IOException("Playlist not found")
        return PlaylistSharing.parse(fresh.comment).sharing
    }

    /**
     * Accepts or declines an invite (declining also covers leaving). The answer is kept on this phone
     * first, so it sticks even if the server won't take it (e.g. the account isn't an admin).
     * Returns false when only the local copy could be saved.
     */
    suspend fun answerInvite(playlistId: String, accept: Boolean): Boolean {
        val me = session.credentials.value?.username ?: return false
        session.setPlaylistDecision(playlistId, accept)
        val after = runCatching { updateSharing(playlistId) { if (accept) it.accept(me) else it.remove(me) } }
        // Only once the server's comment really shows the answer is the local copy dropped, so a later
        // re-invite still shows up but a write the server ignored doesn't bring the invite back.
        val saved = after.isSuccess && PlaylistRules.answerSaved(after.getOrNull(), me, accept)
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
