package im.flume.hearth.ui

// The full-screen layers over the pages: Now Playing, its queue and the visualiser (each with Back to close),
// and the message bar, which must be drawn after them so messages and their Undo stay on top.

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import im.flume.hearth.core.playback.container
import im.flume.hearth.domain.music.Usage
import im.flume.hearth.core.playback.PlayerUiState
import im.flume.hearth.ui.screens.NowPlayingScreen
import im.flume.hearth.ui.screens.QueueScreen
import im.flume.hearth.ui.screens.visualiser.VisualiserScreen
import im.flume.hearth.core.ui.theme.HearthTheme

/**
 * Now Playing slides up over the pages; the queue and the visualiser open above it. The open flags stay
 * with [AppRoot] (which also closes them all when a page is opened from inside the player).
 */
@Composable
internal fun PlayerOverlays(
    playerState: PlayerUiState,
    nowPlayingOpen: Boolean,
    queueOpen: Boolean,
    visualiserOpen: Boolean,
    onCloseNowPlaying: () -> Unit,
    onQueueOpen: (Boolean) -> Unit,
    onVisualiserOpen: (Boolean) -> Unit,
) {
    val c = LocalContext.current.container
    val ease = FastOutSlowInEasing

    AnimatedVisibility(
        visible = nowPlayingOpen && playerState.current != null,
        enter = slideInVertically(tween(SLIDE_MS, easing = ease)) { it },
        exit = slideOutVertically(tween(SLIDE_MS, easing = ease)) { it },
    ) {
        BackHandler(enabled = nowPlayingOpen && !queueOpen && !visualiserOpen) { onCloseNowPlaying() }
        NowPlayingScreen(
            state = playerState,
            onClose = onCloseNowPlaying,
            onOpenQueue = { c.usage.track(Usage.QUEUE); onQueueOpen(true) },
            onOpenVisualiser = { c.usage.track(Usage.VISUALISER); onVisualiserOpen(true) },
        )
    }

    AnimatedVisibility(
        visible = queueOpen && nowPlayingOpen && playerState.current != null,
        enter = slideInVertically(tween(SLIDE_MS, easing = ease)) { it },
        exit = slideOutVertically(tween(SLIDE_MS, easing = ease)) { it },
    ) {
        BackHandler(enabled = queueOpen) { onQueueOpen(false) }
        QueueScreen(onBack = { onQueueOpen(false) })
    }

    AnimatedVisibility(
        visible = visualiserOpen && nowPlayingOpen && playerState.current != null,
        enter = fadeIn(tween(SLIDE_MS)) + scaleIn(tween(SLIDE_MS, easing = ease), initialScale = 0.94f),
        exit = fadeOut(tween(SLIDE_MS)) + scaleOut(tween(SLIDE_MS, easing = ease), targetScale = 0.94f),
    ) {
        BackHandler(enabled = visualiserOpen) { onVisualiserOpen(false) }
        VisualiserScreen(playerState, onClose = { onVisualiserOpen(false) })
    }
}

/**
 * Messages (and their Undo). Call it after [PlayerOverlays] so it's drawn over the player, queue and visualiser.
 * Above the mini player and tabs ([bottomBarPx] tall) normally; just above the system bar over a full-screen player.
 */
@Composable
internal fun BoxScope.MessageHost(snackbar: SnackbarHostState, overlayOpen: Boolean, bottomBarPx: Int, landscape: Boolean) {
    val density = LocalDensity.current
    SnackbarHost(
        snackbar,
        Modifier
            .align(Alignment.BottomCenter)
            .then(
                if (overlayOpen) Modifier.navigationBarsPadding().padding(bottom = HearthTheme.spacing.s)
                else Modifier.padding(bottom = with(density) { bottomBarPx.toDp() })
            )
            .then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)) else Modifier),
    )
}
