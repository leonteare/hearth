package im.flume.hearth.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.AppContainer
import im.flume.hearth.container
import im.flume.hearth.playback.SpokenNowPlaying
import im.flume.hearth.spoken.BookRow
import im.flume.hearth.spoken.ChapterMarks
import im.flume.hearth.spoken.ContinueItem
import im.flume.hearth.spoken.EpisodeRow
import im.flume.hearth.spoken.PodcastEntity
import im.flume.hearth.spoken.SpokenDownloadState
import im.flume.hearth.spoken.SpokenFormat
import im.flume.hearth.spoken.SpokenKey
import im.flume.hearth.spoken.SpokenLists
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.Actions
import im.flume.hearth.ui.components.BackButton
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.DownloadToggle
import im.flume.hearth.ui.components.EmptyState
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MediaCard
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.UnavailablePage
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.theme.Destructive
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Podcasts and audiobooks, woven into Home, Search and Your Library: shared rows and cards, the show
// page ("podcast/{id}") and the book page ("book/{id}").

/** The episode or book in the player (null while music plays), so rows can show what's playing. */
val LocalSpokenNow = compositionLocalOf<SpokenNowPlaying?> { null }

fun podcastRoute(id: String) = "podcast/${Uri.encode(id)}"
fun bookRoute(id: String) = "book/${Uri.encode(id)}"
/** The show page for a feed that may not be on the server yet (from Search). */
fun podcastFeedRoute(feedUrl: String) = "podcast-feed/${Uri.encode(feedUrl)}"

/** Cover of a podcast or book (by its id on the server), or null when signed out. */
fun AppContainer.spokenCover(libraryItemId: String, size: Int = 300): String? = spoken.coverUrl(libraryItemId, size)

/** Plays an episode, unless it can't be: offline and not downloaded. */
fun playEpisode(c: AppContainer, actions: Actions, libraryItemId: String, episodeId: String, downloaded: Boolean, startSec: Double? = null) {
    if (c.network.cannotStream && !downloaded) {
        actions.showMessage("You're offline and this episode isn't downloaded")
        return
    }
    c.player.playEpisode(libraryItemId, episodeId, startSec)
}

fun playBook(c: AppContainer, actions: Actions, libraryItemId: String, downloaded: Boolean, startSec: Double? = null) {
    if (c.network.cannotStream && !downloaded) {
        actions.showMessage("You're offline and this book isn't downloaded")
        return
    }
    c.player.playBook(libraryItemId, startSec)
}

/** A small icon for an episode's or book's download: ticked when it's on the phone. */
@Composable
fun SpokenDownloadIcon(state: SpokenDownloadState?, modifier: Modifier = Modifier) {
    when (state) {
        SpokenDownloadState.DONE -> Icon(Icons.Default.CheckCircle, "Downloaded", modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
        SpokenDownloadState.QUEUED, SpokenDownloadState.DOWNLOADING -> Icon(Icons.Default.Downloading, "Downloading", modifier.size(14.dp), tint = TextSecondary)
        SpokenDownloadState.FAILED -> Icon(Icons.Default.ErrorOutline, "Couldn't download", modifier.size(14.dp), tint = Destructive)
        else -> {}
    }
}

/** A thin progress bar for how far through an episode or book you are. */
@Composable
fun ListenedBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.height(3.dp).clip(CircleShape),
        color = color,
        trackColor = TextSecondary.copy(alpha = 0.25f),
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/**
 * One episode: title (two lines), "date · length / time left", how far you got and whether it's on the
 * phone. Tap plays it; ⋯ (or long-press) opens its menu. [playButton] puts a play button at the end
 * instead of ⋯ (Home's "New episodes").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeListRow(
    row: EpisodeRow,
    modifier: Modifier = Modifier,
    showCover: Boolean = false,
    withShow: Boolean = false,
    playButton: Boolean = false,
    showGoToShow: Boolean = withShow,
    /** Put before the usual line (Search's "Episode"), so it reads apart from songs and shows. */
    label: String? = null,
) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val haptics = LocalHapticFeedback.current
    val now = LocalSpokenNow.current
    var menu by remember { mutableStateOf(false) }
    val e = row.episode
    val isCurrent = now?.episodeId == e.id
    val downloaded = row.downloadState == SpokenDownloadState.DONE
    val playable = e.ino != null
    val accent = MaterialTheme.colorScheme.primary
    val play = { playEpisode(c, actions, e.libraryItemId, e.id, downloaded) }
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = playable,
                onClick = play,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            )
            .padding(start = Dimens.Gutter, end = 4.dp, top = Dimens.RowPaddingV, bottom = Dimens.RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            CoverArt(null, Dimens.RowCover, requestSize = 150, fallback = row.podcastTitle, url = c.spokenCover(e.libraryItemId, 150))
            Spacer(Modifier.width(Dimens.CoverGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                e.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isCurrent -> accent
                    row.finished || !playable -> TextSecondary
                    else -> Color.White
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.downloadState != null && row.downloadState != SpokenDownloadState.REMOVED) {
                    SpokenDownloadIcon(row.downloadState)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    listOfNotNull(label, SpokenFormat.episodeLine(row, withShow = withShow)).joinToString(" • "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                )
            }
            SpokenFormat.fraction(row.progressSec, e.durationSec, row.finished)?.let { f ->
                Spacer(Modifier.height(4.dp))
                ListenedBar(f, Modifier.width(96.dp))
            }
        }
        if (playButton) {
            IconButton(onClick = play, enabled = playable) {
                Icon(Icons.Default.PlayArrow, "Play ${e.title}", tint = Color.White)
            }
        } else {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
        }
    }
    EpisodeMenu(row, menu, onDismiss = { menu = false }, showGoToShow = showGoToShow)
}

/** ⋯ for an episode: play, download / remove, mark played / unplayed, go to the show. */
@Composable
fun EpisodeMenu(row: EpisodeRow, open: Boolean, onDismiss: () -> Unit, showGoToShow: Boolean) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val e = row.episode
    ActionSheet(open, onDismiss, title = e.title, subtitle = row.podcastTitle, fallback = row.podcastTitle, coverUrl = c.spokenCover(e.libraryItemId, 150)) {
        if (e.ino != null) {
            MenuItem(if (row.started) "Resume" else "Play", Icons.Default.PlayArrow) {
                onDismiss(); playEpisode(c, actions, e.libraryItemId, e.id, row.downloadState == SpokenDownloadState.DONE)
            }
            when (row.downloadState) {
                SpokenDownloadState.DONE -> MenuItem("Remove download", Icons.Default.CheckCircle) {
                    onDismiss(); c.spoken.downloads.remove(e.libraryItemId, e.id); actions.showMessage("Download removed")
                }
                SpokenDownloadState.QUEUED, SpokenDownloadState.DOWNLOADING -> MenuItem("Cancel download", Icons.Default.Close) {
                    onDismiss(); c.spoken.downloads.remove(e.libraryItemId, e.id)
                }
                SpokenDownloadState.FAILED -> MenuItem("Try downloading again", Icons.Default.Refresh) {
                    onDismiss(); c.spoken.downloads.retryFailed()
                }
                else -> MenuItem("Download", Icons.Outlined.ArrowCircleDown) {
                    onDismiss(); c.spoken.downloads.downloadEpisode(e.libraryItemId, e.id); actions.showMessage(downloadingMessage(c))
                }
            }
        }
        if (row.finished) {
            MenuItem("Mark as unplayed", Icons.Outlined.RadioButtonUnchecked) {
                onDismiss(); c.appScope.launch { c.spoken.markFinished(e.libraryItemId, e.id, false) }
            }
        } else {
            MenuItem("Mark as played", Icons.Outlined.CheckCircle) {
                onDismiss()
                c.appScope.launch { c.spoken.markFinished(e.libraryItemId, e.id, true) }
                actions.showUndo("Marked as played") { c.spoken.markFinished(e.libraryItemId, e.id, false) }
            }
        }
        if (showGoToShow) {
            MenuItem("Go to show", Icons.Default.Podcasts) { onDismiss(); actions.open(podcastRoute(e.libraryItemId)) }
        }
    }
}

internal fun downloadingMessage(c: AppContainer): String =
    if (c.session.settings.value.wifiOnlyDownloads && !c.network.wifi.value) "Downloading when you're on Wi-Fi" else "Downloading"

// ---------------------------------------------------------------------------------- Home

/** Home: started episodes and books, with how far you got; tap carries on, long-press opens the page. */
fun LazyListScope.continueListeningSection(items: List<ContinueItem>, c: AppContainer, actions: Actions) {
    if (items.isEmpty()) return
    item(key = "continue-listening") {
        SectionHeader("Continue listening")
        LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(Dimens.CarouselSpacing)) {
            items(items, key = { "${it.libraryItemId}/${it.episodeId.orEmpty()}" }) { item -> ContinueCard(item, c, actions) }
        }
    }
}

@Composable
private fun ContinueCard(item: ContinueItem, c: AppContainer, actions: Actions) {
    val states by remember { c.spoken.downloads.states }.collectAsStateWithLifecycle(emptyMap())
    val downloaded = states[SpokenKey(item.libraryItemId, item.episodeId.orEmpty())] == SpokenDownloadState.DONE
    val url = c.spokenCover(item.libraryItemId, 300)
    MediaCard(
        title = item.title,
        subtitle = listOfNotNull(item.subtitle, SpokenFormat.remaining(item.durationSec - item.positionSec)).joinToString(" · "),
        subtitleLines = 2,
        onClick = {
            if (item.episodeId != null) playEpisode(c, actions, item.libraryItemId, item.episodeId, downloaded)
            else playBook(c, actions, item.libraryItemId, downloaded)
        },
        onLongClick = { actions.open(if (item.isBook) bookRoute(item.libraryItemId) else podcastRoute(item.libraryItemId)) },
        cover = {
            Box(Modifier.size(Dimens.CardWidth)) {
                CoverArt(null, Dimens.CardWidth, fallback = item.subtitle ?: item.title, url = url)
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    ListenedBar(item.fraction, Modifier.fillMaxWidth(), color = Color.White)
                }
            }
        },
    )
}

/** Home: the newest episodes of followed shows that nobody's started. */
fun LazyListScope.newEpisodesSection(rows: List<EpisodeRow>) {
    if (rows.isEmpty()) return
    item(key = "new-episodes-head") { SectionHeader("New episodes") }
    items(rows, key = { "new-ep:${it.episode.id}" }) { row ->
        EpisodeListRow(row, Modifier.animateItem(), showCover = true, withShow = true, playButton = true, showGoToShow = true)
    }
}

// ---------------------------------------------------------------------------------- Library rows

@Composable
fun PodcastListRow(p: PodcastEntity, newCount: Int = 0, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    MediaRow(
        p.title,
        listOfNotNull("Podcast", p.author?.takeIf { it.isNotBlank() }, if (newCount > 0) "$newCount new" else null).joinToString(" • "),
        onClick = { actions.open(podcastRoute(p.id)) },
        modifier = modifier,
        coverUrl = c.spokenCover(p.id, 150),
        subtitleColor = TextSecondary,
        trailing = if (newCount > 0) {
            { Box(Modifier.padding(end = 16.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
        } else null,
    )
}

@Composable
fun BookListRow(b: BookRow, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val book = b.book
    val status = when {
        b.finished -> "Finished"
        (b.progressSec ?: 0.0) > 0 -> SpokenFormat.remaining(book.durationSec - (b.progressSec ?: 0.0))
        else -> SpokenFormat.length(book.durationSec).ifEmpty { null }
    }
    Column(modifier) {
        MediaRow(
            book.title,
            listOfNotNull("Audiobook", book.author, status).joinToString(" • "),
            onClick = { actions.open(bookRoute(book.id)) },
            coverUrl = c.spokenCover(book.id, 150),
            subtitleIcon = if (b.downloadState == SpokenDownloadState.DONE || b.downloadState == SpokenDownloadState.DOWNLOADING || b.downloadState == SpokenDownloadState.QUEUED) {
                { SpokenDownloadIcon(b.downloadState) }
            } else null,
        )
        SpokenFormat.fraction(b.progressSec, book.durationSec, b.finished)?.let { f ->
            ListenedBar(f, Modifier.padding(start = Dimens.Gutter + Dimens.RowCover + Dimens.CoverGap, bottom = 6.dp).width(96.dp))
        }
    }
}

/**
 * Picks an OPML file (exported from another podcast app) and adds and follows every show in it.
 * Returns the function that opens the file picker.
 */
@Composable
fun rememberOpmlImport(): () -> Unit {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        actions.showMessage("Adding your podcasts…")
        c.appScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text.isNullOrBlank()) { actions.showMessage("Couldn't read that file"); return@launch }
            val r = runCatching { c.spoken.importOpml(text) }.getOrElse { actions.showMessage("Couldn't import: ${im.flume.hearth.util.userMessage(it)}"); return@launch }
            val total = r.added.size + r.followedExisting.size
            actions.showMessage(
                when {
                    total == 0 && r.failed.isEmpty() -> "No podcasts found in that file"
                    total == 0 -> "Couldn't add any of the ${r.failed.size} podcasts"
                    r.failed.isEmpty() -> "Following ${im.flume.hearth.ui.components.plural(total, "podcast")}"
                    else -> "Following ${im.flume.hearth.ui.components.plural(total, "podcast")} · ${r.failed.size} couldn't be added"
                }
            )
        }
    }
    return { launcher.launch(arrayOf("text/xml", "application/xml", "text/x-opml", "application/octet-stream", "text/plain")) }
}

// ---------------------------------------------------------------------------------- page header

/** Expandable description: three lines, tap for the rest. */
@Composable
internal fun Description(text: String?) {
    // Feeds send HTML (or plain text with entities like &amp;): show it as text.
    val clean = remember(text) {
        text?.let { androidx.core.text.HtmlCompat.fromHtml(it, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
    }?.takeIf { it.isNotEmpty() } ?: return
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.animateContentSize()
            .padding(horizontal = Dimens.Gutter, vertical = 8.dp)
    ) {
        Text(
            clean, color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
        )
        Text(if (expanded) "Less" else "More", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Cover-tinted header shared by the show and book pages. */
@Composable
internal fun SpokenHeader(coverUrl: String?, title: String, lines: List<String>, artSize: Dp = 200.dp) {
    val headerColor = rememberCoverColor(null, imageUrl = coverUrl)
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(headerColor, Color.Transparent)))) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) { BackButton() }
            CoverArt(null, artSize, requestSize = 600, fallback = title, url = coverUrl)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
            lines.filter { it.isNotBlank() }.forEach { line ->
                Text(
                    line, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Big round play button, as on album pages. */
@Composable
internal fun RoundPlayButton(label: String, playing: Boolean = false, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    androidx.compose.material3.FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, label, modifier = Modifier.size(32.dp))
    }
}

// The show page ("podcast/{id}", "podcast-feed/{url}") is in ShowPage.kt.

// ---------------------------------------------------------------------------------- book page

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(id: String) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val book by remember(id) { c.spoken.book(id) }.collectAsStateWithLifecycle(null)
    val chapters by remember(id) { c.spoken.chapters(id) }.collectAsStateWithLifecycle(emptyList())
    val now = LocalSpokenNow.current
    val playerState by c.player.state.collectAsStateWithLifecycle()
    val downloadProgress by remember(id) {
        c.spoken.downloads.progress.map { it[SpokenKey(id)] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)
    var refreshing by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        val b = c.db.spoken().bookOnce(id)
        if ((b == null || b.detailsFetchedAt == 0L) && !c.network.cannotStream) runCatching { c.spoken.fetchItem(id) }
        loaded = true
    }
    val row = book
    if (row == null) {
        if (loaded) UnavailablePage(Icons.Default.Podcasts, "This book isn't on the server any more")
        else Box(Modifier.fillMaxSize())
        return
    }
    val b = row.book
    val isThis = now?.libraryItemId == id && now.episodeId == null
    // Where you are: live while it's playing, otherwise your saved progress.
    var livePosSec by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(isThis, playerState.isPlaying) {
        if (!isThis) { livePosSec = null; return@LaunchedEffect }
        while (true) {
            livePosSec = c.player.spokenPositionMs / 1000.0
            kotlinx.coroutines.delay(if (playerState.isPlaying) 1000 else 3000)
        }
    }
    val posSec = livePosSec ?: row.progressSec ?: 0.0
    val currentChapter = if (isThis || (row.progressSec ?: 0.0) > 0) ChapterMarks.current(chapters, posSec) else -1
    val downloaded = row.downloadState == SpokenDownloadState.DONE
    val downloading = row.downloadState == SpokenDownloadState.QUEUED || row.downloadState == SpokenDownloadState.DOWNLOADING
    val started = (row.progressSec ?: 0.0) > 0 && !row.finished
    val coverUrl = c.spokenCover(id, 600)

    if (confirmRemove) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(if (downloading) "Stop downloading?" else "Remove download?") },
            text = { Text("\"${b.title}\" will be removed from this phone. You can still stream it, or download it again later.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRemove = false; c.spoken.downloads.remove(id) }) {
                    Text(if (downloading) "Stop" else "Remove", color = Destructive)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
    ActionSheet(menu, { menu = false }, title = b.title, subtitle = b.author, showCover = false) {
        if (row.finished || started) {
            MenuItem("Mark as not started", Icons.Outlined.RadioButtonUnchecked) {
                menu = false; c.appScope.launch { c.spoken.markFinished(id, null, false) }
            }
        }
        if (!row.finished) {
            MenuItem("Mark as finished", Icons.Outlined.CheckCircle) {
                menu = false
                c.appScope.launch { c.spoken.markFinished(id, null, true) }
                actions.showUndo("Marked as finished") { c.spoken.markFinished(id, null, false) }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                runCatching { c.spoken.fetchItem(id) }.onFailure { actions.showMessage("Couldn't refresh: ${im.flume.hearth.util.userMessage(it)}") }
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                SpokenHeader(
                    coverUrl, b.title,
                    listOfNotNull(
                        b.subtitle,
                        b.author,
                        b.narrator?.let { "Read by $it" },
                        listOfNotNull(b.series, b.publishedYear, SpokenFormat.length(b.durationSec).ifEmpty { null }).joinToString(" • "),
                    ),
                )
            }
            item(key = "progress") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        row.finished -> Text("Finished", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        posSec > 0 && b.durationSec > 0 -> {
                            ListenedBar((posSec / b.durationSec).toFloat().coerceIn(0f, 1f), Modifier.widthIn(max = 280.dp).fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text(SpokenFormat.remaining(b.durationSec - posSec), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item(key = "buttons") {
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                    DownloadToggle(downloaded = downloaded, progress = if (downloading) downloadProgress ?: 0f else null) {
                        when {
                            downloaded || downloading -> confirmRemove = true
                            else -> {
                                c.spoken.downloads.downloadBook(id)
                                actions.showMessage(downloadingMessage(c) + " \"${b.title}\"" + (if (b.sizeBytes > 0) " (${im.flume.hearth.ui.components.formatBytes(b.sizeBytes)})" else ""))
                            }
                        }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
                    Spacer(Modifier.weight(1f))
                    val playing = isThis && playerState.isPlaying
                    Button(
                        onClick = { if (isThis) c.player.playPause() else playBook(c, actions, id, downloaded) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                playing -> "Pause"
                                isThis || started -> "Resume"
                                row.finished -> "Listen again"
                                else -> "Play"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            item(key = "description") { Description(b.description) }
            if (chapters.isNotEmpty()) {
                item(key = "chapters-head") { SectionHeader("Chapters") }
                itemsIndexed(chapters, key = { _, ch -> "ch:${ch.chapterIndex}" }) { i, ch ->
                    ChapterRow(
                        index = i, title = ch.title, startSec = ch.startSec, lengthSec = ch.endSec - ch.startSec,
                        current = i == currentChapter, done = i < currentChapter || row.finished,
                    ) {
                        if (isThis) c.player.seekSpoken((ch.startSec * 1000).toLong()).also { if (!playerState.isPlaying) c.player.resume() }
                        else playBook(c, actions, id, downloaded, startSec = ch.startSec)
                    }
                }
            } else if (b.detailsFetchedAt == 0L && c.network.cannotStream) {
                item(key = "no-chapters") {
                    Text("Chapters show up once you're online.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

/** A chapter in a list: number, title, where it starts and how long it is. Tap to listen from there. */
@Composable
fun ChapterRow(index: Int, title: String, startSec: Double, lengthSec: Double, current: Boolean, done: Boolean = false, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth()
            .background(if (current) accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.Gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", Modifier.width(32.dp), color = if (current) accent else TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "Chapter ${index + 1}" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = when {
                    current -> accent
                    done -> TextSecondary
                    else -> Color.White
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(SpokenFormat.clock(startSec), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            if (lengthSec > 0) Text(SpokenFormat.length(lengthSec), color = TextSecondary.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Your Library's Podcasts tab when there's nothing to show, and its "add" rows. */
@Composable
fun SpokenSignInPrompt(what: String) {
    val actions = LocalActions.current
    EmptyState(
        Icons.Default.Podcasts,
        "Sign in to podcasts & audiobooks in Settings to see your $what here.",
        action = "Open Settings",
        onAction = { actions.open("settings/storage") },
    )
}


/**
 * A podcast from the directory in Search: Follow adds it to the server (if it isn't there yet) and
 * follows it; one already on the server opens its page.
 */
@Composable
fun DirectoryRow(r: im.flume.hearth.api.AbsPodcastSearchResult, library: List<PodcastEntity>, username: String?, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val (state, existing) = remember(r, library, username) { im.flume.hearth.spoken.SpokenDiscovery.state(r, library, username) }
    var busy by remember(r.feedUrl) { mutableStateOf(false) }
    MediaRow(
        r.title,
        listOfNotNull("Podcast", r.artistName?.takeIf { it.isNotBlank() }, r.trackCount?.takeIf { it > 0 }?.let { im.flume.hearth.ui.components.plural(it, "episode") }).joinToString(" • ").ifEmpty { null },
        // On the server: its page. Not yet: the feed's page, where any episode can be fetched.
        onClick = existing?.let { e -> { actions.open(podcastRoute(e.id)) } } ?: r.feedUrl?.let { f -> { actions.open(podcastFeedRoute(f)) } },
        modifier = modifier,
        coverUrl = r.cover,
        trailing = {
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                Box(Modifier.padding(end = 8.dp)) {
                    FollowButton(following = state == im.flume.hearth.spoken.DirectoryState.FOLLOWING) {
                        if (state == im.flume.hearth.spoken.DirectoryState.FOLLOWING) {
                            existing?.let { actions.open(podcastRoute(it.id)) }
                            return@FollowButton
                        }
                        val feed = r.feedUrl ?: return@FollowButton
                        busy = true
                        c.appScope.launch {
                            c.spoken.addPodcast(feed, r, follow = true).fold(
                                onSuccess = { actions.showMessage("Following ${r.title}. Its newest episodes are on their way.") },
                                onFailure = { actions.showMessage("Couldn't follow ${r.title}: ${im.flume.hearth.util.userMessage(it)}") },
                            )
                            busy = false
                        }
                    }
                }
            }
        },
    )
}
