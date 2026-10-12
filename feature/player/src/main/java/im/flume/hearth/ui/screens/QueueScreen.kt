package im.flume.hearth.ui.screens

// The song queue, opened from Now Playing: played, now playing, up next (drag to reorder, swipe to play next or
// remove) and what's still to load. Podcasts and audiobooks hand over to SpokenQueueScreen.

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import im.flume.hearth.container
import im.flume.hearth.data.SongEntity
import im.flume.hearth.playback.QueueEntry
import im.flume.hearth.playback.isManual
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.playerBackdrop
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.theme.HearthTheme
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun QueueScreen(onBack: (() -> Unit)? = null) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val colors = HearthTheme.colors
    val icons = HearthTheme.icons
    val spacing = HearthTheme.spacing
    val player = c.player
    val state by player.state.collectAsStateWithLifecycle()
    state.spoken?.let { spoken -> return SpokenQueueScreen(state, spoken, onBack) }
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
        val newPos = local.indexOfFirst { it.key == key }
        draggingKey = null
        if (newPos < 0) return
        // By key, not position: the service may have trimmed played songs since this list was drawn.
        player.moveAfter(local[newPos], local.getOrNull(newPos - 1))
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
                onPlayNext = { player.moveToNext(e) },
                onRemove = {
                    revealedKey = null
                    // Neighbours in the full queue (the one before may be the current song), for undo.
                    val pos = state.queue.indexOfFirst { it.key == e.key }
                    val before = state.queue.getOrNull(pos - 1)
                    val after = state.queue.getOrNull(pos + 1)
                    player.remove(e)
                    actions.showUndo("Removed from queue") { player.restore(e, after = before, before = after) }
                },
                onMoveUp = local.indexOfFirst { it.key == e.key }.takeIf { it > 0 }?.let { i -> { player.moveAfter(e, local.getOrNull(i - 2)) } },
                onMoveDown = local.indexOfFirst { it.key == e.key }.takeIf { it in 0 until local.lastIndex }?.let { i -> { player.moveAfter(e, local[i + 1]) } },
            ) {
                QueueRow(
                    e,
                    dragging = dragging,
                    alpha = upcomingAlpha,
                    onClick = { if (revealedKey != null) revealedKey = null else player.skipTo(e) },
                    handle = { Modifier.draggableHandle(onDragStarted = { draggingKey = e.key }, onDragStopped = { commitDrag(e.key) }) },
                )
            }
        }
    }

    // Part of the player: same cover-tinted backdrop and close arrow, and dragging the header down closes it.
    val topColor = rememberCoverColor(state.coverArt)
    val dragDown = remember { Animatable(0f) }
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragDown.value }
            .playerBackdrop(topColor)
    ) {
        QueueTopBar(state.sourceLabel, dragDown, onBack)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = spacing.xl)) {
            if (history.isNotEmpty()) {
                item(key = "h-prev") { Box(Modifier.animateItem()) { QueueHeader("Previously played", 0.6f) } }
                items(history, key = { "p:${it.key}" }) { e -> QueueRow(e, alpha = 0.5f, onClick = { player.skipTo(e) }, modifier = Modifier.animateItem()) }
            }
            state.current?.let { cur ->
                item(key = "h-now") {
                    QueueHeader("Now playing", trailing = if (total > 1) "$position of $total" else null)
                }
                item(key = "now") { QueueRow(QueueEntry(state.currentIndex, cur), isCurrent = true, modifier = Modifier.animateItem()) }
            }
            if (repeatOne) {
                item(key = "repeat-one") {
                    RepeatNote(icons.repeatOne, "Repeating this song. The songs below play once you turn repeat off.")
                }
            }
            if (manual.isNotEmpty()) {
                item(key = "h-manual") { Box(Modifier.animateItem()) { QueueHeader("Next in queue", upcomingAlpha) } }
                items(manual, key = { it.key }) { e -> Upcoming(e) }
            }
            if (auto.isNotEmpty()) {
                item(key = "h-auto") { Box(Modifier.animateItem()) { QueueHeader("Next from: ${state.sourceLabel.ifBlank { "your queue" }}", upcomingAlpha) } }
                items(auto, key = { it.key }) { e -> Upcoming(e) }
            }
            if (later.isNotEmpty()) {
                itemsIndexed(later, key = { i, s -> "l:$i:${s.id}" }) { _, song -> LaterRow(song, upcomingAlpha) }
            }
            if (state.repeatMode == Player.REPEAT_MODE_ALL) {
                item(key = "repeat-all") {
                    RepeatNote(
                        icons.repeat,
                        if (state.shuffle) "Repeat is on: when this runs out it reshuffles and starts again."
                        else "Repeat is on: when this runs out it starts again from the top.",
                    )
                }
            }
            if (state.current != null && local.isEmpty() && state.pendingCount == 0 && state.repeatMode == Player.REPEAT_MODE_OFF) {
                item(key = "end") { Text("Nothing after this song", color = colors.textSecondary, modifier = Modifier.padding(spacing.l)) }
            }
            item(key = "hint") {
                Text(
                    "Swipe right to play next, swipe left to remove, drag ≡ to reorder",
                    color = colors.textSecondary.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(spacing.l),
                )
            }
        }
    }
}

/** "QUEUE" and where it plays from, with a close arrow; dragging it down (moving the page by [dragDown]) closes the queue. */
@Composable
private fun QueueTopBar(sourceLabel: String, dragDown: Animatable<Float, AnimationVector1D>, onBack: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val closeAt = with(LocalDensity.current) { 120.dp.toPx() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HearthTheme.spacing.xl).pointerInput(Unit) {
            var dy = 0f
            detectVerticalDragGestures(
                onDragStart = { dy = 0f },
                onDragEnd = { scope.launch { if (dy > closeAt) onBack?.invoke(); dragDown.animateTo(0f) } },
                onDragCancel = { scope.launch { dragDown.animateTo(0f) } },
            ) { change, amount ->
                change.consume()
                dy += amount
                scope.launch { dragDown.snapTo(dy.coerceAtLeast(0f)) }
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onBack?.invoke() }) { Icon(HearthTheme.icons.collapse, "Close queue", Modifier.size(32.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("QUEUE", style = MaterialTheme.typography.labelMedium, color = HearthTheme.colors.textSecondary)
            Text(sourceLabel.ifBlank { "Your queue" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(48.dp)) // balances the close arrow so the title stays centred
    }
}
