package im.flume.hearth.sync

import im.flume.hearth.api.SongDto
import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.data.AppDatabase
import im.flume.hearth.data.PlaylistSongEntity
import im.flume.hearth.data.SessionStore
import im.flume.hearth.data.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val songsSoFar: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * Copies the whole library index (songs, albums, artists, playlists) into Room so that browsing,
 * search and shuffle never wait on the server. ~7k songs is about 15 requests.
 */
class LibrarySync(
    private val api: SubsonicClient,
    private val db: AppDatabase,
    private val session: SessionStore,
    private val onPlaylistsSynced: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Syncs if forced, never synced, the server has rescanned, or it's been more than 6 hours. */
    suspend fun syncIfNeeded(force: Boolean = false) {
        if (mutex.isLocked) return
        val scan = api.scanStatus()
        val stale = System.currentTimeMillis() - session.lastSyncAt > SIX_HOURS
        val rescanned = scan?.lastScan != null && scan.lastScan != session.lastScan
        if (force || session.lastSyncAt == 0L || stale || rescanned) {
            fullSync(scan?.lastScan)
        }
    }

    suspend fun fullSync(lastScan: String? = null) = mutex.withLock {
        _state.value = SyncState.Running(0)
        try {
            val songs = fetchAllSongs()
            val albums = pageAll { offset -> api.albumList("alphabeticalByName", PAGE, offset) }
            val artists = api.artists()
            val playlists = api.playlists()
            val playlistSongs = playlists.flatMap { pl ->
                api.playlist(pl.id)?.entry.orEmpty().mapIndexed { i, s -> PlaylistSongEntity(pl.id, i, s.id) }
            }
            db.library().replaceLibrary(
                songs = songs.map { it.toEntity() },
                albums = albums.map { it.toEntity() },
                artists = artists.map { it.toEntity() },
                playlists = playlists.map { it.toEntity() },
                playlistSongs = playlistSongs,
            )
            session.lastSyncAt = System.currentTimeMillis()
            if (lastScan != null) session.lastScan = lastScan
            onPlaylistsSynced()
            _state.value = SyncState.Idle
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = SyncState.Failed(e.message ?: "Sync failed")
        }
    }

    private suspend fun fetchAllSongs(): List<SongDto> {
        // Navidrome returns every song for an empty search3 query; page through it.
        val viaSearch = pageAll { offset ->
            api.searchSongs("", PAGE, offset).also { _state.value = SyncState.Running(offset + it.size) }
        }
        if (viaSearch.isNotEmpty()) return viaSearch.filterNot { it.isDir }.distinctBy { it.id }

        // Fallback for servers that don't support empty search: walk every album.
        val albums = pageAll { offset -> api.albumList("alphabeticalByName", PAGE, offset) }
        val songs = ArrayList<SongDto>()
        for (album in albums) {
            songs += api.album(album.id)?.song.orEmpty()
            _state.value = SyncState.Running(songs.size)
        }
        return songs.distinctBy { it.id }
    }

    private suspend fun <T> pageAll(fetch: suspend (offset: Int) -> List<T>): List<T> {
        val out = ArrayList<T>()
        var offset = 0
        while (true) {
            val page = fetch(offset)
            out += page
            if (page.size < PAGE) break
            offset += page.size
        }
        return out
    }

    companion object {
        const val PAGE = 500
        private const val SIX_HOURS = 6 * 60 * 60 * 1000L
    }
}
