// The shared page for any list of songs (album, playlist, genre, mix, Liked Songs): its state and layout.
package im.flume.hearth.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.network.tidalImageUrl
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.domain.music.PlaySource
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.data.SongFilter
import im.flume.hearth.domain.music.SongItem
import im.flume.hearth.domain.music.Usage
import im.flume.hearth.domain.music.download.DownloadRepository
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.LocalRowContext
import im.flume.hearth.core.ui.components.SongRow
import im.flume.hearth.core.ui.components.formatLongDuration
import im.flume.hearth.core.ui.components.plural
import im.flume.hearth.core.ui.components.rememberCoverColor
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.ui.screens.isWideWindow

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
        StopDownloadingDialog(title, onDismiss = { confirmCancel = false }) {
            confirmCancel = false
            if (pin != null) c.downloads.unpinAndRemove(pin.kind, pin.id, songs.map { it.id })
            else actions.removeDownload(songs.map { it.id })
        }
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
        RemoveDownloadsDialog(plan, onDismiss = { confirmRemove = false }) {
            confirmRemove = false
            if (pin != null) c.downloads.unpinAndRemove(pin.kind, pin.id, songs.map { it.id })
            else plan?.let { actions.removeDownload(it.songIds) }
        }
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
    BackHandler(enabled = selection != null) { selection = null }
    val listState = rememberLazyListState()
    // Search within the list: hidden until you pull down at the top, like other music apps.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val pullToSearch = remember(searchable) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Left over downward drag means the list is already at the top and you're pulling further.
                if (searchable && !searchOpen && available.y > 12f && source == NestedScrollSource.UserInput) searchOpen = true
                return Offset.Zero
            }
        }
    }
    val filtered = remember(songs, query, searchable) {
        if (!searchable || query.isBlank()) null else songs.indices.filter { SongFilter.matches(songs[it], query) }
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
    val buttons: @Composable () -> Unit = {
        CollectionButtons(
            onPlay = onPlay,
            onShuffle = { actions.play(source, shuffle = true) },
            remote = remote,
            downloaded = allDownloaded || (isPinned && !anyDownloading),
            downloadProgress = if (anyDownloading) doneCount.toFloat() / songs.size.coerceAtLeast(1) else null,
            onDownloadToggle = {
                when {
                    anyDownloading -> confirmCancel = true
                    pin != null && (isPinned || allDownloaded) -> confirmRemove = true
                    pin != null -> {
                        usage.track(Usage.DOWNLOAD)
                        c.downloads.pinAndDownload(pin.kind, pin.id, songs)
                        onDownloaded()
                        // Keeping an album offline means you want it; removing the download doesn't unsave it.
                        if (saved == false) onSavedChange(true)
                    }
                    allDownloaded -> confirmRemove = true
                    else -> actions.download(songs)
                }
            },
            saved = saved,
            onSavedChange = onSavedChange,
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().then(if (searchable) Modifier.nestedScroll(pullToSearch) else Modifier),
            state = listState, contentPadding = PaddingValues(bottom = HearthTheme.spacing.xl),
        ) {
            item {
                CollectionHeader(
                    title = title,
                    subtitleLine = subtitleLine,
                    cover = cover,
                    headerColor = headerColor,
                    headerIcon = headerIcon,
                    remote = remote,
                    wide = wide,
                    searchable = searchable,
                    searchOpen = searchOpen,
                    query = query,
                    onQueryChange = { query = it },
                    onOpenSearch = { searchOpen = true },
                    onCloseSearch = { query = ""; searchOpen = false },
                    searchFocus = searchFocus,
                    onHeightChanged = { headerHeight = it },
                    headerActions = headerActions,
                    buttons = buttons,
                )
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
                        if (selection == null) {
                            usage.track(Usage.MULTI_SELECT)
                            // Long-press opens the song menu on other pages, so explain the first time it selects here.
                            if (!c.session.selectHintShown) {
                                c.session.selectHintShown = true
                                actions.showMessage("Selecting songs: tap more to add them, or use the bar below")
                            }
                        }
                        selection = (selection ?: emptySet()) + key
                    },
                )
            }
            collectionSongs(songs, keys, tracks, filtered, query, showTrackNumbers, songRow)
            extraContent()
        }

        val sel = selection
        if (sel != null) {
            SongSelectionBar(songs, keys, sel, onSelectionChange = { selection = it })
        } else {
            // Slim bar with the title and a play button, fading in as the big header scrolls away.
            if (showBar) CollectionTopBar(title, headerColor, alpha = { barAlpha }, onPlay = onPlay)
        }
    }
}
