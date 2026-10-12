package im.flume.hearth.domain.requests

import android.content.Context
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
import im.flume.hearth.domain.music.LibraryRepository
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.domain.music.RequestMatching
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.domain.music.download.DownloadRepository
import im.flume.hearth.domain.music.sync.LibrarySync
import im.flume.hearth.core.common.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Music requested from Tidal through Tidarr. Tidarr downloads it into the folder Navidrome scans;
 * this follows each request through Tidarr's queue and Navidrome's scan, then (as asked) likes it,
 * downloads it to the phone that asked, or just leaves it on the server. Polls every 20 s while the app is open, every 15 min otherwise.
 * Searching and browsing live in TidalBrowse.kt, asking and changing requests in RequestActions.kt,
 * the polling in RequestPolling.kt and what happens when music arrives in RequestArrival.kt.
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

    // --- search (TidalBrowse.kt) ---

    internal var catalog: Pair<String, RequestMatching.LocalCatalog>? = null
    @Volatile internal var catalogVersion = 0
    internal val recentSearches = RecentSearches()
    internal val artistIds = TtlCache<String, String>(max = 100, ttlMs = 10 * 60_000L)
    internal val albumIds = TtlCache<String, String>(max = 100, ttlMs = 10 * 60_000L)

    /** Tidal results for [query] that aren't on the server yet: up to 5 songs, 5 albums and 3 artists. */
    suspend fun searchTidal(query: String): TidalSearchResults = searchNotOnServer(query)

    /** A search made in the last 5 minutes, unless the library has changed since (see [RecentSearches]). */
    fun cachedTidalSearch(query: String): TidalSearchResults? = recentSearches.get(query, catalogKey())

    /** A cheap call that opens (and keeps alive) the connection to Tidarr, and caches the country code. */
    suspend fun warmUp() {
        tidarr.isAuthActive()
        tidarr.countryCode()
    }

    /** True when pages and Search should show music that isn't on the server. */
    fun enabled(): Boolean = session.settings.value.tidalSearchEnabled

    /**
     * The remote artist named [name] (exact normalised name), with their popular tracks and releases;
     * null when there's no such artist. Lookups are cached for 10 minutes. Throws when unreachable.
     */
    suspend fun artistByName(name: String): TidalArtistPage? = findArtistByName(name)

    suspend fun artistPage(id: String): TidalArtistPage = tidarr.artistPage(id)

    /** The remote copy of the server's album [title] by [artist], with its tracks; null when not found. Cached 10 minutes. */
    suspend fun albumByName(title: String, artist: String): TidalAlbumPage? = findAlbumByName(title, artist)

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

    // --- following artists the server doesn't have yet (FollowIntents.kt) ---

    private val follows = FollowIntents(context)

    /** Names of artists followed before the server had them; they're followed for real once their music arrives. */
    val followIntents: StateFlow<Set<String>> = follows.names

    fun setFollowIntent(name: String, follow: Boolean) = follows.set(name, follow)

    fun isFollowIntent(name: String): Boolean = follows.contains(name)

    /** Checks that Tidarr answers at [url] and can search. Returns null when fine, or what went wrong. */
    suspend fun testConnection(url: String, key: String?): String? = runCatching {
        val cfg = TidarrConfig(TidarrClient.normalizeUrl(url), key?.trim()?.takeIf { it.isNotEmpty() })
        tidarr.isAuthActive(cfg)
        tidarr.search("test", limit = 1, cfg = cfg)
    }.exceptionOrNull()?.let { userMessage(it, "Couldn't reach Tidarr") }

    // --- requesting (RequestActions.kt) ---

    fun track(t: TidalTrack, now: Long = System.currentTimeMillis()) = draftRequest(
        TidalType.TRACK, t.id, t.displayTitle, t.artist, t.cover, t.isrc, t.albumTitle, t.durationSec, now,
    )

    fun album(a: TidalAlbum, now: Long = System.currentTimeMillis()) = draftRequest(
        TidalType.ALBUM, a.id, a.title, a.artist, a.cover, null, a.year, null, now,
    )

    fun artist(a: TidalArtist, now: Long = System.currentTimeMillis()) = draftRequest(
        TidalType.ARTIST, a.id, a.name, a.name, a.picture, null, null, null, now,
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
    suspend fun request(draft: RequestEntity, like: Boolean, toPhone: Boolean): Outcome = submit(draft, like, toPhone)

    /**
     * Sends a request again. Tidarr retries it if it failed there; if it's listed as anything else
     * (usually "finished" with nothing downloaded) it's taken out and added again, since Tidarr
     * ignores a second save of an id it already has.
     */
    suspend fun retry(r: RequestEntity) = resend(r)

    /** Stops a request: still-waiting items are taken out of Tidarr's queue, and the row goes. */
    suspend fun cancel(r: RequestEntity) = stop(r)

    /**
     * Takes back part of what [r] would do on arrival (liking it, downloading it). Returns true when
     * that left nothing for it to do, so the whole request was stopped as [cancel] does.
     */
    suspend fun dropIntent(r: RequestEntity, like: Boolean = false, toPhone: Boolean = false): Boolean = dropPart(r, like, toPhone)

    /** Keeps [r] coming to the server, but it won't be liked or downloaded when it arrives. */
    suspend fun serverOnly(r: RequestEntity) {
        val current = dao.get(r.type, r.tidalId) ?: r
        dao.upsert(current.copy(like = false, toPhone = false, updatedAt = System.currentTimeMillis()))
    }

    suspend fun clearFinished() = dao.clearDone()

    // --- tracking (RequestPolling.kt) ---

    private val pollLock = Mutex()
    internal var foreground = false
    internal var loop: Job? = null

    /** Starts following requests: in the foreground while the app is open, otherwise via WorkManager. */
    fun start() = followAppLifecycle()

    /**
     * One round: reads Tidarr's queue, moves requests along, and looks on the server for finished
     * ones. Returns true while any of this user's requests are still in progress.
     */
    suspend fun poll(): Boolean = pollLock.withLock { pollRound() }

    companion object {
        const val WORK_NAME = "requests"
    }
}
