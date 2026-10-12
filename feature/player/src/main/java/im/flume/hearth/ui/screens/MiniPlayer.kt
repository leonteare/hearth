package im.flume.hearth.ui.screens

// The mini player above the tabs (swipe sideways to skip a song) and the shared play / pause glyph.

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.container
import im.flume.hearth.data.Usage
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.ui.components.CoverPrefetcher
import im.flume.hearth.ui.components.PlayPauseIcon
import im.flume.hearth.ui.components.PlayerCoverArt
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.theme.CoverSize
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Cover sizes asked of the server by the mini player; the prefetch asks for the same so caches match. */
private const val MINI_COVER_SIZE = CoverSize.SMALL
private const val MINI_SPOKEN_COVER_SIZE = CoverSize.MEDIUM

@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit) {
    val c = LocalContext.current.container
    val player = c.player
    val meta = state.current?.mediaMetadata ?: return
    // A podcast episode or audiobook: its cover, −15 / +30 instead of previous / next, no swipe to skip.
    val spoken = state.spoken
    val spokenCover = spoken?.let { c.spokenCover(it.libraryItemId, MINI_SPOKEN_COVER_SIZE) }
    // Read only by the progress bar's draw, so the rest of the mini player doesn't redraw 4 times a second.
    val pos = if (spoken != null) rememberSpokenPositionState(state) else rememberPositionState(state)
    val durationMs = spoken?.durationMs ?: state.durationMs
    val tint = lerp(rememberCoverColor(if (spoken != null) null else state.coverArt, imageUrl = spokenCover), Color.Black, 0.15f)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(HearthShapes.Card)
            .background(tint)
            .clickable(onClick = onOpen)
            // Swipe sideways to skip, like Spotify (songs only).
            .pointerInput(spoken == null) {
                if (spoken != null) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val v = swipe.value
                            if (abs(v) > threshold) {
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                c.usage.track(Usage.SWIPE_SKIP)
                                if (v < 0) player.next() else player.previous()
                            }
                            swipe.animateTo(0f)
                        }
                    },
                    onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                ) { change, drag -> change.consume(); scope.launch { swipe.snapTo(swipe.value + drag) } }
            }
    ) {
        Row(
            Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp).graphicsLayer {
                translationX = swipe.value * 0.6f
                alpha = 1f - (abs(swipe.value) / (threshold * 3)).coerceAtMost(0.6f)
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Same 4dp cover corner (HearthShapes.Cover) as list rows.
            PlayerCoverArt(
                state.coverArt, 42.dp, corner = Dimens.CoverCorner, requestSize = MINI_COVER_SIZE,
                fallback = meta.albumTitle?.toString(), url = spokenCover,
                onPixelSize = { if (spoken != null) CoverPrefetcher.noteSpoken(MINI_SPOKEN_COVER_SIZE, it) else CoverPrefetcher.noteSong(MINI_COVER_SIZE, it) },
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // A book's player items are its files ("Title (2/5)"); its album title is the book's own.
                val title = if (spoken?.isBook == true) meta.albumTitle else meta.title
                Text(title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(meta.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            if (spoken != null) {
                SpokenMiniControls(state)
            } else {
                IconButton(onClick = { player.previous() }) { Icon(Icons.Default.SkipPrevious, "Previous") }
                IconButton(onClick = { player.playPause() }) {
                    PlayPauseIcon(state.isPlaying, Modifier.size(30.dp))
                }
                IconButton(onClick = { player.next() }) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
        LinearProgressIndicator(
            progress = { if (durationMs > 0) (pos.longValue.toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 8.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.2f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
