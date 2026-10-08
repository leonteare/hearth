package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.text.style.TextAlign
import im.flume.hearth.data.YourLibrary
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var albums by remember { mutableStateOf<List<AlbumEntity>>(emptyList()) }
    var artists by remember { mutableStateOf<List<ArtistEntity>>(emptyList()) }
    var closeMatches by remember { mutableStateOf(false) }
    // True while the search index is being built (the first search after launch or a sync).
    var indexing by remember { mutableStateOf(false) }
    val c = LocalContext.current.container
    val genres by remember { dao.genres() }.collectAsStateWithLifecycle(emptyList())

    val context = LocalContext.current
    val recentPrefs = remember { context.getSharedPreferences("search", android.content.Context.MODE_PRIVATE) }
    var recent by remember { mutableStateOf(recentPrefs.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }) }
    fun saveRecent(q: String) {
        val t = q.trim().takeIf { it.length >= 2 } ?: return
        c.usage.track(im.flume.hearth.data.Usage.SEARCH)
        recent = (listOf(t) + recent.filterNot { it.equals(t, ignoreCase = true) }).take(10)
        recentPrefs.edit().putString("recent", recent.joinToString("\n")).apply()
    }
    // Searches that found something are remembered when you leave the page.
    val latestQuery by rememberUpdatedState(query)
    val hadResults by rememberUpdatedState(songs.isNotEmpty() || albums.isNotEmpty() || artists.isNotEmpty())
    DisposableEffect(Unit) { onDispose { if (hadResults) saveRecent(latestQuery) } }

    val focus = remember { FocusRequester() }
    val gridColumns = gridColumns()
    LaunchedEffect(Unit) { focus.requestFocus() }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            songs = emptyList(); albums = emptyList(); artists = emptyList(); return@LaunchedEffect
        }
        delay(120)
        // Scanning ~7k songs is too slow for the main thread; a new keystroke cancels this anyway.
        indexing = true
        val index = try { c.library.searchIndex(c.session.lastSyncAt) } finally { indexing = false }
        val r = withContext(Dispatchers.Default) { index.search(q) }
        artists = r.artists
        albums = r.albums
        songs = r.songs
        closeMatches = r.fuzzy
    }

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
                    if (query.isEmpty()) Text("Songs, artists or albums", color = SearchFieldHint, style = MaterialTheme.typography.bodyLarge)
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear", tint = OnSearchField) }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
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
                    items(recent, key = { "r:$it" }) { r ->
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
                // The Library tab only shows what you saved; the whole server is one tap away here.
                item { SectionHeader("Browse everything") }
                item {
                    Row(Modifier.padding(horizontal = Dimens.Gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrowseTile("All artists", Icons.Default.Person, Modifier.weight(1f)) { actions.open("browse/artists") }
                        BrowseTile("All albums", Icons.Default.Album, Modifier.weight(1f)) { actions.open("browse/albums") }
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
                if (closeMatches && (artists.isNotEmpty() || albums.isNotEmpty() || songs.isNotEmpty())) {
                    item {
                        Text(
                            "No exact matches. Showing close matches.",
                            color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (artists.isNotEmpty()) {
                    item { SectionHeader("Artists") }
                    items(artists, key = { "ar:${it.id}" }) { a -> ArtistRow(a) }
                }
                if (albums.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    items(albums, key = { "al:${it.id}" }) { a -> AlbumListRow(a) }
                }
                if (songs.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    items(songs, key = { "s:${it.id}" }) { s ->
                        SongRow(s, onClick = {
                            actions.play(PlaySource(PlaySource.Kind.SONGS, label = "Search", songIds = songs.map { it.id }), startSongId = s.id)
                        })
                    }
                }
                if (!indexing && artists.isEmpty() && albums.isEmpty() && songs.isEmpty()) {
                    item { Text("No results for \"$query\"", color = TextSecondary, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BrowseTile(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 56.dp).clip(HearthShapes.Card).background(SurfaceHigh).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private enum class LibraryTab(val label: String) { PLAYLISTS("Playlists"), ARTISTS("Artists"), ALBUMS("Albums") }

private val ARTIST_SORTS = listOf("A–Z", "Most albums")
private val ALBUM_SORTS = listOf("A–Z", "Recently added", "Artist", "Most played", "Year")

private fun sortArtists(artists: List<ArtistEntity>, sort: Int) =
    if (sort == 1) artists.sortedByDescending { it.albumCount } else artists

private fun sortAlbums(albums: List<AlbumEntity>, sort: Int) = when (sort) {
    1 -> albums.sortedByDescending { it.created }
    2 -> albums.sortedWith(compareBy({ it.artist.lowercase().removePrefix("the ") }, { it.year ?: 0 }))
    3 -> albums.sortedByDescending { it.playCount }
    4 -> albums.sortedByDescending { it.year ?: 0 }
    else -> albums
}

@Composable
private fun SortMenu(options: List<String>, sort: Int, onSort: (Int) -> Unit) {
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
    val playlists by remember { dao.playlists() }.collectAsStateWithLifecycle(emptyList())
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
    }
    val sortedPlaylists = when (sort) {
        1 -> playlists.sortedByDescending { it.changed }
        2 -> playlists.sortedByDescending { it.songCount }
        else -> playlists
    }
    val sortedArtists = remember(artists, sort, tab) { if (tab == LibraryTab.ARTISTS) sortArtists(artists, sort) else artists }
    val sortedAlbums = remember(albums, sort, tab) { if (tab == LibraryTab.ALBUMS) sortAlbums(albums, sort) else albums }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Your Library") {
            // Home's tile shuffles just your library; this one is for the whole server.
            IconButton(onClick = { actions.play(PlaySource.All, shuffle = true) }) {
                Icon(Icons.Default.Shuffle, "Shuffle everything", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(start = Dimens.Gutter, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            isRefreshing = sync is SyncState.Running,
            onRefresh = { c.appScope.launch { c.sync.fullSync(c.api.scanStatus()?.lastScan) } },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                when (tab) {
                    LibraryTab.PLAYLISTS -> {
                        item {
                            MediaRow("Liked Songs", null, onClick = { actions.open("liked") }, cover = { IconTile(Icons.Default.Favorite, LikedColor) })
                        }
                        item {
                            MediaRow("Downloads", null, onClick = { actions.open("downloads") }, cover = { IconTile(Icons.Default.DownloadDone, DownloadsColor) })
                        }
                        items(sortedPlaylists, key = { it.id }) { p ->
                            MediaRow(p.name, "Playlist • ${plural(p.songCount, "song")}", onClick = { actions.openPlaylist(p.id) }, coverArt = p.coverArt)
                        }
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
                }
                item {
                    Text(
                        "${plural(songCount, "song")} on the server • pull down to refresh",
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
private fun LibraryEmpty(title: String, body: String, button: String, onBrowse: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBrowse) { Text(button) }
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
private fun BrowseList(title: String, prefKey: String, sortOptions: List<String>, content: LazyListScope.(sort: Int) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE) }
    var sort by remember { mutableStateOf(prefs.getInt(prefKey, 0)) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title) {
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
