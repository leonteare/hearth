// The Search tab: what you type, the lookups it starts (library, Tidal, podcasts) and recent searches.
package im.flume.hearth.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.api.AbsPodcastSearchResult
import im.flume.hearth.api.TidalSearchResults
import im.flume.hearth.container
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SearchFilter
import im.flume.hearth.data.SearchHit
import im.flume.hearth.data.SearchIndex
import im.flume.hearth.data.SearchRanking
import im.flume.hearth.data.SongItem
import im.flume.hearth.data.Usage
import im.flume.hearth.spoken.EpisodeRow
import im.flume.hearth.spoken.SpokenDiscovery
import im.flume.hearth.spoken.SpokenSearch
import im.flume.hearth.spoken.SpokenSearchResults
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalTabRequests
import im.flume.hearth.ui.components.PageHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Set when the Search tab is tapped. Search only takes the keyboard then (with nothing typed), not
 * when you come back to it from a result with your results still showing.
 */
class SearchFocusRequest {
    var pending by mutableStateOf(false)
}

val LocalSearchFocus = staticCompositionLocalOf { SearchFocusRequest() }

@Composable
fun SearchScreen() {
    val dao = LocalContext.current.container.db.library()
    val actions = LocalActions.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    var library by remember { mutableStateOf(SearchIndex.Results.EMPTY) }
    // True while the search index is being built (the first search after launch or a sync).
    var indexing by remember { mutableStateOf(false) }
    val c = LocalContext.current.container
    val genres by remember { dao.genres() }.collectAsStateWithLifecycle(emptyList())

    val context = LocalContext.current
    val recentPrefs = remember { context.getSharedPreferences("search", Context.MODE_PRIVATE) }
    var recent by remember { mutableStateOf(recentPrefs.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }.take(RECENT_SEARCHES)) }
    fun saveRecent(q: String) {
        val t = q.trim().takeIf { it.length >= 2 } ?: return
        c.usage.track(Usage.SEARCH)
        recent = (listOf(t) + recent.filterNot { it.equals(t, ignoreCase = true) }).take(RECENT_SEARCHES)
        recentPrefs.edit().putString("recent", recent.joinToString("\n")).apply()
    }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val gridColumns = gridColumns()
    val focusRequest = LocalSearchFocus.current
    LaunchedEffect(focusRequest.pending) {
        if (!focusRequest.pending) return@LaunchedEffect
        focusRequest.pending = false
        if (query.isEmpty()) runCatching { focus.requestFocus() }
    }
    // Opened from elsewhere (Your Library's "Find a podcast"): that filter, ready to type.
    val tabRequests = LocalTabRequests.current
    LaunchedEffect(tabRequests.searchFilter) {
        val wanted = tabRequests.searchFilter ?: return@LaunchedEffect
        tabRequests.searchFilter = null
        filter = wanted
        query = "" // a fresh search, not whatever was typed last time
        focus.requestFocus()
        keyboard?.show()
    }

    // Music that isn't on the server is fetched separately, so the library's results never wait on it,
    // then ranked into the same list. If it can't be reached, it just isn't shown.
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    var remote by remember { mutableStateOf<TidalSearchResults?>(null) }
    // Open the connection as soon as Search opens, so the first search doesn't pay for it.
    LaunchedEffect(settings.tidalSearchEnabled, online) {
        if (settings.tidalSearchEnabled && online) runCatching { withContext(Dispatchers.IO) { c.requests.warmUp() } }
    }
    LaunchedEffect(query, online, settings.tidalSearchEnabled) {
        val q = query.trim()
        if (!settings.tidalSearchEnabled || !online || q.length < 2) { remote = null; return@LaunchedEffect }
        // Recently seen searches come back instantly; otherwise wait for a short pause in typing.
        c.requests.cachedTidalSearch(q)?.let { remote = it; return@LaunchedEffect }
        delay(200)
        // The previous results stay until the new ones arrive, so the page doesn't jump.
        remote = try {
            withContext(Dispatchers.IO) { c.requests.searchTidal(q) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // Podcasts, episodes and audiobooks from the phone's catalogue (instant, offline).
    val spokenAccount by c.spoken.account.collectAsStateWithLifecycle()
    val spokenPodcasts by remember { c.spoken.podcasts() }.collectAsStateWithLifecycle(emptyList())
    val spokenBooks by remember { c.spoken.books() }.collectAsStateWithLifecycle(emptyList())
    var spokenEpisodes by remember { mutableStateOf<List<EpisodeRow>?>(null) }
    var spoken by remember { mutableStateOf(SpokenSearchResults()) }
    LaunchedEffect(query, spokenAccount != null, spokenPodcasts, spokenBooks) {
        val q = query.trim()
        if (spokenAccount == null || q.isEmpty()) { spoken = SpokenSearchResults(); return@LaunchedEffect }
        delay(120)
        val episodes = spokenEpisodes ?: c.spoken.allEpisodes().also { spokenEpisodes = it }
        spoken = withContext(Dispatchers.Default) { SpokenSearch.search(q, spokenPodcasts, episodes, spokenBooks) }
    }
    // Podcasts that aren't on the server yet, from the podcast directory (online only, after a pause in typing).
    var directory by remember { mutableStateOf<List<AbsPodcastSearchResult>>(emptyList()) }
    var directoryLoading by remember { mutableStateOf(false) }
    LaunchedEffect(query, spokenAccount != null) {
        val q = query.trim()
        if (spokenAccount == null || q.length < 3 || c.network.cannotStream) { directory = emptyList(); directoryLoading = false; return@LaunchedEffect }
        delay(450)
        directoryLoading = true
        directory = try {
            withContext(Dispatchers.IO) { SpokenDiscovery.usable(c.spoken.searchDirectory(q)).take(8) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        } finally {
            directoryLoading = false
        }
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { library = SearchIndex.Results.EMPTY; return@LaunchedEffect }
        delay(120)
        // Scanning ~7k songs is too slow for the main thread; a new keystroke cancels this anyway.
        indexing = true
        val index = try { c.library.searchIndex(c.session.lastSyncAt) } finally { indexing = false }
        library = withContext(Dispatchers.Default) { index.search(q) }
    }

    // Everything in one list, best match first, whatever kind it is.
    val hits = remember(query, library, remote, spoken, directory) { SearchRanking.merge(query, library, remote, spoken, directory) }
    val shown = remember(hits, filter) { SearchRanking.filter(hits, filter) }
    // Tapping a song plays the songs shown, in this order, from that one.
    val shownSongIds = remember(shown) { shown.mapNotNull { ((it as? SearchHit.Song)?.item as? SongItem.Owned)?.song?.id } }

    // Searches that found something are remembered when you leave the page.
    val latestQuery by rememberUpdatedState(query)
    val hadResults by rememberUpdatedState(hits.isNotEmpty())
    DisposableEffect(Unit) { onDispose { if (hadResults) saveRecent(latestQuery) } }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Search")
        Spacer(Modifier.height(8.dp))
        SearchField(
            query = query,
            onQueryChange = { query = it },
            hint = searchHint(filter, spokenAccount != null),
            focus = focus,
            onSearch = { saveRecent(query) },
        )
        SearchFilterChips(filter) { filter = it }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (query.isBlank()) {
                searchLanding(
                    recent = recent,
                    onClearRecent = {
                        recent = emptyList()
                        recentPrefs.edit().remove("recent").apply()
                    },
                    onPickRecent = { query = it },
                    genres = genres,
                    gridColumns = gridColumns,
                    onOpenGenre = { actions.openGenre(it) },
                )
            } else {
                searchResults(
                    query = query,
                    filter = filter,
                    indexing = indexing,
                    library = library,
                    hits = hits,
                    shown = shown,
                    directoryLoading = directoryLoading,
                    podcasts = spokenPodcasts,
                    username = spokenAccount?.username,
                    playSong = { songId ->
                        actions.play(PlaySource(PlaySource.Kind.SONGS, label = "Search", songIds = shownSongIds), startSongId = songId)
                    },
                )
            }
        }
    }
}

/** Recent searches kept on the landing page; more than a few just pushes genres off the screen. */
internal const val RECENT_SEARCHES = 4
