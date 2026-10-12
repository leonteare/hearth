package im.flume.hearth.domain.spoken

import android.content.Context
import im.flume.hearth.core.common.AbsAuthException
import im.flume.hearth.core.network.AbsLibraryItem
import im.flume.hearth.core.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * A show's whole feed, through the server (POST /api/podcasts/feed), so its page can list every episode
 * ever published, not just the few the server has downloaded. Kept in memory and as a light JSON copy
 * in app storage (no descriptions), so the page opens instantly and offline; fetched again when older
 * than an hour or on pull-to-refresh.
 *
 * Also gets single episodes onto the server on request ([getOntoServer]): asks the server to download
 * it, watches until it shows up in the podcast, then plays / downloads it.
 */
class ShowFeeds(context: Context, private val repo: SpokenRepository, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "feeds")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    data class FeedState(val feed: CachedFeed? = null, val loading: Boolean = false, val error: String? = null)

    private val states = ConcurrentHashMap<String, MutableStateFlow<FeedState>>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The full feed objects (with descriptions and chapters) of the shows looked at most recently, by episode key. */
    private val raw = object : LinkedHashMap<String, Map<String, JsonObject>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, JsonObject>>?) = size > 3
    }

    private fun norm(feedUrl: String) = FeedUrls.norm(feedUrl) ?: feedUrl

    private fun flowFor(feedUrl: String) = states.getOrPut(norm(feedUrl)) { MutableStateFlow(FeedState()) }

    fun state(feedUrl: String): StateFlow<FeedState> = flowFor(feedUrl).asStateFlow()

    /** Loads the feed: the copy on the phone first, then the server's if that's missing, stale or [force]d. */
    suspend fun load(feedUrl: String, force: Boolean = false) {
        val k = norm(feedUrl)
        val flow = flowFor(feedUrl)
        locks.getOrPut(k) { Mutex() }.withLock {
            if (flow.value.feed == null) readDisk(feedUrl)?.let { cached -> flow.update { it.copy(feed = cached) } }
            val cur = flow.value.feed
            if (!force && cur != null && System.currentTimeMillis() - cur.fetchedAt < MAX_AGE_MS) return
            flow.update { it.copy(loading = true, error = null) }
            try {
                val fetched = fetch(feedUrl)
                flow.value = FeedState(fetched)
                writeDisk(fetched)
            } catch (e: CancellationException) {
                flow.update { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                flow.update { it.copy(loading = false, error = userMessage(e)) }
            }
        }
    }

    private suspend fun fetch(feedUrl: String): CachedFeed {
        val feed = repo.api.podcastFeed(feedUrl)
        return withContext(Dispatchers.Default) {
            val parsed = FeedParse.episodes(feed.episodes)
            synchronized(raw) { raw[norm(feedUrl)] = parsed.associate { it.first.key to it.second } }
            val m = feed.metadata
            CachedFeed(feedUrl, System.currentTimeMillis(), m.title, m.author, m.image, m.description, parsed.map { it.first })
        }
    }

    private fun fileFor(feedUrl: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(norm(feedUrl).toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    private suspend fun readDisk(feedUrl: String): CachedFeed? = withContext(Dispatchers.IO) {
        runCatching { fileFor(feedUrl).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(CachedFeed.serializer(), it) } }.getOrNull()
    }

    private suspend fun writeDisk(feed: CachedFeed) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val f = fileFor(feed.feedUrl)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(json.encodeToString(CachedFeed.serializer(), feed))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** Remembers a show's feed by its id, so its page can still show the feed after the server removes the show. */
    fun rememberShow(podcastId: String, feedUrl: String) {
        runCatching { dir.mkdirs(); File(dir, "item-$podcastId.txt").takeIf { it.readTextOrNull() != feedUrl }?.writeText(feedUrl) }
    }

    fun feedUrlForShow(podcastId: String): String? = File(dir, "item-$podcastId.txt").readTextOrNull()?.trim()?.ifEmpty { null }

    private fun File.readTextOrNull(): String? = runCatching { if (isFile) readText() else null }.getOrNull()

    // ------------------------------------------------------------------ getting episodes onto the server

    private val _fetches = MutableStateFlow<Map<String, ServerFetch>>(emptyMap())
    /** Episodes being fetched onto the server, by [fetchKey]. */
    val fetches: StateFlow<Map<String, ServerFetch>> = _fetches.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Things to tell the user (snackbar), e.g. "Still getting it". */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Plays an episode once it's on the server; set by the app (it owns the player). */
    var onReadyToPlay: ((libraryItemId: String, episodeId: String) -> Unit)? = null

    fun fetchKey(feedUrl: String, episodeKey: String) = "${norm(feedUrl)}|$episodeKey"

    /**
     * Has the server download [ep] from the show's feed, then does [then]. [podcastId] null means the
     * show isn't on the server at all yet: it's added first (not followed). Asking again while it's on
     * its way just updates what happens when it arrives.
     */
    fun getOntoServer(podcastId: String?, feedUrl: String, ep: FeedEpisode, then: FetchThen) {
        val key = fetchKey(feedUrl, ep.key)
        val existing = _fetches.value[key]
        if (existing != null && existing.phase != FetchPhase.FAILED && existing.phase != FetchPhase.READY) {
            if (then != FetchThen.NOTHING) _fetches.update { it + (key to existing.copy(then = then)) }
            return
        }
        _fetches.update { it + (key to ServerFetch(ep.key, ep.title, FetchPhase.ASKING, then, System.currentTimeMillis())) }
        scope.launch { run(key, podcastId, feedUrl, ep) }
    }

    private val creating = Mutex()

    private fun update(key: String, transform: (ServerFetch) -> ServerFetch) =
        _fetches.update { m -> m[key]?.let { m + (key to transform(it)) } ?: m }

    private fun findOnServer(item: AbsLibraryItem, ep: FeedEpisode): String? {
        val refs = item.media.episodes.orEmpty().map { ServerEpisodeRef(it.id, it.enclosure?.url, it.guid, it.title, it.publishedAt ?: it.addedAt) }
        return FeedMatch.match(listOf(ep), refs)[ep.key]
    }

    private suspend fun run(key: String, podcastId: String?, feedUrl: String, ep: FeedEpisode) {
        val started = System.currentTimeMillis()
        try {
            // Two taps at once mustn't add the show twice.
            val id = podcastId ?: creating.withLock { repo.addPodcast(feedUrl, follow = false, downloadLatest = 0).getOrThrow() }
            rememberShow(id, feedUrl)
            var item = repo.api.item(id) ?: throw IOException("This show isn't on your server any more")
            if (findOnServer(item, ep) == null) {
                val body = synchronized(raw) { raw[norm(feedUrl)]?.get(ep.key) }
                    ?: run { runCatching { load(feedUrl, force = true) }; synchronized(raw) { raw[norm(feedUrl)]?.get(ep.key) } }
                    ?: ep.toRequestJson()
                repo.api.downloadEpisodesOnServer(id, JsonArray(listOf(body)))
            }
            var toldSlow = false
            while (true) {
                findOnServer(item, ep)?.let { episodeId ->
                    repo.storeItem(item)
                    finish(key, id, episodeId, ep)
                    return
                }
                val elapsed = System.currentTimeMillis() - started
                val queue = runCatching { repo.api.episodeDownloadQueue(id) }.getOrNull()
                val index = queue?.indexOfFirst { d -> d.url == ep.enclosureUrl || (ep.guid != null && d.guid == ep.guid) }?.takeIf { it >= 0 }
                val phase = FetchProgress.phase(onServer = false, queueIndex = index, elapsedMs = elapsed)
                if (phase == FetchPhase.FAILED) throw IOException("Your server couldn't get this episode")
                update(key) { it.copy(phase = phase, queuePosition = (index ?: -1) + 1) }
                if (!toldSlow && elapsed > FetchProgress.SLOW_MS && _fetches.value[key]?.then == FetchThen.PLAY) {
                    toldSlow = true
                    _messages.tryEmit("Still getting it — it'll start when ready")
                }
                delay(FetchProgress.pollDelayMs(elapsed))
                // A blip in the connection isn't a failure: keep looking until the give-up time.
                item = try {
                    repo.api.item(id) ?: throw IOException("This show isn't on your server any more")
                } catch (e: AbsAuthException) {
                    throw e
                } catch (e: IOException) {
                    if (e.message?.contains("isn't on your server") == true) throw e
                    item
                }
            }
        } catch (e: CancellationException) {
            _fetches.update { it - key }
            throw e
        } catch (e: Exception) {
            val message = userMessage(e)
            update(key) { it.copy(phase = FetchPhase.FAILED, message = "Couldn't get it") }
            _messages.tryEmit("Couldn't get \"${ep.title}\": $message")
        }
    }

    private suspend fun finish(key: String, podcastId: String, episodeId: String, ep: FeedEpisode) {
        val then = _fetches.value[key]?.then ?: FetchThen.NOTHING
        _fetches.update { it - key }
        when (then) {
            FetchThen.PLAY -> withContext(Dispatchers.Main) { onReadyToPlay?.invoke(podcastId, episodeId) }
            FetchThen.DOWNLOAD -> {
                repo.downloads.downloadEpisode(podcastId, episodeId)
                _messages.tryEmit("\"${ep.title}\" is on your server; downloading it to this phone")
            }
            FetchThen.NOTHING -> _messages.tryEmit("\"${ep.title}\" is on your server")
        }
    }

    companion object {
        const val MAX_AGE_MS = 60 * 60_000L
    }
}
