// Syncing podcasts and audiobooks from Audiobookshelf: the catalogue, each item's episodes or tracks,
// progress, and the periodic background sync.
package im.flume.hearth.spoken

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import im.flume.hearth.core.network.AbsLibrary
import im.flume.hearth.core.network.AbsLibraryItem
import im.flume.hearth.core.common.logHandledError
import im.flume.hearth.core.common.userMessage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import im.flume.hearth.core.data.spoken.ChapterEntity
import im.flume.hearth.core.data.spoken.TrackEntity

/** Keeps the phone's podcast and audiobook catalogue in step with the server. */
internal class SpokenSync(private val repo: SpokenRepository) {
    private val context get() = repo.context
    private val api get() = repo.api
    private val dao get() = repo.dao
    private val store get() = repo.store
    private val progress get() = repo.progress
    private val downloads get() = repo.downloads
    private val username get() = repo.username
    private val syncLock = Mutex()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Why the last sync failed, in plain words (null after a good one). */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** The server's libraries as of the last sync (or the last time adding a podcast needed them). */
    var libraries: List<AbsLibrary> = emptyList()

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
            logHandledError(context, "spoken sync", e)
            false
        } finally {
            _syncing.value = false
        }
    }

    @Volatile private var lastSyncAt = 0L

    private suspend fun syncPodcasts(lib: AbsLibrary, items: List<AbsLibraryItem>, full: Boolean) {
        val local = dao.podcastsOnce().filter { it.libraryId == lib.id }.associateBy { it.id }
        dao.upsertPodcasts(items.map { it.toPodcast(local[it.id]) })
        // Remembered per show, so its page can still list the feed if the server removes the show.
        items.forEach { item -> item.media.metadata.feedUrl?.let { repo.feeds.rememberShow(item.id, it) } }
        val gone = local.keys - items.mapTo(HashSet()) { it.id }
        if (gone.isNotEmpty()) removeItems(gone.toList(), podcast = true)
        for (item in items) {
            val old = local[item.id]
            if (full || old == null || old.updatedAt != item.updatedAt || old.episodesFetchedAt == 0L) fetchItem(item.id)
        }
        repo.follows.healFollows(items)
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

    suspend fun removeItems(ids: List<String>, podcast: Boolean) {
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
        storeItem(item)
    }

    /** Saves a podcast's episodes or a book's tracks and chapters, as just read from the server (expanded). */
    suspend fun storeItem(item: AbsLibraryItem) {
        val libraryItemId = item.id
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

    fun schedulePeriodicSync() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SpokenRepository.SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SpokenSyncWorker>(4, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
