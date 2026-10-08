package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.History
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
import im.flume.hearth.ui.theme.Accent
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
    LaunchedEffect(Unit) { focus.requestFocus() }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            songs = emptyList(); albums = emptyList(); artists = emptyList(); return@LaunchedEffect
        }
        delay(120)
        // Scanning ~7k songs is too slow for the main thread; a new keystroke cancels this anyway.
        val index = c.library.searchIndex(c.session.lastSyncAt)
        val r = withContext(Dispatchers.Default) { index.search(q) }
        artists = r.artists
        albums = r.albums
        songs = r.songs
        closeMatches = r.fuzzy
    }

    Column(Modifier.fillMaxSize()) {
        Text("Search", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.statusBarsPadding().padding(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = Color.Black, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Black),
                cursorBrush = SolidColor(Color.Black),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { saveRecent(query) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Songs, artists or albums", color = Color.DarkGray, style = MaterialTheme.typography.bodyLarge)
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear", tint = Color.Black) }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (query.isBlank()) {
                if (recent.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SectionHeader("Recent searches") }
                            TextButton(onClick = {
                                recent = emptyList()
                                recentPrefs.edit().remove("recent").apply()
                            }) { Text("Clear", color = TextSecondary) }
                        }
                    }
                    items(recent, key = { "r:$it" }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { query = r }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.History, null, tint = TextSecondary)
                            Spacer(Modifier.width(16.dp))
                            Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (genres.isNotEmpty()) item { SectionHeader("Browse genres") }
                items(genres.chunked(2)) { pair ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { g ->
                            Box(
                                Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(8.dp))
                                    .background(genreColor(g.genre)).clickable { actions.openGenre(g.genre) }.padding(12.dp)
                            ) {
                                Text(g.genre, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
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
                if (artists.isEmpty() && albums.isEmpty() && songs.isEmpty()) {
                    item { Text("No results for \"$query\"", color = TextSecondary, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

private fun genreColor(name: String): Color {
    val palette = listOf(0xFF8C1932, 0xFF1E3264, 0xFF477D95, 0xFF8D67AB, 0xFFE8115B, 0xFF148A08, 0xFFBA5D07, 0xFF503750, 0xFF0D73EC, 0xFF777777)
    return Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
}

@Composable
fun ArtistRow(a: ArtistEntity) {
    val actions = LocalActions.current
    Row(
        Modifier.fillMaxWidth().clickable { actions.openArtist(a.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(a.coverArt, 52.dp, Modifier.clip(CircleShape), requestSize = 150, fallback = a.name)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Artist • ${a.albumCount} albums", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun AlbumListRow(a: AlbumEntity) {
    val actions = LocalActions.current
    Row(
        Modifier.fillMaxWidth().clickable { actions.openAlbum(a.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(a.coverArt, 52.dp, requestSize = 150, fallback = a.name)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull("Album", a.artist, a.year?.toString()).joinToString(" • "), color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private enum class LibraryTab(val label: String) { PLAYLISTS("Playlists"), ARTISTS("Artists"), ALBUMS("Albums") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val c = LocalContext.current.container
    val dao = c.db.library()
    val actions = LocalActions.current
    var tab by rememberSaveable { mutableStateOf(LibraryTab.PLAYLISTS) }
    val playlists by remember { dao.playlists() }.collectAsStateWithLifecycle(emptyList())
    val artists by remember { dao.artists() }.collectAsStateWithLifecycle(emptyList())
    val albums by remember { dao.albums() }.collectAsStateWithLifecycle(emptyList())
    val songCount by remember { dao.songCount() }.collectAsStateWithLifecycle(0)
    val sync by c.sync.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sortPrefs = remember { context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE) }
    var sort by remember(tab) { mutableStateOf(sortPrefs.getInt("sort-${tab.name}", 0)) }
    var sortOpen by remember { mutableStateOf(false) }
    val sortOptions = when (tab) {
        LibraryTab.PLAYLISTS -> listOf("A–Z", "Recently updated", "Most songs")
        LibraryTab.ARTISTS -> listOf("A–Z", "Most albums")
        LibraryTab.ALBUMS -> listOf("A–Z", "Recently added", "Artist", "Most played", "Year")
    }
    val sortedPlaylists = when (sort) {
        1 -> playlists.sortedByDescending { it.changed }
        2 -> playlists.sortedByDescending { it.songCount }
        else -> playlists
    }
    val sortedArtists = if (sort == 1 && tab == LibraryTab.ARTISTS) artists.sortedByDescending { it.albumCount } else artists
    val sortedAlbums = when (sort) {
        1 -> albums.sortedByDescending { it.created }
        2 -> albums.sortedWith(compareBy({ it.artist.lowercase().removePrefix("the ") }, { it.year ?: 0 }))
        3 -> albums.sortedByDescending { it.playCount }
        4 -> albums.sortedByDescending { it.year ?: 0 }
        else -> albums
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { actions.play(PlaySource.All, shuffle = true) }) { Icon(Icons.Default.Shuffle, "Shuffle all", tint = Accent) }
            IconButton(onClick = { actions.open("settings") }) { Icon(Icons.Default.Settings, "Settings") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryTab.entries) { t ->
                    FilterChip(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.Black),
                    )
                }
            }
            Box {
                TextButton(onClick = { sortOpen = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sortOptions.getOrElse(sort) { sortOptions[0] }, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                    sortOptions.forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label, color = if (i == sort) Accent else Color.Unspecified) }, onClick = {
                            sort = i
                            sortPrefs.edit().putInt("sort-${tab.name}", i).apply()
                            sortOpen = false
                        })
                    }
                }
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
                        item { LibraryShortcut("Liked Songs", Icons.Default.Favorite, Color(0xFF5038A0)) { actions.open("liked") } }
                        item { LibraryShortcut("Downloads", Icons.Default.DownloadDone, Color(0xFF1E6B52)) { actions.open("downloads") } }
                        items(sortedPlaylists, key = { it.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { actions.openPlaylist(p.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverArt(p.coverArt, 56.dp, requestSize = 150, fallback = p.name)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Playlist • ${p.songCount} songs", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    LibraryTab.ARTISTS -> items(sortedArtists, key = { it.id }) { ArtistRow(it) }
                    LibraryTab.ALBUMS -> items(sortedAlbums, key = { it.id }) { AlbumListRow(it) }
                }
                item {
                    Text(
                        "$songCount songs • pull down to refresh",
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
private fun LibraryShortcut(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
    }
}
