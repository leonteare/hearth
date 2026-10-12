package im.flume.hearth.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.AppContainer
import im.flume.hearth.core.playback.container
import im.flume.hearth.spoken.FeedEpisode
import im.flume.hearth.spoken.FeedUrls
import im.flume.hearth.spoken.FetchPhase
import im.flume.hearth.spoken.FetchProgress
import im.flume.hearth.spoken.FetchThen
import im.flume.hearth.spoken.FollowTags
import im.flume.hearth.core.data.spoken.PodcastEntity
import im.flume.hearth.spoken.ServerFetch
import im.flume.hearth.spoken.ShowEpisodes
import im.flume.hearth.spoken.ShowFeeds
import im.flume.hearth.spoken.ShowListEntry
import im.flume.hearth.core.data.spoken.SpokenDownloadState
import im.flume.hearth.spoken.SpokenFormat
import im.flume.hearth.spoken.SpokenLists
import im.flume.hearth.core.ui.components.ActionSheet
import im.flume.hearth.core.ui.components.Actions
import im.flume.hearth.core.ui.components.EmptyState
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.MenuItem
import im.flume.hearth.core.ui.components.SectionHeader
import im.flume.hearth.core.ui.components.UnavailablePage
import im.flume.hearth.core.ui.components.plural
import im.flume.hearth.core.ui.theme.CoverSize
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.ui.theme.NotOnServerAlpha
import im.flume.hearth.core.common.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import im.flume.hearth.core.ui.screens.Description
import im.flume.hearth.core.ui.screens.EpisodeListRow
import im.flume.hearth.core.ui.screens.FollowButton
import im.flume.hearth.core.ui.screens.LocalSpokenNow
import im.flume.hearth.core.ui.screens.RoundPlayButton
import im.flume.hearth.core.ui.screens.SpokenHeader
import im.flume.hearth.core.ui.screens.playEpisode
import im.flume.hearth.core.ui.screens.spokenCover

/**
 * A podcast's page: every episode the show ever published (its feed, through the server), merged with
 * the ones the server has. Episodes on the server play as always; the rest are dimmed with a cloud, and
 * tapping one has the server fetch it, then plays it. Search within the episodes, newest / oldest first.
 *
 * Reached by the show's id ("podcast/{id}"), or by its feed ([feedUrl], "podcast-feed/{url}") for a show
 * found in Search that isn't on the server yet; it turns into the normal page once the show is added.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(id: String?, feedUrl: String? = null) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    // The feed remembered for this show, should the server have removed it since.
    val rememberedFeed = remember(id) { id?.let { c.spoken.feeds.feedUrlForShow(it) } }
    // By id; or by feed, so a show added from this page (or added back) turns up here.
    val podcastLookup by remember(id, feedUrl) {
        val byFeed = feedUrl ?: rememberedFeed
        c.spoken.podcasts().map { list -> Lookup(list.firstOrNull { it.id == id } ?: byFeed?.let { f -> list.firstOrNull { FeedUrls.same(it.feedUrl, f) } }) }
    }.collectAsStateWithLifecycle(null)
    val p = podcastLookup?.podcast
    val episodes by remember(p?.id) { p?.id?.let { c.spoken.episodes(it) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(null)
    val account by c.spoken.account.collectAsStateWithLifecycle()
    val now = LocalSpokenNow.current
    val playerState by c.player.state.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    var followBusy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        // Never fetched (e.g. just added): get the episodes now.
        if (id != null) {
            val existing = c.db.spoken().podcastOnce(id)
            if ((existing == null || existing.episodesFetchedAt == 0L) && !c.network.cannotStream) runCatching { c.spoken.fetchItem(id) }
        }
        loaded = true
    }
    // The feed: from the show, or the page's own link, or (show gone from the server) the one remembered for it.
    val showFeedUrl = p?.feedUrl ?: feedUrl ?: rememberedFeed
    LaunchedEffect(p?.id, showFeedUrl) {
        if (p != null && showFeedUrl != null) c.spoken.feeds.rememberShow(p.id, showFeedUrl)
    }
    val feedState by remember(showFeedUrl) {
        showFeedUrl?.let { c.spoken.feeds.state(it) } ?: MutableStateFlow(ShowFeeds.FeedState())
    }.collectAsStateWithLifecycle()
    LaunchedEffect(showFeedUrl, account != null) {
        if (showFeedUrl != null && account != null) c.spoken.feeds.load(showFeedUrl)
    }
    val fetches by c.spoken.feeds.fetches.collectAsStateWithLifecycle()

    if (podcastLookup == null) { Box(Modifier.fillMaxSize()); return } // not read from the phone yet
    if (p == null && (showFeedUrl == null || account == null)) {
        if (loaded) UnavailablePage(HearthTheme.icons.podcast, "This show isn't on your server any more")
        else Box(Modifier.fillMaxSize())
        return
    }
    val feed = feedState.feed
    val serverList = episodes.orEmpty()
    val merged = remember(feed, serverList) { ShowEpisodes.merge(feed?.episodes.orEmpty(), serverList) }
    val entries by produceState(emptyList<ShowListEntry>(), merged, query, newestFirst) {
        value = withContext(Dispatchers.Default) { ShowEpisodes.withSeasons(ShowEpisodes.arrange(merged, query, newestFirst)) }
    }
    val following = p != null && account?.username?.let { u -> FollowTags.isFollowing(p.tagList, u) } == true
    val latest = remember(serverList) { SpokenLists.playLatest(serverList) }
    val title = p?.title ?: feed?.title ?: "Podcast"
    val author = p?.author ?: feed?.author
    val coverUrl = p?.let { c.spokenCover(it.id, CoverSize.LARGE) } ?: feed?.image
    val playingThisShow = p != null && now?.libraryItemId == p.id
    val onServerCount = merged.count { it.server != null }
    val countLine = when {
        merged.isEmpty() -> p?.numEpisodes?.takeIf { it > 0 }?.let { plural(it, "episode") }
        feed == null -> plural(merged.size, "episode")
        else -> plural(merged.size, "episode") + if (p != null) " · $onServerCount on your server" else ""
    }

    fun refresh() {
        refreshing = true
        scope.launch {
            if (p != null) runCatching { c.spoken.fetchItem(p.id) }.onFailure { actions.showMessage("Couldn't refresh: ${userMessage(it)}") }
            if (showFeedUrl != null) {
                c.spoken.feeds.load(showFeedUrl, force = true)
                c.spoken.feeds.state(showFeedUrl).value.error?.let { actions.showMessage("Couldn't load every episode: $it") }
            }
            refreshing = false
        }
    }
    fun toggleFollow() {
        if (followBusy) return
        followBusy = true
        c.appScope.launch {
            val error = if (p != null) c.spoken.setFollowing(p.id, !following)
            else c.spoken.addPodcast(showFeedUrl!!, follow = true).exceptionOrNull()?.let(::userMessage)
            followBusy = false
            when {
                error != null -> actions.showMessage("Couldn't ${if (following) "unfollow" else "follow"}: $error")
                following -> actions.showUndo("Unfollowed $title. Its episodes are leaving this phone and your server.") { c.spoken.follow(p!!.id) }
                p == null -> actions.showMessage("Following $title. Its newest episodes are on their way.")
                else -> actions.showMessage("Following $title. New episodes will show on Home.")
            }
        }
    }

    // The show's ⋮ menu, in the same spot as on album, playlist and book pages.
    ActionSheet(menuOpen, onDismiss = { menuOpen = false }, title = title, subtitle = "Podcast", coverUrl = coverUrl, fallback = title) {
        if (account != null) {
            MenuItem(if (following) "Unfollow" else "Follow", HearthTheme.icons.podcast) { menuOpen = false; toggleFollow() }
        }
        if (p != null && latest != null) {
            MenuItem(if (latest.started) "Resume latest episode" else "Play latest episode", HearthTheme.icons.play) {
                menuOpen = false
                playEpisode(c, actions, p.id, latest.episode.id, latest.downloadState == SpokenDownloadState.DONE)
            }
        }
        MenuItem(if (newestFirst) "Show oldest first" else "Show newest first", HearthTheme.icons.sort) { menuOpen = false; newestFirst = !newestFirst }
        MenuItem("Check for new episodes", HearthTheme.icons.refresh) { menuOpen = false; refresh() }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = HearthTheme.spacing.xl)) {
            item(key = "header") {
                SpokenHeader(coverUrl, title, listOfNotNull(author, countLine), onMenu = { menuOpen = true })
            }
            if (p == null && id != null && loaded) {
                item(key = "gone") {
                    Text(
                        "This show isn't on your server any more. Follow it, or pick an episode, to bring it back.",
                        color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s),
                    )
                }
            }
            item(key = "buttons") {
                Row(Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    if (account != null) {
                        FollowButton(following) { toggleFollow() }
                    }
                    Spacer(Modifier.weight(1f))
                    if (p != null && latest != null) {
                        val isLatestPlaying = playingThisShow && now?.episodeId == latest.episode.id && playerState.isPlaying
                        RoundPlayButton(if (latest.started) "Resume latest episode" else "Play latest episode", playing = isLatestPlaying) {
                            if (playingThisShow && now?.episodeId == latest.episode.id) c.player.playPause()
                            else playEpisode(c, actions, p.id, latest.episode.id, latest.downloadState == SpokenDownloadState.DONE)
                        }
                    }
                }
            }
            item(key = "description") { Description(p?.description ?: feed?.description) }
            item(key = "eps-head") {
                SectionHeader("Episodes") {
                    TextButton(onClick = { newestFirst = !newestFirst }) {
                        Icon(HearthTheme.icons.sort, null, tint = HearthTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (newestFirst) "Newest" else "Oldest", color = HearthTheme.colors.textSecondary)
                    }
                }
            }
            if (merged.size > 8 || query.isNotEmpty()) {
                item(key = "filter") { EpisodeFilterField(query) { query = it } }
            }
            when {
                merged.isEmpty() && feedState.loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(HearthTheme.spacing.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
                merged.isEmpty() && (episodes != null || p == null) -> item(key = "empty") {
                    EmptyState(
                        HearthTheme.icons.podcast,
                        when {
                            c.network.cannotStream -> "No episodes on this phone yet. Connect to the internet to see them."
                            feedState.error != null -> "Couldn't load the episodes: ${feedState.error}. Pull down to try again."
                            else -> "No episodes yet. Pull down to check."
                        },
                    )
                }
                entries.isEmpty() && query.isNotBlank() -> item(key = "no-match") {
                    Text(
                        "No episodes match \"$query\"", color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.l),
                    )
                }
            }
            items(entries, key = { it.key }, contentType = { if (it is ShowListEntry.Season) 0 else 1 }) { entry ->
                when (entry) {
                    is ShowListEntry.Season -> SectionHeader("Season ${entry.season}", Modifier.animateItem())
                    is ShowListEntry.Episode -> {
                        val e = entry.episode
                        val server = e.server
                        val feed = e.feed
                        when {
                            server != null -> EpisodeListRow(server, Modifier.animateItem())
                            feed != null && showFeedUrl != null -> FeedEpisodeRow(
                                feed, p?.id, showFeedUrl, title,
                                fetches[c.spoken.feeds.fetchKey(showFeedUrl, feed.key)], Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Filters the episode list as you type. */
@Composable
private fun EpisodeFilterField(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.gutter).padding(bottom = HearthTheme.spacing.s)
            .heightIn(min = 44.dp).clip(HearthTheme.shapes.card).background(HearthTheme.colors.surfaceHigh).padding(start = HearthTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HearthTheme.icons.search, null, tint = HearthTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = HearthTheme.colors.text),
            cursorBrush = SolidColor(HearthTheme.colors.text),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) Text("Find an episode", color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyLarge)
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onChange("") }) { Icon(HearthTheme.icons.close, "Clear", tint = HearthTheme.colors.textSecondary) }
        } else {
            Spacer(Modifier.width(HearthTheme.spacing.m))
        }
    }
}

/** Asks the server for a feed episode, unless offline. */
private fun getEpisode(c: AppContainer, actions: Actions, podcastId: String?, feedUrl: String, ep: FeedEpisode, then: FetchThen) {
    if (c.network.cannotStream) {
        actions.showMessage("You're offline. Connect to get this episode")
        return
    }
    c.spoken.feeds.getOntoServer(podcastId, feedUrl, ep, then)
}

/**
 * An episode the server doesn't have (yet): dimmed, with a cloud, like music that isn't on the server.
 * Tap has the server fetch it and plays it when it arrives; ⋯ (or long-press) can download it to the
 * phone or just add it to the server.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedEpisodeRow(ep: FeedEpisode, podcastId: String?, feedUrl: String, showTitle: String, fetch: ServerFetch?, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val getting = fetch != null && fetch.phase != FetchPhase.FAILED && fetch.phase != FetchPhase.READY
    val failed = fetch?.phase == FetchPhase.FAILED
    val play = {
        if (getting) {
            c.spoken.feeds.getOntoServer(podcastId, feedUrl, ep, FetchThen.PLAY)
            actions.showMessage("It'll start when it's ready")
        } else {
            getEpisode(c, actions, podcastId, feedUrl, ep, FetchThen.PLAY)
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = play,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            )
            .padding(start = HearthTheme.spacing.gutter, end = HearthTheme.spacing.xs, top = HearthTheme.spacing.rowPaddingV, bottom = HearthTheme.spacing.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (getting) MaterialTheme.colorScheme.primary else HearthTheme.colors.text.copy(alpha = NotOnServerAlpha),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    getting -> CircularProgressIndicator(
                        Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary, trackColor = HearthTheme.colors.textSecondary.copy(alpha = 0.3f),
                    )
                    failed -> Icon(HearthTheme.icons.failedOutline, "Couldn't get it", Modifier.size(14.dp), tint = HearthTheme.colors.destructive)
                    else -> Icon(HearthTheme.icons.notOnServer, "Not on your server yet", Modifier.size(14.dp), tint = HearthTheme.colors.textSecondary.copy(alpha = NotOnServerAlpha))
                }
                Spacer(Modifier.width(HearthTheme.spacing.xs))
                val line = listOfNotNull(
                    SpokenFormat.date(ep.publishedAt).ifEmpty { null },
                    SpokenFormat.length(ep.durationSec).ifEmpty { null },
                ).joinToString(" · ")
                Text(
                    when {
                        getting -> FetchProgress.label(fetch!!)
                        failed -> "Couldn't get it · tap to try again"
                        else -> line
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = (if (failed) HearthTheme.colors.destructive else HearthTheme.colors.textSecondary).copy(alpha = if (getting) 1f else NotOnServerAlpha),
                )
            }
        }
        IconButton(onClick = { menu = true }) { Icon(HearthTheme.icons.more, "More", tint = HearthTheme.colors.textSecondary) }
    }
    ActionSheet(menu, { menu = false }, title = ep.title, subtitle = showTitle, fallback = showTitle, coverUrl = podcastId?.let { c.spokenCover(it, 150) }) {
        MenuItem(if (getting) "Play when it's ready" else "Play", HearthTheme.icons.play) { menu = false; play() }
        MenuItem("Download to phone", HearthTheme.icons.download) {
            menu = false
            getEpisode(c, actions, podcastId, feedUrl, ep, FetchThen.DOWNLOAD)
        }
        if (!getting) {
            MenuItem("Add to server", HearthTheme.icons.fetchToServer) {
                menu = false
                getEpisode(c, actions, podcastId, feedUrl, ep, FetchThen.NOTHING)
            }
        }
    }
}

/** The show found for the page (null: not on the server), once the phone's list has been read. */
private class Lookup(val podcast: PodcastEntity?)
