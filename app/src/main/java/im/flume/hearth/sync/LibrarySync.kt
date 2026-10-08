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
import kotlinx.coroutines.flow.first
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
        if (force || session.lastSyncAt == 0L || stale || rescanned || session.syncedSchema < SCHEMA) {
            fullSync(scan?.lastScan)
        } else {
            refreshStars()
        }
    }

    /**
     * Saved albums and followed artists can change from another device between full syncs, and
     * getStarred2 is a single cheap request, so it runs on every check. Only flags change; no rows go.
     */
    suspend fun refreshStars() {
        if (mutex.isLocked) return
        mutex.withLock {
            val starred = runCatching { api.starred() }.getOrNull() ?: return
            db.library().applyStars(starred.album.map { it.id }, starred.artist.map { it.id })
        }
    }

    suspend fun fullSync(lastScan: String? = null) = mutex.withLock {
        _state.value = SyncState.Running(0)
        try {
            val songs = fetchAllSongs()
            // A server that rescanned with its music folder missing returns (almost) nothing; replacing
            // the library with that would make every download and offline song disappear.
            val localCount = db.library().songCount().first()
            if (!looksComplete(songs.size, localCount)) {
                _state.value = SyncState.Failed(SHRUNK_MESSAGE)
                return@withLock
            }
            val albums = pageAll { offset -> api.albumList("alphabeticalByName", PAGE, offset) }
            val artists = api.artists()
            // The list endpoints don't always carry the user's stars, so getStarred2 is the source of truth.
            // If it fails, fall back to whatever the lists said rather than failing the whole sync.
            val starred = runCatching { api.starred() }.getOrNull()
            val starredAlbums = starred?.album?.mapTo(HashSet()) { it.id }
            val starredArtists = starred?.artist?.mapTo(HashSet()) { it.id }
            val playlists = api.playlists()
            val playlistSongs = playlists.flatMap { pl ->
                api.playlist(pl.id)?.entry.orEmpty().mapIndexed { i, s -> PlaylistSongEntity(pl.id, i, s.id) }
            }
            db.library().replaceLibrary(
                songs = songs.map { it.toEntity() },
                albums = albums.map { a -> a.toEntity().let { e -> if (starredAlbums == null) e else e.copy(starred = a.id in starredAlbums) } },
                artists = artists.map { a -> a.toEntity().let { e -> if (starredArtists == null) e else e.copy(starred = a.id in starredArtists) } },
                playlists = playlists.map { it.toEntity() },
                playlistSongs = playlistSongs,
            )
            session.lastSyncAt = System.currentTimeMillis()
            session.syncedSchema = SCHEMA
            if (lastScan != null) session.lastScan = lastScan
            onPlaylistsSynced()
            _state.value = SyncState.Idle
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is java.net.UnknownHostException -> "Couldn't find the server (is Tailscale connected?)"
                is java.net.ConnectException, is java.net.SocketTimeoutException -> "Couldn't reach the server"
                is java.io.IOException -> "Lost connection to the server"
                else -> e.message ?: "Sync failed"
            }.replace(Regex("""https?://\S+"""), "the server") // URLs can carry the login token
            _state.value = SyncState.Failed(message)
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
        private const val SCHEMA = 3
        private const val SIX_HOURS = 6 * 60 * 60 * 1000L
        const val SHRUNK_MESSAGE = "The server returned almost no songs, so the library wasn't changed"

        /**
         * False when the server's song list is suspiciously small: empty, or under half of a sizeable
         * local library. Small libraries may legitimately shrink a lot, so only the empty case applies there.
         */
        fun looksComplete(remoteCount: Int, localCount: Int): Boolean = when {
            remoteCount == 0 -> localCount == 0
            localCount > 200 -> remoteCount * 2 >= localCount
            else -> true
        }
    }
}
