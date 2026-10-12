// Podcasts and audiobooks: the account, the catalogue the UI reads, and the entry points for following,
// adding and syncing (the work itself is in SpokenFollows, SpokenAdd and SpokenSync).
package im.flume.hearth.spoken

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import im.flume.hearth.core.network.AbsClient
import im.flume.hearth.core.network.AbsLibraryItem
import im.flume.hearth.core.network.AbsPodcastSearchResult
import im.flume.hearth.core.network.AbsTokens
import im.flume.hearth.core.network.ServerClock
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.core.data.NetworkMonitor
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.core.common.userMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import im.flume.hearth.core.data.spoken.AbsAccount
import im.flume.hearth.core.data.spoken.AbsAccountStore
import im.flume.hearth.core.data.spoken.BookRow
import im.flume.hearth.core.data.spoken.ChapterEntity
import im.flume.hearth.core.data.spoken.EpisodeRow
import im.flume.hearth.core.data.spoken.PodcastEntity
import im.flume.hearth.core.data.spoken.SpokenDownloadRow
import im.flume.hearth.core.data.spoken.SpokenProgressEntity
import im.flume.hearth.core.data.spoken.SpokenSettings

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
    internal val context: Context,
    private val db: AppDatabase,
    http: OkHttpClient,
    session: SessionStore,
    private val network: NetworkMonitor,
    internal val scope: CoroutineScope,
) {
    val store = AbsAccountStore(context)
    val api = AbsClient(http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build(), store, ServerClock(store.clockOffsetMs))
    val progress = SpokenProgress(context, db, store, api, scope)
    val downloads = SpokenDownloads(context, db, api, session, { store.settings.value }, network, scope)
    internal val follows = SpokenFollows(this)
    internal val sync = SpokenSync(this)
    private val adder = SpokenAdd(this)
    /** Each show's full feed, and episodes being fetched onto the server from it. */
    val feeds = ShowFeeds(context, this, scope)
    /** Audiobooks from folders on this phone, which can be added to the server. */
    val local = LocalBooks(context, db, this, scope)
    /** No network, or offline mode is on: nothing to ask the server. */
    internal val offline: Boolean get() = network.cannotStream
    internal val dao = db.spoken()

    val account: StateFlow<AbsAccount?> = store.account
    val settings: StateFlow<SpokenSettings> = store.settings

    val syncing: StateFlow<Boolean> = sync.syncing

    /** Why the last sync failed, in plain words (null after a good one). */
    val lastError: StateFlow<String?> = sync.lastError

    /** "Resumed from where you left off on another device". */
    val resumeNotices: SharedFlow<ResumeNotice> = progress.resumeNotices

    init {
        progress.onFinished = { id, ep -> downloads.onFinished(id, ep) }
        downloads.ensureBookDetails = { id -> runCatching { fetchItem(id) } }
    }

    /** App start: sync if signed in, send unsent progress, keep the periodic sync scheduled. */
    fun start() {
        local.start()
        if (store.account.value != null) {
            sync.schedulePeriodicSync()
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
            sync.schedulePeriodicSync()
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

    internal val username: String? get() = store.account.value?.username

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
    fun continueListening(limit: Int = 20): Flow<List<ContinueItem>> = mergeContinue(serverContinueListening(limit), local.inProgress(limit), limit)

    private fun serverContinueListening(limit: Int): Flow<List<ContinueItem>> =
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

    /** A server book, or a book on the phone shown the same way (see [LocalBookIds]). */
    fun book(id: String): Flow<BookRow?> = if (LocalBookIds.isLocal(id)) local.book(id).map { it?.toBookRow() } else dao.book(id)

    /**
     * What the player shows for a book: its title, writer and cover. They can change while it plays
     * (a phone book's details are looked up after a scan; the server's can be edited), so the player
     * follows this rather than what was known when it started.
     */
    fun bookDetails(id: String): Flow<SpokenBookDetails?> =
        book(id).map { r -> r?.let { SpokenBookDetails(it.book.title, it.book.author, coverUrl(id, 300)) } }.distinctUntilChanged()

    /** Chapters of a book ([episodeId] null) or episode; times on the whole item's timeline. */
    fun chapters(libraryItemId: String, episodeId: String? = null): Flow<List<ChapterEntity>> =
        if (LocalBookIds.isLocal(libraryItemId)) local.chapters(libraryItemId) else dao.chaptersFlow(libraryItemId, episodeId.orEmpty())

    fun progress(libraryItemId: String, episodeId: String? = null): Flow<SpokenProgressEntity?> = progress.progressFlow(libraryItemId, episodeId)

    /** When each podcast's / book's details last changed on the server, to version its cover URL. */
    private val coverVersions = ConcurrentHashMap<String, Long>()

    init {
        scope.launch { dao.podcasts().collect { list -> list.forEach { coverVersions[it.id] = it.updatedAt } } }
        scope.launch { dao.books().collect { list -> list.forEach { coverVersions[it.book.id] = it.book.updatedAt } } }
    }

    /** Cover image URL (needs no login), for Coil. */
    fun coverUrl(libraryItemId: String, width: Int = 600): String? =
        if (LocalBookIds.isLocal(libraryItemId)) local.coverUrl(libraryItemId) else api.coverUrl(libraryItemId, width, coverVersions[libraryItemId])

    fun isFollowing(podcast: PodcastEntity): Boolean = username?.let { FollowTags.isFollowing(podcast.tagList, it) } == true

    /** Usernames (lowercase) following this podcast. */
    fun followers(podcast: PodcastEntity): List<String> = FollowTags.followers(podcast.tagList)

    // ------------------------------------------------------------------ actions

    suspend fun markFinished(libraryItemId: String, episodeId: String?, finished: Boolean) {
        val duration = if (LocalBookIds.isLocal(libraryItemId)) local.durationOf(libraryItemId) else if (episodeId != null) dao.episodeOnce(episodeId)?.episode?.durationSec ?: 0.0 else dao.bookOnce(libraryItemId)?.durationSec ?: 0.0
        progress.setFinished(libraryItemId, episodeId, finished, duration)
    }

    /**
     * Follows (or unfollows) a podcast for this user, checked against the server ([SpokenFollows.setFollowing]).
     * Returns null on success, or the reason it failed.
     */
    suspend fun setFollowing(podcastId: String, follow: Boolean): String? = follows.setFollowing(podcastId, follow)

    suspend fun follow(podcastId: String): String? = setFollowing(podcastId, true)

    suspend fun unfollow(podcastId: String): String? = setFollowing(podcastId, false)

    /** Searches the podcast directory (iTunes) through the server. */
    suspend fun searchDirectory(term: String): List<AbsPodcastSearchResult> = api.searchPodcasts(term)

    /** The podcast already in the library with this feed, if any. */
    suspend fun findByFeed(feedUrl: String): PodcastEntity? = dao.podcastsOnce().firstOrNull { FeedUrls.same(it.feedUrl, feedUrl) }

    /** Adds a podcast to the server and, if [follow], follows it ([SpokenAdd.addPodcast]). Returns the podcast's id. */
    suspend fun addPodcast(feedUrl: String, directoryEntry: AbsPodcastSearchResult? = null, follow: Boolean = true, downloadLatest: Int = NEWEST_ON_FOLLOW): Result<String> =
        adder.addPodcast(feedUrl, directoryEntry, follow, downloadLatest)

    /** Adds and follows every feed in an OPML export ([SpokenAdd.importOpml]). */
    suspend fun importOpml(opmlText: String): OpmlImportResult = adder.importOpml(opmlText)

    // ------------------------------------------------------------------ sync

    /** Brings the catalogue and progress up to date ([SpokenSync.refresh]); [force] skips the "synced recently" shortcut. */
    suspend fun refresh(force: Boolean = true): Boolean = sync.refresh(force)

    /** Fetches one podcast's episodes or one book's tracks and chapters (pull-to-refresh on its page). */
    suspend fun fetchItem(libraryItemId: String) = sync.fetchItem(libraryItemId)

    /** Saves a podcast's episodes or a book's tracks and chapters, as just read from the server (expanded). */
    internal suspend fun storeItem(item: AbsLibraryItem) = sync.storeItem(item)

    companion object {
        const val SYNC_WORK = "abs-sync"
        /** How many of a show's latest episodes the server fetches when it's followed. */
        const val NEWEST_ON_FOLLOW = 3
        /** Longer than the Undo snackbar, so undoing an unfollow never finds the episodes already gone. */
        const val UNFOLLOW_CLEAR_DELAY_MS = 15_000L
    }
}

/** A book's title, writer and cover as the player and notification show them. */
data class SpokenBookDetails(val title: String, val author: String?, val coverUrl: String?)
