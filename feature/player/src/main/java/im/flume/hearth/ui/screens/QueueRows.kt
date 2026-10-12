package im.flume.hearth.ui.screens

// Rows and headers for the song queue: the swipeable row wrapper, queue and "later" rows, section headers and repeat notes.

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import im.flume.hearth.data.SongEntity
import im.flume.hearth.playback.EXTRA_COVER_ART
import im.flume.hearth.playback.QueueEntry
import im.flume.hearth.ui.components.BannerCard
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.theme.HearthTheme
import im.flume.hearth.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun RepeatNote(icon: ImageVector, text: String) {
    BannerCard(title = null, body = text, icon = icon, tinted = true)
}

/**
 * Swipe right past the threshold to move the song up next (springs back). Swipe left to reveal a
 * Remove button; the song is only removed once that button is tapped.
 */
@Composable
internal fun SwipeableQueueRow(
    revealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onPlayNext: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val reducedMotion = LocalReducedMotion.current
    // Swiping and dragging can't be done with TalkBack or switch access, so the same moves are offered as actions.
    val a11yActions = listOfNotNull(
        CustomAccessibilityAction("Play next") { onPlayNext(); true },
        CustomAccessibilityAction("Remove") { onRemove(); true },
        onMoveUp?.let { up -> CustomAccessibilityAction("Move up") { up(); true } },
        onMoveDown?.let { down -> CustomAccessibilityAction("Move down") { down(); true } },
    )
    val revealPx = with(density) { 104.dp.toPx() }
    val triggerPx = with(density) { 96.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // With animations switched off on the phone, rows jump into place instead of sliding.
    suspend fun settle(to: Float) = if (reducedMotion) offset.snapTo(to) else { offset.animateTo(to); Unit }
    LaunchedEffect(revealed) { if (!revealed && offset.value < 0f) settle(0f) }

    Box(Modifier.fillMaxWidth().semantics { customActions = a11yActions }) {
        val o = offset.value
        val accent = MaterialTheme.colorScheme.primary
        val colors = HearthTheme.colors
        if (o > 0f) {
            val armed = o >= triggerPx
            Row(
                Modifier.matchParentSize().background(if (armed) accent else accent.copy(alpha = 0.45f)).padding(start = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val onAccent = MaterialTheme.colorScheme.onPrimary
                Icon(HearthTheme.icons.playNext, null, tint = onAccent)
                Spacer(Modifier.width(HearthTheme.spacing.s))
                Text("Play next", color = onAccent, fontWeight = FontWeight.Bold)
            }
        } else if (o < 0f) {
            Box(Modifier.matchParentSize().background(colors.destructiveFill)) {
                Column(
                    Modifier.align(Alignment.CenterEnd).width(104.dp).fillMaxHeight().clickable(enabled = revealed, onClick = onRemove),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(HearthTheme.icons.delete, null, tint = colors.text)
                    Text("Remove", color = colors.text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(colors.background)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val v = offset.value
                                when {
                                    v >= triggerPx -> { onPlayNext(); settle(0f) }
                                    v < -revealPx / 2 -> { onRevealChange(true); settle(-revealPx) }
                                    else -> { onRevealChange(false); settle(0f) }
                                }
                            }
                        },
                        onDragCancel = { scope.launch { settle(0f) } },
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
internal fun LaterRow(song: SongEntity, alpha: Float) {
    MediaRow(
        song.title, song.artist, onClick = null,
        coverArt = song.coverArt, fallback = song.album,
        titleColor = HearthTheme.colors.text.copy(alpha = alpha * 0.85f),
        subtitleColor = HearthTheme.colors.textSecondary.copy(alpha = alpha),
    )
}

@Composable
internal fun QueueHeader(text: String, alpha: Float = 1f, trailing: String? = null) {
    // Compact compared with page section headers: the queue is a dense list, not a page of carousels.
    val colors = HearthTheme.colors
    val spacing = HearthTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(start = spacing.gutter, end = spacing.gutter, top = 14.dp, bottom = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = colors.text.copy(alpha = alpha), modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        trailing?.let { Text(it, color = colors.textSecondary, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
internal fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean = false,
    dragging: Boolean = false,
    alpha: Float = 1f,
    onClick: () -> Unit = {},
    handle: (@Composable () -> Modifier)? = null,
    modifier: Modifier = Modifier,
) {
    val meta = entry.item.mediaMetadata
    val colors = HearthTheme.colors
    MediaRow(
        meta.title?.toString().orEmpty(),
        meta.artist?.toString().orEmpty(),
        onClick = if (isCurrent) null else onClick,
        modifier = modifier.background(if (dragging) colors.surfaceHigh else if (isCurrent) colors.surface else colors.background),
        coverArt = meta.extras?.getString(EXTRA_COVER_ART),
        fallback = meta.albumTitle?.toString(),
        titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else colors.text.copy(alpha = alpha),
        subtitleColor = colors.textSecondary.copy(alpha = alpha),
        trailing = handle?.let { h ->
            {
                Box(h().size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(HearthTheme.icons.dragHandle, "Reorder", tint = colors.textSecondary)
                }
            }
        },
    )
}
