package im.flume.hearth.requests

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import im.flume.hearth.HearthApp
import im.flume.hearth.R
import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.api.TidalAlbum
import im.flume.hearth.api.TidalAlbumPage
import im.flume.hearth.api.TidalArtistPage
import im.flume.hearth.api.TidalArtist
import im.flume.hearth.api.TidalSearchResults
import im.flume.hearth.api.TidalTrack
import im.flume.hearth.api.TidalType
import im.flume.hearth.api.TidarrClient
import im.flume.hearth.api.TidarrConfig
import im.flume.hearth.data.AlbumEntity
import im.flume.hearth.data.AppDatabase
import im.flume.hearth.data.Availability
import im.flume.hearth.data.ArtistEntity
import im.flume.hearth.data.LibraryRepository
import im.flume.hearth.data.RequestEntity
import im.flume.hearth.data.RequestMatching
import im.flume.hearth.data.RequestStatus
import im.flume.hearth.data.RequestTracking
import im.flume.hearth.data.SessionStore
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.toEntity
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.logHandledError
import im.flume.hearth.sync.LibrarySync
import im.flume.hearth.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Music requested from Tidal through Tidarr. Tidarr downloads it into the folder Navidrome scans;
 * this follows each request through Tidarr's queue and Navidrome's scan, then likes and downloads
 * the result on the phone that asked. Polls every 20 s while the app is open, every 15 min otherwise.
 */
class RequestsRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val session: SessionStore,
    private val api: SubsonicClient,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val sync: LibrarySync,
    http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val dao = db.requests()

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

    private val me: String? get() = session.credentials.value?.username

    private fun mine(r: RequestEntity) = r.requestedBy.equals(me, ignoreCase = true)

    /** This user's requests, newest first. */
    val requests: Flow<List<RequestEntity>> =
        combine(dao.all(), session.credentials) { all, creds -> all.filter { it.requestedBy.equals(creds?.username, ignoreCase = true) } }

    // --- search ---

    private var catalog: Pair<String, RequestMatching.LocalCatalog>? = null
    @Volatile private var catalogVersion = 0

    private suspend fun localCatalog(): RequestMatching.LocalCatalog = withContext(Dispatchers.Default) {
        val key = "${session.lastSyncAt}:$catalogVersion"
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
            .also { synchronized(searchCache) { searchCache[query.lowercase()] = System.currentTimeMillis() to it } }
    }

    /** Recent Tidal searches (5 minutes), so retyping or going back shows results instantly. */
    private val searchCache = object : LinkedHashMap<String, Pair<Long, TidalSearchResults>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, TidalSearchResults>>?) = size > 30
    }

    fun cachedTidalSearch(query: String): TidalSearchResults? = synchronized(searchCache) {
        searchCache[query.lowercase()]?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 }?.second
    }

    /** A cheap call that opens (and keeps alive) the connection to Tidarr, and caches the country code. */
    suspend fun warmUp() {
        tidarr.isAuthActive()
        tidarr.countryCode()
    }

    private val artistIds = im.flume.hearth.api.TtlCache<String, String>(max = 100, ttlMs = 10 * 60_000L)
    private val albumIds = im.flume.hearth.api.TtlCache<String, String>(max = 100, ttlMs = 10 * 60_000L)

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
    private val _followIntents = kotlinx.coroutines.flow.MutableStateFlow(prefs.getStringSet("follow", emptySet()).orEmpty().toSet())

    /** Names of artists followed before the server had them; they're followed for real once their music arrives. */
    val followIntents: kotlinx.coroutines.flow.StateFlow<Set<String>> = _followIntents

    fun setFollowIntent(name: String, follow: Boolean) {
        val n = im.flume.hearth.data.SearchIndex.norm(name)
        val next = if (follow) _followIntents.value + n else _followIntents.value - n
        _followIntents.value = next
        prefs.edit().putStringSet("follow", next).apply()
    }

    fun isFollowIntent(name: String): Boolean = im.flume.hearth.data.SearchIndex.norm(name) in _followIntents.value

    /** Follows the server's artist for any arrived music whose artist was followed early. */
    private suspend fun applyFollowIntents(credits: List<Pair<String, String?>>) {
        if (_followIntents.value.isEmpty()) return
        for ((artist, artistId) in credits) {
            if (artistId == null) continue
            val hit = _followIntents.value.firstOrNull { RequestMatching.artistMatches(listOf(it), artist) } ?: continue
            runCatching { library.setArtistFollowed(artistId, true) }.onSuccess { setFollowIntent(hit, false) }
        }
    }

    /** Checks that Tidarr answers at [url] and can search. Returns null when fine, or what went wrong. */
    suspend fun testConnection(url: String, key: String?): String? = runCatching {
        val cfg = TidarrConfig(TidarrClient.normalizeUrl(url), key?.trim()?.takeIf { it.isNotEmpty() })
        tidarr.isAuthActive(cfg)
        tidarr.search("test", limit = 1, cfg = cfg)
    }.exceptionOrNull()?.let { im.flume.hearth.util.userMessage(it, "Couldn't reach Tidarr") }

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
    enum class Outcome { REQUESTED, ALREADY_REQUESTED, ALREADY_ON_SERVER }

    /**
     * Asks Tidarr for [draft] (made by [track], [album] or [artist]). Something already requested just
     * keeps its status; a failed one is retried. Songs and albums the server turns out to have already
     * are liked and downloaded straight away instead. Throws (with a safe message) if Tidarr can't be reached.
     */
    suspend fun request(draft: RequestEntity): Outcome {
        val existing = dao.get(draft.type, draft.tidalId)
        if (existing != null && mine(existing) && existing.status != RequestStatus.FAILED) return Outcome.ALREADY_REQUESTED
        if (existing != null && mine(existing)) { retry(existing); return Outcome.REQUESTED }
        if (draft.type != TidalType.ARTIST.api) {
            val found = runCatching { findOnServer(draft) }.getOrNull()
            if (found != null) {
                complete(draft.copy(status = RequestStatus.ADDING), found, notify = false)
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

    suspend fun clearFinished() = dao.clearDone()

    // --- tracking ---

    private val pollLock = Mutex()
    private var foreground = false
    private var loop: Job? = null

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

    private suspend fun hasActive() = dao.active().any(::mine)

    private fun ensureTracking() {
        scheduleBackground()
        scope.launch(Dispatchers.Main) { if (foreground) startLoop() }
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                val more = try { poll() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    logHandledError(context, "requests poll", e); true
                }
                if (!more) break
                delay(FOREGROUND_POLL_MS)
            }
        }
    }

    private fun scheduleBackground() {
        if (session.credentials.value == null) return
        val request = PeriodicWorkRequestBuilder<RequestsWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * One round: reads Tidarr's queue, moves requests along, and looks on the server for finished
     * ones. Returns true while any of this user's requests are still in progress.
     */
    suspend fun poll(): Boolean = pollLock.withLock {
        if (me == null) return@withLock false
        val active = dao.active().filter(::mine)
        if (active.isEmpty()) return@withLock false
        val now = System.currentTimeMillis()

        var justFinished = false
        if (config() != null && active.any { it.status == RequestStatus.REQUESTED || it.status == RequestStatus.DOWNLOADING }) {
            val queue = runCatching { tidarr.queue() }.getOrNull()
            if (queue != null) {
                val byId = queue.associateBy { it.id }
                active.forEach { r ->
                    val next = RequestTracking.afterQueue(r, byId[r.tidalId], now)
                    if (next != r) dao.upsert(next)
                    if (next.status == RequestStatus.ADDING && r.status != RequestStatus.ADDING) justFinished = true
                }
            }
        }
        // Tidarr's own rescan can fail (e.g. a wrong Navidrome password in its settings), so ask the
        // server to scan too. Both users are admins; if this one isn't, the folder watcher still helps.
        if (justFinished) api.startScan()

        val adding = dao.active().filter { mine(it) && it.status == RequestStatus.ADDING }
        // Long overdue (e.g. scan status never answered): one last look, then it failed.
        for (r in adding.filter { RequestTracking.addingTimedOut(it, now) }) {
            val result = runCatching { findOnServer(r) }
            if (result.isFailure) continue
            val found = result.getOrNull()
            if (found != null) complete(r, found, notify = true) else giveUp(r)
        }
        val due = adding.filter { !RequestTracking.addingTimedOut(it, now) && RequestTracking.lookupDue(it, now) }
        if (due.isNotEmpty() && awaitScanIdle()) {
            for (r in due) {
                val result = runCatching { findOnServer(r) }
                if (result.isFailure) continue // server unreachable: not an attempt
                val found = result.getOrNull()
                if (found != null) { complete(r, found, notify = true); continue }
                val next = r.copy(lookupAttempts = r.lookupAttempts + 1, updatedAt = System.currentTimeMillis())
                if (RequestTracking.lookupExhausted(next)) giveUp(next) else dao.upsert(next)
            }
        }
        dao.active().any(::mine)
    }

    /** Waits (up to 5 min) for Navidrome to finish scanning. False if it's still going or can't be asked. */
    private suspend fun awaitScanIdle(): Boolean {
        val deadline = System.currentTimeMillis() + SCAN_WAIT_MS
        while (true) {
            val status = api.scanStatus() ?: return false
            if (!status.scanning) return true
            if (System.currentTimeMillis() > deadline) return false
            delay(SCAN_POLL_MS)
        }
    }

    private sealed interface Found {
        data class Songs(val songs: List<SongEntity>) : Found
        data class Album(val album: AlbumEntity, val songs: List<SongEntity>) : Found
        data class Artist(val artist: ArtistEntity) : Found
    }

    /** Looks for the request on the server. Null when it isn't there (yet); throws when the server can't be asked. */
    private suspend fun findOnServer(r: RequestEntity): Found? {
        val artist = RequestMatching.splitArtists(r.artist).first()
        val title = RequestMatching.searchTitle(r.title)
        return when (TidalType.of(r.type)) {
            TidalType.TRACK -> {
                for (q in listOf("$title $artist", title).distinct()) {
                    val candidates = api.search(q, songCount = 20, albumCount = 0, artistCount = 0).song
                        .filterNot { it.isDir }.map { it.toEntity() }
                    val song = RequestMatching.pickTrack(candidates, r.title, r.artist, r.isrc, r.durationSec)
                    if (song != null) return Found.Songs(listOf(song))
                }
                null
            }
            TidalType.ALBUM -> {
                for (q in listOf("$title $artist", title).distinct()) {
                    val candidates = api.search(q, songCount = 0, albumCount = 20, artistCount = 0).album.map { it.toEntity() }
                    val album = RequestMatching.pickAlbum(candidates, r.title, r.artist) ?: continue
                    val full = api.album(album.id) ?: continue
                    return Found.Album(full.toEntity(), full.song.map { it.toEntity() })
                }
                null
            }
            TidalType.ARTIST -> {
                val candidates = api.search(r.title, songCount = 0, albumCount = 0, artistCount = 10).artist.map { it.toEntity() }
                RequestMatching.pickArtist(candidates, r.title)?.let { Found.Artist(it) }
            }
        }
    }

    /** Puts what was found into the library, likes or saves it, downloads it, and marks the request done. */
    private suspend fun complete(r: RequestEntity, found: Found, notify: Boolean) {
        val lib = db.library()
        val matched: List<String>
        var route: String? = null
        when (found) {
            is Found.Songs -> {
                lib.insertMissingSongs(found.songs)
                // Songs on albums this phone hasn't synced yet get their album row too, so "Go to album" works.
                found.songs.mapNotNull { it.albumId }.distinct().filter { lib.albumOnce(it) == null }.forEach { id ->
                    runCatching { api.album(id) }.getOrNull()?.let { lib.insertMissingAlbums(listOf(it.toEntity())) }
                }
                found.songs.forEach { s -> runCatching { library.setStarred(s.id, true) } }
                applyFollowIntents(found.songs.map { it.artist to it.artistId })
                downloads.download(library.songsByIds(found.songs.map { it.id }))
                matched = found.songs.map { it.id }
                route = found.songs.firstOrNull()?.albumId?.let { "album/${Uri.encode(it)}" }
            }
            is Found.Album -> {
                lib.insertMissingSongs(found.songs)
                lib.insertMissingAlbums(listOf(found.album))
                runCatching { library.setAlbumSaved(found.album.id, true) }
                applyFollowIntents(listOf(found.album.artist to found.album.artistId))
                downloads.pinAndDownload(DownloadRepository.KIND_ALBUM, found.album.id, found.songs)
                matched = listOf(found.album.id)
                route = "album/${Uri.encode(found.album.id)}"
            }
            is Found.Artist -> {
                lib.insertMissingArtists(listOf(found.artist))
                runCatching { library.setArtistFollowed(found.artist.id, true) }
                setFollowIntent(found.artist.name, false)
                matched = listOf(found.artist.id)
                route = "artist/${Uri.encode(found.artist.id)}"
                // The whole discography arrives with the next full sync; nothing is downloaded to the phone.
                scope.launch { runCatching { sync.syncIfNeeded() } }
            }
        }
        catalogVersion++
        dao.upsert(
            r.copy(
                status = RequestStatus.DONE, errorMessage = null, matchedIds = matched.joinToString(","),
                updatedAt = System.currentTimeMillis(),
            )
        )
        if (notify) {
            val title = if (found is Found.Artist) "Added to the server: ${r.title}" else "Added to your phone: ${r.title}"
            notifyDone(r, title, r.artist.takeIf { found !is Found.Artist }, route)
        }
    }

    /**
     * Tidarr said "finished" but nothing turned up on the server. That's a failure, not a success:
     * Tidarr marks a download that produced no files as finished (e.g. on a free Tidal account).
     * A sync still runs in case it did arrive under a name that couldn't be matched.
     */
    private suspend fun giveUp(r: RequestEntity) {
        dao.upsert(RequestTracking.gaveUp(r, System.currentTimeMillis()))
        scope.launch { runCatching { sync.syncIfNeeded() } }
    }

    private fun notifyDone(r: RequestEntity, title: String, text: String?, route: String?) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "New music", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, route ?: "downloads")
            val id = "${r.type}:${r.tidalId}".hashCode()
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .apply { text?.let { setContentText(it) } }
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        }
    }

    companion object {
        const val WORK_NAME = "requests"
        private const val CHANNEL = "requests"
        private const val FOREGROUND_POLL_MS = 20_000L
        private const val SCAN_WAIT_MS = 5 * 60_000L
        private const val SCAN_POLL_MS = 10_000L
    }
}

/** Follows requests every 15 minutes while the app is closed; cancels itself once nothing is in progress. */
class RequestsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as HearthApp).container
        val more = runCatching { c.requests.poll() }.getOrDefault(true)
        if (!more) WorkManager.getInstance(applicationContext).cancelUniqueWork(RequestsRepository.WORK_NAME)
        return Result.success()
    }
}
