package im.flume.hearth.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.playback.PlaybackService
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.playback.SpokenNowPlaying
import im.flume.hearth.spoken.ChapterEntity
import im.flume.hearth.spoken.ChapterMarks
import im.flume.hearth.spoken.SPOKEN_SPEEDS
import im.flume.hearth.spoken.SpokenDownloadState
import im.flume.hearth.spoken.SpokenFormat
import im.flume.hearth.spoken.SpokenKey
import im.flume.hearth.spoken.SpokenLists
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.ChoiceSheet
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.formatDuration
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.theme.Background
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The player while a podcast episode or audiobook plays: −15 / +30 instead of previous / next, speed,
// chapters, "end of chapter" sleep timer and "Back to music". No shuffle, repeat, like, lyrics or visualiser.

/** Position on the whole episode / book (not the current file), polled while visible. */
@Composable
internal fun rememberSpokenPosition(state: PlayerUiState): Long {
    val player = LocalContext.current.container.player
    var pos by remember { mutableLongStateOf(player.spokenPositionMs) }
    LaunchedEffect(state.current?.mediaId, state.isPlaying) {
        while (true) {
            pos = player.spokenPositionMs
            delay(if (state.isPlaying) 250 else 1000)
        }
    }
    return pos
}

/** Title and show / author of what's playing, from the catalogue (falls back to the player's own). */
private data class SpokenInfo(val title: String, val subtitle: String, val isBook: Boolean, val downloadState: SpokenDownloadState?, val finished: Boolean)

@Composable
private fun rememberSpokenInfo(state: PlayerUiState, spoken: SpokenNowPlaying): SpokenInfo {
    val c = LocalContext.current.container
    val meta = state.current?.mediaMetadata
    val fallbackTitle = (if (spoken.isBook) meta?.albumTitle else meta?.title)?.toString().orEmpty()
    val fallbackSubtitle = meta?.artist?.toString().orEmpty()
    return if (spoken.episodeId != null) {
        val ep by remember(spoken.episodeId) { c.spoken.episode(spoken.episodeId) }.collectAsStateWithLifecycle(null)
        SpokenInfo(ep?.episode?.title ?: fallbackTitle, ep?.podcastTitle ?: fallbackSubtitle, false, ep?.downloadState, ep?.finished == true)
    } else {
        val book by remember(spoken.libraryItemId) { c.spoken.book(spoken.libraryItemId) }.collectAsStateWithLifecycle(null)
        SpokenInfo(book?.book?.title ?: fallbackTitle, book?.book?.author ?: fallbackSubtitle, true, book?.downloadState, book?.finished == true)
    }
}

/** Circular arrow with the number of seconds inside: back 15 / forward 30. */
@Composable
internal fun SkipIcon(back: Boolean, seconds: Int, size: Dp) {
    val label = if (back) "Back $seconds seconds" else "Forward $seconds seconds"
    Box(Modifier.size(size).clearAndSetSemantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Replay, null,
            Modifier.fillMaxSize().graphicsLayer { if (!back) scaleX = -1f },
        )
        Text(
            "$seconds", fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.27f).sp,
            modifier = Modifier.padding(top = size * 0.08f),
        )
    }
}


/** The mini player's controls in spoken-word mode. */
@Composable
internal fun SpokenMiniControls(state: PlayerUiState) {
    val player = LocalContext.current.container.player
    IconButton(onClick = { player.skipBack() }) { SkipIcon(back = true, seconds = 15, size = 24.dp) }
    IconButton(onClick = { player.playPause() }) { PlayPauseIcon(state.isPlaying, Modifier.size(30.dp)) }
    IconButton(onClick = { player.skipForward() }) { SkipIcon(back = false, seconds = 30, size = 24.dp) }
}

@Composable
internal fun SpokenNowPlayingScreen(state: PlayerUiState, spoken: SpokenNowPlaying, onClose: () -> Unit, onOpenQueue: () -> Unit) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val player = c.player
    val info = rememberSpokenInfo(state, spoken)
    val chapters by remember(spoken.libraryItemId, spoken.episodeId) { c.spoken.chapters(spoken.libraryItemId, spoken.episodeId) }
        .collectAsStateWithLifecycle(emptyList())
    val coverUrl = c.spokenCover(spoken.libraryItemId, 1000)
    val topColor = rememberCoverColor(null, imageUrl = c.spokenCover(spoken.libraryItemId, 300))
    val pageRoute = if (spoken.isBook) bookRoute(spoken.libraryItemId) else podcastRoute(spoken.libraryItemId)
    val openPage = { onClose(); actions.open(pageRoute) }

    val scope = rememberCoroutineScope()
    val dragDown = remember { Animatable(0f) }
    val closeAt = with(LocalDensity.current) { 140.dp.toPx() }
    val dragToClose = Modifier.pointerInput(Unit) {
        var dy = 0f
        detectVerticalDragGestures(
            onDragStart = { dy = 0f },
            onDragEnd = { scope.launch { if (dy > closeAt) onClose(); dragDown.animateTo(0f) } },
            onDragCancel = { scope.launch { dragDown.animateTo(0f) } },
        ) { change, amount ->
            change.consume()
            dy += amount
            scope.launch { dragDown.snapTo(dy.coerceAtLeast(0f)) }
        }
    }

    var speedSheet by remember { mutableStateOf(false) }
    var chapterSheet by remember { mutableStateOf(false) }
    var sleepSheet by remember { mutableStateOf(false) }
    if (speedSheet) {
        val current = SPOKEN_SPEEDS.minByOrNull { kotlin.math.abs(it - spoken.speed) } ?: 1f
        ChoiceSheet(true, "Playback speed", SPOKEN_SPEEDS.map { it to SpokenFormat.speed(it) }, current, onDismiss = { speedSheet = false }) { player.setSpeed(it) }
    }
    if (chapterSheet) ChaptersSheet(chapters, spoken, state, onDismiss = { chapterSheet = false })
    if (sleepSheet) SpokenSleepSheet(state.sleepAt, chapters.isNotEmpty(), spoken.isBook, onDismiss = { sleepSheet = false })

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragDown.value }
            .background(Brush.verticalGradient(listOf(topColor, Background, Background)))
            .clickable(enabled = false) {}
            .safeDrawingPadding()
    ) {
        val landscape = maxWidth > maxHeight
        val art: @Composable (Modifier) -> Unit = { m ->
            Box(m.then(dragToClose), contentAlignment = Alignment.Center) {
                CoverArt(
                    null, null, Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
                    corner = Dimens.CardCorner, requestSize = 1000, fallback = info.subtitle.ifBlank { info.title }, url = coverUrl,
                )
            }
        }
        val controls: @Composable () -> Unit = {
            SpokenControls(state, spoken, info, chapters, openPage, { speedSheet = true }, { chapterSheet = true }, { sleepSheet = true }, onOpenQueue)
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Row(dragToClose, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (spoken.isBook) "AUDIOBOOK" else "PODCAST", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Text(
                        if (spoken.isBook) info.subtitle.ifBlank { info.title } else info.subtitle,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    SleepStatus(state.sleepAt)
                }
                SpokenPlayerMenu(state, spoken, info, onClose, openPage, { sleepSheet = true })
            }
            if (landscape) {
                Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    art(Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) { controls() }
                }
            } else {
                art(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp))
                controls()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpokenControls(
    state: PlayerUiState,
    spoken: SpokenNowPlaying,
    info: SpokenInfo,
    chapters: List<ChapterEntity>,
    openPage: () -> Unit,
    onSpeed: () -> Unit,
    onChapters: () -> Unit,
    onSleep: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val player = LocalContext.current.container.player
    val accent = MaterialTheme.colorScheme.primary
    val pos = rememberSpokenPosition(state)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val totalMs = spoken.durationMs.coerceAtLeast(1)
    val totalSec = totalMs / 1000.0
    val shownMs = if (dragging) (dragValue * totalMs).toLong() else pos
    val posSec = shownMs / 1000.0
    val chapterIndex = ChapterMarks.current(chapters, posSec)
    val chapter = chapters.getOrNull(chapterIndex)
    val ticks = remember(chapters, totalSec) { ChapterMarks.ticks(chapters, totalSec) }

    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = if (spoken.isBook) "Go to book" else "Go to show", onClick = openPage),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(info.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(info.subtitle, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (chapter != null) {
        val left = ChapterMarks.leftInChapter(chapters, posSec, totalSec)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button, onClickLabel = "Chapters", onClick = onChapters),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                chapter.title.ifBlank { "Chapter ${chapterIndex + 1}" }, color = accent, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            left?.let {
                Text(" · " + SpokenFormat.remaining(it, spoken.speed) + " in chapter", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }

    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }

    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f))
    Slider(
        value = if (dragging) dragValue else (pos.toFloat() / totalMs).coerceIn(0f, 1f),
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = { player.seekSpoken((dragValue * totalMs).toLong()); dragging = false },
        colors = colors,
        track = { sliderState ->
            Box(contentAlignment = Alignment.Center) {
                SliderDefaults.Track(sliderState = sliderState, colors = colors)
                if (ticks.isNotEmpty()) {
                    // Chapter starts: small gaps in the bar.
                    Canvas(Modifier.matchParentSize()) {
                        val w = 2.dp.toPx()
                        ticks.forEach { f -> drawRect(Background, topLeft = Offset(f * size.width - w / 2, 0f), size = Size(w, size.height)) }
                    }
                }
            }
        },
    )
    Row {
        Text(formatDuration(shownMs / 1000), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(Modifier.weight(1f))
        // Time left at the chosen speed, like other podcast apps.
        val leftSec = ((totalMs - shownMs).coerceAtLeast(0) / 1000.0 / spoken.speed.coerceAtLeast(0.1f)).toLong()
        Text("−" + formatDuration(leftSec), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onSpeed, modifier = Modifier.size(width = 64.dp, height = 48.dp), contentPadding = PaddingValues(0.dp)) {
            Text(SpokenFormat.speed(spoken.speed), color = if (spoken.speed != 1f) accent else Color.White, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { player.skipBack() }, Modifier.size(56.dp)) {
            SkipIcon(back = true, seconds = 15, size = 40.dp)
        }
        FilledIconButton(
            onClick = { player.playPause() },
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
        ) { PlayPauseIcon(state.isPlaying, Modifier.size(40.dp)) }
        IconButton(onClick = { player.skipForward() }, Modifier.size(56.dp)) {
            SkipIcon(back = false, seconds = 30, size = 40.dp)
        }
        if (chapters.isNotEmpty()) {
            IconButton(onClick = onChapters, Modifier.size(width = 64.dp, height = 48.dp)) { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Chapters") }
        } else {
            Spacer(Modifier.width(64.dp))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = onSleep) {
            Icon(Icons.Outlined.Bedtime, if (state.sleepAt != 0L) "Sleep timer (on)" else "Sleep timer", tint = if (state.sleepAt != 0L) accent else Color.White)
        }
        if (!spoken.isBook) IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Up next") }
    }
}

@Composable
private fun SpokenPlayerMenu(state: PlayerUiState, spoken: SpokenNowPlaying, info: SpokenInfo, onClose: () -> Unit, openPage: () -> Unit, onSleep: () -> Unit) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    var open by remember { mutableStateOf(false) }
    val key = SpokenKey(spoken.libraryItemId, spoken.episodeId.orEmpty())
    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreHoriz, "More options") }
    ActionSheet(open, { open = false }, title = info.title, subtitle = info.subtitle, coverUrl = c.spokenCover(spoken.libraryItemId, 150), fallback = info.subtitle) {
        if (spoken.canGoBackToMusic) {
            MenuItem("Back to music", Icons.Default.LibraryMusic) { open = false; c.player.backToMusic() }
        }
        MenuItem(if (spoken.isBook) "Go to book" else "Go to show", Icons.Default.Podcasts) { open = false; openPage() }
        when (info.downloadState) {
            SpokenDownloadState.DONE -> MenuItem("Remove download", Icons.Default.CheckCircle) {
                open = false; c.spoken.downloads.remove(key.libraryItemId, key.episodeId)
            }
            SpokenDownloadState.QUEUED, SpokenDownloadState.DOWNLOADING -> MenuItem("Cancel download", Icons.Default.Close) {
                open = false; c.spoken.downloads.remove(key.libraryItemId, key.episodeId)
            }
            else -> MenuItem("Download", Icons.Outlined.ArrowCircleDown) {
                open = false
                if (spoken.episodeId != null) c.spoken.downloads.downloadEpisode(spoken.libraryItemId, spoken.episodeId) else c.spoken.downloads.downloadBook(spoken.libraryItemId)
                actions.showMessage("Downloading")
            }
        }
        if (!info.finished) {
            MenuItem(if (spoken.isBook) "Mark as finished" else "Mark as played", Icons.Outlined.CheckCircle) {
                open = false
                c.appScope.launch { c.spoken.markFinished(spoken.libraryItemId, spoken.episodeId, true) }
            }
        }
        MenuItem(
            if (state.sleepAt != 0L) "Sleep timer (on)" else "Sleep timer", Icons.Outlined.Bedtime,
            tint = if (state.sleepAt != 0L) MaterialTheme.colorScheme.primary else Color.White,
        ) { open = false; onSleep() }
    }
}

@Composable
private fun SpokenSleepSheet(sleepAt: Long, hasChapters: Boolean, isBook: Boolean, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val pick = { ms: Long ->
        if (ms != 0L) c.usage.track(im.flume.hearth.data.Usage.SLEEP)
        c.player.setSleepTimer(ms)
        onDismiss()
    }
    ActionSheet(true, onDismiss, title = "Sleep timer", showCover = false) {
        if (hasChapters) MenuItem("End of this chapter", Icons.Outlined.Timer, checked = sleepAt == PlaybackService.SLEEP_END_OF_CHAPTER) { pick(PlaybackService.SLEEP_END_OF_CHAPTER) }
        listOf(15, 30, 45, 60).forEach { m -> MenuItem("$m minutes", Icons.Outlined.Timer) { pick(m * 60_000L) } }
        // A book is several files, so "end of this file" would stop at an odd place; episodes are one file.
        if (!isBook) MenuItem("End of this episode", Icons.Outlined.Timer, checked = sleepAt == PlaybackService.SLEEP_END_OF_SONG) { pick(PlaybackService.SLEEP_END_OF_SONG) }
        if (sleepAt != 0L) MenuItem("Turn off timer", Icons.Outlined.TimerOff, tint = MaterialTheme.colorScheme.primary, tintText = true) { pick(0) }
    }
}

@Composable
private fun ChaptersSheet(chapters: List<ChapterEntity>, spoken: SpokenNowPlaying, state: PlayerUiState, onDismiss: () -> Unit) {
    val player = LocalContext.current.container.player
    val pos = rememberSpokenPosition(state)
    val current = ChapterMarks.current(chapters, pos / 1000.0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    ActionSheet(true, onDismiss, title = "Chapters", subtitle = im.flume.hearth.ui.components.plural(chapters.size, "chapter"), showCover = false) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), state = listState) {
            itemsIndexed(chapters, key = { _, ch -> ch.chapterIndex }) { i, ch ->
                ChapterRow(i, ch.title, ch.startSec, ch.endSec - ch.startSec, current = i == current, done = i < current) {
                    player.seekSpoken((ch.startSec * 1000).toLong())
                    if (!state.isPlaying) player.resume()
                    onDismiss()
                }
            }
        }
    }
}

/** The queue in spoken-word mode: what's playing, then more from the same show. */
@Composable
internal fun SpokenQueueScreen(state: PlayerUiState, spoken: SpokenNowPlaying, onBack: (() -> Unit)?) {
    val c = LocalContext.current.container
    val info = rememberSpokenInfo(state, spoken)
    val episodes by remember(spoken.libraryItemId) { c.spoken.episodes(spoken.libraryItemId) }.collectAsStateWithLifecycle(emptyList())
    val upNext = remember(episodes, spoken.episodeId) { SpokenLists.upNext(episodes, spoken.episodeId) }
    val topColor = rememberCoverColor(null, imageUrl = c.spokenCover(spoken.libraryItemId, 300))
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(topColor, Background, Background)))
            .clickable(enabled = false) {}
            .safeDrawingPadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onBack?.invoke() }) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("UP NEXT", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text(info.subtitle, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.size(48.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            episodes.firstOrNull { it.episode.id == spoken.episodeId }?.let { now ->
                item(key = "h-now") { QueueSectionTitle("Now playing") }
                item(key = "now") { EpisodeListRow(now, showCover = true) }
            }
            if (upNext.isNotEmpty()) {
                item(key = "h-more") { QueueSectionTitle("More from ${info.subtitle}") }
                items(upNext, key = { it.episode.id }) { row -> EpisodeListRow(row, Modifier.animateItem(), showCover = true) }
            } else {
                item(key = "none") {
                    Text("You're up to date with this show.", color = TextSecondary, modifier = Modifier.padding(16.dp))
                }
            }
            item(key = "hint") {
                Text(
                    "Tap an episode to play it. Episodes don't play one after another on their own.",
                    color = TextSecondary.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun QueueSectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 14.dp, bottom = 4.dp),
    )
}
