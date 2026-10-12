// Adding podcasts to the server (from a feed or the podcast directory) and importing OPML files.
package im.flume.hearth.spoken

import im.flume.hearth.core.common.AbsAuthException
import im.flume.hearth.core.network.AbsFolder
import im.flume.hearth.core.network.AbsLibrary
import im.flume.hearth.core.network.AbsPodcastSearchResult
import im.flume.hearth.core.common.userMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Adding podcasts to the server's library, one feed at a time or a whole OPML export. */
internal class SpokenAdd(private val repo: SpokenRepository) {
    private val scope get() = repo.scope
    private val api get() = repo.api
    private val dao get() = repo.dao
    private val username get() = repo.username

    /**
     * Adds a podcast to the server's Podcasts library (or finds it if it's already there) and, if
     * [follow], follows it. A new show gets its newest [downloadLatest] episodes fetched by the server
     * right away, so there's something to play. Returns the podcast's id.
     */
    suspend fun addPodcast(feedUrl: String, directoryEntry: AbsPodcastSearchResult? = null, follow: Boolean = true, downloadLatest: Int = SpokenRepository.NEWEST_ON_FOLLOW): Result<String> = runCatching {
        val user = username ?: throw AbsAuthException("Not signed in to podcasts & audiobooks")
        repo.findByFeed(feedUrl)?.let { existing ->
            if (follow) repo.setFollowing(existing.id, true)?.let { throw IllegalStateException(it) }
            return@runCatching existing.id
        }
        val (library, folder) = podcastFolder()
        val feed = api.podcastFeed(feedUrl)
        val item = api.createPodcast(
            library.id, folder.id, folder.fullPath, feed.metadata, feedUrl, directoryEntry,
            tags = if (follow) listOf(FollowTags.tagFor(user)) else FollowTags.nextTags(emptyList(), user, false, api.clock.serverNow()),
            autoDownload = follow,
        )
        dao.upsertPodcasts(listOf(item.toPodcast(null)))
        if (follow) {
            // Make sure the follower tag really landed (the server's cleanup relies on it): read the new
            // item back and, if the tag is missing, write it the careful way (read-modify-write, verified).
            repo.follows.rememberIntent(item.id, user, true)
            val tags = runCatching { api.item(item.id)?.media?.tags }.getOrNull()
            if (tags == null || !FollowTags.isFollowing(tags, user)) {
                repo.setFollowing(item.id, true)?.let { if (repo.setFollowing(item.id, true) != null) throw IllegalStateException("Added the podcast, but couldn't follow it: $it") }
            } else {
                dao.setPodcastTags(item.id, tags.joinToString("\n"))
            }
        }
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
            val already = repo.findByFeed(f.feedUrl)
            addPodcast(f.feedUrl, follow = true, downloadLatest = 1).fold(
                onSuccess = { if (already != null) existing += name else added += name },
                onFailure = { failed += name to userMessage(it) },
            )
        }
        scope.launch { repo.refresh(force = true) }
        return OpmlImportResult(added, existing, failed)
    }

    private suspend fun podcastFolder(): Pair<AbsLibrary, AbsFolder> {
        if (repo.sync.libraries.isEmpty()) repo.sync.libraries = api.libraries()
        val lib = repo.sync.libraries.firstOrNull { it.mediaType == "podcast" } ?: throw IllegalStateException("The server has no podcast library")
        return lib to (lib.folders.firstOrNull() ?: throw IllegalStateException("The podcast library has no folder"))
    }
}
