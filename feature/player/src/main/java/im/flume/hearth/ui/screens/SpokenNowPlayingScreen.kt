package im.flume.hearth.ui.screens

// The player while a podcast episode or audiobook plays: −15 / +30 instead of previous / next, speed,
// chapters, "end of chapter" sleep timer and "Back to music". No shuffle, repeat, like, lyrics or visualiser.
// This file holds the page itself; its controls, menu and sheets live in the other Spoken* files.

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import im.flume.hearth.container
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.playback.SpokenNowPlaying
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.playerBackdrop
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.theme.CoverSize
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthTheme
import kotlinx.coroutines.launch

@Composable
internal fun SpokenNowPlayingScreen(state: PlayerUiState, spoken: SpokenNowPlaying, onClose: () -> Unit, onOpenQueue: () -> Unit) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val player = c.player
    val info = rememberSpokenInfo(state, spoken)
    val chapters by remember(spoken.libraryItemId, spoken.episodeId) { c.spoken.chapters(spoken.libraryItemId, spoken.episodeId) }
        .collectAsStateWithLifecycle(emptyList())
    val coverUrl = c.spokenCover(spoken.libraryItemId, CoverSize.LARGE)
    val topColor = rememberCoverColor(null, imageUrl = c.spokenCover(spoken.libraryItemId, 300))
    val pageRoute = if (spoken.isBook) bookRoute(spoken.libraryItemId) else podcastRoute(spoken.libraryItemId)
    val openPage = { onClose(); actions.open(pageRoute) }

    val scope = rememberCoroutineScope()
    val dragDown = remember { Animatable(0f) }
    val closeAt = with(LocalDensity.current) { 140.dp.toPx() }
    val spacing = HearthTheme.spacing
    val dragToClose = Modifier.pointerInput(Unit) {
        var dy = 0f
        detectVerticalDragGestures(
            onDragStart = { dy = 0f },
            onDragEnd = { scope.launch { if (dy > closeAt) onClose(); dragDown.animateTo(0f) } },
            onDragCancel = { scope.launch { dragDown.animateTo(0f) } },
        ) { change, amount ->
            change.consume()
            dy += amount
            scope.launch { dragDown.snapTo(dy.coerceAtLeast(0f)) }
        }
    }

    var speedSheet by remember { mutableStateOf(false) }
    var chapterSheet by remember { mutableStateOf(false) }
    var sleepSheet by remember { mutableStateOf(false) }
    if (speedSheet) SpeedSheet(spoken.speed, onDismiss = { speedSheet = false }) { player.setSpeed(it) }
    if (chapterSheet) ChaptersSheet(chapters, spoken, state, onDismiss = { chapterSheet = false })
    if (sleepSheet) SpokenSleepSheet(state.sleepAt, chapters.isNotEmpty(), spoken.isBook, onDismiss = { sleepSheet = false })

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragDown.value }
            .playerBackdrop(topColor)
    ) {
        val landscape = maxWidth > maxHeight
        val art: @Composable (Modifier) -> Unit = { m ->
            Box(m.then(dragToClose), contentAlignment = Alignment.Center) {
                CoverArt(
                    null, null, Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
                    corner = Dimens.CardCorner, requestSize = CoverSize.LARGE, fallback = info.subtitle.ifBlank { info.title }, url = coverUrl,
                )
            }
        }
        val controls: @Composable () -> Unit = {
            SpokenControls(state, spoken, info, chapters, openPage, { speedSheet = true }, { chapterSheet = true }, onOpenQueue)
        }
        Column(Modifier.fillMaxSize().padding(horizontal = spacing.xl)) {
            SpokenNowPlayingHeader(state, spoken, info, onClose, openPage, { sleepSheet = true }, dragToClose)
            if (landscape) {
                Row(Modifier.weight(1f).padding(vertical = spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    art(Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(spacing.xxl))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) { controls() }
                }
            } else {
                art(Modifier.weight(1f).fillMaxWidth().padding(vertical = spacing.l))
                controls()
                Spacer(Modifier.height(spacing.s))
            }
        }
    }
}

/** Close arrow, "AUDIOBOOK" / "PODCAST" and the author or show (with the sleep timer under it), and the ⋯ menu. */
@Composable
private fun SpokenNowPlayingHeader(
    state: PlayerUiState,
    spoken: SpokenNowPlaying,
    info: SpokenInfo,
    onClose: () -> Unit,
    openPage: () -> Unit,
    onSleep: () -> Unit,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(HearthTheme.icons.collapse, "Close", Modifier.size(32.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (spoken.isBook) "AUDIOBOOK" else "PODCAST", style = MaterialTheme.typography.labelMedium, color = HearthTheme.colors.textSecondary)
            Text(
                if (spoken.isBook) info.subtitle.ifBlank { info.title } else info.subtitle,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            SleepStatus(state.sleepAt)
        }
        SpokenPlayerMenu(state, spoken, info, onClose, openPage, onSleep)
    }
}
