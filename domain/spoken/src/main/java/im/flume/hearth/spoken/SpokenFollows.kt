// Following and unfollowing podcasts: the server-side follower tags, the newest episodes on follow,
// clearing unfollowed shows off the server, and re-writing follows the server lost.
package im.flume.hearth.spoken

import im.flume.hearth.core.network.AbsLibraryItem
import im.flume.hearth.core.common.logHandledError
import im.flume.hearth.core.common.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import im.flume.hearth.core.data.spoken.FollowIntent

/** Following and unfollowing podcasts for this user, through follower tags on the server item. */
internal class SpokenFollows(private val repo: SpokenRepository) {
    private val context get() = repo.context
    private val scope get() = repo.scope
    private val api get() = repo.api
    private val dao get() = repo.dao
    private val store get() = repo.store
    private val downloads get() = repo.downloads
    private val username get() = repo.username

    /**
     * Follows (or unfollows) a podcast for this user: reads the podcast's tags fresh from the server,
     * adds or removes only this user's follower tag, writes them back, and reads again to check another
     * phone didn't write at the same moment (retrying if it did). Following also turns on the server's
     * auto-download for the show. Returns null on success, or the reason it failed.
     */
    suspend fun setFollowing(podcastId: String, follow: Boolean): String? {
        val user = username ?: return "Not signed in to podcasts & audiobooks"
        val before = dao.podcastOnce(podcastId)
        before?.let { dao.setPodcastTags(podcastId, FollowTags.nextTags(it.tagList, user, follow, api.clock.serverNow()).joinToString("\n")) } // instant UI
        rememberIntent(podcastId, user, follow)
        try {
            repeat(3) {
                val item = api.item(podcastId) ?: run {
                    repo.sync.removeItems(listOf(podcastId), podcast = true)
                    forgetIntent(podcastId)
                    return "This podcast is no longer on the server"
                }
                val tags = item.media.tags
                val wasFollowing = FollowTags.isFollowing(tags, user)
                // Already unfollowed and marked: leave the marker's time alone.
                val alreadyDone = !follow && !FollowTags.isFollowing(tags, user) && (FollowTags.followers(tags).isNotEmpty() || tags.any(FollowTags::isUnfollowedMarker))
                val next = if (alreadyDone) tags else FollowTags.nextTags(tags, user, follow, api.clock.serverNow())
                if (next != tags) api.setTags(podcastId, next)
                if (follow && item.media.autoDownloadEpisodes != true) {
                    api.setAutoDownload(podcastId, true)
                    dao.setPodcastAutoDownload(podcastId, true)
                }
                val check = api.item(podcastId)?.media?.tags ?: next
                dao.setPodcastTags(podcastId, check.joinToString("\n"))
                if (FollowTags.isFollowing(check, user) == follow) {
                    if (follow) {
                        // A show already on the server (an episode picked from its page, or followed before)
                        // gets its newest episodes too, as a newly added one does.
                        if (!wasFollowing) runCatching { fetchNewestOnServer(item, SpokenRepository.NEWEST_ON_FOLLOW) }
                        downloads.onFollowed(podcastId)
                        dao.podcastOnce(podcastId)?.let { downloads.planAutoDownloads(listOf(it)) }
                    } else {
                        downloads.onUnfollowed(podcastId)
                        clearFromServerSoon(podcastId)
                    }
                    return null
                }
            }
            return "Couldn't save that on the server; try again"
        } catch (e: Exception) {
            before?.let { dao.setPodcastTags(podcastId, it.tags) }
            return userMessage(e)
        }
    }

    /**
     * Once Undo has had its chance, clears an unfollowed show's episodes off the server if nobody follows
     * it any more. Anything this misses (app closed, offline) the server's hourly cleanup removes.
     */
    private fun clearFromServerSoon(podcastId: String) {
        scope.launch {
            delay(SpokenRepository.UNFOLLOW_CLEAR_DELAY_MS)
            try {
                val user = username ?: return@launch
                val item = api.item(podcastId) ?: return@launch
                if (!UnfollowServerCleanup.shouldClear(item.media.tags)) return@launch
                // No new episodes arriving for a show nobody follows.
                if (item.media.autoDownloadEpisodes == true) {
                    api.setAutoDownload(podcastId, false)
                    dao.setPodcastAutoDownload(podcastId, false)
                }
                val othersPartWay = api.users().filter { !it.username.equals(user, ignoreCase = true) }
                    .flatMap { api.user(it.id).mediaProgress }
                    .filter { it.libraryItemId == podcastId && !it.isFinished }
                    .mapNotNullTo(HashSet()) { it.episodeId }
                val current = downloads.current?.takeIf { it.libraryItemId == podcastId }?.episodeId
                val gone = UnfollowServerCleanup.toDelete(item.media.episodes.orEmpty().map { it.id }, othersPartWay, current)
                gone.forEach { api.deleteEpisode(podcastId, it) }
                if (gone.isNotEmpty()) repo.refresh(force = true)
            } catch (e: Exception) {
                logHandledError(context, "spoken unfollow cleanup", e)
            }
        }
    }

    /** Has the server fetch the show's newest [count] episodes that it doesn't already have. */
    private suspend fun fetchNewestOnServer(item: AbsLibraryItem, count: Int) {
        val feedUrl = item.media.metadata.feedUrl ?: return
        val newest = FeedParse.episodes(api.podcastFeed(feedUrl).episodes).sortedByDescending { it.first.publishedAt }.take(count)
        val onServer = item.media.episodes.orEmpty().map { ServerEpisodeRef(it.id, it.enclosure?.url, it.guid, it.title, it.publishedAt ?: it.addedAt) }
        val have = FeedMatch.match(newest.map { it.first }, onServer)
        val missing = newest.filter { it.first.key !in have }.map { it.second }
        if (missing.isNotEmpty()) api.downloadEpisodesOnServer(item.id, JsonArray(missing))
    }

    fun rememberIntent(podcastId: String, user: String, follow: Boolean) =
        store.updateFollowIntents { it + (podcastId to FollowIntent(podcastId, user.lowercase(), follow, System.currentTimeMillis())) }

    private fun forgetIntent(podcastId: String) = store.updateFollowIntents { it - podcastId }

    /**
     * Self-heal after a sync: where this user recently followed (or unfollowed) a show on this phone and
     * the server's tags say otherwise, write the choice again. Choices expire after a few days, so a
     * later change made on another device isn't fought over for ever.
     */
    suspend fun healFollows(items: List<AbsLibraryItem>) {
        val user = username ?: return
        val now = System.currentTimeMillis()
        val byId = items.associateBy { it.id }
        val intents = store.followIntents()
        store.updateFollowIntents { m -> m.filterValues { now - it.at < FollowTags.INTENT_TTL_MS && it.podcastId in byId } }
        for (intent in intents.values) {
            if (now - intent.at >= FollowTags.INTENT_TTL_MS || !intent.username.equals(user, ignoreCase = true)) continue
            val item = byId[intent.podcastId] ?: continue
            if (FollowTags.heal(intent.follow, item.media.tags, user) != FollowTags.Heal.NONE) {
                logHandledError(context, "spoken follow heal", IllegalStateException("Follow tag out of step; writing it again"))
                setFollowing(intent.podcastId, intent.follow)
            }
        }
    }
}
