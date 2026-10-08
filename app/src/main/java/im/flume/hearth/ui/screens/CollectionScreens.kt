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
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Radio
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import im.flume.hearth.ui.components.EmptyState
import im.flume.hearth.ui.components.LoadingPage
import im.flume.hearth.ui.components.UnavailablePage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.BackButton
import im.flume.hearth.ui.components.MediaCard
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.TopBar
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.theme.Destructive
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.DownloadsColor
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.LikedColor
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
import im.flume.hearth.ui.theme.TextSecondary

/** Pin target for the download toggle: albums and playlists stay in sync; other lists are one-off downloads. */
data class PinTarget(val kind: String, val id: String)

@Composable
fun CollectionScreen(
    title: String,
    subtitle: String,
    cover: String?,
    songs: List<SongEntity>,
    source: PlaySource,
    pin: PinTarget? = null,
    showTrackNumbers: Boolean = false,
    /** Replaces the cover art in the header; drawn at the given size. */
    headerIcon: (@Composable (Dp) -> Unit)? = null,
    /** Shown in place of the song list when the caller knows the list is loaded and empty. */
    emptyState: (@Composable () -> Unit)? = null,
    extraContent: LazyListScope.() -> Unit = {},
    topContent: LazyListScope.() -> Unit = {},
    onRemoveSong: ((index: Int) -> Unit)? = null,
    headerActions: @Composable () -> Unit = {},
    /** Albums only: whether it's in Your Library. Null hides the heart. */
    saved: Boolean? = null,
    onSavedChange: (Boolean) -> Unit = {},
) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val accent = MaterialTheme.colorScheme.primary
    val downloads = LocalRowContext.current.downloads
    val pinned by remember { c.downloads.pinned }.collectAsStateWithLifecycle(emptySet())
    val isPinned = pin != null && "${pin.kind}:${pin.id}" in pinned
    val doneCount = remember(songs, downloads) { songs.count { downloads[it.id] == DownloadState.DONE } }
    val allDownloaded = songs.isNotEmpty() && doneCount == songs.size
    // A song can be downloading because another album or playlist was downloaded; only show this
    // collection as downloading if it was itself downloaded (or has no pin, e.g. a genre).
    val anyDownloading = remember(songs, downloads, isPinned) {
        (pin == null || isPinned) &&
            songs.any { downloads[it.id] == DownloadState.QUEUED || downloads[it.id] == DownloadState.DOWNLOADING }
    }
    val headerColor = rememberCoverColor(cover)
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Stop downloading?") },
            text = { Text("This removes \"$title\" from your downloads, including songs that already finished. Songs your other downloads need are kept.") },
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
    val totalSec = remember(songs) { songs.sumOf { it.durationSec.toLong() } }

    // Stable row keys: the song id, plus a count for repeats (a playlist can hold a song twice).
    // Index-based keys would churn every time the list changes.
    val keys = remember(songs) {
        val seen = HashMap<String, Int>()
        songs.map { s -> val n = seen.merge(s.id, 1, Int::plus)!!; if (n == 1) s.id else "${s.id}#$n" }
    }

    // Long-press a song to start selecting; tap to add or remove songs from the selection.
    // Held by key so it survives the list refreshing; songs that disappear drop out of it.
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(keys) {
        val sel = selection ?: return@LaunchedEffect
        val present = keys.toHashSet()
        selection = sel.filterTo(HashSet()) { it in present }.takeIf { it.isNotEmpty() }
    }
    val usage = c.usage
    androidx.activity.compose.BackHandler(enabled = selection != null) { selection = null }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The slim top bar fades in over the second half of the header's scroll instead of popping in.
    var headerHeight by remember { mutableIntStateOf(0) }
    val barAlpha by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                headerHeight == 0 -> 0f
                else -> ((listState.firstVisibleItemScrollOffset - headerHeight * 0.45f) / (headerHeight * 0.4f)).coerceIn(0f, 1f)
            }
        }
    }
    val showBar by remember { derivedStateOf { barAlpha > 0f } }
    val wide = isWideWindow()
    val subtitleLine = listOfNotNull(subtitle.takeIf { it.isNotBlank() }, plural(songs.size, "song"), formatLongDuration(totalSec).takeIf { totalSec > 0 })
        .joinToString(" • ")
    val art: @Composable (Dp) -> Unit = { size ->
        if (headerIcon != null) headerIcon(size) else CoverArt(cover, size, requestSize = 600, fallback = title)
    }
    val buttons: @Composable () -> Unit = {
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
                            pin != null -> {
                                usage.track(im.flume.hearth.data.Usage.DOWNLOAD)
                                c.downloads.pinAndDownload(pin.kind, pin.id, songs)
                                // Keeping an album offline means you want it; removing the download doesn't unsave it.
                                if (saved == false) onSavedChange(true)
                            }
                            allDownloaded -> actions.removeDownload(songs.map { it.id })
                            else -> actions.download(songs)
                        }
                    }
                    if (saved != null) {
                        IconButton(onClick = { onSavedChange(!saved) }) {
                            Icon(
                                if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                if (saved) "Remove from Your Library" else "Save to Your Library",
                                tint = if (saved) accent else TextSecondary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                },
            )
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { headerHeight = it.height }
                    .background(Brush.verticalGradient(listOf(headerColor, Color.Transparent)))
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BackButton()
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.statusBarsPadding()) { headerActions() }
                    }
                    if (wide) {
                        // Landscape: art on the left, title and buttons beside it, so the list stays in view.
                        Row(Modifier.fillMaxWidth().padding(start = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                            art(160.dp)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = Dimens.Gutter))
                                Text(
                                    subtitleLine, color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 4.dp),
                                )
                                buttons()
                            }
                        }
                    } else {
                        art(220.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                        Text(
                            subtitleLine,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        if (!wide) item { buttons() }
        topContent()
        if (emptyState != null) item(key = "empty") { emptyState() }
        val multiDisc = showTrackNumbers && songs.any { it.disc != songs[0].disc }
        itemsIndexed(songs, key = { i, _ -> keys[i] }) { index, song ->
            val key = keys[index]
            Column(Modifier.animateItem()) {
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
                        if (sel != null) selection = (if (key in sel) sel - key else sel + key).takeIf { it.isNotEmpty() }
                        else actions.play(source, startSongId = song.id)
                    },
                    onRemoveFromPlaylist = onRemoveSong?.let { remove -> { remove(index) } },
                    showCover = !showTrackNumbers,
                    leading = if (showTrackNumbers) song.track.takeIf { it > 0 }?.toString() ?: "–" else null,
                    selected = selection?.let { key in it },
                    onLongPress = {
                        if (selection == null) usage.track(im.flume.hearth.data.Usage.MULTI_SELECT)
                        selection = (selection ?: emptySet()) + key
                    },
                )
            }
        }
        extraContent()
    }

    val sel = selection
    if (sel != null) {
        val picked = songs.filterIndexed { i, _ -> keys.getOrNull(i) in sel }
        SelectionBar(
            count = picked.size,
            onClose = { selection = null },
            onPlayNext = { actions.playNext(picked.map { it.id }); selection = null },
            onQueue = { actions.addToQueue(picked.map { it.id }); selection = null },
            onPlaylist = { actions.addToPlaylist(picked.map { it.id }); selection = null },
            onDownload = { actions.download(picked); selection = null },
            onSelectAll = { selection = keys.toSet() },
        )
    } else {
        // Slim bar with the title and a play button, fading in as the big header scrolls away.
        if (showBar) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = barAlpha }
                    .background(lerp(headerColor, Background, 0.35f))
                    .statusBarsPadding()
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton()
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                FilledIconButton(
                    onClick = { actions.play(source) },
                    // 40dp circle inside a 48dp touch target.
                    modifier = Modifier.minimumInteractiveComponentSize().size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
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
        IconButton(onClick = onPlayNext) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Play next") }
        IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") }
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
        saved = a?.starred,
        onSavedChange = { actions.setAlbumSaved(id, it) },
        extraContent = {
            item { AlbumDetails(a, id, songs.size, songs.sumOf { it.durationSec.toLong() }) }
            a?.artistId?.let { artistId ->
                item {
                    TextButton(onClick = { actions.openArtist(artistId) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("More by ${a.artist}")
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
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        mix = runCatching { c.mixes.today(online).getOrNull(index) }.getOrNull()
        songs = mix?.let { c.library.songsByIds(it.songIds) }.orEmpty()
        loaded = true
    }
    if (!loaded) return LoadingPage()
    // A stale link (e.g. today's mixes were rebuilt with fewer entries).
    val m = mix ?: return UnavailablePage(Icons.Default.Radio, "This mix isn't available any more")
    CollectionScreen(
        title = m.title,
        subtitle = m.subtitle,
        cover = m.covers.firstOrNull(),
        songs = songs,
        source = PlaySource(PlaySource.Kind.SONGS, label = "${m.title} · ${m.subtitle}", songIds = m.songIds),
        headerIcon = { size -> MixCover(m, size) },
    )
}

@Composable
fun PlaylistScreen(id: String) {
    val dao = LocalContext.current.container.db.library()
    val playlist by remember(id) { dao.playlist(id) }.collectAsStateWithLifecycle(null)
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val me = c.session.credentials.collectAsStateWithLifecycle().value?.username
    val loadedSongs by remember(id) { dao.playlistSongs(id) }.collectAsStateWithLifecycle(null)
    val songs = loadedSongs.orEmpty()
    val screenScope = androidx.compose.runtime.rememberCoroutineScope()
    CollectionScreen(
        title = playlist?.name.orEmpty(),
        // Only name the owner when it's someone else's playlist.
        subtitle = playlist?.owner?.takeIf { !it.equals(me, ignoreCase = true) }.orEmpty(),
        onRemoveSong = if (playlist?.owner == null || playlist?.owner.equals(me, ignoreCase = true)) { index ->
            val before = songs.map { it.id }
            c.appScope.launch {
                runCatching { c.library.removeFromPlaylist(id, index) }
                    .onSuccess { actions.showUndo("Removed from playlist") { c.library.setPlaylistSongs(id, before) } }
                    .onFailure { actions.showMessage("Couldn't remove the song (offline?)") }
            }
        } else null,
        cover = playlist?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.PLAYLIST, id, playlist?.name.orEmpty()),
        pin = PinTarget(DownloadRepository.KIND_PLAYLIST, id),
        emptyState = if (loadedSongs?.isEmpty() == true) {
            { EmptyState(Icons.AutoMirrored.Filled.QueueMusic, "This playlist is empty. Add songs from any song's menu.") }
        } else null,
        headerActions = {
            if (playlist != null && (playlist?.owner == null || playlist?.owner.equals(me, ignoreCase = true))) {
                PlaylistMenu(id, playlist!!.name, playlist?.coverArt, screenScope)
            }
        },
    )
}

@Composable
private fun PlaylistMenu(id: String, name: String, coverArt: String?, screenScope: kotlinx.coroutines.CoroutineScope) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var newName by remember(name) { mutableStateOf(name) }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Playlist options") }
    ActionSheet(open, { open = false }, title = name, subtitle = "Playlist", coverArt = coverArt, fallback = name) {
        MenuItem("Rename", Icons.Default.Edit) { open = false; renaming = true }
        MenuItem("Edit order", Icons.Default.SwapVert) { open = false; actions.open("playlist-edit/$id") }
        MenuItem("Delete playlist", Icons.Default.Delete, tint = Destructive, tintText = true) { open = false; deleting = true }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    renaming = false
                    c.appScope.launch {
                        runCatching { c.library.renamePlaylist(id, newName.trim()) }
                            .onFailure { actions.showMessage("Couldn't rename the playlist (offline?)") }
                    }
                }) { Text("Save") }
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
                    // The request runs in the app scope so leaving the screen can't cut it short; going
                    // back is tied to the screen (this menu vanishes once the playlist row is deleted).
                    screenScope.launch {
                        val ok = c.appScope.async {
                            runCatching { c.library.deletePlaylist(id) }
                                .onSuccess { c.downloads.unpinAndRemove(DownloadRepository.KIND_PLAYLIST, id, emptyList()) }
                                .isSuccess
                        }.await()
                        if (ok) back?.onBackPressed() else actions.showMessage("Couldn't delete the playlist (offline?)")
                    }
                }) { Text("Delete", color = Destructive) }
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
    val actions = LocalActions.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf<List<Pair<Int, SongEntity>>>(emptyList()) }
    LaunchedEffect(original) { if (order.isEmpty()) order = original.mapIndexed { i, s -> i to s } }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Edit order") {
            // Stay on the screen until Navidrome has the new order, so a failed save doesn't lose the edit.
            TextButton(enabled = !saving, onClick = {
                saving = true
                val ids = order.map { it.second.id }
                scope.launch {
                    val ok = c.appScope.async { runCatching { c.library.setPlaylistSongs(id, ids) }.isSuccess }.await()
                    saving = false
                    if (ok) back?.onBackPressed() else actions.showMessage("Couldn't save the new order (offline?)")
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(order, key = { it.first }) { (key, song) ->
                ReorderableItem(reorder, key = key) { dragging ->
                    MediaRow(
                        song.title, song.artist, onClick = null,
                        modifier = Modifier.background(if (dragging) SurfaceHigh else Background),
                        coverArt = song.coverArt, fallback = song.album,
                    ) {
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

    val wide = isWideWindow()
    val artistCover = artist?.coverArt ?: albums.firstOrNull()?.coverArt
    val counts = "${plural(albums.size, "album")} • ${plural(songs.size, "song")}"
    val buttons: @Composable () -> Unit = {
        PlayShuffleButtons(
            onPlay = { actions.play(source) },
            onShuffle = { actions.play(source, shuffle = true) },
            extra = {
                artist?.let { ar -> FollowButton(ar.starred) { actions.setArtistFollowed(id, !ar.starred) } }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { actions.startArtistRadio(id, name) }) {
                    Icon(Icons.Default.Radio, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Radio")
                }
            },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            val artistColor = rememberCoverColor(artistCover)
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(artistColor, Color.Transparent)))) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) { BackButton() }
                    if (wide) {
                        Row(Modifier.fillMaxWidth().padding(start = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                            CoverArt(artistCover, 160.dp, Modifier.clip(CircleShape), requestSize = 600, fallback = name)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = Dimens.Gutter))
                                Text(counts, color = TextSecondary, modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 4.dp))
                                buttons()
                            }
                        }
                    } else {
                        CoverArt(artistCover, 180.dp, Modifier.clip(CircleShape), requestSize = 600)
                        Spacer(Modifier.height(16.dp))
                        Text(name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text(counts, color = TextSecondary, modifier = Modifier.padding(4.dp))
                    }
                }
            }
        }
        if (!wide) item { buttons() }
        if (songs.isNotEmpty()) {
            item { SectionHeader("Popular") }
            items(songs.take(5), key = { "top:${it.id}" }) { s ->
                SongRow(s, onClick = { actions.play(PlaySource(PlaySource.Kind.SONGS, label = name, songIds = songs.map { it.id }), startSongId = s.id) })
            }
        }
        if (albums.isNotEmpty()) {
            item {
                SectionHeader("Albums")
                LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(Dimens.CarouselSpacing)) {
                    items(albums, key = { it.id }) { al ->
                        MediaCard(al.name, al.year?.toString().orEmpty(), onClick = { actions.openAlbum(al.id) }, coverArt = al.coverArt)
                    }
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

/** Outlined pill: "Follow" adds the artist to Your Library, "Following" when they're already there. */
@Composable
private fun FollowButton(following: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, if (following) accent else TextSecondary),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = Modifier.height(32.dp),
    ) {
        Text(if (following) "Following" else "Follow", color = if (following) accent else Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GenreScreen(name: String) {
    val dao = LocalContext.current.container.db.library()
    val loaded by remember(name) { dao.genreSongs(name) }.collectAsStateWithLifecycle(null)
    val songs = loaded.orEmpty()
    CollectionScreen(
        title = name,
        subtitle = "Genre",
        cover = songs.firstOrNull()?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.GENRE, name, name),
        emptyState = if (loaded?.isEmpty() == true) {
            { EmptyState(Icons.Default.MusicNote, "No songs in this genre any more") }
        } else null,
    )
}

@Composable
fun LikedScreen() {
    val dao = LocalContext.current.container.db.library()
    val loaded by remember { dao.starredSongs() }.collectAsStateWithLifecycle(null)
    CollectionScreen(
        title = "Liked Songs",
        subtitle = "",
        cover = null,
        songs = loaded.orEmpty(),
        source = PlaySource.Liked,
        pin = PinTarget(DownloadRepository.KIND_LIKED, "liked"),
        headerIcon = { size -> BigIcon(Icons.Default.Favorite, LikedColor, size) },
        emptyState = if (loaded?.isEmpty() == true) {
            { EmptyState(Icons.Default.FavoriteBorder, "Songs you like show up here. Tap the heart on any song to add it.") }
        } else null,
    )
}

@Composable
fun DownloadsScreen() {
    val c = LocalContext.current.container
    val songs by remember { c.db.library().downloadedSongs() }.collectAsStateWithLifecycle(emptyList())
    val pending by remember { c.db.library().pendingDownloadSongs() }.collectAsStateWithLifecycle(emptyList())
    val bytes by remember { c.downloads.totalBytes }.collectAsStateWithLifecycle(0L)
    // Download progress is deliberately not read here: it ticks every 256 KB, and reading it at this
    // level would recompose the whole screen each time. Each queued row collects its own.
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val onWifi by c.network.wifi.collectAsStateWithLifecycle()
    val states = LocalRowContext.current.downloads
    val (queued, failed) = remember(pending, states) { pending.partition { states[it.id] != DownloadState.FAILED } }
    val userPaused = settings.downloadsPaused
    val waitingForWifi = queued.isNotEmpty() && settings.wifiOnlyDownloads && !onWifi
    val lastError by c.downloads.lastError.collectAsStateWithLifecycle()
    val backingOff by c.downloads.backingOff.collectAsStateWithLifecycle()
    val storageFull by c.downloads.storageFull.collectAsStateWithLifecycle()
    var confirmRemoveAll by remember { mutableStateOf(false) }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("Remove all downloads?") },
            text = { Text("Everything downloaded to this phone will be deleted. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = { confirmRemoveAll = false; c.downloads.removeAll() }) {
                    Text("Remove", color = Destructive)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } },
        )
    }

    CollectionScreen(
        title = "Downloads",
        subtitle = formatBytes(bytes),
        cover = null,
        songs = songs,
        source = PlaySource.Downloads,
        headerIcon = { size -> BigIcon(Icons.Default.DownloadDone, DownloadsColor, size) },
        topContent = {
            if (queued.isNotEmpty()) {
                item(key = "q-head") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                userPaused -> "Paused"
                                storageFull -> "Stopped"
                                else -> "Downloading"
                            } + " · ${queued.size} left",
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        )
                        if (userPaused) {
                            TextButton(onClick = { c.downloads.resume() }) { Text("Resume") }
                        } else if (!storageFull) {
                            TextButton(onClick = { c.downloads.pause() }) { Text("Pause") }
                        }
                        TextButton(onClick = { c.downloads.cancelPending() }) { Text("Cancel all", color = TextSecondary) }
                    }
                    when {
                        userPaused -> {}
                        storageFull -> Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Phone storage is full. Free up some space, then try again.",
                                color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { c.downloads.retryFailed() }) { Text("Try again") }
                        }
                        waitingForWifi -> Text(
                            "Waiting for Wi-Fi. Turn off \"Download on Wi-Fi only\" in Settings → Storage to use mobile data.",
                            color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        backingOff -> Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                lastError ?: "Server busy, trying again shortly",
                                color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { c.downloads.retryFailed() }) { Text("Retry now") }
                        }
                    }
                }
                items(queued.take(50), key = { "q:${it.id}" }) { song -> QueuedSongRow(song, Modifier.animateItem()) }
                if (queued.size > 50) {
                    item(key = "q-more") { Text("+ ${queued.size - 50} more waiting", color = TextSecondary, modifier = Modifier.padding(16.dp)) }
                }
            }
            if (failed.isNotEmpty()) {
                item(key = "f-head") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${failed.size} couldn't download", style = MaterialTheme.typography.titleMedium)
                            // Storage and backoff messages are already shown above.
                            if (!storageFull && !backingOff) {
                                lastError?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        if (!storageFull) TextButton(onClick = { c.downloads.retryFailed() }) { Text("Retry") }
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
                    TextButton(onClick = { confirmRemoveAll = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Remove all downloads", color = Destructive)
                    }
                }
            } else if (pending.isEmpty()) {
                item {
                    EmptyState(Icons.Outlined.ArrowCircleDown, "Nothing downloaded yet. Tap the download icon on an album or playlist to keep it on your phone.")
                }
            }
        },
    )
}

/** A song waiting to download, with a progress bar while it's on its way. Collects only its own progress. */
@Composable
private fun QueuedSongRow(song: SongEntity, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    // null when not downloading; 0 when the size isn't known yet.
    val progress by remember(song.id) {
        c.downloads.progress.map { if (song.id in it) it[song.id] ?: 0f else null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)
    Column(modifier) {
        SongRow(song, onClick = {})
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier.fillMaxWidth().padding(start = Dimens.Gutter + Dimens.RowCover + Dimens.CoverGap, end = Dimens.Gutter).height(3.dp),
                trackColor = TextSecondary.copy(alpha = 0.25f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun BigIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, size: Dp = 220.dp) {
    Box(Modifier.size(size * 0.82f).clip(HearthShapes.Cover).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(72.dp), tint = Color.White)
    }
}
