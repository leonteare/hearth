package im.flume.hearth.ui.screens

// The full-screen Now Playing page for songs: header, cover (or lyrics) and the drag gestures that close or skip.
// Podcasts and audiobooks hand over to SpokenNowPlayingScreen.

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.data.Usage
import im.flume.hearth.core.playback.PlayerUiState
import im.flume.hearth.core.ui.components.CoverPrefetcher
import im.flume.hearth.core.ui.components.PlayerCoverArt
import im.flume.hearth.core.ui.components.playerBackdrop
import im.flume.hearth.core.ui.components.rememberCoverColor
import im.flume.hearth.core.ui.theme.CoverSize
import im.flume.hearth.core.ui.theme.Dimens
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import im.flume.hearth.core.ui.screens.coverArt

/** Cover size asked of the server by Now Playing; the prefetch asks for the same so caches match. */
private const val NOW_PLAYING_COVER_SIZE = CoverSize.LARGE

@Composable
fun NowPlayingScreen(state: PlayerUiState, onClose: () -> Unit, onOpenQueue: () -> Unit, onOpenVisualiser: () -> Unit) {
    state.spoken?.let { spoken ->
        if (state.current != null) SpokenNowPlayingScreen(state, spoken, onClose, onOpenQueue)
        return
    }
    val c = LocalContext.current.container
    val current = state.current ?: return
    val songId = current.mediaId
    val song by remember(songId) { c.db.library().songFlow(songId) }.collectAsStateWithLifecycle(null)
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val topColor = rememberCoverColor(state.coverArt)

    val player = c.player
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dragDown = remember { Animatable(0f) }
    val coverSwipe = remember { Animatable(0f) }
    val density = LocalDensity.current
    val closeAt = with(density) { 140.dp.toPx() }
    val skipAt = with(density) { 90.dp.toPx() }
    val spacing = HearthTheme.spacing

    // Drag down anywhere on the header or cover to close; drag the cover sideways to skip.
    fun Modifier.playerGestures(allowSkip: Boolean) = pointerInput(allowSkip) {
        var dx = 0f
        var dy = 0f
        detectDragGestures(
            onDragStart = { dx = 0f; dy = 0f },
            onDragEnd = {
                scope.launch {
                    when {
                        dy > closeAt && abs(dy) > abs(dx) -> onClose()
                        allowSkip && abs(dx) > skipAt -> {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            c.usage.track(Usage.SWIPE_SKIP)
                            if (dx < 0) player.next() else player.previous()
                        }
                    }
                    launch { coverSwipe.animateTo(0f) }
                    dragDown.animateTo(0f)
                }
            },
            onDragCancel = { scope.launch { coverSwipe.animateTo(0f); dragDown.animateTo(0f) } },
        ) { change, drag ->
            change.consume()
            dx += drag.x
            dy += drag.y
            scope.launch {
                if (abs(dy) > abs(dx)) dragDown.snapTo(dy.coerceAtLeast(0f))
                else if (allowSkip) coverSwipe.snapTo(dx)
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragDown.value }
            .playerBackdrop(topColor)
    ) {
        val landscape = maxWidth > maxHeight
        val artOrLyrics: @Composable (Modifier) -> Unit = { m ->
            CoverOrLyrics(showLyrics, song, state, coverSwipe, skipAt, if (showLyrics) m else m.playerGestures(allowSkip = true))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = spacing.xl)) {
            NowPlayingHeader(state, song, onClose, Modifier.playerGestures(allowSkip = false))
            if (landscape) {
                Row(Modifier.weight(1f).padding(vertical = spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    artOrLyrics(Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(spacing.xxl))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        PlayerControls(state, song, onClose, showLyrics, { showLyrics = !showLyrics }, onOpenQueue, onOpenVisualiser)
                    }
                }
            } else {
                artOrLyrics(Modifier.weight(1f).fillMaxWidth().padding(vertical = spacing.l))
                PlayerControls(state, song, onClose, showLyrics, { showLyrics = !showLyrics }, onOpenQueue, onOpenVisualiser)
                Spacer(Modifier.height(spacing.s))
            }
        }
    }
}

/** Close arrow, "Playing from …" (with the sleep timer under it) and the ⋯ menu. [modifier] carries the drag-to-close. */
@Composable
private fun NowPlayingHeader(state: PlayerUiState, song: SongEntity?, onClose: () -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(HearthTheme.icons.collapse, "Close", Modifier.size(32.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PLAYING FROM", style = MaterialTheme.typography.labelMedium, color = HearthTheme.colors.textSecondary)
            Text(state.sourceLabel.ifBlank { "Your queue" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SleepStatus(state.sleepAt)
        }
        NowPlayingMenu(song, state.sleepAt, onClose)
    }
}

/** The big cover (which follows a sideways drag in [coverSwipe]) or, when switched on, the lyrics in its place. */
@Composable
private fun CoverOrLyrics(
    showLyrics: Boolean,
    song: SongEntity?,
    state: PlayerUiState,
    coverSwipe: Animatable<Float, AnimationVector1D>,
    skipAt: Float,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            showLyrics,
            transitionSpec = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f) togetherWith fadeOut(tween(150)) },
            contentAlignment = Alignment.Center,
            label = "coverLyrics",
        ) { lyricsShown ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (lyricsShown) {
                    LyricsView(song, state, Modifier.fillMaxSize())
                } else {
                    PlayerCoverArt(
                        state.coverArt, null,
                        Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true).graphicsLayer {
                            translationX = coverSwipe.value
                            rotationZ = coverSwipe.value / 60f
                            alpha = 1f - (abs(coverSwipe.value) / (skipAt * 4)).coerceAtMost(0.5f)
                        },
                        corner = Dimens.CardCorner, requestSize = NOW_PLAYING_COVER_SIZE,
                        fallback = state.current?.mediaMetadata?.albumTitle?.toString(),
                        onPixelSize = { CoverPrefetcher.noteSong(NOW_PLAYING_COVER_SIZE, it) },
                    )
                }
            }
        }
    }
}
