// Pieces the music and spoken-word players share: the cover-tinted backdrop, the seek bar with the times
// under it, and the big play button.
package im.flume.hearth.core.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.ui.theme.HearthTheme

/** Diameter of the big play button, and of the glyph inside it. */
private val BigPlaySize = 72.dp
private val BigPlayIconSize = 40.dp

/** Width of the gap a chapter start leaves in the seek bar. */
private val TickWidth = 2.dp

/** How faint the part of the seek bar you haven't reached yet is. */
private const val RestAlpha = 0.3f

/**
 * The full-screen player's backdrop: the cover colour fading into the page by a third of the way down.
 * It also swallows taps so they don't reach the page underneath, and keeps content clear of the system
 * bars. Put any drag offset (graphicsLayer) before it, as the player screens do.
 */
// Composable so the fade ends on the skin's page background.
@Composable
@ReadOnlyComposable
fun Modifier.playerBackdrop(topColor: Color): Modifier = this
    .background(Brush.verticalGradient(listOf(topColor, HearthTheme.colors.background, HearthTheme.colors.background)))
    .clickable(enabled = false) {}
    .safeDrawingPadding()

/** Play / pause glyph that crossfades between states and says what tapping it will do. */
@Composable
fun PlayPauseIcon(isPlaying: Boolean, modifier: Modifier) {
    Crossfade(isPlaying, animationSpec = tween(150), label = "playPause") { playing ->
        Icon(if (playing) HearthTheme.icons.pause else HearthTheme.icons.play, if (playing) "Pause" else "Play", modifier)
    }
}

/** The big white play / pause button in the middle of the player controls. */
@Composable
fun BigPlayButton(isPlaying: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(BigPlaySize),
        shape = HearthTheme.shapes.round,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = HearthTheme.colors.playButton, contentColor = HearthTheme.colors.onPlayButton),
    ) { PlayPauseIcon(isPlaying, Modifier.size(BigPlayIconSize)) }
}

/**
 * Where the seek bar's thumb is while someone drags it. Hoisted so the time under the bar can follow
 * the finger before the player has actually moved.
 */
@Stable
class SeekState {
    var dragging by mutableStateOf(false)
    /** The dragged position, 0..1; only meaningful while [dragging]. */
    var value by mutableFloatStateOf(0f)
}

@Composable
fun rememberSeekState(): SeekState = remember { SeekState() }

/**
 * The player's seek bar: a thin rounded line with a small round grab handle that grows while you drag.
 * Shows [fraction] (0..1) of the way through, follows a drag, and calls [onSeek] with the chosen fraction
 * once the finger lifts. [ticks] are chapter starts (fractions), drawn as gaps in the line. The line is
 * thin, but the whole height of the row takes the touch, so it stays easy to grab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSlider(
    fraction: Float,
    seek: SeekState,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    ticks: List<Float> = emptyList(),
) {
    val spacing = HearthTheme.spacing
    val played = HearthTheme.colors.onCover
    val rest = played.copy(alpha = RestAlpha)
    val gap = HearthTheme.colors.background
    val value = if (seek.dragging) seek.value else fraction.coerceIn(0f, 1f)
    val handle by animateDpAsState(if (seek.dragging) spacing.seekThumbDragging else spacing.seekThumb, label = "seekHandle")
    Box(modifier.height(spacing.seekTouch), contentAlignment = Alignment.Center) {
        Slider(
            value = value,
            onValueChange = { v -> seek.dragging = true; seek.value = v },
            modifier = Modifier.fillMaxWidth(),
            onValueChangeFinished = { onSeek(seek.value); seek.dragging = false },
            colors = SliderDefaults.colors(thumbColor = played, activeTrackColor = played, inactiveTrackColor = rest),
            thumb = {
                // The box keeps the bigger size, so the line doesn't jump when the handle grows.
                Box(Modifier.size(spacing.seekThumbDragging), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(handle).background(played, CircleShape))
                }
            },
            track = { SeekLine(value, played, rest, gap, ticks) },
        )
    }
}

/** The line itself: the part already played solid, the rest faint, with a gap at each chapter start. */
@Composable
private fun SeekLine(value: Float, played: Color, rest: Color, gap: Color, ticks: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(HearthTheme.spacing.seekTrack)) {
        val corner = CornerRadius(size.height / 2)
        drawRoundRect(rest, cornerRadius = corner)
        val done = size.width * value.coerceIn(0f, 1f)
        if (done > 0f) drawRoundRect(played, size = Size(done, size.height), cornerRadius = corner)
        val width = TickWidth.toPx()
        ticks.forEach { f -> drawRect(gap, topLeft = Offset(f * size.width - width / 2, 0f), size = Size(width, size.height)) }
    }
}

/**
 * The small, quiet times under the seek bar, lined up with its ends. They sit up inside the empty part
 * of the bar's touch area, so the bar and its times read as one piece.
 */
@Composable
fun PlayerTimes(elapsed: String, remaining: String, modifier: Modifier = Modifier) {
    val spacing = HearthTheme.spacing
    Row(
        modifier.fillMaxWidth().offset(y = -spacing.s).padding(horizontal = spacing.seekThumbDragging / 2),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val style = MaterialTheme.typography.labelSmall
        val color = HearthTheme.colors.textSecondary
        Text(elapsed, style = style, color = color)
        Text(remaining, style = style, color = color)
    }
}
