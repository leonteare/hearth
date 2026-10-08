package im.flume.hearth.data

import android.os.Bundle
import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.download.DownloadRepository

/** Something the user can press play on. Converted to a Bundle to travel to the playback service. */
data class PlaySource(val kind: Kind, val id: String? = null, val label: String = "", val songIds: List<String> = emptyList()) {
    enum class Kind { ALL, ALBUM, ARTIST, PLAYLIST, GENRE, LIKED, DOWNLOADS, SONGS }

    fun toBundle() = Bundle().apply {
        putString("kind", kind.name)
        putString("id", id)
        putString("label", label)
        putStringArrayList("songIds", ArrayList(songIds))
    }

    companion object {
        fun fromBundle(b: Bundle) = PlaySource(
            kind = Kind.valueOf(b.getString("kind") ?: Kind.ALL.name),
            id = b.getString("id"),
            label = b.getString("label").orEmpty(),
            songIds = b.getStringArrayList("songIds").orEmpty(),
        )

        val All = PlaySource(Kind.ALL, label = "Your library")
        val Liked = PlaySource(Kind.LIKED, label = "Liked Songs")
        val Downloads = PlaySource(Kind.DOWNLOADS, label = "Downloads")
    }
}

class LibraryRepository(
    private val db: AppDatabase,
    private val api: SubsonicClient,
    private val downloads: DownloadRepository,
) {
    val dao = db.library()

    /** Songs for [source] in their natural order. When [offlineOnly], keeps just downloaded songs. */
    suspend fun resolve(source: PlaySource, offlineOnly: Boolean): List<SongEntity> {
        val songs = when (source.kind) {
            PlaySource.Kind.ALL -> if (offlineOnly) dao.downloadedSongsOnce() else songsByIds(dao.allSongIds())
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

    /** Server's "recently played" albums, mapped onto local rows so covers and counts are consistent. */
    suspend fun serverRecentAlbums(limit: Int): List<AlbumEntity> {
        val remote = api.albumList("recent", limit)
        val local = dao.albumsByIds(remote.map { it.id }).associateBy { it.id }
        return remote.map { local[it.id] ?: it.toEntity() }
    }
}
