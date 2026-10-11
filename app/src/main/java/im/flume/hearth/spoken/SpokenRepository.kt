package im.flume.hearth.spoken

import android.content.Context
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
import im.flume.hearth.api.AbsAuthException
import im.flume.hearth.api.AbsClient
import im.flume.hearth.api.AbsFolder
import im.flume.hearth.api.AbsLibrary
import im.flume.hearth.api.AbsLibraryItem
import im.flume.hearth.api.AbsPodcastSearchResult
import im.flume.hearth.api.AbsTokens
import im.flume.hearth.api.ServerClock
import im.flume.hearth.data.AppDatabase
import im.flume.hearth.data.NetworkMonitor
import im.flume.hearth.data.SessionStore
import im.flume.hearth.util.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** An episode or book with progress, for "Continue listening". */
data class ContinueItem(
    val libraryItemId: String,
    val episodeId: String?,
    val title: String,
    /** Show name for an episode, author for a book. */
    val subtitle: String?,
    val positionSec: Double,
    val durationSec: Double,
    /** Server clock, epoch ms. */
    val lastPlayedAt: Long,
) {
    val isBook: Boolean get() = episodeId == null
    val fraction: Float get() = if (durationSec > 0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f
}

data class OpmlImportResult(val added: List<String>, val followedExisting: List<String>, val failed: List<Pair<String, String>>)

/**
 * Podcasts and audiobooks from Audiobookshelf: the account, the local catalogue (instant and offline),
 * following, discovery, progress and downloads. The UI talks to this; everything is a Flow from Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpokenRepository(
    private val context: Context,
    private val db: AppDatabase,
    http: OkHttpClient,
    session: SessionStore,
    network: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    val store = AbsAccountStore(context)
    val api = AbsClient(http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build(), store, ServerClock(store.clockOffsetMs))
    val progress = SpokenProgress(context, db, store, api, scope)
    val downloads = SpokenDownloads(context, db, api, session, { store.settings.value }, network, scope)
    private val dao = db.spoken()
    private val syncLock = Mutex()

    val account: StateFlow<AbsAccount?> = store.account
    val settings: StateFlow<SpokenSettings> = store.settings

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Why the last sync failed, in plain words (null after a good one). */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** "Resumed from where you left off on another device". */
    val resumeNotices: SharedFlow<ResumeNotice> = progress.resumeNotices

    private var libraries: List<AbsLibrary> = emptyList()

    init {
        progress.onFinished = { id, ep -> downloads.onFinished(id, ep) }
        downloads.ensureBookDetails = { id -> runCatching { fetchItem(id) } }
    }

    /** App start: sync if signed in, send unsent progress, keep the periodic sync scheduled. */
    fun start() {
        if (store.account.value != null) {
            schedulePeriodicSync()
            scope.launch { refresh(force = false) }
        }
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (store.account.value != null) scope.launch { refresh(force = false) }
                }
            })
        }
    }

    // ------------------------------------------------------------------ account

    /** Signs in. Returns null on success, or what went wrong in plain words. The password isn't kept. */
    suspend fun signIn(serverUrl: String, username: String, password: String): String? {
        val url = AbsClient.normalizeUrl(serverUrl.ifBlank { AbsAccountStore.DEFAULT_SERVER_URL })
        return try {
            val resp = api.login(url, username, password)
            val access = resp.user.accessToken ?: return "The server didn't return a login token (is it Audiobookshelf 2.26 or newer?)"
            val refresh = resp.user.refreshToken ?: return "The server didn't return a refresh token"
            val previous = store.account.value
            if (previous != null && previous.userId != resp.user.id) progress.clearLocal()
            store.signedIn(url, resp.user.username, resp.user.id, AbsTokens(access, refresh))
            store.clockOffsetMs = api.clock.offsetMs
            schedulePeriodicSync()
            scope.launch { refresh(force = true) }
            null
        } catch (e: Exception) {
            userMessage(e)
        }
    }

    /** Checks the saved login works. Null when it does, otherwise what's wrong. */
    suspend fun testConnection(): String? = try {
        val me = api.me()
        if (store.account.value?.userId != me.id) "Signed in as a different user (${me.username})" else null
    } catch (e: Exception) {
        userMessage(e)
    }

    /**
     * Signs out: tries to send unsent progress first (anything left waits for this user's next sign-in),
     * ends the server session, and forgets this user's progress on the phone. Downloads are kept.
     */
    suspend fun signOut() {
        runCatching { progress.flush() }
        runCatching { api.logout() }
        store.signOut()
        progress.clearLocal()
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK)
    }

    private val username: String? get() = store.account.value?.username

    // ------------------------------------------------------------------ catalogue (Flows for the UI)

    fun podcasts(): Flow<List<PodcastEntity>> = dao.podcasts()

    /** Podcasts this user follows. */
    fun followedPodcasts(): Flow<List<PodcastEntity>> =
        store.account.map { it?.username }.distinctUntilChanged().flatMapLatest { u -> if (u == null) flowOf(emptyList()) else dao.podcastsTagged(FollowTags.tagFor(u)) }

    fun podcast(id: String): Flow<PodcastEntity?> = dao.podcast(id)

    fun episodes(podcastId: String): Flow<List<EpisodeRow>> = dao.episodes(podcastId)

    fun episode(episodeId: String): Flow<EpisodeRow?> = dao.episode(episodeId)

    /** Newest episodes across the shows this user follows. */
    fun latestEpisodes(limit: Int = 50): Flow<List<EpisodeRow>> =
        store.account.map { it?.username }.distinctUntilChanged().flatMapLatest { u -> if (u == null) flowOf(emptyList()) else dao.latestEpisodesTagged(FollowTags.tagFor(u), limit) }

    /** Started, unfinished episodes and books, most recently played first. */
    fun continueListening(limit: Int = 20): Flow<List<ContinueItem>> =
        combine(dao.episodesInProgress(limit), dao.booksInProgress(limit)) { eps, books ->
            (eps.map { ContinueItem(it.episode.libraryItemId, it.episode.id, it.episode.title, it.podcastTitle, it.progressSec ?: 0.0, it.episode.durationSec, it.progressUpdatedAt ?: 0) } +
                books.map { ContinueItem(it.book.id, null, it.book.title, it.book.author, it.progressSec ?: 0.0, it.book.durationSec, it.progressUpdatedAt ?: 0) })
                .sortedByDescending { it.lastPlayedAt }.take(limit)
        }

    fun books(): Flow<List<BookRow>> = dao.books()

    /** Every episode on the phone, for search. */
    suspend fun allEpisodes(): List<EpisodeRow> = dao.allEpisodesOnce()

    /** Downloads with what they are (episode / book title, show / author), for the Downloads page. */
    fun downloadRows(): Flow<List<SpokenDownloadRow>> = dao.downloadRows()

    fun book(id: String): Flow<BookRow?> = dao.book(id)

    /** Chapters of a book ([episodeId] null) or episode; times on the whole item's timeline. */
    fun chapters(libraryItemId: String, episodeId: String? = null): Flow<List<ChapterEntity>> = dao.chaptersFlow(libraryItemId, episodeId.orEmpty())

    fun progress(libraryItemId: String, episodeId: String? = null): Flow<SpokenProgressEntity?> = progress.progressFlow(libraryItemId, episodeId)

    /** When each podcast's / book's details last changed on the server, to version its cover URL. */
    private val coverVersions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        scope.launch { dao.podcasts().collect { list -> list.forEach { coverVersions[it.id] = it.updatedAt } } }
        scope.launch { dao.books().collect { list -> list.forEach { coverVersions[it.book.id] = it.book.updatedAt } } }
    }

    /** Cover image URL (needs no login), for Coil. */
    fun coverUrl(libraryItemId: String, width: Int = 600): String? = api.coverUrl(libraryItemId, width, coverVersions[libraryItemId])

    fun isFollowing(podcast: PodcastEntity): Boolean = username?.let { FollowTags.isFollowing(podcast.tagList, it) } == true

    /** Usernames (lowercase) following this podcast. */
    fun followers(podcast: PodcastEntity): List<String> = FollowTags.followers(podcast.tagList)

    // ------------------------------------------------------------------ actions

    suspend fun markFinished(libraryItemId: String, episodeId: String?, finished: Boolean) {
        val duration = if (episodeId != null) dao.episodeOnce(episodeId)?.episode?.durationSec ?: 0.0 else dao.bookOnce(libraryItemId)?.durationSec ?: 0.0
        progress.setFinished(libraryItemId, episodeId, finished, duration)
    }

    /**
     * Follows (or unfollows) a podcast for this user: reads the podcast's tags fresh from the server,
     * adds or removes only this user's follower tag, writes them back, and reads again to check another
     * phone didn't write at the same moment (retrying if it did). Following also turns on the server's
     * auto-download for the show. Returns null on success, or the reason it failed.
     */
    suspend fun setFollowing(podcastId: String, follow: Boolean): String? {
        val user = username ?: return "Not signed in to podcasts & audiobooks"
        val before = dao.podcastOnce(podcastId)
        before?.let { dao.setPodcastTags(podcastId, FollowTags.withFollower(it.tagList, user, follow).joinToString("\n")) } // instant UI
        try {
            repeat(3) {
                val item = api.item(podcastId) ?: run {
                    dao.deletePodcasts(listOf(podcastId))
                    return "This podcast is no longer on the server"
                }
                val tags = item.media.tags
                val next = FollowTags.withFollower(tags, user, follow)
                if (next != tags) api.setTags(podcastId, next)
                if (follow && item.media.autoDownloadEpisodes != true) {
                    api.setAutoDownload(podcastId, true)
                    dao.setPodcastAutoDownload(podcastId, true)
                }
                val check = api.item(podcastId)?.media?.tags ?: next
                dao.setPodcastTags(podcastId, check.joinToString("\n"))
                if (FollowTags.isFollowing(check, user) == follow) {
                    if (follow) dao.podcastOnce(podcastId)?.let { downloads.planAutoDownloads(listOf(it)) } else downloads.onUnfollowed(podcastId)
                    return null
                }
            }
            return "Couldn't save that on the server; try again"
        } catch (e: Exception) {
            before?.let { dao.setPodcastTags(podcastId, it.tags) }
            return userMessage(e)
        }
    }

    suspend fun follow(podcastId: String): String? = setFollowing(podcastId, true)

    suspend fun unfollow(podcastId: String): String? = setFollowing(podcastId, false)

    /** Searches the podcast directory (iTunes) through the server. */
    suspend fun searchDirectory(term: String): List<AbsPodcastSearchResult> = api.searchPodcasts(term)

    /** The podcast already in the library with this feed, if any. */
    suspend fun findByFeed(feedUrl: String): PodcastEntity? = dao.podcastsOnce().firstOrNull { sameFeed(it.feedUrl, feedUrl) }

    /**
     * Adds a podcast to the server's Podcasts library (or finds it if it's already there) and, if
     * [follow], follows it. A new show gets its newest [downloadLatest] episodes fetched by the server
     * right away, so there's something to play. Returns the podcast's id.
     */
    suspend fun addPodcast(feedUrl: String, directoryEntry: AbsPodcastSearchResult? = null, follow: Boolean = true, downloadLatest: Int = 3): Result<String> = runCatching {
        val user = username ?: throw AbsAuthException("Not signed in to podcasts & audiobooks")
        findByFeed(feedUrl)?.let { existing ->
            if (follow) setFollowing(existing.id, true)?.let { throw IllegalStateException(it) }
            return@runCatching existing.id
        }
        val (library, folder) = podcastFolder()
        val feed = api.podcastFeed(feedUrl)
        val item = api.createPodcast(
            library.id, folder.id, folder.fullPath, feed.metadata, feedUrl, directoryEntry,
            tags = if (follow) listOf(FollowTags.tagFor(user)) else emptyList(), autoDownload = follow,
        )
        dao.upsertPodcasts(listOf(item.toPodcast(null)))
        if (downloadLatest > 0) {
            val newest = feed.episodes.sortedByDescending { e -> e.jsonObject["publishedAt"]?.jsonPrimitive?.longOrNull ?: 0L }.take(downloadLatest)
            if (newest.isNotEmpty()) runCatching { api.downloadEpisodesOnServer(item.id, JsonArray(newest)) }
        }
        item.id
    }

    /**
     * Imports an OPML export (e.g. from another podcast app): every feed is added if it isn't in the
     * library yet and followed for this user. One at a time, so a big file doesn't swamp the server.
     */
    suspend fun importOpml(opmlText: String): OpmlImportResult {
        val feeds = api.parseOpml(opmlText)
        val added = ArrayList<String>(); val existing = ArrayList<String>(); val failed = ArrayList<Pair<String, String>>()
        for (f in feeds) {
            val name = f.title ?: f.feedUrl
            val already = findByFeed(f.feedUrl)
            addPodcast(f.feedUrl, follow = true, downloadLatest = 1).fold(
                onSuccess = { if (already != null) existing += name else added += name },
                onFailure = { failed += name to userMessage(it) },
            )
        }
        scope.launch { refresh(force = true) }
        return OpmlImportResult(added, existing, failed)
    }

    private suspend fun podcastFolder(): Pair<AbsLibrary, AbsFolder> {
        if (libraries.isEmpty()) libraries = api.libraries()
        val lib = libraries.firstOrNull { it.mediaType == "podcast" } ?: throw IllegalStateException("The server has no podcast library")
        return lib to (lib.folders.firstOrNull() ?: throw IllegalStateException("The podcast library has no folder"))
    }

    private fun sameFeed(a: String?, b: String?): Boolean = FeedUrls.same(a, b)

    // ------------------------------------------------------------------ sync

    /**
     * Brings the catalogue and progress up to date: sends unsent progress, lists both libraries, fetches
     * episodes / tracks only for items that changed (or every 12 hours), merges progress, then
     * auto-downloads and auto-deletes. [force] skips the "synced in the last few minutes" shortcut.
     */
    suspend fun refresh(force: Boolean = true): Boolean = syncLock.withLock {
        val account = store.account.value ?: return@withLock false
        if (account.needsSignIn) { _lastError.value = "Sign in to podcasts & audiobooks again in Settings"; return@withLock false }
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAt < 5 * 60_000) return@withLock true
        _syncing.value = true
        try {
            progress.flush()
            libraries = api.libraries()
            val full = now - store.lastFullSyncAt > 12 * 3_600_000L
            for (lib in libraries) {
                val items = api.allLibraryItems(lib.id)
                when (lib.mediaType) {
                    "podcast" -> syncPodcasts(lib, items, full)
                    "book" -> syncBooks(lib, items, full)
                }
            }
            // Podcasts / books from libraries that no longer exist.
            val libIds = libraries.mapTo(HashSet()) { it.id }
            dao.podcastsOnce().filter { it.libraryId !in libIds }.map { it.id }.takeIf { it.isNotEmpty() }?.let { removeItems(it, podcast = true) }
            dao.booksOnce().filter { it.libraryId !in libIds }.map { it.id }.takeIf { it.isNotEmpty() }?.let { removeItems(it, podcast = false) }

            progress.applyServer(api.me().mediaProgress)
            store.clockOffsetMs = api.clock.offsetMs
            if (full) store.lastFullSyncAt = now
            lastSyncAt = now
            username?.let { u -> downloads.planAutoDownloads(dao.podcastsTaggedOnce(FollowTags.tagFor(u))) }
            downloads.autoDeleteFinished()
            _lastError.value = null
            true
        } catch (e: Exception) {
            _lastError.value = userMessage(e)
            im.flume.hearth.logHandledError(context, "spoken sync", e)
            false
        } finally {
            _syncing.value = false
        }
    }

    @Volatile private var lastSyncAt = 0L

    private suspend fun syncPodcasts(lib: AbsLibrary, items: List<AbsLibraryItem>, full: Boolean) {
        val local = dao.podcastsOnce().filter { it.libraryId == lib.id }.associateBy { it.id }
        dao.upsertPodcasts(items.map { it.toPodcast(local[it.id]) })
        val gone = local.keys - items.mapTo(HashSet()) { it.id }
        if (gone.isNotEmpty()) removeItems(gone.toList(), podcast = true)
        for (item in items) {
            val old = local[item.id]
            if (full || old == null || old.updatedAt != item.updatedAt || old.episodesFetchedAt == 0L) fetchItem(item.id)
        }
    }

    private suspend fun syncBooks(lib: AbsLibrary, items: List<AbsLibraryItem>, full: Boolean) {
        val local = dao.booksOnce().filter { it.libraryId == lib.id }.associateBy { it.id }
        dao.upsertBooks(items.map { it.toBook(local[it.id]) })
        val gone = local.keys - items.mapTo(HashSet()) { it.id }
        if (gone.isNotEmpty()) removeItems(gone.toList(), podcast = false)
        for (item in items) {
            val old = local[item.id]
            if (full || old == null || old.updatedAt != item.updatedAt || old.detailsFetchedAt == 0L) fetchItem(item.id)
        }
    }

    private suspend fun removeItems(ids: List<String>, podcast: Boolean) {
        downloads.onItemsRemoved(ids)
        if (podcast) { dao.deleteEpisodesOf(ids); dao.deletePodcasts(ids) } else { dao.deleteTracks(ids); dao.deleteBooks(ids) }
        dao.deleteChaptersOf(ids)
    }

    /** Fetches one podcast's episodes or one book's tracks and chapters (pull-to-refresh on its page). */
    suspend fun fetchItem(libraryItemId: String) {
        val item = api.item(libraryItemId) ?: run {
            val isPodcast = dao.podcastOnce(libraryItemId) != null
            removeItems(listOf(libraryItemId), podcast = isPodcast)
            return
        }
        val now = System.currentTimeMillis()
        if (item.mediaType == "podcast") {
            val old = dao.podcastOnce(libraryItemId)
            dao.upsertPodcasts(listOf(item.toPodcast(old)))
            val episodes = item.media.episodes.orEmpty()
            val oldEpisodes = dao.episodesOnce(libraryItemId).associate { it.episode.id to it.episode.ino }
            val removed = oldEpisodes.keys - episodes.mapTo(HashSet()) { it.id }
            if (removed.isNotEmpty()) downloads.onEpisodesRemoved(libraryItemId, removed, oldEpisodes)
            dao.replaceEpisodes(
                libraryItemId,
                episodes.map { it.toEntity(libraryItemId) },
                episodes.flatMap { e -> e.chapters.mapIndexed { i, c -> ChapterEntity(libraryItemId, e.id, i, c.start, c.end, c.title) } },
                removed.toList(), now, item.updatedAt,
            )
        } else {
            val tracks = item.media.tracks.orEmpty().sortedBy { it.index }
            var offset = 0.0
            val trackRows = tracks.mapIndexedNotNull { i, t ->
                val ino = t.ino ?: t.contentUrl?.substringAfterLast('/') ?: return@mapIndexedNotNull null
                TrackEntity(libraryItemId, i, ino, offset, t.duration, t.mimeType, t.metadata?.size ?: 0).also { offset += t.duration }
            }
            val chapters = item.media.chapters.orEmpty().mapIndexed { i, c -> ChapterEntity(libraryItemId, "", i, c.start, c.end, c.title) }
            dao.replaceBookDetails(item.toBook(dao.bookOnce(libraryItemId)).copy(detailsFetchedAt = now, updatedAt = item.updatedAt), trackRows, chapters)
        }
    }

    private fun schedulePeriodicSync() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SpokenSyncWorker>(4, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }

    companion object {
        const val SYNC_WORK = "abs-sync"
    }
}

/** Every few hours: send unsent progress, refresh the catalogue, auto-download new episodes. */
class SpokenSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val spoken = (applicationContext as HearthApp).container.spoken
        if (spoken.account.value == null) return Result.success()
        spoken.refresh(force = true)
        return Result.success()
    }
}

// ------------------------------------------------------------------ mapping

internal fun AbsLibraryItem.toPodcast(old: PodcastEntity?): PodcastEntity = PodcastEntity(
    id = id,
    libraryId = libraryId ?: old?.libraryId.orEmpty(),
    title = media.metadata.title ?: old?.title ?: "Untitled podcast",
    author = media.metadata.author,
    description = media.metadata.descriptionPlain ?: media.metadata.description,
    feedUrl = media.metadata.feedUrl,
    imageUrl = media.metadata.imageUrl,
    language = media.metadata.language,
    explicit = media.metadata.explicit == true,
    tags = media.tags.joinToString("\n"),
    autoDownloadEpisodes = media.autoDownloadEpisodes == true,
    numEpisodes = media.numEpisodes ?: media.episodes?.size ?: old?.numEpisodes ?: 0,
    addedAt = addedAt,
    // Kept as the version the episodes were fetched at; fetchItem sets it.
    updatedAt = old?.updatedAt ?: 0,
    episodesFetchedAt = old?.episodesFetchedAt ?: 0,
)

internal fun AbsLibraryItem.toBook(old: BookEntity?): BookEntity {
    val m = media.metadata
    return BookEntity(
        id = id,
        libraryId = libraryId ?: old?.libraryId.orEmpty(),
        title = m.title ?: old?.title ?: "Untitled book",
        subtitle = m.subtitle,
        author = m.authorName ?: m.authors.joinToString(", ") { it.name }.ifEmpty { null },
        narrator = m.narratorName ?: m.narrators.joinToString(", ").ifEmpty { null },
        series = m.seriesName ?: m.series.joinToString(", ") { s -> s.sequence?.let { "${s.name} #$it" } ?: s.name }.ifEmpty { null },
        description = m.descriptionPlain ?: m.description,
        publishedYear = m.publishedYear,
        durationSec = media.duration ?: old?.durationSec ?: 0.0,
        sizeBytes = media.size ?: old?.sizeBytes ?: 0,
        numTracks = media.numTracks ?: media.tracks?.size ?: old?.numTracks ?: 0,
        numChapters = media.numChapters ?: media.chapters?.size ?: old?.numChapters ?: 0,
        tags = media.tags.joinToString("\n"),
        addedAt = addedAt,
        updatedAt = old?.updatedAt ?: 0,
        detailsFetchedAt = old?.detailsFetchedAt ?: 0,
    )
}

internal fun im.flume.hearth.api.AbsEpisode.toEntity(libraryItemId: String): EpisodeEntity {
    val file = audioTrack?.let { it.ino to it } ?: audioFile?.let { it.ino to null }
    return EpisodeEntity(
        id = id,
        libraryItemId = libraryItemId,
        title = title.ifBlank { "Untitled episode" },
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        description = description,
        publishedAt = publishedAt ?: addedAt,
        durationSec = duration ?: audioFile?.duration ?: audioTrack?.duration ?: 0.0,
        sizeBytes = size ?: audioFile?.metadata?.size ?: 0,
        ino = file?.first,
        mimeType = audioFile?.mimeType ?: audioTrack?.mimeType,
        season = season?.takeIf { it.isNotBlank() },
        episode = episode?.takeIf { it.isNotBlank() },
        episodeType = episodeType,
        addedAt = addedAt,
    )
}
