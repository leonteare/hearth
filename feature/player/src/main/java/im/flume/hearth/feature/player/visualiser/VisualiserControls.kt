package im.flume.hearth.feature.player.visualiser

// The visualiser's overlay controls, the layer each style draws into, and keeping the screen on while it plays.

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.playback.PlayerUiState
import im.flume.hearth.core.ui.theme.HearthTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun Controls(
    state: PlayerUiState,
    style: VisualiserStyle,
    onClose: () -> Unit,
    onPick: () -> Unit,
    onAction: () -> Unit,
) {
    val player = LocalContext.current.container.player
    val meta = state.current?.mediaMetadata
    // Drawn over the visualiser's black canvas, so the scrims stay black whatever the skin; text uses onCover.
    val onCanvas = HearthTheme.colors.onCover
    val icons = HearthTheme.icons
    val spacing = HearthTheme.spacing
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(280.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
        )
        Row(Modifier.safeDrawingPadding().padding(spacing.s).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(icons.close, "Close visualiser", tint = onCanvas) }
            Column(Modifier.weight(1f).padding(horizontal = spacing.s)) {
                Text(meta?.title?.toString().orEmpty(), color = onCanvas, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta?.artist?.toString().orEmpty(), color = onCanvas.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = spacing.xl).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .background(onCanvas.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .clickable(onClickLabel = "Choose a style", onClick = onPick)
                    .padding(start = 14.dp, end = spacing.s, top = spacing.s, bottom = spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icons.visualiser, null, tint = onCanvas, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.s))
                Text(style.label, color = onCanvas, style = MaterialTheme.typography.labelLarge)
                Icon(icons.expandUp, null, tint = onCanvas)
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(onClick = { onAction(); player.previous() }, Modifier.size(56.dp)) {
                    Icon(icons.previous, "Previous", tint = onCanvas, modifier = Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = { onAction(); player.playPause() },
                    modifier = Modifier.size(68.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = onCanvas, contentColor = Color.Black),
                ) {
                    Icon(
                        if (state.isPlaying) icons.pause else icons.play,
                        if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(38.dp),
                    )
                }
                IconButton(onClick = { onAction(); player.next() }, Modifier.size(56.dp)) {
                    Icon(icons.next, "Next", tint = onCanvas, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Swipe sideways for another style", color = onCanvas.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** One style's drawing surface, faded by [alpha] during a crossfade. */
@Composable
internal fun RenderLayer(renderer: VisualiserRenderer, frame: VisFrame, tick: () -> Long, alpha: () -> Float) {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha() }
    ) {
        if (tick() >= 0L) renderer.draw(this, frame)
    }
}

/**
 * Hides the status and navigation bars (swipe to peek) until disposed, and keeps the screen on
 * while [keepScreenOn]: only while music plays, so a visualiser left paused lets the phone sleep.
 */
@Composable
internal fun ImmersiveMode(keepScreenOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
