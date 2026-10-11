package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.text.style.TextAlign
import im.flume.hearth.data.YourLibrary
import im.flume.hearth.data.LibraryOrder
import im.flume.hearth.data.SearchFilter
import im.flume.hearth.data.SearchHit
import im.flume.hearth.data.SearchIndex
import im.flume.hearth.data.SearchRanking
import im.flume.hearth.ui.components.LocalTabRequests
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import im.flume.hearth.data.AlbumItem
import im.flume.hearth.data.ArtistItem
import im.flume.hearth.data.SongItem
import im.flume.hearth.ui.components.MissingAlbumRow
import im.flume.hearth.ui.components.MissingArtistRow
import im.flume.hearth.ui.components.MissingSongRow
import im.flume.hearth.ui.components.remoteAlbumId
import im.flume.hearth.ui.components.remoteArtistId
import im.flume.hearth.data.PlaylistItem
import im.flume.hearth.data.PlaylistRules
import im.flume.hearth.ui.theme.NoticeDot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.combine
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.data.AlbumEntity
import im.flume.hearth.data.ArtistEntity
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.SongOrder
import im.flume.hearth.sync.SyncState
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.SongRow
import im.flume.hearth.ui.components.ChoiceSheet
import im.flume.hearth.ui.components.IconTile
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.PageHeader
import im.flume.hearth.ui.components.TopBar
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.DownloadsColor
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.LikedColor
import im.flume.hearth.ui.theme.OnSearchField
import im.flume.hearth.ui.theme.SearchFieldColor
import im.flume.hearth.ui.theme.SearchFieldHint
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val recentPrefs = remember { context.getSharedPreferences("search", android.content.Context.MODE_PRIVATE) }
    var recent by remember { mutableStateOf(recentPrefs.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }.take(RECENT_SEARCHES)) }
    fun saveRecent(q: String) {
        val t = q.trim().takeIf { it.length >= 2 } ?: return
        c.usage.track(im.flume.hearth.data.Usage.SEARCH)
        recent = (listOf(t) + recent.filterNot { it.equals(t, ignoreCase = true) }).take(RECENT_SEARCHES)
        recentPrefs.edit().putString("recent", recent.joinToString("\n")).apply()
    }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val gridColumns = gridColumns()
    LaunchedEffect(Unit) { focus.requestFocus() }
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
    var remote by remember { mutableStateOf<im.flume.hearth.api.TidalSearchResults?>(null) }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // Podcasts, episodes and audiobooks from the phone's catalogue (instant, offline).
    val spokenAccount by c.spoken.account.collectAsStateWithLifecycle()
    val spokenPodcasts by remember { c.spoken.podcasts() }.collectAsStateWithLifecycle(emptyList())
    val spokenBooks by remember { c.spoken.books() }.collectAsStateWithLifecycle(emptyList())
    var spokenEpisodes by remember { mutableStateOf<List<im.flume.hearth.spoken.EpisodeRow>?>(null) }
    var spoken by remember { mutableStateOf(im.flume.hearth.spoken.SpokenSearchResults()) }
    LaunchedEffect(query, spokenAccount != null, spokenPodcasts, spokenBooks) {
        val q = query.trim()
        if (spokenAccount == null || q.isEmpty()) { spoken = im.flume.hearth.spoken.SpokenSearchResults(); return@LaunchedEffect }
        delay(120)
        val episodes = spokenEpisodes ?: c.spoken.allEpisodes().also { spokenEpisodes = it }
        spoken = withContext(Dispatchers.Default) { im.flume.hearth.spoken.SpokenSearch.search(q, spokenPodcasts, episodes, spokenBooks) }
    }
    // Podcasts that aren't on the server yet, from the podcast directory (online only, after a pause in typing).
    var directory by remember { mutableStateOf<List<im.flume.hearth.api.AbsPodcastSearchResult>>(emptyList()) }
    var directoryLoading by remember { mutableStateOf(false) }
    LaunchedEffect(query, spokenAccount != null) {
        val q = query.trim()
        if (spokenAccount == null || q.length < 3 || c.network.cannotStream) { directory = emptyList(); directoryLoading = false; return@LaunchedEffect }
        delay(450)
        directoryLoading = true
        directory = try {
            withContext(Dispatchers.IO) { im.flume.hearth.spoken.SpokenDiscovery.usable(c.spoken.searchDirectory(q)).take(8) }
        } catch (e: kotlinx.coroutines.CancellationException) {
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.Gutter)
                .heightIn(min = 48.dp)
                .clip(HearthShapes.Card)
                .background(SearchFieldColor)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = OnSearchField, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = OnSearchField),
                cursorBrush = SolidColor(OnSearchField),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { saveRecent(query) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            searchHint(filter, spokenAccount != null),
                            color = SearchFieldHint, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear", tint = OnSearchField) }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
        SearchFilterChips(filter) { filter = it }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (query.isBlank()) {
                if (recent.isNotEmpty()) {
                    item {
                        SectionHeader("Recent searches") {
                            TextButton(onClick = {
                                recent = emptyList()
                                recentPrefs.edit().remove("recent").apply()
                            }) { Text("Clear", color = TextSecondary) }
                        }
                    }
                    items(recent.take(RECENT_SEARCHES), key = { "r:$it" }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { query = r }.padding(horizontal = Dimens.Gutter, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.History, null, tint = TextSecondary)
                            Spacer(Modifier.width(16.dp))
                            Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (genres.isNotEmpty()) item { SectionHeader("Browse genres") }
                items(genres.chunked(gridColumns)) { pair ->
                    Row(Modifier.padding(horizontal = Dimens.Gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { g ->
                            Box(
                                Modifier.weight(1f).heightIn(min = 72.dp).clip(HearthShapes.Card)
                                    .background(genreColor(g.genre)).clickable { actions.openGenre(g.genre) }.padding(12.dp)
                            ) {
                                Text(g.genre, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(gridColumns - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else {
                if (indexing) {
                    item(key = "indexing") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Getting your library ready to search…", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (library.fuzzy && !library.isEmpty) {
                    item(key = "close") {
                        Text(
                            "No exact matches. Showing close matches.",
                            color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(shown, key = { it.key }) { hit ->
                    Column(Modifier.animateItem()) {
                        SearchHitRow(hit, spokenPodcasts, spokenAccount?.username) { songId ->
                            actions.play(PlaySource(PlaySource.Kind.SONGS, label = "Search", songIds = shownSongIds), startSongId = songId)
                        }
                    }
                }
                val lookingForPodcasts = directoryLoading && (filter == SearchFilter.ALL || filter == SearchFilter.PODCASTS)
                if (lookingForPodcasts) {
                    item(key = "directory-loading") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Looking for more podcasts…", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (!indexing && !directoryLoading && shown.isEmpty()) {
                    item(key = "none") {
                        Text(
                            if (hits.isEmpty()) "No results for \"$query\"" else "No ${filter.label.lowercase()} for \"$query\"",
                            color = TextSecondary, modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Recent searches kept on the landing page; more than a few just pushes genres off the screen. */
private const val RECENT_SEARCHES = 4

private fun searchHint(filter: SearchFilter, spoken: Boolean): String = when (filter) {
    SearchFilter.ARTISTS -> "Artists"
    SearchFilter.ALBUMS -> "Albums"
    SearchFilter.SONGS -> "Songs"
    SearchFilter.PODCASTS -> "Podcasts and episodes"
    SearchFilter.BOOKS -> "Audiobooks"
    SearchFilter.ALL -> if (spoken) "Songs, artists, albums or podcasts" else "Songs, artists or albums"
}

/**
 * All · Artists · Albums · Songs · Podcasts · Books, all six side by side at equal width. Small
 * enough to fit a 360dp phone without scrolling, so every filter is always one tap away.
 */
@Composable
private fun SearchFilterChips(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SearchFilter.entries.forEach { f ->
            val on = f == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .clip(HearthShapes.Chip)
                    .background(if (on) accent else SurfaceHigh)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(f) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    f.label,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** One row of Search's list, in the same row the rest of the app uses for that kind of thing. */
@Composable
private fun SearchHitRow(
    hit: SearchHit,
    podcasts: List<im.flume.hearth.spoken.PodcastEntity>,
    username: String?,
    playSong: (String) -> Unit,
) {
    val actions = LocalActions.current
    when (hit) {
        is SearchHit.Artist -> when (val a = hit.item) {
            is ArtistItem.Owned -> ArtistRow(a.artist)
            is ArtistItem.Missing -> MissingArtistRow(a.artist) { actions.openArtist(remoteArtistId(a.artist.id)) }
        }
        is SearchHit.Album -> when (val a = hit.item) {
            is AlbumItem.Owned -> AlbumListRow(a.album)
            is AlbumItem.Missing -> MissingAlbumRow(a.album) { actions.openAlbum(remoteAlbumId(a.album.id)) }
        }
        is SearchHit.Song -> when (val s = hit.item) {
            is SongItem.Owned -> SongRow(s.song, onClick = { playSong(s.song.id) }, subtitle = "Song • ${s.song.artist}")
            is SongItem.Missing -> MissingSongRow(s.track, subtitle = "Song • ${s.track.artist}")
        }
        is SearchHit.Podcast -> PodcastListRow(hit.podcast)
        is SearchHit.DirectoryPodcast -> DirectoryRow(hit.result, podcasts, username)
        is SearchHit.Episode -> EpisodeListRow(hit.row, showCover = true, withShow = true, label = "Episode")
        is SearchHit.Book -> BookListRow(hit.row)
    }
}

private fun genreColor(name: String): Color {
    val palette = listOf(0xFF8C1932, 0xFF1E3264, 0xFF477D95, 0xFF8D67AB, 0xFFE8115B, 0xFF148A08, 0xFFBA5D07, 0xFF503750, 0xFF0D73EC, 0xFF777777)
    return Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
}

@Composable
fun ArtistRow(a: ArtistEntity) {
    val actions = LocalActions.current
    MediaRow(a.name, "Artist • ${plural(a.albumCount, "album")}", onClick = { actions.openArtist(a.id) }, coverArt = a.coverArt, round = true)
}

@Composable
fun AlbumListRow(a: AlbumEntity) {
    val actions = LocalActions.current
    MediaRow(
        a.name,
        listOfNotNull("Album", a.artist, a.year?.toString()).joinToString(" • "),
        onClick = { actions.openAlbum(a.id) },
        coverArt = a.coverArt,
    )
}

/** Your Library's tabs; [Actions.openLibraryTab] names one by its [name]. */
private enum class LibraryTab(val label: String) { PLAYLISTS("Playlists"), ARTISTS("Artists"), ALBUMS("Albums"), PODCASTS("Podcasts"), AUDIOBOOKS("Audiobooks") }

private val ARTIST_SORTS = listOf("A–Z", "Most albums")
private val ALBUM_SORTS = listOf("A–Z", "Recently added", "Artist", "Most played", "Year")

private fun sortArtists(artists: List<ArtistEntity>, sort: Int) = LibraryOrder.artists(artists, sort)

private fun sortAlbums(albums: List<AlbumEntity>, sort: Int) = LibraryOrder.albums(albums, sort)

@Composable
internal fun SortMenu(options: List<String>, sort: Int, onSort: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Icon(Icons.AutoMirrored.Filled.Sort, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(options.getOrElse(sort) { options[0] }, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
    ChoiceSheet(open, "Sort by", options.mapIndexed { i, label -> i to label }, sort, onDismiss = { open = false }, onPick = onSort)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val c = LocalContext.current.container
    val dao = c.db.library()
    val actions = LocalActions.current
    var tab by rememberSaveable { mutableStateOf(LibraryTab.PLAYLISTS) }
    val tabsState = rememberLazyListState()
    // Opened on a given tab (Home's Podcasts and Audiobooks tiles).
    val tabRequests = LocalTabRequests.current
    LaunchedEffect(tabRequests.libraryTab) {
        val wanted = tabRequests.libraryTab ?: return@LaunchedEffect
        tabRequests.libraryTab = null
        LibraryTab.entries.firstOrNull { it.name == wanted }?.let {
            tab = it
            tabsState.animateScrollToItem(it.ordinal)
        }
    }
    val playlistItems by remember { c.library.playlistItems }.collectAsStateWithLifecycle(emptyList())
    // Yours, ones you've joined, and invites waiting for an answer. Other people's playlists stay hidden.
    val playlists = remember(playlistItems) { PlaylistRules.visible(playlistItems) }
    // Personal: saved albums, and artists you follow or saved an album by. The whole server is under Search.
    val albums by remember { dao.savedAlbums() }.collectAsStateWithLifecycle(emptyList())
    val artists by remember { combine(dao.artists(), dao.savedAlbums(), YourLibrary::artists) }.collectAsStateWithLifecycle(emptyList())
    val songCount by remember { dao.songCount() }.collectAsStateWithLifecycle(0)
    val sync by c.sync.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sortPrefs = remember { context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE) }
    var sort by remember(tab) { mutableStateOf(sortPrefs.getInt("sort-${tab.name}", 0)) }
    val sortOptions = when (tab) {
        LibraryTab.PLAYLISTS -> listOf("A–Z", "Recently updated", "Most songs")
        LibraryTab.ARTISTS -> ARTIST_SORTS
        LibraryTab.ALBUMS -> ALBUM_SORTS
        LibraryTab.PODCASTS -> listOf("A–Z", "Newest episode")
        LibraryTab.AUDIOBOOKS -> listOf("A–Z", "Recently added", "Author", "Recently listened")
    }
    // Podcasts you follow and the server's audiobooks (each person follows their own shows).
    val spokenAccount by c.spoken.account.collectAsStateWithLifecycle()
    val spokenSyncing by c.spoken.syncing.collectAsStateWithLifecycle()
    // Null until loaded, so "No podcasts yet" doesn't flash up before the shows you follow.
    val followedOrNull by remember { c.spoken.followedPodcasts() }.collectAsStateWithLifecycle(null)
    val followed = followedOrNull.orEmpty()
    val latestEpisodes by remember { c.spoken.latestEpisodes(300) }.collectAsStateWithLifecycle(emptyList())
    val books by remember { c.spoken.books() }.collectAsStateWithLifecycle(emptyList())
    val newCounts = remember(latestEpisodes) { im.flume.hearth.spoken.SpokenLists.newCounts(latestEpisodes) }
    val sortedPodcasts = remember(followed, latestEpisodes, sort, tab) {
        if (tab == LibraryTab.PODCASTS && sort == 1) {
            val newest = latestEpisodes.groupBy { it.episode.libraryItemId }.mapValues { (_, eps) -> eps.maxOf { it.episode.publishedAt } }
            followed.sortedByDescending { newest[it.id] ?: 0L }
        } else followed
    }
    val phoneBooks by remember { c.spoken.local.books() }.collectAsStateWithLifecycle(emptyList())
    val sortedBooks = remember(books, sort, tab) {
        if (tab != LibraryTab.AUDIOBOOKS) books else when (sort) {
            1 -> books.sortedByDescending { it.book.addedAt }
            2 -> books.sortedWith(compareBy({ it.book.author.orEmpty().lowercase() }, { it.book.title.lowercase() }))
            3 -> books.sortedByDescending { it.progressUpdatedAt ?: 0L }
            else -> books
        }
    }
    val importOpml = rememberOpmlImport()
    val spokenTab = tab == LibraryTab.PODCASTS || tab == LibraryTab.AUDIOBOOKS
    val sortedPlaylists = when (sort) {
        1 -> playlists.sortedByDescending { it.playlist.changed }
        2 -> playlists.sortedByDescending { it.playlist.songCount }
        else -> playlists
    }
    val sortedArtists = remember(artists, sort, tab) { if (tab == LibraryTab.ARTISTS) sortArtists(artists, sort) else artists }
    val sortedAlbums = remember(albums, sort, tab) { if (tab == LibraryTab.ALBUMS) sortAlbums(albums, sort) else albums }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Your Library") {
            // Same as Home's tile: your library. Browse → All songs shuffles the whole server.
            IconButton(onClick = { actions.play(PlaySource.MyLibrary, shuffle = true) }) {
                Icon(Icons.Default.Shuffle, "Shuffle your library", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), state = tabsState, contentPadding = PaddingValues(start = Dimens.Gutter, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryTab.entries) { t ->
                    FilterChip(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                        shape = HearthShapes.Chip,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            SortMenu(sortOptions, sort) {
                sort = it
                sortPrefs.edit().putInt("sort-${tab.name}", it).apply()
            }
        }
        PullToRefreshBox(
            isRefreshing = if (spokenTab) spokenSyncing else sync is SyncState.Running,
            onRefresh = {
                if (spokenTab) c.appScope.launch { c.spoken.refresh(force = true) }
                else c.appScope.launch { c.sync.fullSync(c.api.scanStatus()?.lastScan) }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                when (tab) {
                    LibraryTab.PLAYLISTS -> {
                        item {
                            MediaRow(
                                "New playlist", null,
                                onClick = { actions.newPlaylist() },
                                cover = { IconTile(Icons.Default.Add, SurfaceHigh) },
                            )
                        }
                        item {
                            MediaRow("Liked Songs", null, onClick = { actions.open("liked") }, cover = { IconTile(Icons.Default.Favorite, LikedColor) })
                        }
                        // Not a playlist: only shown while songs are downloading or failed (always reachable from Settings → Storage).
                        item(key = "downloads") {
                            val counts by remember { c.allDownloadCounts() }.collectAsStateWithLifecycle(im.flume.hearth.data.DownloadCounts())
                            val coming by remember { c.requests.requests }.collectAsStateWithLifecycle(emptyList())
                            downloadsShortcutLabel(counts, coming)?.let { label ->
                                MediaRow(label, null, onClick = { actions.open("downloads") }, cover = { IconTile(Icons.Default.DownloadDone, DownloadsColor) })
                            }
                        }
                        items(sortedPlaylists, key = { it.playlist.id }) { item -> PlaylistListRow(item) }
                    }
                    LibraryTab.ARTISTS -> {
                        if (sortedArtists.isEmpty()) item {
                            LibraryEmpty(
                                "No artists yet",
                                "Tap Follow on an artist's page, or save one of their albums, and they'll show up here.",
                                "Browse all artists",
                            ) { actions.open("browse/artists") }
                        }
                        items(sortedArtists, key = { it.id }) { ArtistRow(it) }
                    }
                    LibraryTab.ALBUMS -> {
                        if (sortedAlbums.isEmpty()) item {
                            LibraryEmpty(
                                "No saved albums yet",
                                "Tap the heart on an album to save it here. Albums you download are saved too.",
                                "Browse all albums",
                            ) { actions.open("browse/albums") }
                        }
                        items(sortedAlbums, key = { it.id }) { AlbumListRow(it) }
                    }
                    LibraryTab.PODCASTS -> {
                        if (spokenAccount == null) {
                            item { SpokenSignInPrompt("podcasts") }
                        } else {
                            // Nothing followed yet: say what the tab is for first, then how to start.
                            if (followedOrNull?.isEmpty() == true) item(key = "no-podcasts") {
                                LibraryEmpty(
                                    "No podcasts yet",
                                    "Search for a show to follow, or grab a single episode. New episodes from shows you follow " +
                                        "download on Wi-Fi and leave your phone once you've finished them.",
                                )
                            }
                            // The Search tab itself (not a page on top of this one), so Your Library still comes back here.
                            item(key = "add-podcast") {
                                MediaRow(
                                    "Find a podcast", null,
                                    onClick = { actions.openSearchTab(SearchFilter.PODCASTS) },
                                    cover = { IconTile(Icons.Default.Search, SurfaceHigh) },
                                )
                            }
                            item(key = "import-opml") {
                                MediaRow(
                                    "Import podcasts (OPML)", "Bring the shows you follow from another app",
                                    onClick = importOpml,
                                    cover = { IconTile(Icons.Default.UploadFile, SurfaceHigh) },
                                )
                            }
                            items(sortedPodcasts, key = { "pod:${it.id}" }) { PodcastListRow(it, newCounts[it.id] ?: 0) }
                        }
                    }
                    LibraryTab.AUDIOBOOKS -> {
                        item(key = "phone-books") { PhoneAudiobooks(sort) }
                        when {
                            spokenAccount == null -> item { SpokenSignInPrompt("audiobooks") }
                            sortedBooks.isEmpty() && phoneBooks.isEmpty() -> item {
                                LibraryEmpty(
                                    "No audiobooks yet",
                                    if (spokenSyncing) "Getting the list from the server…" else "Audiobooks on your server show up here. You can also add a folder of audiobooks on this phone in Settings → Storage.",
                                    "Search",
                                ) { actions.openSearchTab(SearchFilter.BOOKS) }
                            }
                        }
                        if (spokenAccount != null) items(sortedBooks, key = { "book:${it.book.id}" }) { BookListRow(it) }
                    }
                }
                item {
                    Text(
                        when (tab) {
                            LibraryTab.PODCASTS -> if (spokenAccount == null || followed.isEmpty()) "" else "Following ${plural(followed.size, "podcast")} • pull down to refresh"
                            LibraryTab.AUDIOBOOKS -> if (spokenAccount == null || books.isEmpty()) "" else "${plural(books.size, "audiobook")} on the server • pull down to refresh"
                            else -> "${plural(songCount, "song")} on the server • pull down to refresh"
                        },
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistListRow(item: PlaylistItem) {
    val actions = LocalActions.current
    val p = item.playlist
    val a = item.access
    val owner = PlaylistRules.displayName(a.owner)
    val subtitle = when {
        a.invite != null -> "$owner invited you"
        a.isOwner -> "Playlist • ${plural(p.songCount, "song")}"
        else -> "Playlist • by $owner"
    }
    MediaRow(
        p.name, subtitle, onClick = { actions.openPlaylist(p.id) }, coverArt = p.coverArt,
        trailing = if (a.invite != null) {
            { Box(Modifier.padding(end = 12.dp).size(10.dp).clip(CircleShape).background(NoticeDot).semantics { contentDescription = "New invite" }) }
        } else null,
    )
}

@Composable
private fun LibraryEmpty(title: String, body: String, button: String? = null, onBrowse: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (button != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBrowse) { Text(button) }
        }
    }
}

/** Every artist on the server, for when you want more than Your Library. */
@Composable
fun BrowseArtistsScreen() {
    val dao = LocalContext.current.container.db.library()
    val artists by remember { dao.artists() }.collectAsStateWithLifecycle(emptyList())
    BrowseList("All artists", "browse-ARTISTS", ARTIST_SORTS) { sort ->
        items(sortArtists(artists, sort), key = { it.id }) { ArtistRow(it) }
    }
}

/** Every album on the server, saved or not. */
@Composable
fun BrowseAlbumsScreen() {
    val dao = LocalContext.current.container.db.library()
    val albums by remember { dao.albums() }.collectAsStateWithLifecycle(emptyList())
    BrowseList("All albums", "browse-ALBUMS", ALBUM_SORTS) { sort ->
        items(sortAlbums(albums, sort), key = { it.id }) { AlbumListRow(it) }
    }
}

@Composable
fun BrowseSongsScreen() {
    val dao = LocalContext.current.container.db.library()
    val actions = LocalActions.current
    val songs by remember { dao.allSongs() }.collectAsStateWithLifecycle(emptyList())
    val sortedCache = remember(songs) { HashMap<SongOrder, List<SongEntity>>() }
    BrowseList(
        "All songs", "browse-SONGS", SONG_SORTS,
        extraActions = {
            IconButton(onClick = { actions.play(PlaySource.All, shuffle = true) }) {
                Icon(Icons.Default.Shuffle, "Shuffle all songs", tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) { sort ->
        // Sorting ~7k songs is too slow to redo on every recomposition: once per list and order.
        val order = SongOrder.fromMenu(sort)
        val sorted = sortedCache.getOrPut(order) { order.sort(songs) }
        // Tapping a song plays everything, in this order, starting from that song. The service sorts
        // the library itself: thousands of ids in a Bundle can exceed the Binder limit.
        items(sorted, key = { it.id }) { song ->
            SongRow(song, onClick = {
                actions.play(PlaySource(PlaySource.Kind.ALL, label = "All songs", order = order), startSongId = song.id)
            })
        }
    }
}

private val SONG_SORTS = listOf("A–Z", "Artist", "Recently added", "Most played")

@Composable
private fun BrowseList(
    title: String,
    prefKey: String,
    sortOptions: List<String>,
    extraActions: @Composable () -> Unit = {},
    content: LazyListScope.(sort: Int) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE) }
    var sort by remember { mutableStateOf(prefs.getInt(prefKey, 0)) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title) {
            extraActions()
            SortMenu(sortOptions, sort) {
                sort = it
                prefs.edit().putInt(prefKey, it).apply()
            }
        }
        LazyColumn(Modifier.fillMaxSize()) { content(sort) }
    }
}

/** Columns for tile grids (genres, Home's quick tiles): 2 on phones, 3-4 on wide windows. */
@Composable
internal fun gridColumns(): Int {
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    return when {
        width > 840 -> 4
        width > 600 -> 3
        else -> 2
    }
}

/** True when the window is wider than tall (landscape), so tall headers go side by side. */
@Composable
internal fun isWideWindow(): Boolean {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    return config.screenWidthDp > config.screenHeightDp
}
