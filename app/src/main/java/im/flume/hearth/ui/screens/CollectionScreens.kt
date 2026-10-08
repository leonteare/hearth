package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Radio
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import im.flume.hearth.ui.theme.Background
import im.flume.hearth.ui.theme.SurfaceHigh
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.data.DownloadState
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SongEntity
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.ui.components.AlbumCard
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.DownloadToggle
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalRowContext
import im.flume.hearth.ui.components.PlayShuffleButtons
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.SongRow
import im.flume.hearth.ui.components.formatBytes
import im.flume.hearth.ui.components.formatLongDuration
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.TextSecondary

/** Pin target for the download toggle: albums and playlists stay in sync; other lists are one-off downloads. */
data class PinTarget(val kind: String, val id: String)

@Composable
fun BackButton() {
    val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    IconButton(onClick = { dispatcher?.onBackPressed() }, modifier = Modifier.statusBarsPadding()) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
    }
}

@Composable
fun CollectionScreen(
    title: String,
    subtitle: String,
    cover: String?,
    songs: List<SongEntity>,
    source: PlaySource,
    pin: PinTarget? = null,
    showTrackNumbers: Boolean = false,
    headerIcon: (@Composable () -> Unit)? = null,
    extraContent: LazyListScope.() -> Unit = {},
    topContent: LazyListScope.() -> Unit = {},
    onRemoveSong: ((index: Int) -> Unit)? = null,
    headerActions: @Composable () -> Unit = {},
) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val downloads = LocalRowContext.current.downloads
    val pinned by remember { c.downloads.pinned }.collectAsStateWithLifecycle(emptySet())
    val isPinned = pin != null && "${pin.kind}:${pin.id}" in pinned
    val allDownloaded = songs.isNotEmpty() && songs.all { downloads[it.id] == DownloadState.DONE }
    val anyDownloading = songs.any { downloads[it.id] == DownloadState.QUEUED || downloads[it.id] == DownloadState.DOWNLOADING }
    val doneCount = songs.count { downloads[it.id] == DownloadState.DONE }
    val headerColor = rememberCoverColor(cover)
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Stop downloading?") },
            text = { Text("This removes \"$title\" from your downloads, including songs that already finished.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    if (pin != null) c.downloads.unpinAndRemove(pin.kind, pin.id, songs.map { it.id })
                    else actions.removeDownload(songs.map { it.id })
                }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep downloading") } },
        )
    }
    val totalSec = songs.sumOf { it.durationSec.toLong() }

    // Long-press a song to start selecting; tap to add or remove songs from the selection.
    var selection by remember(songs) { mutableStateOf<Set<Int>?>(null) }
    val usage = c.usage
    androidx.activity.compose.BackHandler(enabled = selection != null) { selection = null }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val collapsed by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(headerColor, Color.Transparent)))
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BackButton()
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.statusBarsPadding()) { headerActions() }
                    }
                    if (headerIcon != null) headerIcon() else CoverArt(cover, 220.dp, requestSize = 600, fallback = title)
                    Spacer(Modifier.height(16.dp))
                    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                    Text(
                        listOfNotNull(subtitle.takeIf { it.isNotBlank() }, "${songs.size} songs", formatLongDuration(totalSec).takeIf { totalSec > 0 })
                            .joinToString(" • "),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
        item {
            PlayShuffleButtons(
                onPlay = { actions.play(source) },
                onShuffle = { actions.play(source, shuffle = true) },
                extra = {
                    DownloadToggle(
                        downloaded = allDownloaded || (isPinned && !anyDownloading),
                        progress = if (anyDownloading) doneCount.toFloat() / songs.size.coerceAtLeast(1) else null,
                    ) {
                        when {
                            anyDownloading -> confirmCancel = true
                            pin != null && (isPinned || allDownloaded) -> c.downloads.unpinAndRemove(pin.kind, pin.id, songs.map { it.id })
                            pin != null -> c.downloads.pinAndDownload(pin.kind, pin.id, songs)
                            allDownloaded -> actions.removeDownload(songs.map { it.id })
                            else -> actions.download(songs)
                        }
                    }
                },
            )
        }
        topContent()
        val multiDisc = showTrackNumbers && songs.map { it.disc }.distinct().size > 1
        itemsIndexed(songs, key = { i, s -> "$i:${s.id}" }) { index, song ->
            if (multiDisc && (index == 0 || songs[index - 1].disc != song.disc)) {
                Text(
                    "Disc ${song.disc}",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 16.dp, top = if (index == 0) 4.dp else 16.dp, bottom = 4.dp),
                )
            }
            SongRow(
                song,
                onClick = {
                    val sel = selection
                    if (sel != null) selection = (if (index in sel) sel - index else sel + index).takeIf { it.isNotEmpty() }
                    else actions.play(source, startSongId = song.id)
                },
                onRemoveFromPlaylist = onRemoveSong?.let { remove -> { remove(index) } },
                showCover = !showTrackNumbers,
                leading = if (showTrackNumbers) song.track.takeIf { it > 0 }?.toString() ?: "–" else null,
                selected = selection?.let { index in it },
                onLongPress = {
                    if (selection == null) usage.track(im.flume.hearth.data.Usage.MULTI_SELECT)
                    selection = (selection ?: emptySet()) + index
                },
            )
        }
        extraContent()
    }

    val sel = selection
    if (sel != null) {
        val picked = sel.sorted().mapNotNull { songs.getOrNull(it) }
        SelectionBar(
            count = picked.size,
            onClose = { selection = null },
            onPlayNext = { actions.playNext(picked.map { it.id }); selection = null },
            onQueue = { actions.addToQueue(picked.map { it.id }); selection = null },
            onPlaylist = { actions.addToPlaylist(picked.map { it.id }); selection = null },
            onDownload = { actions.download(picked); selection = null },
            onSelectAll = { selection = songs.indices.toSet() },
        )
    } else {
        // Slim bar with the title and a play button once the big header has scrolled away.
        androidx.compose.animation.AnimatedVisibility(
            visible = collapsed,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Row(
                Modifier.fillMaxWidth().background(lerp(headerColor, Background, 0.35f)).statusBarsPadding().padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton()
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                FilledIconButton(
                    onClick = { actions.play(source) },
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color.Black),
                ) { Icon(Icons.Default.PlayArrow, "Play") }
            }
        }
    }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    onPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceHigh).statusBarsPadding().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Stop selecting") }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, "Select all") }
        IconButton(onClick = onPlayNext) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Play next") }
        IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Add to queue") }
        IconButton(onClick = onPlaylist) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") }
        IconButton(onClick = onDownload) { Icon(Icons.Outlined.ArrowCircleDown, "Download") }
    }
}

@Composable
fun AlbumScreen(id: String) {
    val dao = LocalContext.current.container.db.library()
    val album by remember(id) { dao.album(id) }.collectAsStateWithLifecycle(null)
    val songs by remember(id) { dao.albumSongs(id) }.collectAsStateWithLifecycle(emptyList())
    val a = album
    val actions = LocalActions.current
    CollectionScreen(
        title = a?.name ?: songs.firstOrNull()?.album.orEmpty(),
        subtitle = listOfNotNull(a?.artist, a?.year?.toString()).joinToString(" • "),
        cover = a?.coverArt ?: songs.firstOrNull()?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.ALBUM, id, a?.name.orEmpty()),
        pin = PinTarget(DownloadRepository.KIND_ALBUM, id),
        showTrackNumbers = true,
        extraContent = {
            item { AlbumDetails(a, id, songs.size, songs.sumOf { it.durationSec.toLong() }) }
            a?.artistId?.let { artistId ->
                item {
                    TextButton(onClick = { actions.openArtist(artistId) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("More by ${a.artist}", color = Accent)
                    }
                }
            }
        },
    )
}

@Composable
fun MixScreen(index: Int) {
    val c = LocalContext.current.container
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    var mix by remember { mutableStateOf<im.flume.hearth.data.Mix?>(null) }
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    LaunchedEffect(index) {
        mix = c.mixes.today(online).getOrNull(index)
        songs = mix?.let { c.library.songsByIds(it.songIds) }.orEmpty()
    }
    val m = mix ?: return
    CollectionScreen(
        title = m.title,
        subtitle = m.subtitle,
        cover = m.covers.firstOrNull(),
        songs = songs,
        source = PlaySource(PlaySource.Kind.SONGS, label = "${m.title} · ${m.subtitle}", songIds = m.songIds),
        headerIcon = { MixCover(m, 220.dp) },
    )
}

@Composable
fun PlaylistScreen(id: String) {
    val dao = LocalContext.current.container.db.library()
    val playlist by remember(id) { dao.playlist(id) }.collectAsStateWithLifecycle(null)
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val me = c.session.credentials.collectAsStateWithLifecycle().value?.username
    val songs by remember(id) { dao.playlistSongs(id) }.collectAsStateWithLifecycle(emptyList())
    CollectionScreen(
        title = playlist?.name.orEmpty(),
        // Only name the owner when it's someone else's playlist.
        subtitle = playlist?.owner?.takeIf { !it.equals(me, ignoreCase = true) }.orEmpty(),
        onRemoveSong = if (playlist?.owner == null || playlist?.owner.equals(me, ignoreCase = true)) { index ->
            val before = songs.map { it.id }
            c.appScope.launch { runCatching { c.library.removeFromPlaylist(id, index) } }
            actions.showUndo("Removed from playlist") { c.library.setPlaylistSongs(id, before) }
        } else null,
        cover = playlist?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.PLAYLIST, id, playlist?.name.orEmpty()),
        pin = PinTarget(DownloadRepository.KIND_PLAYLIST, id),
        headerActions = {
            if (playlist != null && (playlist?.owner == null || playlist?.owner.equals(me, ignoreCase = true))) {
                PlaylistMenu(id, playlist!!.name, songs.map { it.id })
            }
        },
    )
}

@Composable
private fun PlaylistMenu(id: String, name: String, songIds: List<String>) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var newName by remember(name) { mutableStateOf(name) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Playlist options") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { open = false; renaming = true })
            DropdownMenuItem(text = { Text("Edit order") }, leadingIcon = { Icon(Icons.Default.SwapVert, null) }, onClick = { open = false; actions.open("playlist-edit/$id") })
            DropdownMenuItem(text = { Text("Delete playlist") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { open = false; deleting = true })
        }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    renaming = false
                    c.appScope.launch { runCatching { c.library.renamePlaylist(id, newName.trim()) } }
                }) { Text("Save", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"$name\"?") },
            text = { Text("The playlist is removed from Navidrome for good. The songs stay in your library.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    c.appScope.launch {
                        runCatching { c.library.deletePlaylist(id) }
                        c.downloads.unpinAndRemove(DownloadRepository.KIND_PLAYLIST, id, emptyList())
                    }
                    back?.onBackPressed()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** Drag songs into a new order, then save it to Navidrome. */
@Composable
fun PlaylistEditScreen(id: String) {
    val c = LocalContext.current.container
    val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val original by remember(id) { c.db.library().playlistSongs(id) }.collectAsStateWithLifecycle(emptyList())
    var order by remember { mutableStateOf<List<Pair<Int, SongEntity>>>(emptyList()) }
    LaunchedEffect(original) { if (order.isEmpty()) order = original.mapIndexed { i, s -> i to s } }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton()
            Text("Edit order", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                c.appScope.launch { runCatching { c.library.setPlaylistSongs(id, order.map { it.second.id }) } }
                back?.onBackPressed()
            }) { Text("Save", color = Accent) }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(order, key = { it.first }) { (key, song) ->
                ReorderableItem(reorder, key = key) { dragging ->
                    Row(
                        Modifier.fillMaxWidth().background(if (dragging) SurfaceHigh else Background).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverArt(song.coverArt, 44.dp, requestSize = 150, fallback = song.album)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, maxLines = 1)
                            Text(song.artist, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                        Box(Modifier.draggableHandle().size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DragHandle, "Reorder", tint = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(id: String) {
    val dao = LocalContext.current.container.db.library()
    val artist by remember(id) { dao.artist(id) }.collectAsStateWithLifecycle(null)
    val songs by remember(id) { dao.artistSongs(id) }.collectAsStateWithLifecycle(emptyList())
    val albums by remember(id) { dao.artistAlbums(id) }.collectAsStateWithLifecycle(emptyList())
    val actions = LocalActions.current
    val name = artist?.name ?: songs.firstOrNull()?.artist.orEmpty()
    val source = PlaySource(PlaySource.Kind.ARTIST, id, name)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            val artistColor = rememberCoverColor(artist?.coverArt ?: albums.firstOrNull()?.coverArt)
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(artistColor, Color.Transparent)))) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) { BackButton() }
                    CoverArt(artist?.coverArt ?: albums.firstOrNull()?.coverArt, 180.dp, Modifier.clip(CircleShape), requestSize = 600)
                    Spacer(Modifier.height(16.dp))
                    Text(name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text("${albums.size} albums • ${songs.size} songs", color = TextSecondary, modifier = Modifier.padding(4.dp))
                }
            }
        }
        item {
            PlayShuffleButtons(
                onPlay = { actions.play(source) },
                onShuffle = { actions.play(source, shuffle = true) },
                extra = {
                    TextButton(onClick = { actions.startArtistRadio(id, name) }) {
                        Icon(Icons.Default.Radio, null, tint = Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Radio", color = Accent)
                    }
                },
            )
        }
        if (songs.isNotEmpty()) {
            item { SectionHeader("Popular") }
            items(songs.take(5), key = { "top:${it.id}" }) { s ->
                SongRow(s, onClick = { actions.play(PlaySource(PlaySource.Kind.SONGS, label = name, songIds = songs.map { it.id }), startSongId = s.id) })
            }
        }
        if (albums.isNotEmpty()) {
            item {
                SectionHeader("Albums")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(albums, key = { it.id }) { AlbumCard(it, subtitle = it.year?.toString().orEmpty()) }
                }
            }
        }
        if (songs.size > 5) {
            item { SectionHeader("All songs") }
            items(songs.drop(5), key = { "all:${it.id}" }) { s ->
                SongRow(s, onClick = { actions.play(source, startSongId = s.id) })
            }
        }
        item { ArtistAbout(id) }
    }
}

@Composable
fun GenreScreen(name: String) {
    val dao = LocalContext.current.container.db.library()
    val songs by remember(name) { dao.genreSongs(name) }.collectAsStateWithLifecycle(emptyList())
    CollectionScreen(
        title = name,
        subtitle = "Genre",
        cover = songs.firstOrNull()?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.GENRE, name, name),
    )
}

@Composable
fun LikedScreen() {
    val dao = LocalContext.current.container.db.library()
    val songs by remember { dao.starredSongs() }.collectAsStateWithLifecycle(emptyList())
    CollectionScreen(
        title = "Liked Songs",
        subtitle = "",
        cover = null,
        songs = songs,
        source = PlaySource.Liked,
        pin = PinTarget(DownloadRepository.KIND_LIKED, "liked"),
        headerIcon = { BigIcon(Icons.Default.Favorite, Color(0xFF5038A0)) },
    )
}

@Composable
fun DownloadsScreen() {
    val c = LocalContext.current.container
    val songs by remember { c.db.library().downloadedSongs() }.collectAsStateWithLifecycle(emptyList())
    val pending by remember { c.db.library().pendingDownloadSongs() }.collectAsStateWithLifecycle(emptyList())
    val bytes by remember { c.downloads.totalBytes }.collectAsStateWithLifecycle(0L)
    val progress by c.downloads.progress.collectAsStateWithLifecycle()
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val states = LocalRowContext.current.downloads
    val queued = pending.filter { states[it.id] != DownloadState.FAILED }
    val failed = pending.filter { states[it.id] == DownloadState.FAILED }
    val waitingForWifi = queued.isNotEmpty() && progress.isEmpty() && settings.wifiOnlyDownloads && !c.network.onWifi()
    val lastError by c.downloads.lastError.collectAsStateWithLifecycle()
    val paused by remember { c.downloads.pausedAfterErrors }.collectAsStateWithLifecycle(false)

    CollectionScreen(
        title = "Downloads",
        subtitle = formatBytes(bytes),
        cover = null,
        songs = songs,
        source = PlaySource.Downloads,
        headerIcon = { BigIcon(Icons.Default.DownloadDone, Color(0xFF1E6B52)) },
        topContent = {
            if (queued.isNotEmpty()) {
                item(key = "q-head") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (settings.downloadsPaused) "Paused" else "Downloading") + " · ${queued.size} left",
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        )
                        if (settings.downloadsPaused) {
                            TextButton(onClick = { c.downloads.resume() }) { Text("Resume", color = Accent) }
                        } else {
                            TextButton(onClick = { c.downloads.pause() }) { Text("Pause", color = Accent) }
                        }
                        TextButton(onClick = { c.downloads.cancelPending() }) { Text("Cancel all", color = TextSecondary) }
                    }
                    if (paused && !waitingForWifi && !settings.downloadsPaused) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Paused" + (lastError?.let { ": $it" } ?: ""),
                                color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { c.downloads.retryFailed() }) { Text("Retry now", color = Accent) }
                        }
                    }
                    if (waitingForWifi && !settings.downloadsPaused) {
                        Text(
                            "Waiting for Wi-Fi. Turn off \"Download on Wi-Fi only\" in Settings → Storage to use mobile data.",
                            color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                items(queued.take(50), key = { "q:${it.id}" }) { song ->
                    val downloading = song.id in progress
                    Column {
                        SongRow(song, onClick = {})
                        if (downloading) {
                            LinearProgressIndicator(
                                progress = { progress[song.id] ?: 0f },
                                modifier = Modifier.fillMaxWidth().padding(start = 76.dp, end = 16.dp).height(3.dp),
                                color = Accent,
                                trackColor = TextSecondary.copy(alpha = 0.25f),
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                        }
                    }
                }
                if (queued.size > 50) {
                    item(key = "q-more") { Text("+ ${queued.size - 50} more waiting", color = TextSecondary, modifier = Modifier.padding(16.dp)) }
                }
            }
            if (failed.isNotEmpty()) {
                item(key = "f-head") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${failed.size} couldn't download", style = MaterialTheme.typography.titleMedium)
                            lastError?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium) }
                        }
                        TextButton(onClick = { c.downloads.retryFailed() }) { Text("Retry", color = Accent) }
                    }
                }
            }
            if (songs.isNotEmpty() && (queued.isNotEmpty() || failed.isNotEmpty())) {
                item(key = "d-head") { SectionHeader("Downloaded") }
            }
        },
        extraContent = {
            if (songs.isNotEmpty()) {
                item {
                    TextButton(onClick = { c.downloads.removeAll() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Remove all downloads", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else if (pending.isEmpty()) {
                item {
                    Text(
                        "Nothing downloaded yet. Tap the download icon on an album or playlist to keep it on your phone.",
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun BigIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Box(Modifier.size(180.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(72.dp), tint = Color.White)
    }
}
