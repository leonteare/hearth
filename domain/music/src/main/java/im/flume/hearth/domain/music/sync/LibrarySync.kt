package im.flume.hearth.domain.music.sync

import im.flume.hearth.core.network.SongDto
import im.flume.hearth.core.network.SubsonicClient
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.core.data.LikeKind
import im.flume.hearth.core.data.PlaylistSongEntity
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.core.data.toEntity
import im.flume.hearth.domain.music.KidsFilter
import im.flume.hearth.domain.music.LikeOutboxPlan
import im.flume.hearth.domain.music.PendingLike
import im.flume.hearth.domain.music.toPending
import im.flume.hearth.core.common.scrubSecrets
import im.flume.hearth.core.common.userMessage
import java.net.UnknownHostException
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
    /**
     * Called after playlists (or the whole library) changed. [removedPlaylists] maps playlists the
     * server no longer has to the songs they held; [complete] is true after a full sync that passed
     * [looksComplete], when anything missing from the library really is gone from the server.
     */
    private val onSynced: suspend (removedPlaylists: Map<String, List<String>>, complete: Boolean) -> Unit = { _, _ -> },
) {
    /**
     * Songs of playlists about to be dropped, for un-downloading them. Only the signed-in user's own
     * playlists count: someone else's can vanish just because a different account signed in, and
     * their downloads must stay.
     */
    private suspend fun songsOf(playlistIds: Collection<String>): Map<String, List<String>> {
        val me = session.credentials.value?.username ?: return emptyMap()
        return playlistIds
            .filter { id -> db.library().playlistOnce(id)?.owner.equals(me, ignoreCase = true) }
            .associateWith { id -> db.library().playlistSongsOnce(id).map { it.id } }
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Syncs everything if [needsFullSync] says so; otherwise just the cheap stars and playlists checks. */
    suspend fun syncIfNeeded(force: Boolean = false) {
        if (mutex.isLocked) return
        val scan = api.scanStatus()
        if (needsFullSync(force, session.lastSyncAt, System.currentTimeMillis(), scan?.lastScan, session.lastScan, session.syncedSchema)) {
            fullSync(scan?.lastScan)
        } else {
            refreshStars()
            refreshPlaylists()
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
            // A like still waiting to be pushed was made after this answer, so it wins over it.
            val pending = db.likeOutbox().all().map { it.toPending() }
            db.library().applyStars(
                LikeOutboxPlan.merge(starred.album.map { it.id }, pending, LikeKind.ALBUM),
                LikeOutboxPlan.merge(starred.artist.map { it.id }, pending, LikeKind.ARTIST),
            )
        }
    }

    /**
     * The playlist list is one cheap request, and shared playlists change from the other phone, so it
     * runs on every check: new invites show up quickly. Only playlists whose details changed are re-read.
     */
    suspend fun refreshPlaylists() {
        if (mutex.isLocked) return
        val blockListChanged = mutex.withLock {
            val remote = runCatching { api.playlists() }.getOrNull() ?: return
            val local = db.library().playlistsOnce().associateBy { it.id }
            val entities = remote.map { it.toEntity() }
            val kids = session.settings.value.kidsMode
            val changed = HashMap<String, List<PlaylistSongEntity>>()
            var blockListChanged = false
            for (pl in entities) {
                val old = local[pl.id]
                if (old != null && old.changed == pl.changed && old.songCount == pl.songCount) continue
                val songs = runCatching { api.playlist(pl.id) }.getOrNull() ?: continue
                // Kids mode never lists a "Not for kids" list's songs; a changed one means a full sync (below).
                val blockList = kids && KidsFilter.isBlockList(pl.comment)
                if (blockList) blockListChanged = true
                changed[pl.id] = if (blockList) emptyList() else songs.entry.mapIndexed { i, s -> PlaylistSongEntity(pl.id, i, s.id) }
            }
            val removed = local.keys - remote.mapTo(HashSet()) { it.id }
            if (kids && removed.any { KidsFilter.isBlockList(local[it]?.comment) }) blockListChanged = true
            val removedSongs = songsOf(removed)
            db.library().applyPlaylists(entities, changed, removed.toList())
            if (changed.isNotEmpty() || removed.isNotEmpty()) onSynced(removedSongs, false)
            blockListChanged
        }
        if (blockListChanged) fullSync(session.lastScan)
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
            // Likes still waiting to be pushed are newer than anything the server just said.
            val pending = db.likeOutbox().all().map { it.toPending() }
            val playlists = api.playlists()
            val entries = playlists.associate { pl -> pl.id to api.playlist(pl.id)?.entry.orEmpty().map { it.id } }
            // Kids mode: songs on the parents' "Not for kids" lists stay out of everything (KidsFilter).
            val blocked = if (session.settings.value.kidsMode) {
                KidsFilter.blocked(playlists.filter { KidsFilter.isBlockList(it.comment) }.map { entries.getValue(it.id) })
            } else emptySet()
            val keptSongs = if (blocked.isEmpty()) songs else songs.filterNot { it.id in blocked }
            val keptAlbums = if (blocked.isEmpty()) albums else {
                val ids = KidsFilter.keptAlbumIds(keptSongs.map { it.albumId })
                albums.filter { it.id in ids }
            }
            val playlistSongs = playlists.flatMap { pl ->
                entries.getValue(pl.id).filterNot { it in blocked }.mapIndexed { i, id -> PlaylistSongEntity(pl.id, i, id) }
            }
            val remoteIds = playlists.mapTo(HashSet()) { it.id }
            val removedSongs = songsOf(db.library().playlistsOnce().map { it.id }.filter { it !in remoteIds })
            db.library().replaceLibrary(
                songs = keepPending(keptSongs.map { it.toEntity() }, pending, LikeKind.SONG, { it.id }) { s, liked -> s.copy(starred = liked) },
                albums = keepPending(
                    keptAlbums.map { a -> a.toEntity().let { e -> if (starredAlbums == null) e else e.copy(starred = a.id in starredAlbums) } },
                    pending, LikeKind.ALBUM, { it.id },
                ) { a, liked -> a.copy(starred = liked) },
                artists = keepPending(
                    artists.map { a -> a.toEntity().let { e -> if (starredArtists == null) e else e.copy(starred = a.id in starredArtists) } },
                    pending, LikeKind.ARTIST, { it.id },
                ) { a, liked -> a.copy(starred = liked) },
                playlists = playlists.map { it.toEntity() },
                playlistSongs = playlistSongs,
            )
            session.lastSyncAt = System.currentTimeMillis()
            session.syncedSchema = SCHEMA
            if (lastScan != null) session.lastScan = lastScan
            onSynced(removedSongs, true)
            _state.value = SyncState.Idle
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is UnknownHostException -> "Couldn't find the server (is Tailscale connected?)"
                else -> userMessage(e, "Sync failed")
            }.let { scrubSecrets(it) } // URLs can carry the login token
            _state.value = SyncState.Failed(message)
        }
    }

    /**
     * Rows the server has just described, with any like that hasn't reached it yet put back: the
     * listener tapped after the server's answer was true, so a sync must not undo it.
     */
    private fun <T> keepPending(
        rows: List<T>,
        pending: List<PendingLike>,
        kind: LikeKind,
        id: (T) -> String,
        liked: (T, Boolean) -> T,
    ): List<T> {
        val mine = LikeOutboxPlan.likedByIds(pending, kind)
        return if (mine.isEmpty()) rows else rows.map { row -> mine[id(row)]?.let { liked(row, it) } ?: row }
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
        const val SCHEMA = 5 // 4: playlist comment and public flag; 5: song ISRCs
        /** A safety net only: new music shows up through the server's scan time changing. */
        const val SAFETY_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L
        /** For a server that doesn't report when it last scanned, the old regular resync. */
        const val NO_SCAN_INFO_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val SHRUNK_MESSAGE = "The server returned almost no songs, so the library wasn't changed"

        /**
         * Whether to re-read the whole library (~15 requests for 7k songs). The library only changes
         * when the server rescans its music folder, which shows as a new [remoteLastScan], so with that
         * known a full sync is needed only then (or after a week, as a safety net). A server that can't
         * say when it scanned ([remoteLastScan] null) keeps the old 6-hour resync.
         */
        fun needsFullSync(
            force: Boolean,
            lastSyncAt: Long,
            nowMs: Long,
            remoteLastScan: String?,
            localLastScan: String?,
            syncedSchema: Int,
        ): Boolean {
            if (force || lastSyncAt == 0L || syncedSchema < SCHEMA) return true
            if (remoteLastScan != null && remoteLastScan != localLastScan) return true
            val interval = if (remoteLastScan == null) NO_SCAN_INFO_INTERVAL_MS else SAFETY_INTERVAL_MS
            return nowMs - lastSyncAt > interval
        }

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
