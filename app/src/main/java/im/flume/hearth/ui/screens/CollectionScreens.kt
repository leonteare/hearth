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
import im.flume.hearth.ui.components.DownloadsIndicator
import im.flume.hearth.data.PlaylistRules
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.automirrored.filled.ExitToApp
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import im.flume.hearth.data.SongOrder
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.SongItem
import im.flume.hearth.data.RequestEntity
import im.flume.hearth.api.tidalImageUrl
import im.flume.hearth.ui.components.AvailabilityIcon
import im.flume.hearth.ui.components.MissingSongRow
import im.flume.hearth.ui.components.RemoteCover
import im.flume.hearth.ui.components.formatDuration
import im.flume.hearth.ui.theme.NotOnServerAlpha
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

/** Header of a page for music that isn't on the server: its artwork, a status line, and what Play does (a preview). */
data class RemoteHeader(
    val coverUuid: String?,
    /** Replaces the "N songs • duration" line. */
    val info: String,
    /** "Not on your server yet", "Coming to your server…". */
    val status: String,
    val request: RequestEntity?,
    val onPlay: () -> Unit,
    /** True while it's on its way and will be downloaded to this phone. */
    val toPhone: Boolean = false,
    /** The download toggle: get it onto this phone (or take that back); null hides it. */
    val onDownload: (() -> Unit)? = null,
)

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
    /**
     * The list to show when it includes songs that aren't on the server (in album order); null shows
     * [songs]. Owned items must be in [songs].
     */
    tracks: List<SongItem>? = null,
    /** Music not on the server: artwork from the remote image server, a dimmed header and this status line. */
    remote: RemoteHeader? = null,
    /** Called when the download toggle downloads the collection (not when it removes it). */
    onDownloaded: () -> Unit = {},
    /** Pulling down at the top reveals a search bar that filters the songs (playlists, Liked Songs). */
    searchable: Boolean = false,
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
    val headerColor = rememberCoverColor(cover, imageUrl = remote?.let { tidalImageUrl(it.coverUuid, 160) })
    val onPlay: () -> Unit = remote?.onPlay ?: { actions.play(source) }
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
    // Removing a finished download always asks first, saying how much goes.
    var confirmRemove by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<DownloadRepository.RemovalPlan?>(null) }
    LaunchedEffect(confirmRemove) {
        removal = null
        if (confirmRemove) removal = c.downloads.removalPlan(pin?.kind, pin?.id, songs.map { it.id })
    }
    if (confirmRemove) {
        val plan = removal
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove downloads?") },
            text = {
                Text(
                    when {
                        plan == null -> "Checking…"
                        plan.songIds.isEmpty() -> "Nothing will be deleted: your other downloads or Liked Songs need all of these songs."
                        else -> "${plural(plan.songIds.size, "song")}, ${formatBytes(plan.bytes)} will be removed from this phone. " +
                            "Songs other downloads or Liked Songs need are kept."
                    }
                )
            },
            confirmButton = {
                TextButton(enabled = plan != null, onClick = {
                    confirmRemove = false
                    if (pin != null) c.downloads.unpinAndRemove(pin.kind, pin.id, songs.map { it.id })
                    else plan?.let { actions.removeDownload(it.songIds) }
                }) { Text("Remove", color = Destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
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
    // Search within the list: hidden until you pull down at the top, like other music apps.
    var searchOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val pullToSearch = remember(searchable) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                // Left over downward drag means the list is already at the top and you're pulling further.
                if (searchable && !searchOpen && available.y > 12f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) searchOpen = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val filtered = remember(songs, query, searchable) {
        if (!searchable || query.isBlank()) null else songs.indices.filter { im.flume.hearth.data.SongFilter.matches(songs[it], query) }
    }
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
    val subtitleLine = remote?.info ?: listOfNotNull(subtitle.takeIf { it.isNotBlank() }, plural(songs.size, "song"), formatLongDuration(totalSec).takeIf { totalSec > 0 })
        .joinToString(" • ")
    val titleColor = if (remote != null) Color.White.copy(alpha = NotOnServerAlpha) else Color.Unspecified
    val art: @Composable (Dp) -> Unit = { size ->
        when {
            remote != null -> Box(Modifier.size(size).clip(HearthShapes.Cover)) { RemoteCover(remote.coverUuid, title, 640) }
            headerIcon != null -> headerIcon(size)
            else -> CoverArt(cover, size, requestSize = 600, fallback = title)
        }
    }
    val statusLine: @Composable (TextAlign) -> Unit = { align ->
        remote?.let { r ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (align == TextAlign.Center) Arrangement.Center else Arrangement.Start,
            ) {
                AvailabilityIcon(r.request)
                Spacer(Modifier.width(4.dp))
                Text(r.status, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    val buttons: @Composable () -> Unit = {
            PlayShuffleButtons(
                onPlay = onPlay,
                onShuffle = if (remote != null) null else ({ actions.play(source, shuffle = true) }),
                playLabel = if (remote != null) "Play preview" else "Play",
                extra = {
                    if (remote == null) DownloadToggle(
                        downloaded = allDownloaded || (isPinned && !anyDownloading),
                        progress = if (anyDownloading) doneCount.toFloat() / songs.size.coerceAtLeast(1) else null,
                    ) {
                        when {
                            anyDownloading -> confirmCancel = true
                            pin != null && (isPinned || allDownloaded) -> confirmRemove = true
                            pin != null -> {
                                usage.track(im.flume.hearth.data.Usage.DOWNLOAD)
                                c.downloads.pinAndDownload(pin.kind, pin.id, songs)
                                onDownloaded()
                                // Keeping an album offline means you want it; removing the download doesn't unsave it.
                                if (saved == false) onSavedChange(true)
                            }
                            allDownloaded -> confirmRemove = true
                            else -> actions.download(songs)
                        }
                    }
                    remote?.onDownload?.let { onDownload -> DownloadToggle(downloaded = remote.toPhone, progress = null, onClick = onDownload) }
                    if (saved != null) {
                        IconButton(onClick = { onSavedChange(!saved) }) {
                            Icon(
                                if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                when {
                                    remote != null && saved -> "Don't save it when it arrives"
                                    remote != null -> "Save this album"
                                    saved -> "Remove from Your Library"
                                    else -> "Save to Your Library"
                                },
                                tint = if (saved) accent else TextSecondary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                },
            )
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().then(if (searchable) Modifier.nestedScroll(pullToSearch) else Modifier),
        state = listState, contentPadding = PaddingValues(bottom = 24.dp),
    ) {
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
                        Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                            DownloadsIndicator()
                            // Same search bar the pull-down opens, for anyone who doesn't know the gesture.
                            if (searchable && !searchOpen) {
                                IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, "Find in ${if (title.isBlank()) "this list" else title}") }
                            }
                            headerActions()
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(searchOpen) {
                        LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("Find in ${if (title.isBlank()) "this list" else title}") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                IconButton(onClick = { query = ""; searchOpen = false }) { Icon(Icons.Default.Close, "Close search") }
                            },
                            singleLine = true,
                            shape = HearthShapes.Card,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 8.dp).focusRequester(searchFocus),
                        )
                    }
                    if (wide) {
                        // Landscape: art on the left, title and buttons beside it, so the list stays in view.
                        Row(Modifier.fillMaxWidth().padding(start = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                            art(160.dp)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(title, style = MaterialTheme.typography.headlineMedium, color = titleColor, modifier = Modifier.padding(horizontal = Dimens.Gutter))
                                Text(
                                    subtitleLine, color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 4.dp),
                                )
                                statusLine(TextAlign.Start)
                                buttons()
                            }
                        }
                    } else {
                        art(220.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(title, style = MaterialTheme.typography.headlineMedium, color = titleColor, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                        Text(
                            subtitleLine,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        statusLine(TextAlign.Center)
                    }
                }
            }
        }
        if (!wide) item { buttons() }
        topContent()
        if (emptyState != null) item(key = "empty") { emptyState() }
        val songRow: @Composable (Int, SongEntity) -> Unit = { index, song ->
            val key = keys[index]
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
        val discHeader: @Composable (Int, Boolean) -> Unit = { disc, first ->
            Text(
                "Disc $disc",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                modifier = Modifier.padding(start = 16.dp, top = if (first) 4.dp else 16.dp, bottom = 4.dp),
            )
        }
        if (filtered != null) {
            // Searching: just the matching songs (tapping one still plays the whole list from it).
            if (filtered.isEmpty()) item(key = "no-match") {
                Text("No songs match \"$query\"", color = TextSecondary, modifier = Modifier.padding(Dimens.Gutter))
            }
            items(filtered, key = { keys[it] }) { index -> Column(Modifier.animateItem()) { songRow(index, songs[index]) } }
        } else if (tracks == null) {
            val multiDisc = showTrackNumbers && songs.any { it.disc != songs[0].disc }
            itemsIndexed(songs, key = { i, _ -> keys[i] }) { index, song ->
                Column(Modifier.animateItem()) {
                    if (multiDisc && (index == 0 || songs[index - 1].disc != song.disc)) discHeader(song.disc, index == 0)
                    songRow(index, song)
                }
            }
        } else {
            // Songs the server doesn't have sit among the ones it does, dimmed, in album order.
            fun disc(item: SongItem) = when (item) {
                is SongItem.Owned -> item.song.disc
                is SongItem.Missing -> item.track.volumeNumber ?: 1
            }
            val multiDisc = showTrackNumbers && tracks.any { disc(it) != disc(tracks[0]) }
            val indexById = songs.withIndex().associate { it.value.id to it.index }
            itemsIndexed(tracks, key = { _, item -> item.key }) { index, item ->
                Column(Modifier.animateItem()) {
                    if (multiDisc && (index == 0 || disc(tracks[index - 1]) != disc(item))) discHeader(disc(item), index == 0)
                    when (item) {
                        is SongItem.Owned -> indexById[item.song.id]?.let { songRow(it, item.song) }
                        is SongItem.Missing -> MissingSongRow(
                            item.track,
                            leading = if (showTrackNumbers) item.track.trackNumber?.toString() ?: "–" else null,
                            showCover = !showTrackNumbers,
                            subtitle = listOfNotNull(
                                item.track.artist.ifBlank { null },
                                item.track.durationSec?.takeIf { it > 0 }?.let { formatDuration(it.toLong()) },
                            ).joinToString(" · "),
                        )
                    }
                }
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
                DownloadsIndicator()
                FilledIconButton(

                    onClick = onPlay,
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

/**
 * A mix built on this phone, found by its title ("Artist Mix", one per artist) rather than its place
 * in the list: the list can be rebuilt differently (e.g. offline), and the page must stay the mix you tapped.
 */
@Composable
fun MixScreen(key: String) {
    val c = LocalContext.current.container
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    var mix by remember { mutableStateOf<im.flume.hearth.data.Mix?>(null) }
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        mix = runCatching { c.mixes.today(online).firstOrNull { it.title == key } }.getOrNull()
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
    val c = LocalContext.current.container
    val dao = c.db.library()
    val item by remember(id) { c.library.playlistItem(id) }.collectAsStateWithLifecycle(null)
    val playlist = item?.playlist
    val access = item?.access
    val actions = LocalActions.current
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    // Entries keep their server positions, including songs this phone hasn't synced yet.
    val entries by remember(id) { dao.playlistEntries(id) }.collectAsStateWithLifecycle(null)
    val shown = remember(entries) { entries.orEmpty().withIndex().filter { it.value.song != null } }
    val songs = remember(shown) { shown.map { it.value.song!! } }
    val loadedSongs = entries?.let { songs }
    val screenScope = androidx.compose.runtime.rememberCoroutineScope()
    // Shared playlists change from the other phone; re-read on open so those edits show up right away.
    // Also re-read when an entry's song isn't known here yet, which brings its details in.
    val shared = access != null && (access.isShared || !access.isOwner)
    val unknownSongs = entries?.any { it.song == null } == true
    LaunchedEffect(id, shared, online, unknownSongs) {
        if ((shared || unknownSongs) && online) runCatching { c.library.refreshPlaylist(id) }
    }
    CollectionScreen(
        searchable = true,
        title = playlist?.name.orEmpty(),
        // Only name the owner when it's someone else's playlist. A server mix says when it leaves instead.
        subtitle = when {
            playlist != null && im.flume.hearth.data.ServerMixes.isMix(playlist!!) -> im.flume.hearth.data.ServerMixes.description(playlist!!)
            access == null || access.isOwner -> ""
            else -> "by ${PlaylistRules.displayName(access.owner)}"
        },
        onRemoveSong = if (access?.canEdit == true) { index ->
            // Identify the song by id and occurrence, not list position: the server re-finds it.
            val all = entries.orEmpty().map { it.songId }
            shown.getOrNull(index)?.let { (at, entry) ->
                val occurrence = im.flume.hearth.data.PlaylistEdits.occurrenceAt(all, at)
                c.appScope.launch {
                    runCatching { c.library.removeFromPlaylist(id, entry.songId, occurrence) }
                        .onSuccess { removal ->
                            if (removal != null) actions.showUndo("Removed from playlist") {
                                runCatching { c.library.undoRemoval(id, removal) }
                                    .onFailure { actions.showMessage(c.library.editError(it, access.owner, "Couldn't put the song back (offline?)")) }
                            }
                        }
                        .onFailure { actions.showMessage(c.library.editError(it, access.owner, "Couldn't remove the song (offline?)")) }
                }
            }
        } else null,
        cover = playlist?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.PLAYLIST, id, playlist?.name.orEmpty()),
        pin = PinTarget(DownloadRepository.KIND_PLAYLIST, id),
        emptyState = if (loadedSongs?.isEmpty() == true) {
            { EmptyState(Icons.AutoMirrored.Filled.QueueMusic, "This playlist is empty. Add songs from any song's menu.") }
        } else null,
        topContent = {
            item?.takeIf { it.access.invite != null }?.let { pending -> item(key = "invite") { InviteBanner(pending) } }
        },
        headerActions = {
            val current = item
            // Always there, in the same spot as on album and show pages; what's inside depends on your access.
            if (current != null) PlaylistMenu(current, screenScope, songs.map { it.id })
        },
    )
}

/** The playlist's ⋯ menu. What's in it depends on whether you own it, can add to it, or only listen. */
@Composable
private fun PlaylistMenu(item: im.flume.hearth.data.PlaylistItem, screenScope: kotlinx.coroutines.CoroutineScope, songIds: List<String>) {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val actions = LocalActions.current
    val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val id = item.playlist.id
    val name = item.playlist.name
    val access = item.access
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // A photo action waiting for the password dialog to finish.
    var afterPassword by remember { mutableStateOf<(() -> Unit)?>(null) }
    var newName by remember(name) { mutableStateOf(name) }

    fun withPassword(action: () -> Unit) {
        if (c.session.vault.hasPassword) action() else afterPassword = action
    }
    fun photoChange(progress: String, done: String, change: suspend () -> Unit) {
        c.appScope.launch {
            actions.showMessage(progress)
            runCatching { change() }
                .onSuccess { actions.showMessage(done) }
                .onFailure { e ->
                    actions.showMessage(
                        if (e is im.flume.hearth.api.SubsonicException) c.library.editError(e, access.owner, "Couldn't change the photo (offline?)")
                        else photoError(e) { c.session.vault.clear(); c.nativeApi.forgetSession() }
                    )
                }
        }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) withPassword {
            photoChange("Uploading photo…", "Photo updated") {
                c.library.setPlaylistPhoto(id, im.flume.hearth.data.PhotoPrep.jpeg(context, uri))
            }
        }
    }

    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Playlist options") }
    ActionSheet(open, { open = false }, title = name, subtitle = "Playlist", coverArt = item.playlist.coverArt, fallback = name) {
        if (songIds.isNotEmpty()) {
            MenuItem("Play next", Icons.AutoMirrored.Filled.PlaylistPlay) { open = false; actions.playNext(songIds) }
            MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { open = false; actions.addToQueue(songIds) }
            MenuItem("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) { open = false; actions.addToPlaylist(songIds) }
        }
        if (access.canEdit) {

            MenuItem("Rename", Icons.Default.Edit) { open = false; renaming = true }
            MenuItem("Edit order", Icons.Default.SwapVert) { open = false; actions.open("playlist-edit/$id") }
            MenuItem("Change photo", Icons.Default.AddPhotoAlternate) {
                open = false
                picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            // Navidrome doesn't say whether the photo is custom; removing an automatic one is harmless.
            MenuItem("Remove photo", Icons.Default.HideImage) {
                open = false
                withPassword { photoChange("Removing photo…", "Photo removed") { c.library.removePlaylistPhoto(id) } }
            }
        }
        if (access.isOwner) {
            MenuItem("Share", Icons.Default.GroupAdd) { open = false; sharing = true }
            MenuItem("Delete playlist", Icons.Default.Delete, tint = Destructive, tintText = true) { open = false; deleting = true }
        }
        if (access.isMember) {
            MenuItem("Leave playlist", Icons.AutoMirrored.Filled.ExitToApp, tint = Destructive, tintText = true) { open = false; leaving = true }
        }
    }
    if (sharing) ShareSheet(item) { sharing = false }
    afterPassword?.let { action ->
        PasswordDialog(onDismiss = { afterPassword = null }, onSaved = { afterPassword = null; action() })
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
                            .onFailure { actions.showMessage(c.library.editError(it, access.owner, "Couldn't rename the playlist (offline?)")) }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave \"$name\"?") },
            text = { Text("It disappears from your library. ${PlaylistRules.displayName(access.owner)} can invite you again.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    screenScope.launch {
                        c.appScope.async { c.library.answerInvite(id, accept = false) }.await()
                        back?.onBackPressed()
                    }
                }) { Text("Leave", color = Destructive) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
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
                            // Read the songs first: deleting the playlist drops them, and its downloads would stay forever.
                            val songIds = c.db.library().playlistSongsOnce(id).map { it.id }
                            runCatching { c.library.deletePlaylist(id) }
                                .onSuccess { c.downloads.unpinAndRemove(DownloadRepository.KIND_PLAYLIST, id, songIds) }
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
    val owner = remember(id) { c.library.playlistItem(id) }.collectAsStateWithLifecycle(null).value?.access?.owner
    val actions = LocalActions.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    // The server's list when editing began; null until loaded. Save merges against it.
    var original by remember(id) { mutableStateOf<List<String>?>(null) }
    // (stable key, song id, song details or null when this phone doesn't know the song)
    var order by remember(id) { mutableStateOf<List<Triple<Int, String, SongEntity?>>>(emptyList()) }
    LaunchedEffect(id) {
        val ids = c.library.playlistSongIdsForEdit(id)
        val known = c.library.songsByIds(ids.distinct()).associateBy { it.id }
        order = ids.mapIndexed { i, s -> Triple(i, s, known[s]) }
        original = ids
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Edit order") {
            // Stay on the screen until Navidrome has the new order, so a failed save doesn't lose the edit.
            TextButton(enabled = !saving && original != null, onClick = {
                val start = original ?: return@TextButton
                saving = true
                val ids = order.map { it.second }
                scope.launch {
                    val error = c.appScope.async { runCatching { c.library.savePlaylistEdit(id, start, ids) }.exceptionOrNull() }.await()
                    saving = false
                    if (error == null) back?.onBackPressed()
                    else actions.showMessage(c.library.editError(error, owner, "Couldn't save the new order (offline?)"))
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(order, key = { it.first }) { (key, _, song) ->
                ReorderableItem(reorder, key = key) { dragging ->
                    MediaRow(
                        song?.title ?: "Song not synced yet", song?.artist.orEmpty(), onClick = null,
                        modifier = Modifier.background(if (dragging) SurfaceHigh else Background),
                        coverArt = song?.coverArt, fallback = song?.album.orEmpty(),
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
    // Title A–Z unless you pick another order; the choice is remembered like Your Library's sorts.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE) }
    var sort by remember { mutableStateOf(prefs.getInt("sort-LIKED", 0)) }
    val order = SongOrder.liked(sort)
    val sorted = remember(loaded, order) { loaded?.let { order.sort(it) }.orEmpty() }
    CollectionScreen(
        searchable = true,
        title = "Liked Songs",
        subtitle = "",
        cover = null,
        songs = sorted,
        source = PlaySource.Liked.copy(order = order),
        pin = PinTarget(DownloadRepository.KIND_LIKED, "liked"),
        headerIcon = { size -> BigIcon(Icons.Default.Favorite, LikedColor, size) },
        headerActions = {
            SortMenu(SongOrder.LIKED.map { it.second }, sort) {
                sort = it
                prefs.edit().putInt("sort-LIKED", it).apply()
            }
        },
        emptyState = if (loaded?.isEmpty() == true) {
            { EmptyState(Icons.Default.FavoriteBorder, "Songs you like show up here. Tap the heart on any song to add it.") }
        } else null,
    )
}

@Composable
private fun BigIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, size: Dp = 220.dp) {
    Box(Modifier.size(size * 0.82f).clip(HearthShapes.Cover).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(72.dp), tint = Color.White)
    }
}
