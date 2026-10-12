// Pieces the music and spoken-word players share: the cover-tinted backdrop, the seek bar and the big
// play button. They look exactly like the copies in the player screens, which can switch to these.
package im.flume.hearth.core.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
 * The player's seek bar. Shows [fraction] (0..1) of the way through, follows a drag, and calls [onSeek]
 * with the chosen fraction once the finger lifts. [ticks] are chapter starts (fractions), drawn as gaps.
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
    val onCover = HearthTheme.colors.onCover
    val tickColor = HearthTheme.colors.background
    val colors = SliderDefaults.colors(thumbColor = onCover, activeTrackColor = onCover, inactiveTrackColor = onCover.copy(alpha = 0.25f))
    val value = if (seek.dragging) seek.value else fraction.coerceIn(0f, 1f)
    val onValueChange = { v: Float -> seek.dragging = true; seek.value = v }
    val onFinished = { onSeek(seek.value); seek.dragging = false }
    if (ticks.isEmpty()) {
        Slider(value = value, onValueChange = onValueChange, modifier = modifier, onValueChangeFinished = onFinished, colors = colors)
    } else {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            onValueChangeFinished = onFinished,
            colors = colors,
            track = { sliderState ->
                Box(contentAlignment = Alignment.Center) {
                    SliderDefaults.Track(sliderState = sliderState, colors = colors)
                    // Chapter starts: small gaps in the bar.
                    Canvas(Modifier.matchParentSize()) {
                        val w = TickWidth.toPx()
                        ticks.forEach { f -> drawRect(tickColor, topLeft = Offset(f * size.width - w / 2, 0f), size = Size(w, size.height)) }
                    }
                }
            },
        )
    }
}
