package im.flume.hearth.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.Bedtime
import im.flume.hearth.playback.PlaybackService
import androidx.compose.material3.DropdownMenuItem
import im.flume.hearth.data.currentIndex
import im.flume.hearth.data.rows
import im.flume.hearth.ui.components.LocalRowContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import im.flume.hearth.container
import im.flume.hearth.data.Lyrics
import im.flume.hearth.data.SongEntity
import im.flume.hearth.playback.EXTRA_COVER_ART
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.playback.QueueEntry
import im.flume.hearth.playback.isManual
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.formatDuration
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.AccentDeep
import androidx.compose.ui.graphics.lerp
import im.flume.hearth.ui.theme.Background
import im.flume.hearth.ui.theme.Surface
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

private val PlayerUiState.coverArt: String? get() = current?.mediaMetadata?.extras?.getString(EXTRA_COVER_ART)

/** Polls playback position while visible. */
@Composable
private fun rememberPosition(state: PlayerUiState): Long {
    val player = LocalContext.current.container.player
    var pos by remember { mutableLongStateOf(player.positionMs) }
    LaunchedEffect(state.current?.mediaId, state.isPlaying) {
        while (true) {
            pos = player.positionMs
            delay(if (state.isPlaying) 250 else 1000)
        }
    }
    return pos
}

@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit) {
    val player = LocalContext.current.container.player
    val meta = state.current?.mediaMetadata ?: return
    val pos = rememberPosition(state)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AccentDeep)
            .clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(state.coverArt, 42.dp, requestSize = 150)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(meta.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = { player.previous() }) { Icon(Icons.Default.SkipPrevious, "Previous") }
            IconButton(onClick = { player.playPause() }) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", Modifier.size(30.dp))
            }
            IconButton(onClick = { player.next() }) { Icon(Icons.Default.SkipNext, "Next") }
        }
        LinearProgressIndicator(
            progress = { if (state.durationMs > 0) (pos.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 8.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.2f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
fun NowPlayingScreen(state: PlayerUiState, onClose: () -> Unit, onOpenQueue: () -> Unit) {
    val c = LocalContext.current.container
    val current = state.current ?: return
    val songId = current.mediaId
    val song by remember(songId) { c.db.library().songFlow(songId) }.collectAsStateWithLifecycle(null)
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val topColor = rememberCoverColor(state.coverArt, lerp(Accent, Color.Black, 0.6f))

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(topColor, Background, Background)))
            .clickable(enabled = false) {}
            .safeDrawingPadding()
    ) {
        val landscape = maxWidth > maxHeight
        val artOrLyrics: @Composable (Modifier) -> Unit = { m ->
            Box(m, contentAlignment = Alignment.Center) {
                if (showLyrics) {
                    LyricsView(song, state, Modifier.fillMaxSize())
                } else {
                    CoverArt(state.coverArt, null, Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true), corner = 8.dp, requestSize = 900)
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Text(state.sourceLabel.ifBlank { "Your queue" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SleepStatus(state.sleepAt)
                }
                NowPlayingMenu(song, state.sleepAt, onClose)
            }
            if (landscape) {
                Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    artOrLyrics(Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        PlayerControls(state, song, onClose, showLyrics, { showLyrics = !showLyrics }, onOpenQueue)
                    }
                }
            } else {
                artOrLyrics(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp))
                PlayerControls(state, song, onClose, showLyrics, { showLyrics = !showLyrics }, onOpenQueue)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlayerUiState,
    song: SongEntity?,
    onClose: () -> Unit,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val actions = LocalActions.current
    val player = LocalContext.current.container.player
    val meta = state.current?.mediaMetadata ?: return
    val songId = state.current.mediaId
    val pos = rememberPosition(state)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(meta.title?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                meta.artist?.toString().orEmpty(),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = song?.artistId != null) {
                    onClose(); song?.artistId?.let(actions::openArtist)
                },
            )
        }
        val starred = song?.starred == true
        IconButton(onClick = { actions.setStarred(songId, !starred) }) {
            Icon(if (starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Like", tint = if (starred) Accent else Color.White)
        }
    }

    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }

    val duration = state.durationMs.coerceAtLeast(1)
    Slider(
        value = if (dragging) dragValue else (pos.toFloat() / duration).coerceIn(0f, 1f),
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = { player.seekTo((dragValue * duration).toLong()); dragging = false },
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
    )
    Row {
        Text(formatDuration((if (dragging) (dragValue * duration).toLong() else pos) / 1000), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(formatDuration(state.durationMs / 1000), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { player.toggleShuffle() }) {
            Icon(Icons.Default.Shuffle, "Shuffle", tint = if (state.shuffle) Accent else Color.White)
        }
        IconButton(onClick = { player.previous() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(40.dp)) }
        FilledIconButton(
            onClick = { player.playPause() },
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
        ) {
            Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", Modifier.size(40.dp))
        }
        IconButton(onClick = { player.next() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(40.dp)) }
        IconButton(onClick = { player.cycleRepeat() }) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                "Repeat",
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) Color.White else Accent,
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = onToggleLyrics) {
            Icon(if (showLyrics) Icons.Filled.Mic else Icons.Outlined.Mic, "Lyrics", tint = if (showLyrics) Accent else Color.White)
        }
        IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
    }
}

@Composable
private fun LyricsView(song: SongEntity?, state: PlayerUiState, modifier: Modifier) {
    val c = LocalContext.current.container
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    var loading by remember(song?.id) { mutableStateOf(true) }
    var lyrics by remember(song?.id) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(song?.id) {
        if (song == null) return@LaunchedEffect
        lyrics = c.lyrics.get(song, online)
        loading = false
    }

    val l = lyrics
    when {
        loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        l == null -> LyricsMessage("Lyrics aren't available offline for this song", modifier)
        l.isEmpty -> LyricsMessage("No lyrics for this song", modifier)
        else -> SyncedOrPlainLyrics(l, state, modifier)
    }
}

@Composable
private fun LyricsMessage(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(text, color = TextSecondary) }
}

@Composable
private fun NowPlayingMenu(song: SongEntity?, sleepAt: Long, onClose: () -> Unit) {
    val actions = LocalActions.current
    val player = LocalContext.current.container.player
    val downloaded = song != null && LocalRowContext.current.downloads[song.id] != null
    var open by remember { mutableStateOf(false) }
    var sleepPicker by remember { mutableStateOf(false) }
    if (sleepPicker) {
        SleepTimerDialog(active = sleepAt != 0L, onPick = { player.setSleepTimer(it); sleepPicker = false }, onDismiss = { sleepPicker = false })
    }
    Box {
        IconButton(onClick = { open = true }, enabled = song != null) { Icon(Icons.Default.MoreHoriz, "More") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (song != null) {
                DropdownMenuItem(
                    text = { Text("Start radio") },
                    leadingIcon = { Icon(Icons.Default.Radio, null) },
                    onClick = { open = false; actions.startRadio(song) },
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    onClick = { open = false; actions.addToPlaylist(listOf(song.id)) },
                )
            }
            DropdownMenuItem(
                text = { Text(if (sleepAt != 0L) "Sleep timer (on)" else "Sleep timer") },
                leadingIcon = { Icon(Icons.Outlined.Bedtime, null, tint = if (sleepAt != 0L) Accent else LocalContentColor.current) },
                onClick = { open = false; sleepPicker = true },
            )
            song?.albumId?.let { id ->
                DropdownMenuItem(
                    text = { Text("Go to album") },
                    leadingIcon = { Icon(Icons.Default.Album, null) },
                    onClick = { open = false; onClose(); actions.openAlbum(id) },
                )
            }
            song?.artistId?.let { id ->
                DropdownMenuItem(
                    text = { Text("Go to artist") },
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    onClick = { open = false; onClose(); actions.openArtist(id) },
                )
            }
            if (song != null) {
                DropdownMenuItem(
                    text = { Text(if (downloaded) "Remove download" else "Download") },
                    leadingIcon = { Icon(if (downloaded) Icons.Default.CheckCircle else Icons.Outlined.ArrowCircleDown, null) },
                    onClick = {
                        open = false
                        if (downloaded) actions.removeDownload(listOf(song.id)) else actions.download(listOf(song))
                    },
                )
            }
        }
    }
}

@Composable
private fun SyncedOrPlainLyrics(lyrics: Lyrics, state: PlayerUiState, modifier: Modifier) {
    val c = LocalContext.current.container
    val player = c.player
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val fontSize = LYRIC_SIZES.getOrElse(settings.lyricsSize) { 22 }.sp
    val gap = LYRIC_GAPS.getOrElse(settings.lyricsSpacing) { 8 }.dp
    val pos = rememberPosition(state)
    val rows = remember(lyrics) { lyrics.rows() }
    val current = rows.currentIndex(pos)
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(vertical = 24.dp)) {
        itemsIndexed(rows) { i, row ->
            if (row.isBreak) {
                val active = i == current
                val progress = when {
                    active && row.start != null && row.end != null && row.end > row.start ->
                        ((pos - row.start).toFloat() / (row.end - row.start)).coerceIn(0f, 1f)
                    i < current -> 1f
                    else -> 0f
                }
                BreakDots(active, progress, Modifier.padding(vertical = gap + 20.dp))
            } else {
                val active = !lyrics.synced || i == current
                val past = lyrics.synced && i < current
                Text(
                    row.text,
                    fontSize = if (lyrics.synced) fontSize else fontSize * 0.8f,
                    lineHeight = (if (lyrics.synced) fontSize else fontSize * 0.8f) * 1.25f,
                    fontWeight = if (lyrics.synced) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        active -> Color.White
                        past -> Color.White.copy(alpha = 0.55f)
                        else -> Color.White.copy(alpha = 0.35f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = lyrics.synced && row.start != null,
                            interactionSource = null,
                            indication = null,
                        ) { player.seekTo(row.start ?: 0) }
                        .padding(vertical = if (lyrics.synced) gap else gap / 3),
                )
            }
        }
    }
}

/** "Stops in 23 min" / "Stops after this song" under the source label. */
@Composable
private fun SleepStatus(sleepAt: Long) {
    if (sleepAt == 0L) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepAt) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val text = if (sleepAt == PlaybackService.SLEEP_END_OF_SONG) "Stops after this song"
    else "Stops in ${((sleepAt - now) / 60_000 + 1).coerceAtLeast(1)} min"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Bedtime, null, tint = Accent, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Accent)
    }
}

@Composable
private fun SleepTimerDialog(active: Boolean, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(15, 30, 45, 60).map { "$it minutes" to it * 60_000L } + ("End of this song" to PlaybackService.SLEEP_END_OF_SONG)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                options.forEach { (label, ms) ->
                    Text(label, modifier = Modifier.fillMaxWidth().clickable { onPick(ms) }.padding(vertical = 12.dp))
                }
                if (active) {
                    Text("Turn off timer", color = Accent, modifier = Modifier.fillMaxWidth().clickable { onPick(0) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lyric text sizes (sp) and line gaps (dp) for the Appearance settings. */
val LYRIC_SIZES = listOf(18, 22, 27, 32)
val LYRIC_GAPS = listOf(4, 8, 14)

/** Instrumental break: three small dots that slowly fill with white, one after another, until the next line. */
@Composable
private fun BreakDots(active: Boolean, progress: Float, modifier: Modifier = Modifier) {
    val smooth by animateFloatAsState(progress, tween(300, easing = LinearEasing), label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val lit = (smooth * 3 - i).coerceIn(0f, 1f)
            val alpha = when {
                active -> 0.3f + 0.7f * lit
                progress >= 1f -> 0.55f
                else -> 0.3f
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = alpha)))
        }
    }
}

@Composable
fun QueueScreen() {
    val c = LocalContext.current.container
    val player = c.player
    val state by player.state.collectAsStateWithLifecycle()
    val upcoming = state.queue.drop(state.currentIndex + 1)

    // Local copy so drag-reordering is smooth; committed to the player when the drag ends.
    var local by remember { mutableStateOf(upcoming) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var revealedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(upcoming) { if (draggingKey == null) local = upcoming }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = local.indexOfFirst { it.key == from.key }
        val toIdx = local.indexOfFirst { it.key == to.key }
        if (fromIdx >= 0 && toIdx >= 0) local = local.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
    }

    fun commitDrag(key: String) {
        val entry = local.firstOrNull { it.key == key } ?: return
        val newPos = local.indexOf(entry)
        player.move(entry.index, state.currentIndex + 1 + newPos)
        draggingKey = null
    }

    // Songs already played (kept briefly in the player) and everything still to come beyond it.
    val history = state.queue.take(state.currentIndex)
    val info by c.queueInfo.collectAsStateWithLifecycle()
    var later by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    LaunchedEffect(info.pending) { later = c.library.songsByIds(info.pending) }
    val total = info.trimmed + state.queue.size + info.pending.size
    val position = info.trimmed + state.currentIndex + 1
    LaunchedEffect(Unit) { if (history.isNotEmpty()) listState.scrollToItem(history.size + 1) }

    val firstAuto = local.indexOfFirst { !it.item.isManual }.let { if (it < 0) local.size else it }
    val manual = local.take(firstAuto)
    val auto = local.drop(firstAuto)
    val repeatOne = state.repeatMode == Player.REPEAT_MODE_ONE
    val upcomingAlpha = if (repeatOne) 0.4f else 1f

    @Composable
    fun LazyItemScope.Upcoming(e: QueueEntry) {
        ReorderableItem(reorderState, key = e.key) { dragging ->
            SwipeableQueueRow(
                revealed = revealedKey == e.key,
                onRevealChange = { open -> revealedKey = if (open) e.key else if (revealedKey == e.key) null else revealedKey },
                onPlayNext = { player.moveToNext(e.index) },
                onRemove = { revealedKey = null; player.remove(e.index) },
            ) {
                QueueRow(
                    e,
                    dragging = dragging,
                    alpha = upcomingAlpha,
                    onClick = { if (revealedKey != null) revealedKey = null else player.skipTo(e.index) },
                    handle = { Modifier.draggableHandle(onDragStarted = { draggingKey = e.key }, onDragStopped = { commitDrag(e.key) }) },
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            BackButton()
            Text("Queue", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (history.isNotEmpty()) {
                item(key = "h-prev") { QueueHeader("Previously played", 0.6f) }
                items(history, key = { "p:${it.key}" }) { e -> QueueRow(e, alpha = 0.5f, onClick = { player.skipTo(e.index) }) }
            }
            state.current?.let { cur ->
                item(key = "h-now") {
                    Row(verticalAlignment = Alignment.Bottom) {
                        QueueHeader("Now playing")
                        if (total > 1) {
                            Text(
                                "$position of $total",
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
                item(key = "now") { QueueRow(QueueEntry(state.currentIndex, cur), isCurrent = true) }
            }
            if (repeatOne) {
                item(key = "repeat-one") {
                    RepeatNote(Icons.Default.RepeatOne, "Repeating this song. The songs below play once you turn repeat off.")
                }
            }
            if (manual.isNotEmpty()) {
                item(key = "h-manual") { QueueHeader("Next in queue", upcomingAlpha) }
                items(manual, key = { it.key }) { e -> Upcoming(e) }
            }
            if (auto.isNotEmpty()) {
                item(key = "h-auto") { QueueHeader("Next from: ${state.sourceLabel.ifBlank { "your queue" }}", upcomingAlpha) }
                items(auto, key = { it.key }) { e -> Upcoming(e) }
            }
            if (later.isNotEmpty()) {
                itemsIndexed(later, key = { i, s -> "l:$i:${s.id}" }) { _, song -> LaterRow(song, upcomingAlpha) }
            }
            if (state.repeatMode == Player.REPEAT_MODE_ALL) {
                item(key = "repeat-all") {
                    RepeatNote(
                        Icons.Default.Repeat,
                        if (state.shuffle) "Repeat is on: when this runs out it reshuffles and starts again."
                        else "Repeat is on: when this runs out it starts again from the top.",
                    )
                }
            }
            if (state.current != null && local.isEmpty() && state.pendingCount == 0 && state.repeatMode == Player.REPEAT_MODE_OFF) {
                item(key = "end") { Text("Nothing after this song", color = TextSecondary, modifier = Modifier.padding(16.dp)) }
            }
            item(key = "hint") {
                Text(
                    "Swipe right to play next, swipe left to remove, drag ≡ to reorder",
                    color = TextSecondary.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RepeatNote(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(8.dp))
            .background(Accent.copy(alpha = 0.15f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Swipe right past the threshold to move the song up next (springs back). Swipe left to reveal a
 * Remove button; the song is only removed once that button is tapped.
 */
@Composable
private fun SwipeableQueueRow(
    revealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onPlayNext: () -> Unit,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { 104.dp.toPx() }
    val triggerPx = with(density) { 96.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revealed) { if (!revealed && offset.value < 0f) offset.animateTo(0f) }

    Box(Modifier.fillMaxWidth()) {
        val o = offset.value
        if (o > 0f) {
            val armed = o >= triggerPx
            Row(
                Modifier.matchParentSize().background(if (armed) Accent else Accent.copy(alpha = 0.45f)).padding(start = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Play next", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else if (o < 0f) {
            Box(Modifier.matchParentSize().background(Color(0xFFB3261E))) {
                Column(
                    Modifier.align(Alignment.CenterEnd).width(104.dp).fillMaxHeight().clickable(enabled = revealed, onClick = onRemove),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Delete, null, tint = Color.White)
                    Text("Remove", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(Background)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val v = offset.value
                                when {
                                    v >= triggerPx -> { onPlayNext(); offset.animateTo(0f) }
                                    v < -revealPx / 2 -> { onRevealChange(true); offset.animateTo(-revealPx) }
                                    else -> { onRevealChange(false); offset.animateTo(0f) }
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + drag).coerceIn(-revealPx * 1.3f, triggerPx * 1.6f)) }
                    }
                }
        ) { content() }
    }
}

/** A song further down the queue than the player has loaded yet; it moves into the list above as you listen. */
@Composable
private fun LaterRow(song: SongEntity, alpha: Float) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverArt(song.coverArt, 44.dp, requestSize = 150)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = alpha * 0.85f))
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary.copy(alpha = alpha), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun QueueHeader(text: String, alpha: Float = 1f) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = alpha),
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean = false,
    dragging: Boolean = false,
    alpha: Float = 1f,
    onClick: () -> Unit = {},
    handle: (@Composable () -> Modifier)? = null,
) {
    val meta = entry.item.mediaMetadata
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (dragging) SurfaceHigh else if (isCurrent) Surface else Background)
            .clickable(enabled = !isCurrent, onClick = onClick)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(meta.extras?.getString(EXTRA_COVER_ART), 44.dp, requestSize = 150)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                meta.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) Accent else Color.White.copy(alpha = alpha),
            )
            Text(
                meta.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = TextSecondary.copy(alpha = alpha), style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (handle != null) {
            Box(handle().size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DragHandle, "Reorder", tint = TextSecondary)
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
}
