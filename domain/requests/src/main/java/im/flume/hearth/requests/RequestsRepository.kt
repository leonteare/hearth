package im.flume.hearth.requests

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import im.flume.hearth.core.network.SubsonicClient
import im.flume.hearth.core.network.TidalAlbum
import im.flume.hearth.core.network.TidalAlbumPage
import im.flume.hearth.core.network.TidalArtist
import im.flume.hearth.core.network.TidalArtistPage
import im.flume.hearth.core.network.TidalSearchResults
import im.flume.hearth.core.network.TidalTrack
import im.flume.hearth.core.network.TidalType
import im.flume.hearth.core.network.TidarrClient
import im.flume.hearth.core.network.TidarrConfig
import im.flume.hearth.core.network.TtlCache
import im.flume.hearth.core.data.AlbumEntity
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.core.data.ArtistEntity
import im.flume.hearth.data.Availability
import im.flume.hearth.data.LibraryRepository
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.data.RequestIntent
import im.flume.hearth.data.RequestMatching
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.data.RequestTracking
import im.flume.hearth.core.data.SearchIndex
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.sync.LibrarySync
import im.flume.hearth.core.common.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Music requested from Tidal through Tidarr. Tidarr downloads it into the folder Navidrome scans;
 * this follows each request through Tidarr's queue and Navidrome's scan, then (as asked) likes it,
 * downloads it to the phone that asked, or just leaves it on the server. Polls every 20 s while the app is open, every 15 min otherwise.
 * The polling lives in RequestPolling.kt and what happens when music arrives in RequestArrival.kt.
 */
class RequestsRepository(
    internal val context: Context,
    internal val db: AppDatabase,
    internal val session: SessionStore,
    internal val api: SubsonicClient,
    internal val library: LibraryRepository,
    internal val downloads: DownloadRepository,
    internal val sync: LibrarySync,
    http: OkHttpClient,
    internal val scope: CoroutineScope,
) {
    internal val dao = db.requests()

    init {
        // Before 2.3.1 a request that produced nothing was marked done ("On the server"); it actually failed.
        scope.launch {
            runCatching { dao.allOnce().forEach { r -> RequestTracking.reclassified(r)?.let { dao.upsert(it) } } }
            // Finished ones are in the library now; nothing lists them any more.
            runCatching { dao.clearDone() }
        }
    }

    @Volatile private var apiKey: String? = null
    @Volatile private var apiKeyLoaded = false

    /** The optional Tidarr API key (decrypted once, then kept in memory). Never logged. */
    fun apiKey(): String? {
        if (!apiKeyLoaded) { apiKey = session.tidarrVault.read(); apiKeyLoaded = true }
        return apiKey
    }

    fun setApiKey(key: String?) {
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        if (k == null) session.tidarrVault.clear() else session.tidarrVault.save(k)
        apiKey = k; apiKeyLoaded = true
    }

    fun config(): TidarrConfig? =
        session.settings.value.tidarrUrl.takeIf { it.isNotBlank() }?.let { TidarrConfig(TidarrClient.normalizeUrl(it), apiKey()) }

    val tidarr = TidarrClient(http) { config() }

    internal val me: String? get() = session.credentials.value?.username

    internal fun mine(r: RequestEntity) = r.requestedBy.equals(me, ignoreCase = true)

    /** This user's requests, newest first. */
    val requests: Flow<List<RequestEntity>> =
        combine(dao.all(), session.credentials) { all, creds -> all.filter { it.requestedBy.equals(creds?.username, ignoreCase = true) } }

    // --- search ---

    private var catalog: Pair<String, RequestMatching.LocalCatalog>? = null
    @Volatile internal var catalogVersion = 0

    /** Changes whenever the library does (a sync, or requested music arriving). */
    private fun catalogKey() = "${session.lastSyncAt}:$catalogVersion"

    private suspend fun localCatalog(): RequestMatching.LocalCatalog = withContext(Dispatchers.Default) {
        val key = catalogKey()
        catalog?.takeIf { it.first == key }?.second
            ?: RequestMatching.LocalCatalog(db.library().allSongsOnce(), db.library().allAlbumsOnce(), db.library().allArtistsOnce()).also { catalog = key to it }
    }

    /** Tidal results for [query] that aren't on the server yet: up to 5 songs, 5 albums and 3 artists. */
    suspend fun searchTidal(query: String): TidalSearchResults {
        val raw = tidarr.search(query, limit = 15)
        val filtered = localCatalog().filter(raw)
        val cat = localCatalog()
        // Artists the server has are shown from the library (their page fills in what's missing).
        val artists = filtered.artists.filter { cat.localArtist(it.name) == null }
        return TidalSearchResults(filtered.tracks.take(5), filtered.albums.take(5), artists.take(3))
            .also { synchronized(searchCache) { searchCache[query.lowercase()] = Triple(System.currentTimeMillis(), catalogKey(), it) } }
    }

    /**
     * Recent searches (5 minutes), so retyping or going back shows results instantly. Each is tied to
     * the library as it was: once music arrives or a sync runs, it's searched again, so something that
     * just landed shows as yours instead of still "not on your server".
     */
    private val searchCache = object : LinkedHashMap<String, Triple<Long, String, TidalSearchResults>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<Long, String, TidalSearchResults>>?) = size > 30
    }

    fun cachedTidalSearch(query: String): TidalSearchResults? = synchronized(searchCache) {
        searchCache[query.lowercase()]
            ?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 && it.second == catalogKey() }?.third
    }

    /** A cheap call that opens (and keeps alive) the connection to Tidarr, and caches the country code. */
    suspend fun warmUp() {
        tidarr.isAuthActive()
        tidarr.countryCode()
    }

    private val artistIds = TtlCache<String, String>(max = 100, ttlMs = 10 * 60_000L)
    private val albumIds = TtlCache<String, String>(max = 100, ttlMs = 10 * 60_000L)

    /** True when pages and Search should show music that isn't on the server. */
    fun enabled(): Boolean = session.settings.value.tidalSearchEnabled

    /**
     * The remote artist named [name] (exact normalised name), with their popular tracks and releases;
     * null when there's no such artist. Lookups are cached for 10 minutes. Throws when unreachable.
     */
    suspend fun artistByName(name: String): TidalArtistPage? {
        val key = RequestMatching.baseTitle(name).ifEmpty { name.lowercase() }
        val id = artistIds.get(key) ?: run {
            val found = Availability.pickArtist(tidarr.search(name, limit = 10).artists, name)?.id.orEmpty()
            artistIds.put(key, found)
            found
        }
        return id.takeIf { it.isNotEmpty() }?.let { tidarr.artistPage(it) }
    }

    suspend fun artistPage(id: String): TidalArtistPage = tidarr.artistPage(id)

    /** The remote copy of the server's album [title] by [artist], with its tracks; null when not found. Cached 10 minutes. */
    suspend fun albumByName(title: String, artist: String): TidalAlbumPage? {
        val key = "$title|$artist".lowercase()
        val id = albumIds.get(key) ?: run {
            val q = "${RequestMatching.searchTitle(title)} ${RequestMatching.splitArtists(artist).first()}"
            val found = Availability.pickAlbum(tidarr.search(q, limit = 15).albums, title, artist)?.id.orEmpty()
            albumIds.put(key, found)
            found
        }
        return id.takeIf { it.isNotEmpty() }?.let { tidarr.albumPage(it) }
    }

    suspend fun albumPage(id: String): TidalAlbumPage = tidarr.albumPage(id)

    /** The server's copies of these tracks, by remote track id. */
    suspend fun ownedTracks(tracks: List<TidalTrack>): Map<String, SongEntity> {
        val cat = localCatalog()
        return tracks.mapNotNull { t -> cat.localTrack(t)?.let { t.id to it } }.toMap()
    }

    /** The server's copies of these albums, by remote album id. */
    suspend fun ownedAlbums(albums: List<TidalAlbum>): Map<String, AlbumEntity> {
        val cat = localCatalog()
        return albums.mapNotNull { a -> cat.localAlbum(a)?.let { a.id to it } }.toMap()
    }

    suspend fun localArtist(name: String): ArtistEntity? = localCatalog().localArtist(name)

    // --- following artists the server doesn't have yet ---

    private val prefs = context.getSharedPreferences("get-music", Context.MODE_PRIVATE)
    private val _followIntents = MutableStateFlow(prefs.getStringSet("follow", emptySet()).orEmpty().toSet())

    /** Names of artists followed before the server had them; they're followed for real once their music arrives. */
    val followIntents: StateFlow<Set<String>> = _followIntents

    fun setFollowIntent(name: String, follow: Boolean) {
        val n = SearchIndex.norm(name)
        val next = if (follow) _followIntents.value + n else _followIntents.value - n
        _followIntents.value = next
        prefs.edit().putStringSet("follow", next).apply()
    }

    fun isFollowIntent(name: String): Boolean = SearchIndex.norm(name) in _followIntents.value



    /** Checks that Tidarr answers at [url] and can search. Returns null when fine, or what went wrong. */
    suspend fun testConnection(url: String, key: String?): String? = runCatching {
        val cfg = TidarrConfig(TidarrClient.normalizeUrl(url), key?.trim()?.takeIf { it.isNotEmpty() })
        tidarr.isAuthActive(cfg)
        tidarr.search("test", limit = 1, cfg = cfg)
    }.exceptionOrNull()?.let { userMessage(it, "Couldn't reach Tidarr") }

    // --- requesting ---

    fun track(t: TidalTrack, now: Long = System.currentTimeMillis()) = newRequest(
        TidalType.TRACK, t.id, t.displayTitle, t.artist, t.cover, t.isrc, t.albumTitle, t.durationSec, now,
    )

    fun album(a: TidalAlbum, now: Long = System.currentTimeMillis()) = newRequest(
        TidalType.ALBUM, a.id, a.title, a.artist, a.cover, null, a.year, null, now,
    )

    fun artist(a: TidalArtist, now: Long = System.currentTimeMillis()) = newRequest(
        TidalType.ARTIST, a.id, a.name, a.name, a.picture, null, null, null, now,
    )

    private fun newRequest(
        type: TidalType, id: String, title: String, artist: String, cover: String?, isrc: String?,
        albumTitle: String?, durationSec: Int?, now: Long,
    ) = RequestEntity(
        tidalId = id, type = type.api, title = title, artist = artist, coverUuid = cover, isrc = isrc,
        albumTitle = albumTitle, durationSec = durationSec, requestedAt = now, requestedBy = me.orEmpty(),
        status = RequestStatus.REQUESTED, errorMessage = null, matchedIds = null, updatedAt = now,
        seenInQueue = false, finishedAt = null, lookupAttempts = 0,
    )

    /** What happened when the user asked for something. */
    enum class Outcome {
        REQUESTED,
        /** On its way already, wanting all of this already. */
        ALREADY_REQUESTED,
        /** On its way already; what it does on arrival now includes this too. */
        UPDATED,
        ALREADY_ON_SERVER,
    }

    /**
     * Asks Tidarr for [draft] (made by [track], [album] or [artist]). Once it arrives it's liked (an
     * album saved) if [like], and downloaded to this phone if [toPhone]; with neither it's just on the
     * server. Something already on its way keeps going and gains these wishes; a failed one is
     * retried with them. Songs and albums the server turns out to have already get them straight away
     * instead. Throws (with a safe message) if Tidarr can't be reached.
     */
    suspend fun request(draft: RequestEntity, like: Boolean, toPhone: Boolean): Outcome {
        val existing = dao.get(draft.type, draft.tidalId)?.takeIf(::mine)
        if (existing != null && existing.status.active) {
            val merged = RequestIntent.merge(existing, like, toPhone)
            if (merged == existing) return Outcome.ALREADY_REQUESTED
            dao.upsert(merged.copy(updatedAt = System.currentTimeMillis()))
            return Outcome.UPDATED
        }
        if (existing != null && existing.status == RequestStatus.FAILED) {
            retry(RequestIntent.merge(existing, like, toPhone))
            return Outcome.REQUESTED
        }
        @Suppress("NAME_SHADOWING") val draft = draft.copy(like = like, toPhone = toPhone)
        if (draft.type != TidalType.ARTIST.api) {
            val found = runCatching { findOnServer(draft) }.getOrNull()
            if (found != null) {
                complete(draft.copy(status = RequestStatus.ADDING), found)
                runCatching { downloads.refreshPinned() }
                return Outcome.ALREADY_ON_SERVER
            }
        }
        // The other phone (or Tidarr's own page) may have asked for it already; ids must be unique there.
        val inQueue = runCatching { tidarr.queue() }.getOrNull()?.firstOrNull { it.id == draft.tidalId }
        val failedThere = inQueue != null && (inQueue.error || inQueue.status == "error")
        when {
            inQueue == null -> tidarr.save(TidalType.of(draft.type), draft.tidalId, draft.title, draft.artist)
            failedThere -> tidarr.retryFailed()
        }
        val now = System.currentTimeMillis()
        var row = draft.copy(requestedBy = me.orEmpty(), requestedAt = now, updatedAt = now, seenInQueue = inQueue != null)
        // Already in Tidarr's queue (and not failed, or just retried): pick up where it is.
        if (inQueue != null && !failedThere) row = RequestTracking.afterQueue(row, inQueue, now)
        dao.upsert(row)
        ensureTracking()
        return Outcome.REQUESTED
    }

    /**
     * Sends a request again. Tidarr retries it if it failed there; if it's listed as anything else
     * (usually "finished" with nothing downloaded) it's taken out and added again, since Tidarr
     * ignores a second save of an id it already has.
     */
    suspend fun retry(r: RequestEntity) {
        val inQueue = tidarr.queue().firstOrNull { it.id == r.tidalId }
        val action = RequestTracking.retryAction(inQueue)
        when (action) {
            RequestTracking.RetryAction.RETRY_FAILED -> tidarr.retryFailed()
            RequestTracking.RetryAction.REMOVE_AND_SAVE -> {
                tidarr.remove(r.tidalId)
                tidarr.save(TidalType.of(r.type), r.tidalId, r.title, r.artist)
            }
            RequestTracking.RetryAction.SAVE -> tidarr.save(TidalType.of(r.type), r.tidalId, r.title, r.artist)
        }
        dao.upsert(RequestTracking.afterRetry(r, action, System.currentTimeMillis()))
        ensureTracking()
    }

    /** Stops a request: still-waiting items are taken out of Tidarr's queue, and the row goes. */
    suspend fun cancel(r: RequestEntity) {
        if (r.status == RequestStatus.REQUESTED || r.status == RequestStatus.DOWNLOADING || r.status == RequestStatus.FAILED) {
            runCatching { tidarr.remove(r.tidalId) }.onFailure { if (r.status != RequestStatus.FAILED) throw it }
        }
        dao.delete(r.type, r.tidalId)
    }

    /**
     * Takes back part of what [r] would do on arrival (liking it, downloading it). Returns true when
     * that left nothing for it to do, so the whole request was stopped as [cancel] does.
     */
    suspend fun dropIntent(r: RequestEntity, like: Boolean = false, toPhone: Boolean = false): Boolean {
        val current = dao.get(r.type, r.tidalId) ?: r
        val next = RequestIntent.without(current, dropLike = like, dropToPhone = toPhone)
        if (next == null) { cancel(current); return true }
        dao.upsert(next.copy(updatedAt = System.currentTimeMillis()))
        return false
    }

    /** Keeps [r] coming to the server, but it won't be liked or downloaded when it arrives. */
    suspend fun serverOnly(r: RequestEntity) {
        val current = dao.get(r.type, r.tidalId) ?: r
        dao.upsert(current.copy(like = false, toPhone = false, updatedAt = System.currentTimeMillis()))
    }

    suspend fun clearFinished() = dao.clearDone()

    // --- tracking ---

    private val pollLock = Mutex()
    internal var foreground = false
    internal var loop: Job? = null

    /** Starts following requests: in the foreground while the app is open, otherwise via WorkManager. */
    fun start() {
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { foreground = true; startLoop() }
                override fun onStop(owner: LifecycleOwner) {
                    foreground = false
                    loop?.cancel(); loop = null
                    scope.launch { if (hasActive()) scheduleBackground() }
                }
            })
        }
        scope.launch { if (hasActive()) scheduleBackground() }
    }

    /**
     * One round: reads Tidarr's queue, moves requests along, and looks on the server for finished
     * ones. Returns true while any of this user's requests are still in progress.
     */
    suspend fun poll(): Boolean = pollLock.withLock { pollRound() }

    companion object {
        const val WORK_NAME = "requests"
    }
}
