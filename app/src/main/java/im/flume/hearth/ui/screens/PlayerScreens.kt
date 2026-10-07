package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import im.flume.hearth.container
import im.flume.hearth.playback.EXTRA_COVER_ART
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.playback.QueueEntry
import im.flume.hearth.playback.isManual
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.formatDuration
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.Background
import im.flume.hearth.ui.theme.Surface
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
            .background(Color(0xFF3A2A20))
            .clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(state.coverArt, 42.dp, requestSize = 150)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(meta.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
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
            drawStopIndicator = {},
        )
    }
}

@Composable
fun NowPlayingScreen(state: PlayerUiState, onClose: () -> Unit, onOpenQueue: () -> Unit) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val player = c.player
    val meta = state.current?.mediaMetadata ?: return
    val songId = state.current.mediaId
    val song by remember(songId) { c.db.library().songFlow(songId) }.collectAsStateWithLifecycle(null)
    val pos = rememberPosition(state)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF5A3A26), Background, Background)))
            .clickable(enabled = false) {}
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PLAYING FROM", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text(state.sourceLabel.ifBlank { "Your queue" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
        }

        Spacer(Modifier.weight(1f))
        CoverArt(state.coverArt, null, Modifier.fillMaxWidth().aspectRatio(1f), corner = 8.dp, requestSize = 900)
        Spacer(Modifier.weight(1f))

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
        Spacer(Modifier.height(16.dp))
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

    val firstAuto = local.indexOfFirst { !it.item.isManual }.let { if (it < 0) local.size else it }
    val manual = local.take(firstAuto)
    val auto = local.drop(firstAuto)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            BackButton()
            Text("Queue", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            state.current?.let { cur ->
                item(key = "h-now") { QueueHeader("Now playing") }
                item(key = "now") { QueueRow(QueueEntry(state.currentIndex, cur), isCurrent = true) }
            }
            if (manual.isNotEmpty()) {
                item(key = "h-manual") { QueueHeader("Next in queue") }
                items(manual, key = { it.key }) { e ->
                    ReorderableItem(reorderState, key = e.key) { dragging ->
                        QueueRow(e, dragging = dragging, handle = {
                            Modifier.draggableHandle(onDragStarted = { draggingKey = e.key }, onDragStopped = { commitDrag(e.key) })
                        })
                    }
                }
            }
            if (auto.isNotEmpty()) {
                item(key = "h-auto") { QueueHeader("Next from: ${state.sourceLabel.ifBlank { "your queue" }}") }
                items(auto, key = { it.key }) { e ->
                    ReorderableItem(reorderState, key = e.key) { dragging ->
                        QueueRow(e, dragging = dragging, handle = {
                            Modifier.draggableHandle(onDragStarted = { draggingKey = e.key }, onDragStopped = { commitDrag(e.key) })
                        })
                    }
                }
            }
            if (state.pendingCount > 0) {
                item(key = "more") {
                    Text("+ ${state.pendingCount} more songs", color = TextSecondary, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean = false,
    dragging: Boolean = false,
    handle: (@Composable () -> Modifier)? = null,
) {
    val player = LocalContext.current.container.player
    val meta = entry.item.mediaMetadata
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (dragging) SurfaceHigh else if (isCurrent) Surface else Color.Transparent)
            .clickable(enabled = !isCurrent) { player.skipTo(entry.index) }
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(meta.extras?.getString(EXTRA_COVER_ART), 44.dp, requestSize = 150)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(meta.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isCurrent) Accent else Color.White)
            Text(meta.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        if (!isCurrent) {
            IconButton(onClick = { player.remove(entry.index) }) { Icon(Icons.Default.Close, "Remove", tint = TextSecondary) }
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
